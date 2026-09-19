package io.dobby.core.nlu.llm

import io.dobby.core.registry.Introspection
import io.dobby.core.registry.ParamCoercion
import io.dobby.core.registry.SockRegistry
import io.dobby.core.sock.CommandInvocation
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Everything the model needs for one palette: the prompt scaffold and the grammar.
 *
 * [fingerprint] identifies the pair. The native side prefills the system prefix once per process
 * and keeps it in the KV cache; a program whose fingerprint differs from the prefilled one means
 * the palette changed underneath, and the correct response is to re-prefill rather than to
 * answer from a cache built for a different set of commands. It is also recorded in the
 * fallthrough log, so a logged resolution says which palette produced it.
 */
data class Tier2Program(
    val systemPrefix: String,
    val assistantSuffix: String,
    val grammar: String,
    val commandIds: List<String>,
) {
    /** Hash of the full scaffold *and* the grammar — a change to either invalidates the cache. */
    val fingerprint: String =
        "%08x".format((systemPrefix + assistantSuffix + grammar).hashCode()) +
            "%08x".format(commandIds.joinToString(",").hashCode())

    /** The complete prompt for one utterance. */
    fun promptFor(utterance: String): String = systemPrefix + utterance + assistantSuffix

    companion object {
        /**
         * Builds the program, or throws if a Sock declares something the prompt cannot render.
         *
         * This is the **build-failure** entry point: `PromptGenerator` rejects a few-shot that
         * is not normalized, and a test over the real registry is what turns that into a red
         * build on a laptop. Production code calls [ofOrNull] instead.
         */
        fun of(registry: SockRegistry, introspection: Introspection = Introspection(registry)): Tier2Program {
            val catalog = Tier2Catalog.of(registry)
            return Tier2Program(
                systemPrefix = PromptGenerator.systemPrefix(catalog, introspection),
                assistantSuffix = PromptGenerator.ASSISTANT_SUFFIX,
                grammar = GrammarGenerator.generate(catalog),
                commandIds = catalog.commandIds,
            )
        }

        /**
         * The same, for a running panel: null rather than an exception.
         *
         * Tier 2 is an addition to a product that already works without it, so no failure of
         * its own may take the panel down — and `START_STICKY` turns an exception at service
         * start into a boot loop. A Sock with a malformed few-shot therefore costs Tier 2 and
         * nothing else, loudly, and the same mistake fails the build before it ever ships.
         */
        fun ofOrNull(
            registry: SockRegistry,
            introspection: Introspection = Introspection(registry),
            onError: (String) -> Unit = {},
        ): Tier2Program? = try {
            of(registry, introspection)
        } catch (e: IllegalArgumentException) {
            onError("Tier 2 disabled: ${e.message}")
            null
        }
    }
}

/**
 * Runs the model. The only part of Tier 2 that is not pure, and the only part that needs a phone.
 *
 * Implementations must **never throw** — every failure is a null, exactly like
 * `VoicePipeline.prepare()`. A Tier 2 that can take the turn down with it is worse than no
 * Tier 2, because the panel already works without one.
 */
interface Tier2Resolver {

    /** False when the model is missing, the CPU is unsupported, or loading crashed before. */
    val available: Boolean

    /** Why [available] is false, in German, for the settings screen. Null when it is true. */
    val unavailableReason: String?

    /**
     * Raw model output for one utterance, or null if it could not run.
     *
     * [utterance] is already normalized — the same string Tier 1 was given.
     */
    suspend fun generate(utterance: String, program: Tier2Program): String?
}

/** What Tier 2 made of an utterance. Everything but [Resolved] behaves like today's miss. */
sealed interface Tier2Outcome {

    /** The model named a command, and it survived the gate. */
    data class Resolved(val invocation: CommandInvocation) : Tier2Outcome

    /** The model said no command fits. The most valuable answer it can give after a real one. */
    data object NoCommand : Tier2Outcome

    /** The model answered, and the gate threw it out. */
    data class Rejected(val reason: String, val raw: String) : Tier2Outcome

    /** No resolver, or the resolver is not usable on this device. */
    data class Unavailable(val reason: String) : Tier2Outcome

    /** The deadline passed. */
    data object Timeout : Tier2Outcome
}

