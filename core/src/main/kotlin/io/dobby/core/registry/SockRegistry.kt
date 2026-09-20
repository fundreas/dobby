package io.dobby.core.registry

import io.dobby.core.nlu.Fillers
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
            if (example.tier2Only) {
                // A few-shot Tier 1 can already match is not a few-shot: it is a template that
                // exists, described to the model as though it did not. A held-out case Tier 1
                // can match is worse — it would never reach Tier 2 at all, so the accuracy
                // number it contributes to is measuring the template matcher.
                if (match != null) {
                    val kind = if (example.heldOut) "held-out" else "Tier 2"
                    problems += "$from: $kind example \"${example.utterance}\" is matched by " +
                        "Tier 1 via \"${match.entry.template.source}\" → ${match.invocation.commandId}; " +
                        "promote it to a template, or change the wording"
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

    /**
     * What the filler list must never cost, checked against the palette that was actually built.
     *
     * [io.dobby.core.nlu.Fillers] is a judgement — "no command's meaning changes when this word
     * is dropped" — and this is the mechanical form of it. Two rules, and both of them have
     * already fired on this catalog: `halt` and `danke` are commands in one word, and a first
     * draft of the list carried them.
     *
     * 1. **A filler is never a command on its own.** If a bare template can be satisfied by one
     *    word, that word means something, whatever a grammar book says about it.
     * 2. **Two commands never become the same sentence once fillers are gone.** This is the
     *    distinguishing-word check: if the only thing telling `a.x` and `b.y` apart is a word
     *    the recogniser swaps at random, then the utterance was always a coin flip and skipping
     *    is about to make it one in practice.
     *
     * Errors rather than warnings, and surfaced wherever the registry is assembled — the CLI
     * prints them at startup and the tests assert they are empty.
     */
    fun checkFillers(): List<String> {
        val fillers = palette.fillers
        if (fillers.isEmpty) return emptyList()
        val problems = mutableListOf<String>()

        for (entry in palette.entries) {
            for (keyword in soleKeywords(entry.template.root).distinct()) {
                if (keyword in fillers) {
                    problems += "${entry.command.id}: \"$keyword\" satisfies template " +
                        "\"${entry.template.source}\" on its own and is also a ${fillers.language} " +
                        "filler — a command is never noise; take it off the list"
                }
            }
        }

        // backbone → the wordings already claimed for it, and by whom.
        val claimed = mutableMapOf<List<String>, MutableList<Triple<String, String, Wording>>>()
        for (entry in palette.entries) {
            val wordings = wordings(entry.template.root, fillers) ?: run {
                problems += "${entry.command.id}: template \"${entry.template.source}\" has more " +
                    "than $MAX_TEMPLATE_PATHS wordings; split it, or the filler check cannot see it"
                continue
            }
            for (wording in wordings) {
                if (wording.backbone.isEmpty()) {
                    problems += "${entry.command.id}: template \"${entry.template.source}\" has a " +
                        "wording made of nothing but ${fillers.language} fillers"
                    continue
                }
                val others = claimed.getOrPut(wording.backbone) { mutableListOf() }
                for ((id, source, other) in others) {
                    if (id == entry.command.id || !other.confusableWith(wording)) continue
                    problems += "$id (\"$source\") and ${entry.command.id} " +
                        "(\"${entry.template.source}\") are both " +
                        "\"${wording.backbone.joinToString(" ")}\" once fillers are dropped — one " +
                        "of the words telling them apart is on the list"
                }
                others += Triple(entry.command.id, entry.template.source, wording)
            }
        }
        return problems.distinct()
    }

    /**
     * One wording of one template: the words that carry it, and where filler sat between them.
     *
     * The [backbone] is the template with its fillers removed, a slot standing in for itself by
     * kind — `{amount:int}` and `{query}` are not the same surface even where the words around
     * them are. [precededByFiller] has one more entry than the backbone: entry *k* is whether
     * filler ran immediately before backbone word *k*, and the last is whether any trailed.
     */
    private data class Wording(val backbone: List<String>, val precededByFiller: List<Boolean>) {

        /**
         * Whether some utterance could match both wordings once filler skipping is on.
         *
         * Equal backbones are necessary and not sufficient, and the difference is the whole
         * reason this is not a set comparison. Such an utterance has to contain both wordings'
         * filler words, and each side then has to *skip* the other's — which the matcher only
         * does in front of a keyword, never in front of a slot. So `{a:int} mal` and
         * `mal {b:int}` share the backbone `{int}` and are still not confusable: the utterance
         * that would prove it has to put a "mal" in front of an integer slot, and no skipping
         * happens there. `timer aus` and `timer das aus` are, and that is the pair this rule is
         * for.
         */
        fun confusableWith(other: Wording): Boolean {
            if (backbone != other.backbone) return false
            return backbone.indices.all { k ->
                (!precededByFiller[k] && !other.precededByFiller[k]) || !backbone[k].startsWith("{")
            }
        }
    }

    /** Every wording of [node], fillers folded away, or null if there are too many. */
    private fun wordings(node: Node, fillers: Fillers): Set<Wording>? {
        val all = mutableSetOf<Wording>()
        for (path in paths(node)) {
            if (all.size > MAX_TEMPLATE_PATHS) return null
            val backbone = mutableListOf<String>()
            val preceded = mutableListOf<Boolean>()
            var gap = false
            for (item in path) {
                if (item in fillers) {
                    gap = true
                } else {
                    backbone += item
                    preceded += gap
                    gap = false
                }
            }
            all += Wording(backbone, preceded + gap)
        }
        return all
    }

    /** Every literal path through a template, a slot standing in for itself. */
    private fun paths(node: Node): Sequence<List<String>> = when (node) {
        is Node.Word -> sequenceOf(listOf(node.text))
        is Node.Slot -> sequenceOf(listOf("{${node.kind.name.lowercase()}}"))
        is Node.Alt -> node.options.asSequence().flatMap { paths(it) }
        is Node.Opt -> paths(node.node) + sequenceOf(emptyList())
        is Node.Seq -> node.nodes.fold(sequenceOf(emptyList())) { prefixes, next ->
            prefixes.flatMap { prefix -> paths(next).map { prefix + it } }
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

        /**
         * How many wordings one template may have before [checkFillers] gives up on it.
         *
         * The calculator's optional run-up alone is sixty-odd, and it multiplies with the
         * operator alternation and the trailing verb. Ten thousand is comfortably above every
         * template in the catalog and far below anything that would make the check slow; a
         * template past it is reported rather than silently skipped.
         */
        private const val MAX_TEMPLATE_PATHS = 10_000

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
                // German is the only language the engine has: the normalizer's locale, the
                // Kölner phonetics and this list are one decision, and they move together
                // the day a language setting lands.
                palette = Palette(paletteEntries, fillers = Fillers.DE),
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
