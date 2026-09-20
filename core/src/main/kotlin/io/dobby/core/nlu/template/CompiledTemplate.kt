package io.dobby.core.nlu.template

import io.dobby.core.nlu.Fillers
import io.dobby.core.nlu.GermanNumbers

/**
 * How specific a template is. Drives palette ordering: specific templates must be tried before
 * generic ones, or `spiele {query}` swallows "spiele radio fm4".
 */
data class Specificity(
    /** True if the template can end in an open text slot, i.e. it is greedy for the rest. */
    val endsOpen: Boolean,
    /** Literal keywords that must be present, counting the *cheapest* path through alternations. */
    val literalWords: Int,
    val textSlots: Int,
) {
    companion object {
        /** Specific first: closed templates, then more keywords, then fewer open slots. */
        val ORDER: Comparator<Specificity> = compareBy<Specificity> { it.endsOpen }
            .thenByDescending { it.literalWords }
            .thenBy { it.textSlots }
    }
}

/** One parsed template, ready to match against a token list. */
class CompiledTemplate(val source: String, val root: Node.Seq) {

    val slots: List<Node.Slot> = collectSlots(root)

    /**
     * Every literal keyword in the template, in source order.
     *
     * The palette's phonetic tier is built from these, across every template it holds: a
     * keyword's code is only safe to act on once it has been compared to every other keyword
     * anybody could have said instead ([KeywordMatcher.over]).
     */
    val literals: List<String> = collectLiterals(root)

    val specificity: Specificity = Specificity(
        endsOpen = endsOpen(root),
        literalWords = minWords(root),
        textSlots = slots.count { it.kind == SlotKind.TEXT },
    )

    /**
     * True if one literal word and nothing else satisfies this template.
     *
     * The template with no anchor: nothing else in the utterance has to agree with it, so
     * whatever protects it has to come from the word alone. Two rules hang off this, and they
     * are the same argument twice:
     *
     * - [io.dobby.core.registry.Palette] matches it with [KeywordMatcher.STRICT], because a
     *   single phonetic near-miss would be the whole match.
     * - Filler is never skipped *in front of* it — see [match]. A sentence of filler words with
     *   one command word somewhere inside it is what an ordinary remark looks like ("es ist
     *   jetzt aus"), and a bare `aus` would take all of them.
     */
    val isBareKeyword: Boolean = specificity.literalWords == 1 && slots.isEmpty()

    /**
     * Matches the whole token list, anchored at both ends.
     *
     * @param keywords how a literal is compared to a spoken token. Defaults to
     *   [KeywordMatcher.STRICT], the pre-M6 behaviour, so a caller with no palette behind it —
     *   a test, a follow-up template — keeps exact-plus-Levenshtein and nothing more.
     * @param fillers tokens the matcher may skip before a keyword and after the last one.
     *   Defaults to [Fillers.NONE], which is this method byte for byte as it was — the palette
     *   turns skipping on for a second pass only, once nothing has matched strictly. An
     *   [isBareKeyword] template skips only what *trails* it: "stopp bitte" is politeness,
     *   "es ist jetzt aus" is somebody talking about the oven.
     * @param enumValues allowed values for an `{x:enum}` slot, by slot name.
     * @return raw slot bindings, or null if the template does not match.
     */
    fun match(
        tokens: List<String>,
        keywords: KeywordMatcher = KeywordMatcher.STRICT,
        fillers: Fillers = Fillers.NONE,
        enumValues: (String) -> List<String>?,
    ): Map<String, String>? =
        walk(root, tokens, State(0, emptyMap()), keywords, fillers, enumValues)
            // The end anchor holds: everything left over has to be filler, and with
            // [Fillers.NONE] "filler" is nothing at all.
            .firstOrNull { state -> (state.position until tokens.size).all { tokens[it] in fillers } }
            ?.bindings

    override fun toString(): String = source

    private data class State(val position: Int, val bindings: Map<String, String>)

    @Suppress("LongParameterList")
    private fun walk(
        node: Node,
        tokens: List<String>,
        state: State,
        keywords: KeywordMatcher,
        fillers: Fillers,
        enums: (String) -> List<String>?,
    ): Sequence<State> = when (node) {
        // A keyword may be preceded by filler: try it where it stands first, so an exact path
        // is always found before a skipping one, then after 1..n consecutive filler tokens.
        // Skipping only ever *adds* paths, which is why a filler that is also a literal (`es`,
        // `ist`, `die`, `mir`) still takes the literal path — and why an [isBareKeyword]
        // template, which has no anchor to add them to, gets none.
        is Node.Word ->
            skips(tokens, state.position, if (isBareKeyword) Fillers.NONE else fillers)
                .filter { at -> at < tokens.size && keywords.matches(tokens[at], node.text) }
                .map { at -> state.copy(position = at + 1) }

        is Node.Seq ->
            node.nodes.fold(sequenceOf(state)) { states, next ->
                states.flatMap { walk(next, tokens, it, keywords, fillers, enums) }
            }

        is Node.Alt ->
            node.options.asSequence().flatMap { walk(it, tokens, state, keywords, fillers, enums) }

        // Try consuming first: in "spiele {query}( ab)?" the trailing "ab" should be the optional,
        // not part of the query.
        is Node.Opt -> walk(node.node, tokens, state, keywords, fillers, enums) + sequenceOf(state)

        // Slots are untouched by filler skipping: "{query}" captures what was said, fillers
        // included, or "spiele es muss liebe sein" would lose its first two words. An INT or
        // ENUM slot takes exactly one token as before — filler in front of one is skipped by
        // the keyword or the start of the utterance, not here.
        is Node.Slot -> matchSlot(node, tokens, state, enums)
    }