/** One Tier 2 consultation, for the detail line and the flywheel. */
data class Tier2Trace(
    val outcome: Tier2Outcome,
    val latency: Duration,
    val fingerprint: String,
    /** What the model actually emitted. Null when it never ran. */
    val raw: String?,
) {
    override fun toString(): String {
        val what = when (outcome) {
            is Tier2Outcome.Resolved -> {
                val params = outcome.invocation.params
                val rendered = if (params.isEmpty()) {
                    ""
                } else {
                    params.entries.joinToString(", ", prefix = " (", postfix = ")") { "${it.key}=${it.value}" }
                }
                "${outcome.invocation.commandId}$rendered"
            }

            Tier2Outcome.NoCommand -> "none"
            is Tier2Outcome.Rejected -> "rejected: ${outcome.reason}"
            is Tier2Outcome.Unavailable -> "unavailable: ${outcome.reason}"
            Tier2Outcome.Timeout -> "timeout"
        }
        return "tier2 ${"%.1f".format(latency.inWholeMilliseconds / 1000.0)}s · $what"
    }
}

/**
 * The gate: everything between the model's opinion and a Sock actually running.
 *
 * This is what makes "malformed or unknown-command output is impossible" true rather than hoped
 * for. The grammar already prevents most of it, but `:core` cannot verify that the grammar was
 * applied, so the gate re-derives the guarantee from the registry:
 *
 * 1. `none` is the one branch that means "no command", and [SockRegistry.build] already rejects
 *    any Sock declaring that id — so `registry.commands["none"]` is always absent and the branch
 *    has exactly one meaning. This closes the loop the reserved id was opened for.
 * 2. An id no Sock declares is rejected.
 * 3. The params go through [ParamCoercion] — the same function Tier 1's palette uses. It costs
 *    one `mapValues` and buys the entire Tier 1 validation path: types, enum membership,
 *    required-ness, defaults. **The LLM gets no route into a Sock that a template does not also
 *    have.**
 */
class Tier2(
    private val registry: SockRegistry,
    private val resolver: Tier2Resolver,
    val program: Tier2Program,
    private val timeout: Duration = DEFAULT_TIMEOUT,
) {

    suspend fun resolve(utterance: String): Tier2Trace {
        val started = TimeSource.Monotonic.markNow()
        if (!resolver.available) {
            return Tier2Trace(
                Tier2Outcome.Unavailable(resolver.unavailableReason ?: "kein Modell"),
                started.elapsedNow(),
                program.fingerprint,
                raw = null,
            )
        }

        // The JVM-side backstop. The native side enforces the same deadline inside
        // llama_decode via abort_callback, because a flag checked between tokens cannot stop a
        // prefill that is already in flight — but a resolver that ignores both must still not
        // hold the turn open forever.
        val raw = try {
            withTimeout(timeout) { resolver.generate(utterance, program) }
        } catch (_: TimeoutCancellationException) {
            return Tier2Trace(Tier2Outcome.Timeout, started.elapsedNow(), program.fingerprint, null)
        }
            ?: return Tier2Trace(
                Tier2Outcome.Unavailable(resolver.unavailableReason ?: "keine Antwort"),
                started.elapsedNow(),
                program.fingerprint,
                raw = null,
            )

        return Tier2Trace(gate(raw), started.elapsedNow(), program.fingerprint, raw)
    }

    private fun gate(raw: String): Tier2Outcome {
        val reply = Tier2Json.decode(raw)
            ?: return Tier2Outcome.Rejected("unparsable", raw)
        if (reply.commandId == CommandInvocation.NONE) return Tier2Outcome.NoCommand

        val spec = registry.commands[reply.commandId]
            ?: return Tier2Outcome.Rejected("unknown command '${reply.commandId}'", raw)

        // Straight back through Tier 1's coercion. `toString()` because the palette's contract
        // is raw slot strings in, typed params out, and an Int from JSON is the same "20" a
        // template would have captured.
        val params = ParamCoercion.coerce(spec, reply.params.mapValues { it.value.toString() })
            ?: return Tier2Outcome.Rejected("params do not satisfy ${spec.id}", raw)

        return Tier2Outcome.Resolved(CommandInvocation(spec.id, params))
    }

    companion object {
        /**
         * Deliberately the same as `DobbyController.SPEECH_WINDOW`.
         *
         * A panel whose two waits are the same length reads as deliberate; one whose waits
         * differ reads as broken. The plan's ≤4 s done-when is the *measured* target the tier is
         * tuned against — this is the safety net above it, not the goal.
         */
        val DEFAULT_TIMEOUT: Duration = 5.seconds

        /**
         * The `max_tokens` the native side is given. Derived, not chosen.
         *
         * `(DEFAULT_TIMEOUT − prefill allowance) × pessimistic tok/s` = (5 s − 1 s) × 10 = 40.
         * `ReplyLengthTest` asserts that the longest reply any branch of the generated grammar
         * admits still fits inside it, so a Sock with a long id and three optional params fails
         * the build rather than being truncated mid-JSON at run time.
         */
        const val MAX_REPLY_TOKENS: Int = 40
    }
}
