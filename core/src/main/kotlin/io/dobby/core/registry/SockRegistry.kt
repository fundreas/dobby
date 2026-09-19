package io.dobby.core.registry

import io.dobby.core.nlu.Normalizer
import io.dobby.core.nlu.template.CompiledTemplate
import io.dobby.core.nlu.template.Levenshtein
import io.dobby.core.nlu.template.Node
import io.dobby.core.nlu.template.SlotKind
import io.dobby.core.nlu.template.TemplateSyntaxException
import io.dobby.core.nlu.template.compileTemplate
import io.dobby.core.sock.CommandInvocation
import io.dobby.core.sock.CommandSpec
import io.dobby.core.sock.ExclusiveCommandSpec
import io.dobby.core.sock.SharedCommandSpec
import io.dobby.core.sock.SharedSubscription
import io.dobby.core.sock.Sock
import io.dobby.core.sock.TemplatePattern

/** One Sock's place in one chain. */
data class Subscriber(
    val sock: Sock,
    val subscription: SharedSubscription,
    /** Registration order, the final tiebreak after activity and priority. */
    val order: Int,
)

data class Chain(val command: SharedCommandSpec, val subscribers: List<Subscriber>)

class RegistryValidationException(val errors: List<String>) :
    IllegalStateException("Sock registry is invalid:\n" + errors.joinToString("\n") { "  - $it" })

data class RegistryBuild(val registry: SockRegistry?, val errors: List<String>) {
    val isValid: Boolean get() = registry != null && errors.isEmpty()
}

/**
 * Assembles the command palette from the registered Socks and validates that they compose.
 *
 * A validation failure is fatal in debug builds ([buildOrThrow]) and a surfaced degraded state
 * in release ([build]) — see `dobby-plan.md` §3.5.
 */