    private fun matchSlot(
        slot: Node.Slot,
        tokens: List<String>,
        state: State,
        enums: (String) -> List<String>?,
    ): Sequence<State> = when (slot.kind) {
        // Non-greedy: shortest capture first, so a following literal gets its chance.
        SlotKind.TEXT -> (1..(tokens.size - state.position)).asSequence().map { length ->
            val value = tokens.subList(state.position, state.position + length).joinToString(" ")
            State(state.position + length, state.bindings + (slot.name to value))
        }

        SlotKind.INT -> {
            val value = tokens.getOrNull(state.position)?.let { GermanNumbers.parseSlotValue(it) }
            if (value == null) {
                emptySequence()
            } else {
                sequenceOf(State(state.position + 1, state.bindings + (slot.name to value.toString())))
            }
        }

        SlotKind.ENUM -> {
            val token = tokens.getOrNull(state.position)
            val allowed = enums(slot.name)
            val hit = if (token == null || allowed == null) {
                null
            } else {
                allowed.firstOrNull { it.equals(token, ignoreCase = true) }
                    // A constrained slot may be fuzzy: the candidate set is closed, so "minute"
                    // still reaches the "minuten" enum value without risking a false positive.
                    ?: allowed.firstOrNull { Levenshtein.atMost(token, it.lowercase(), 1) }
                    // …and for the same reason it may be phonetic. The candidate set is the
                    // guard the palette needs [KeywordMatcher] for: `sekunden`, `minuten` and
                    // `stunden` are 84626, 6626 and 82626, so "schtunden" can only mean one of
                    // them. `singleOrNull` is the whole safety argument — two candidates with
                    // one code is an ambiguity, and an ambiguous unit is worse than a reprompt.
                    ?: allowed.singleOrNull { soundsLike(token, it.lowercase()) }
            }
            if (hit == null) {
                emptySequence()
            } else {
                sequenceOf(State(state.position + 1, state.bindings + (slot.name to hit)))
            }
        }
    }

    private companion object {
        /**
         * The positions a keyword may be tried at: [from], then past each consecutive filler.
         *
         * [from] comes first and unconditionally, so the no-skip path is always explored before
         * any skipping one and an utterance that matched before matches the same way now.
         */
        fun skips(tokens: List<String>, from: Int, fillers: Fillers): Sequence<Int> =
            if (fillers.isEmpty) {
                sequenceOf(from)
            } else {
                sequence {
                    var at = from
                    yield(at)
                    while (at < tokens.size && tokens[at] in fillers) {
                        at++
                        yield(at)
                    }
                }
            }

        /**
         * Whether a spoken token is a garble of one closed-set candidate.
         *
         * The same test [KeywordMatcher] applies to a keyword, minus the palette-wide contested
         * check, which the caller replaces with `singleOrNull` over the candidate set.
         */
        fun soundsLike(token: String, candidate: String): Boolean {
            if (token.length < KeywordMatcher.MIN_LENGTH || candidate.length < KeywordMatcher.MIN_LENGTH) {
                return false
            }
            val code = Phonetics.koelner(candidate)
            if (code.length < KeywordMatcher.MIN_CODE_LENGTH || Phonetics.koelner(token) != code) return false
            return Phonetics.skeleton(token) != Phonetics.skeleton(candidate)
        }

        fun collectLiterals(node: Node): List<String> = when (node) {
            is Node.Word -> listOf(node.text)
            is Node.Slot -> emptyList()
            is Node.Seq -> node.nodes.flatMap { collectLiterals(it) }
            is Node.Alt -> node.options.flatMap { collectLiterals(it) }
            is Node.Opt -> collectLiterals(node.node)
        }

        fun collectSlots(node: Node): List<Node.Slot> = when (node) {
            is Node.Slot -> listOf(node)
            is Node.Word -> emptyList()
            is Node.Seq -> node.nodes.flatMap { collectSlots(it) }
            is Node.Alt -> node.options.flatMap { collectSlots(it) }
            is Node.Opt -> collectSlots(node.node)
        }

        /** Keywords on the cheapest path — an optional group may contribute nothing. */
        fun minWords(node: Node): Int = when (node) {
            is Node.Word -> 1
            is Node.Slot -> 0
            is Node.Opt -> 0
            is Node.Seq -> node.nodes.sumOf { minWords(it) }
            is Node.Alt -> node.options.minOfOrNull { minWords(it) } ?: 0
        }

        fun endsOpen(node: Node): Boolean = when (node) {
            is Node.Slot -> node.kind == SlotKind.TEXT
            is Node.Word -> false
            is Node.Alt -> node.options.any { endsOpen(it) }
            is Node.Opt -> endsOpen(node.node)
            is Node.Seq -> {
                // Trailing optionals do not close a template: "spiele {query}( ab)?" is still greedy.
                var i = node.nodes.lastIndex
                var open = false
                while (i >= 0) {
                    val last = node.nodes[i]
                    if (last is Node.Opt) {
                        if (endsOpen(last)) {
                            open = true
                            break
                        }
                        i--
                    } else {
                        open = endsOpen(last)
                        break
                    }
                }
                open
            }
        }
    }
}

fun compileTemplate(source: String): CompiledTemplate =
    CompiledTemplate(source, TemplateParser.parse(source))
