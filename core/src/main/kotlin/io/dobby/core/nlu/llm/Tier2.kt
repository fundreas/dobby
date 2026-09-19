package io.dobby.core.nlu.llm

import io.dobby.core.registry.Introspection
import io.dobby.core.registry.ParamCoercion
import io.dobby.core.registry.SockRegistry
import io.dobby.core.sock.CommandInvocation
import io.dobby.core.sock.CommandSpec
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** Which of the two questions a request is asking. */
enum class Step {
    /** "Which command is this?" Always asked. */
    ROUTE,

    /** "What are its parameters?" Only for a command that has any. */
    FILL,
}

/** Step 1: the prompt and grammar that turn an utterance into a command id. */
data class RouteProgram(
    val systemPrefix: String,
    val grammar: String,
    /** Every legal answer, `none` included. The grammar is a flat alternation of exactly these. */
    val labels: List<String>,
)

/** Step 2, for one command: the user turn that describes its params, and the grammar for them. */
data class FillProgram(val userTurn: String, val grammar: String)

/**
 * Everything the model needs for one palette: two prompts, two grammars, one identity.
 *
 * [fingerprint] covers all of it. The native side prefills [RouteProgram.systemPrefix] once per
 * process and keeps it in the KV cache; a program whose fingerprint differs from the prefilled
 * one means the palette changed underneath, and the correct response is to re-prefill rather
 * than to answer from a cache built for a different set of commands.
 *
 * One fingerprint rather than one per program, deliberately: a change to any fill turn
 * re-prefills the route prefix once, which is cheap and leaves exactly one key to reason about.
 * It is also recorded in the fallthrough log, so a logged resolution says which palette
 * produced it.
 */