class SockRegistry private constructor(
    val socks: List<Sock>,
    val palette: Palette,
    val commands: Map<String, CommandSpec>,
    private val owners: Map<String, Sock>,
    private val chains: Map<String, Chain>,
) {
    fun ownerOf(commandId: String): Sock? = owners[commandId]

    fun chainFor(commandId: String): Chain? = chains[commandId]

    val chainIds: Set<String> get() = chains.keys

    /**
     * Runs every declared [io.dobby.core.sock.Example] through the palette.
     *
     * This is the collision gate: if a Sock's own example resolves to somebody else's command,
     * two Socks are fighting over an utterance and one of them has to move — or the command
     * has to become shared.
     *
     * It gates three things, because an [io.dobby.core.sock.Example] does three jobs:
     *
     * 1. A Tier 1 example must resolve to its own command, with its own params.
     * 2. A Tier 2 few-shot (`matchedByTemplates = false`) must *not* resolve. Tier 1 being
     *    unable to reach it is the entire reason it is in the system prompt, so one that Tier 1
     *    can reach means either the flag is wrong or somebody added a template that covers it —
     *    and in both cases the prompt is now teaching the model to duplicate Tier 1's work.
     * 3. [SharedSubscription.extraExamples] are checked at all, which until now they were not.
     *    They are the phrasings one Sock adds to somebody else's chain, which is precisely where
     *    a collision is easiest to create and hardest to notice.
     */
    fun checkExamples(): List<String> {
        val problems = mutableListOf<String>()
        for (command in commands.values) {
            problems += check(command.id, command.examples)
        }
        for (sock in socks) {
            for (subscription in sock.shared) {
                problems += check(
                    subscription.command.id,
                    subscription.extraExamples,
                    from = "${sock.id} extraExamples",
                )
            }
        }
        return problems
    }

    private fun check(
        commandId: String,
        examples: List<io.dobby.core.sock.Example>,
        from: String = commandId,
    ): List<String> {
        val problems = mutableListOf<String>()
        for (example in examples) {
            val match = palette.match(Normalizer.tokenize(example.utterance))
            if (!example.matchedByTemplates) {
                // A few-shot Tier 1 can already match is not a few-shot. It is a template that
                // exists, described to the model as though it did not.
                if (match != null) {
                    problems += "$from: Tier 2 example \"${example.utterance}\" is matched by " +
                        "Tier 1 via \"${match.entry.template.source}\" → ${match.invocation.commandId}; " +
                        "drop matchedByTemplates = false, or drop the example"
                }
                continue
            }
            when {
                match == null ->
                    problems += "$from: example \"${example.utterance}\" matches no template"

                match.invocation.commandId != commandId ->
                    problems += "$from: example \"${example.utterance}\" resolves to " +
                        "${match.invocation.commandId} via \"${match.entry.template.source}\""

                example.params.isNotEmpty() && match.invocation.params != example.params ->
                    problems += "$from: example \"${example.utterance}\" produced " +
                        "${match.invocation.params}, expected ${example.params}"
            }
        }
        return problems
    }

    /**
     * Templates a single spoken word can satisfy, where that word is long enough to be fuzzed.
     *
     * A warning and never an error: `lauter`, `leiser` and `stumm` are correct uses of exactly
     * this shape. The point is that each one is a judgement — "does anything else in spoken
     * German land inside this keyword's tolerance" (`socks.specs/README.md` §6) — and a
     * judgement is worth listing so a reviewer makes it deliberately. A bare `zeit` is the case
     * that fails it: four characters, tolerance 1, and `seit`, `weit` and `zeig` come with it.
     */
    fun checkSingleKeywordTemplates(): List<String> =
        palette.entries.flatMap { entry ->
            soleKeywords(entry.template.root)
                .filter { it.length in FUZZED_ALONE }
                .map { keyword ->
                    "${entry.command.id}: template \"${entry.template.source}\" can be matched by " +
                        "the single word \"$keyword\" at tolerance ${Levenshtein.tolerance(keyword.length)}"
                }
        }

    /** The keywords that alone satisfy [node]; empty when it needs more than one word. */
    private fun soleKeywords(node: Node): List<String> = when (node) {
        is Node.Word -> listOf(node.text)
        is Node.Alt -> node.options.flatMap(::soleKeywords)
        is Node.Seq -> node.nodes.filterNot { it is Node.Opt }.singleOrNull()
            ?.let(::soleKeywords).orEmpty()

        // An omitted optional contributes nothing, and a slot is not a keyword.
        is Node.Opt, is Node.Slot -> emptyList()
    }

    companion object {
        private val SOCK_ID = Regex("[a-z][a-z0-9_]*")

        /** Tolerance 1: long enough to be fuzzed, short enough to have close neighbours. */
        private val FUZZED_ALONE = 4..7

        fun buildOrThrow(socks: List<Sock>): SockRegistry {
            val build = build(socks)
            if (build.registry == null || build.errors.isNotEmpty()) {
                throw RegistryValidationException(build.errors)
            }
            return build.registry
        }

        @Suppress("CyclomaticComplexMethod")
        fun build(socks: List<Sock>): RegistryBuild {
            val errors = mutableListOf<String>()
            val owners = mutableMapOf<String, Sock>()
            val commands = mutableMapOf<String, CommandSpec>()
            val paletteEntries = mutableListOf<PaletteEntry>()
            val subscriptions = mutableMapOf<String, MutableList<Subscriber>>()
            val sharedSpecs = mutableMapOf<String, SharedCommandSpec>()
            var order = 0

            val seenSockIds = mutableSetOf<String>()
            for (sock in socks) {
                if (!SOCK_ID.matches(sock.id)) {
                    errors += "sock id '${sock.id}' must match ${SOCK_ID.pattern}"
                }
                if (!seenSockIds.add(sock.id)) {
                    errors += "duplicate sock id '${sock.id}'"
                }
            }

            for (sock in socks) {
                for (command in sock.commands) {
                    when {
                        command.id == CommandInvocation.NONE ->
                            errors += "'${CommandInvocation.NONE}' is reserved by core (${sock.id})"

                        command.id.startsWith(CommandInvocation.SHARED_PREFIX) ->
                            errors += "'${command.id}' uses the reserved shared namespace but is " +
                                "declared as an exclusive command of '${sock.id}'"

                        !command.id.startsWith("${sock.id}.") ->
                            errors += "'${command.id}' must be prefixed with '${sock.id}.'"
                    }
                    if (commands.containsKey(command.id)) {
                        errors += "duplicate command id '${command.id}'"
                    }
                    commands[command.id] = command
                    owners[command.id] = sock
                    errors += validateParamsAreNamed(command)
                    order = addTemplates(command, command.templates, null, paletteEntries, errors, order)
                    if (command.templates.isEmpty()) {
                        errors += "'${command.id}' declares no templates and can never be reached"
                    }
                }

                for (subscription in sock.shared) {
                    val spec = subscription.command
                    if (!spec.id.startsWith(CommandInvocation.SHARED_PREFIX)) {
                        errors += "shared command '${spec.id}' (${sock.id}) must start with " +
                            "'${CommandInvocation.SHARED_PREFIX}'"
                    }
                    val existing = sharedSpecs[spec.id]
                    if (existing == null) {
                        sharedSpecs[spec.id] = spec
                        commands[spec.id] = spec
                        errors += validateParamsAreNamed(spec)
                        order = addTemplates(spec, spec.templates, null, paletteEntries, errors, order)
                    } else if (existing.params != spec.params) {
                        // Chain members must agree on the shape of the command, or the dispatcher
                        // would hand different Socks differently-shaped params.
                        errors += "subscribers to '${spec.id}' declare different params: " +
                            "${existing.params} vs ${spec.params} (${sock.id})"
                    }
                    subscriptions.getOrPut(spec.id) { mutableListOf() } +=
                        Subscriber(sock, subscription, subscriptions[spec.id]?.size ?: 0)
                    order = addTemplates(
                        spec,
                        subscription.extraTemplates,
                        sock.id,
                        paletteEntries,
                        errors,
                        order,
                    )
                }
            }

            val chains = sharedSpecs.mapValues { (id, spec) ->
                Chain(spec, subscriptions[id].orEmpty().toList())
            }

            val registry = SockRegistry(
                socks = socks,
                palette = Palette(paletteEntries),
                commands = commands,
                owners = owners,
                chains = chains,
            )
            return RegistryBuild(if (errors.isEmpty()) registry else null, errors)
        }

        /** Every `{slot}` must have a matching [io.dobby.core.sock.ParamSpec], or it binds nothing. */
        private fun validateParamsAreNamed(command: CommandSpec): List<String> {
            val declared = command.params.map { it.name }.toSet()
            val problems = mutableListOf<String>()
            for (template in command.templates) {
                for (fixed in template.params.keys) {
                    if (fixed !in declared) {
                        problems += "'${command.id}' template \"${template.pattern}\" fixes param " +
                            "'$fixed' but declares no such param"
                    }
                }
                val compiled = runCatching { compileTemplate(template.pattern) }.getOrNull() ?: continue
                for (slot in compiled.slots) {
                    if (slot.name !in declared) {
                        problems += "'${command.id}' template \"${template.pattern}\" captures " +
                            "{${slot.name}} but declares no such param"
                    }
                }
            }
            return problems
        }

        private fun addTemplates(
            command: CommandSpec,
            templates: List<TemplatePattern>,
            contributedBy: String?,
            into: MutableList<PaletteEntry>,
            errors: MutableList<String>,
            startOrder: Int,
        ): Int {
            var order = startOrder
            for (template in templates) {
                val compiled: CompiledTemplate = try {
                    compileTemplate(template.pattern)
                } catch (e: TemplateSyntaxException) {
                    errors += "'${command.id}': ${e.message}"
                    continue
                }
                val only = compiled.root.nodes.singleOrNull()
                // A bare TEXT slot matches literally any utterance. A bare ENUM slot is fine —
                // its candidate set is closed, which is how "lauter" works.
                if (only is Node.Slot && only.kind == SlotKind.TEXT) {
                    errors += "'${command.id}' template \"${template.pattern}\" is a bare text slot " +
                        "and would match every utterance"
                    continue
                }
                into += PaletteEntry(command, compiled, template.params, contributedBy, order++)
            }
            return order
        }
    }
}

/** Builds a registry from an exclusive or shared command spec list. Convenience for tests. */
fun List<Sock>.toRegistry(): SockRegistry = SockRegistry.buildOrThrow(this)
