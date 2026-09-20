package io.dobby.core.nlu.template

/**
 * Renders a parsed template back into something a person can read.
 *
 * The help screen and the spoken "erkläre das Kommando …" both have to answer *how do I say
 * this*, and the only honest source for that answer is the grammar the matcher actually runs.
 * A hand-written usage line is a second copy of the templates that nothing keeps in step: the
 * day somebody drops `(einen)?` the help goes on promising it. So the syntax is derived, and
 * the only thing an author writes by hand is the German prose around it ([io.dobby.core.sock.CommandHelp]).
 *
 * Two renderings, because the eye and the ear want different things:
 *
 * - [of] keeps every branch — `stell [einen] timer auf <amount> <unit>` — which is what a
 *   reader standing in front of the panel needs: it says what is optional and what the
 *   alternatives are.
 * - [spoken] picks one path through it — "stell einen timer auf <amount> <unit>" — because
 *   brackets and pipes read aloud as noise.
 */
object Syntax {

    /**
     * Readable grammar: `(a|b)` stays an alternation, `(x)?` becomes `[x]`, `{s}` becomes `<s>`.
     *
     * @param maxOptions how many branches of an alternation to show before "…". The Clock's
     *   set-timer verb list is twelve words long; a reader needs to recognise the shape, not to
     *   audit it, and the exact grammar is one line further down in the terminal listing.
     */
    fun of(template: CompiledTemplate, maxOptions: Int = ALL): String = of(template.root, maxOptions)

    fun of(node: Node, maxOptions: Int = ALL): String = render(node, maxOptions)

    /** Every branch, however many there are. */
    const val ALL: Int = Int.MAX_VALUE

    /**
     * One sayable path: first alternative everywhere, optionals dropped, slots as `<name>`.
     *
     * Dropping optionals rather than keeping them is deliberate — an optional group is by
     * definition a word the matcher does not need, and the shortest form is the one somebody
     * standing in a kitchen will actually repeat.
     */
    fun spoken(template: CompiledTemplate): String = spoken(template.root)

    fun spoken(node: Node): String = shortest(node).joinToString(" ")

    private fun render(node: Node, max: Int): String = when (node) {
        is Node.Word -> node.text
        is Node.Slot -> "<${node.name}>"
        is Node.Seq -> node.nodes.joinToString(" ") { grouped(it, max) }
        is Node.Alt -> node.options.take(max).joinToString("|") { render(it, max) } +
            if (node.options.size > max) "|…" else ""
        // No parentheses inside the brackets: `[die|der|das]` already says both things.
        is Node.Opt -> "[${render(node.node, max)}]"
    }

    /** An alternation needs its parentheses back once it sits beside anything else. */
    private fun grouped(node: Node, max: Int): String =
        if (node is Node.Alt && node.options.size > 1) "(${render(node, max)})" else render(node, max)

    private fun shortest(node: Node): List<String> = when (node) {
        is Node.Word -> listOf(node.text)
        is Node.Slot -> listOf("<${node.name}>")
        is Node.Seq -> node.nodes.flatMap { shortest(it) }
        is Node.Alt -> node.options.firstOrNull()?.let { shortest(it) }.orEmpty()
        is Node.Opt -> emptyList()
    }
}