data class Tier2Program(
    val route: RouteProgram,
    /** Command id → its fill program. Only commands with params are in here. */
    val fill: Map<String, FillProgram>,
    val commandIds: List<String>,
) {
    val fingerprint: String = buildString {
        append(route.systemPrefix)
        append(route.grammar)
        append(PromptGenerator.ASSISTANT_SUFFIX)
        for ((id, program) in fill) {
            append(id).append(program.userTurn).append(program.grammar)
        }
    }.let { "%08x".format(it.hashCode()) + "%08x".format(commandIds.joinToString(",").hashCode()) }

    /** The step-1 request for one utterance. */
    fun routeRequest(utterance: String): Tier2Request = Tier2Request(
        step = Step.ROUTE,
        utterance = utterance,
        commandId = null,
        systemPrefix = route.systemPrefix,
        fingerprint = fingerprint,
        tail = utterance + PromptGenerator.ASSISTANT_SUFFIX,
        grammar = route.grammar,
        root = GrammarGenerator.ROOT,
        maxTokens = Tier2.maxRouteTokens(route.labels),
    )

    /**
     * The step-2 request, or null if that command needs no fill.
     *
     * **The tail replays the step-1 exchange.** Keeping the KV cache warm after step 1 and
     * appending to it would save re-decoding ~20 tokens of *batched* prefill — and would cost
     * the one invariant that makes the cache safe, because `nativeGenerate` truncates to
     * `n_system` at the start of every request precisely so a cancelled or timed-out request
     * cannot leave the cache dirty for the next one. So the fill request is an ordinary
     * request: same code path, same cancellation, same timings, no native change.
     */
    fun fillRequest(commandId: String, utterance: String): Tier2Request? {
        val program = fill[commandId] ?: return null
        return Tier2Request(
            step = Step.FILL,
            utterance = utterance,
            commandId = commandId,
            systemPrefix = route.systemPrefix,
            fingerprint = fingerprint,
            tail = utterance + PromptGenerator.ASSISTANT_SUFFIX + commandId + "<|im_end|>\n" +
                "<|im_start|>user\n" + program.userTurn + PromptGenerator.ASSISTANT_SUFFIX,
            grammar = program.grammar,
            root = GrammarGenerator.ROOT,
            maxTokens = Tier2.MAX_FILL_TOKENS,
        )
    }

    companion object {
        /**
         * Builds the program, or throws if a Sock declares something a prompt cannot render.
         *
         * This is the **build-failure** entry point: `PromptGenerator` rejects a few-shot that
         * is not normalized, and a test over the real registry is what turns that into a red
         * build on a laptop. Production code calls [ofOrNull] instead.
         */
        fun of(registry: SockRegistry, introspection: Introspection = Introspection(registry)): Tier2Program {
            val catalog = Tier2Catalog.of(registry)
            return Tier2Program(
                route = RouteProgram(
                    systemPrefix = PromptGenerator.routePrefix(catalog, introspection),
                    grammar = GrammarGenerator.route(catalog),
                    labels = catalog.commandIds + CommandInvocation.NONE,
                ),
                fill = catalog.withParams.associate { command ->
                    command.id to FillProgram(
                        userTurn = PromptGenerator.fillTurn(catalog, introspection, command),
                        grammar = GrammarGenerator.fill(catalog, command),
                    )
                },
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
 * One generation, fully assembled.
 *
 * The resolver does not know what a prompt is. With two request shapes, assembling them in the
 * resolver would mean the same string-building logic in every implementation — the real one, a
 * scripted one, whatever replaces them — so it lives in `:core`, once, where it is golden-tested.
 * The native side needs only [tail], [grammar], [root] and [maxTokens]; the rest is for the
 * cache check, the log and the fakes.
 */
data class Tier2Request(
    val step: Step,
    /** The normalized utterance. The same string Tier 1 was given. */
    val utterance: String,
    /** The routed command, on a [Step.FILL] request. Null on a route. */
    val commandId: String?,
    /** What must already be in the KV cache for [tail] to make sense. */
    val systemPrefix: String,
    val fingerprint: String,
    /** Everything after the prefix, through the assistant scaffold. */
    val tail: String,
    val grammar: String,
    val root: String,
    val maxTokens: Int,
)

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

    /** Raw model output for one request, or null if it could not run. */
    suspend fun generate(request: Tier2Request): String?
}

/** What Tier 2 made of an utterance. Everything but [Resolved] behaves like today's miss. */
sealed interface Tier2Outcome {

    /** The model named a command, and it survived the gate. */
    data class Resolved(val invocation: CommandInvocation) : Tier2Outcome

    /**
     * No command fits.
     *
     * Either the route said so, or the fill step did — "the route was right and the sentence
     * still does not contain what this command needs" is the same honest answer.
     */
    data object NoCommand : Tier2Outcome

    /** The model answered, and the gate threw it out. */
    data class Rejected(val reason: String, val raw: String) : Tier2Outcome

    /** No resolver, or the resolver is not usable on this device. */
    data class Unavailable(val reason: String) : Tier2Outcome

    /** The deadline passed. Which step it passed in is in [Tier2Trace]. */
    data object Timeout : Tier2Outcome
}

/** What one step cost and what it said. */
data class StepTrace(val raw: String?, val latency: Duration) {
    fun render(name: String): String = "$name ${"%.1f".format(latency.inWholeMilliseconds / 1000.0)}s"
}

/**
 * One Tier 2 consultation, for the detail line and the flywheel.
 *
 * The per-step record is what makes a miss diagnosable. `rejected: params do not satisfy
 * clock.set_timer · route 0.8s · fill 2.9s` says the model knew the command and could not fill
 * it — which points at the fill prompt or the parameter spec, and not at routing. The single
 * shot could not tell those apart.
 */
data class Tier2Trace(
    val outcome: Tier2Outcome,
    val latency: Duration,
    val fingerprint: String,
    /** Null only if the route never ran — no resolver, or unavailable. */
    val route: StepTrace?,
    /** Null for a zero-param command, for `none`, and for a route that failed. */
    val fill: StepTrace?,
) {
    /** What the model actually emitted, last step first. Null when it never ran. */
    val raw: String? get() = fill?.raw ?: route?.raw

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
        val steps = listOfNotNull(route?.render("route"), fill?.render("fill"))
        val tail = if (steps.isEmpty()) "" else " · " + steps.joinToString(" · ")
        return "tier2 ${"%.1f".format(latency.inWholeMilliseconds / 1000.0)}s · $what$tail"
    }
}

/**
 * Route, then fill, then the gate.
 *
 * The gate is what makes "malformed or unknown-command output is impossible" true rather than
 * hoped for. The grammars already prevent most of it, but `:core` cannot verify that a grammar
 * was applied, so the gate re-derives the guarantee from the registry:
 *
 * 1. `none` from either step is the one answer meaning "no command", and [SockRegistry.build]
 *    already rejects any Sock declaring that id — so `registry.commands["none"]` is always
 *    absent and the label has exactly one meaning. This closes the loop the reserved id was
 *    opened for.
 * 2. A label no Sock declares is rejected.
 * 3. The params go through [ParamCoercion] — the same function Tier 1's palette uses. It costs
 *    one `mapValues` and buys the entire Tier 1 validation path: types, enum membership,
 *    required-ness, defaults. **The LLM gets no route into a Sock that a template does not also
 *    have.**
 *
 * Both steps run under **one** [DEFAULT_TIMEOUT]: step 2 gets whatever step 1 left. A person
 * waiting on a panel is waiting on an answer, not on a stage of one.
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
                route = null,
                fill = null,
            )
        }

        // One deadline over both steps. The native side enforces the same one inside
        // llama_decode via abort_callback, because a flag checked between tokens cannot stop a
        // prefill already in flight — but a resolver that ignores both must still not hold the
        // turn open forever. `steps` survives the timeout so the trace can say how far it got.
        val steps = Steps()
        return try {
            withTimeout(timeout) { run(utterance, steps) }
        } catch (_: TimeoutCancellationException) {
            Tier2Outcome.Timeout
        }.let { outcome ->
            Tier2Trace(outcome, started.elapsedNow(), program.fingerprint, steps.route, steps.fill)
        }
    }

    /** Mutable across the timeout boundary, so a cancelled run still reports its first step. */
    private class Steps {
        var route: StepTrace? = null
        var fill: StepTrace? = null
    }

    private suspend fun run(utterance: String, steps: Steps): Tier2Outcome {
        val routeRaw = timed { resolver.generate(program.routeRequest(utterance)) }
            .also { steps.route = it.trace }
            .value
            ?: return Tier2Outcome.Unavailable(resolver.unavailableReason ?: "keine Antwort")

        val label = routeRaw.trim()
        if (label == CommandInvocation.NONE) return Tier2Outcome.NoCommand
        val spec = registry.commands[label]
            ?: return Tier2Outcome.Rejected("unknown label '$label'", routeRaw)

        // Seven of eleven commands end here: no second request, no second prefill, no second
        // decode. That is the number that makes the split cheap rather than merely tidier.
        if (spec.params.isEmpty()) return resolved(spec, emptyMap(), routeRaw)

        val request = program.fillRequest(spec.id, utterance)
            ?: return Tier2Outcome.Rejected("no fill program for '${spec.id}'", routeRaw)
        val fillRaw = timed { resolver.generate(request) }
            .also { steps.fill = it.trace }
            .value
            ?: return Tier2Outcome.Unavailable(resolver.unavailableReason ?: "keine Antwort")

        // The fill grammar's escape. "The route was right and the sentence still does not
        // contain what this command needs" is the honest answer, and without the escape the
        // model would be forced to invent a duration for "stell einen timer".
        if (fillRaw.trim() == CommandInvocation.NONE) return Tier2Outcome.NoCommand

        val params = Tier2Json.decodeParams(fillRaw)
            ?: return Tier2Outcome.Rejected("unparsable params", fillRaw)
        return resolved(spec, params, fillRaw)
    }

    private fun resolved(spec: CommandSpec, params: Map<String, Any>, raw: String): Tier2Outcome {
        // Straight back through Tier 1's coercion. `toString()` because the palette's contract
        // is raw slot strings in, typed params out, and an Int from JSON is the same "20" a
        // template would have captured.
        val coerced = ParamCoercion.coerce(spec, params.mapValues { it.value.toString() })
            ?: return Tier2Outcome.Rejected("params do not satisfy ${spec.id}", raw)
        return Tier2Outcome.Resolved(CommandInvocation(spec.id, coerced))
    }

    private class Timed<T>(val value: T, val trace: StepTrace)

    private suspend fun timed(block: suspend () -> String?): Timed<String?> {
        val mark = TimeSource.Monotonic.markNow()
        val value = block()
        return Timed(value, StepTrace(value, mark.elapsedNow()))
    }

    companion object {
        /**
         * Deliberately the same as `DobbyController.SPEECH_WINDOW`, and it covers both steps.
         *
         * A panel whose two waits are the same length reads as deliberate; one whose waits
         * differ reads as broken. The plan's ≤4 s done-when is the *measured* target the tier
         * is tuned against — this is the safety net above it, not the goal.
         */
        val DEFAULT_TIMEOUT: Duration = 5.seconds

        /**
         * `max_tokens` for a fill request. Derived, not chosen.
         *
         * `(DEFAULT_TIMEOUT − route allowance − fill prefill allowance) × pessimistic tok/s`
         * = `(5 − 1 − 1.5) × 10` = 25. `PromptBudgetTest` asserts that the longest params-only
         * reply any fill grammar admits still fits inside it, so a Sock with three optional
         * params fails the build rather than being truncated mid-JSON at run time.
         */
        const val MAX_FILL_TOKENS: Int = 25

        /**
         * `max_tokens` for a route request: the longest label, plus room to stop.
         *
         * A bare command id is six to ten tokens and every one is a decode step, forced or not.
         * Computed from the catalog rather than fixed, so a Sock with a long id cannot be
         * silently truncated into a label that does not exist.
         */
        fun maxRouteTokens(labels: List<String>): Int =
            labels.maxOf { TokenEstimate.of(it) } + ROUTE_TOKEN_SLACK

        /** Room for the end-of-turn token, and for the estimator being one token optimistic. */
        const val ROUTE_TOKEN_SLACK: Int = 4
    }
}
