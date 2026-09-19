package io.dobby.core.nlu.template

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
     * Matches the whole token list, anchored at both ends.
     *
     * @param keywords how a literal is compared to a spoken token. Defaults to
     *   [KeywordMatcher.STRICT], the pre-M6 behaviour, so a caller with no palette behind it —
     *   a test, a follow-up template — keeps exact-plus-Levenshtein and nothing more.
     * @param enumValues allowed values for an `{x:enum}` slot, by slot name.
     * @return raw slot bindings, or null if the template does not match.
     */
    fun match(
        tokens: List<String>,
        keywords: KeywordMatcher = KeywordMatcher.STRICT,
        enumValues: (String) -> List<String>?,
    ): Map<String, String>? =
        walk(root, tokens, State(0, emptyMap()), keywords, enumValues)
            .firstOrNull { it.position == tokens.size }
            ?.bindings

    override fun toString(): String = source

    private data class State(val position: Int, val bindings: Map<String, String>)

    private fun walk(
        node: Node,
        tokens: List<String>,
        state: State,
        keywords: KeywordMatcher,
        enums: (String) -> List<String>?,
    ): Sequence<State> = when (node) {
        is Node.Word ->
            if (state.position < tokens.size && keywords.matches(tokens[state.position], node.text)) {
                sequenceOf(state.copy(position = state.position + 1))
            } else {
                emptySequence()
            }

        is Node.Seq ->
            node.nodes.fold(sequenceOf(state)) { states, next ->
                states.flatMap { walk(next, tokens, it, keywords, enums) }
            }

        is Node.Alt -> node.options.asSequence().flatMap { walk(it, tokens, state, keywords, enums) }

        // Try consuming first: in "spiele {query}( ab)?" the trailing "ab" should be the optional,
        // not part of the query.
        is Node.Opt -> walk(node.node, tokens, state, keywords, enums) + sequenceOf(state)

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
            }
            if (hit == null) {
                emptySequence()
            } else {
                sequenceOf(State(state.position + 1, state.bindings + (slot.name to hit)))
            }
        }
    }

    private companion object {
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
