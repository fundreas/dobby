package io.dobby.core.nlu.template

/** What kind of value a `{slot}` captures. */
enum class SlotKind { TEXT, INT, ENUM }

/**
 * A parsed template.
 *
 * Deliberately NOT compiled to a regex, although the plan originally said so: regexes cannot
 * express Levenshtein tolerance, and fuzzy keyword matching is a hard requirement (STT says
 * "schbiele"). A regex would need a second verification pass over every keyword position,
 * which is more machinery than a direct token matcher, not less. So templates compile to this
 * tree and are matched against the token list with backtracking.
 */
sealed interface Node {
    /** A literal keyword. Matched with Levenshtein tolerance — see [Levenshtein.fuzzyEquals]. */
    data class Word(val text: String) : Node

    /** A named capture. Slot content is never fuzzy-matched (except a constrained [SlotKind.ENUM]). */
    data class Slot(val name: String, val kind: SlotKind) : Node

    data class Seq(val nodes: List<Node>) : Node

    /** `(a|b)` */
    data class Alt(val options: List<Node>) : Node

    /** `(...)?` */
    data class Opt(val node: Node) : Node
}

class TemplateSyntaxException(source: String, message: String) :
    IllegalArgumentException("invalid template \"$source\": $message")

/**
 * Parses the template DSL documented in `socks.specs/README.md` §6.
 *
 * ```
 * seq   := item*
 * item  := group | slot | word
 * group := '(' seq ('|' seq)* ')' '?'?
 * slot  := '{' name (':' ('int'|'enum'|'text'))? '}'
 * word  := [^ ()|{}?]+
 * ```
 *
 * Whitespace only separates words; it carries no meaning, because matching is token-based.
 * That is why `( ab)?` and `(ab)?` are equivalent here.
 */
object TemplateParser {
    private const val SPECIAL = "()|{}?"

    fun parse(source: String): Node.Seq {
        val parser = Cursor(source)
        val seq = parser.sequence()
        if (!parser.atEnd) throw TemplateSyntaxException(source, "unexpected '${parser.peek()}' at ${parser.index}")
        if (seq.nodes.isEmpty()) throw TemplateSyntaxException(source, "template is empty")
        return seq
    }

    private class Cursor(private val src: String) {
        var index: Int = 0
            private set

        val atEnd: Boolean get() = index >= src.length

        fun peek(): Char? = src.getOrNull(index)

        fun sequence(): Node.Seq {
            val nodes = mutableListOf<Node>()
            while (!atEnd) {
                when (val c = src[index]) {
                    ' ', '\t' -> index++
                    '|', ')' -> return Node.Seq(nodes)
                    '(' -> nodes += group()
                    '{' -> nodes += slot()
                    '}' -> throw TemplateSyntaxException(src, "unmatched '}' at $index")
                    '?' -> throw TemplateSyntaxException(src, "'?' at $index does not follow a group")
                    else -> nodes += word(c)
                }
            }
            return Node.Seq(nodes)
        }

        private fun group(): Node {
            val open = index
            index++ // '('
            val options = mutableListOf<Node>()
            options += sequence()
            while (peek() == '|') {
                index++
                options += sequence()
            }
            if (peek() != ')') throw TemplateSyntaxException(src, "unclosed '(' at $open")
            index++ // ')'
            if (options.any { it is Node.Seq && it.nodes.isEmpty() }) {
                throw TemplateSyntaxException(src, "empty alternative in group at $open")
            }
            val node: Node = if (options.size == 1) options.single() else Node.Alt(options)
            return if (peek() == '?') {
                index++
                Node.Opt(node)
            } else {
                node
            }
        }

        private fun slot(): Node.Slot {
            val open = index
            index++ // '{'
            val close = src.indexOf('}', index)
            if (close < 0) throw TemplateSyntaxException(src, "unclosed '{' at $open")
            val body = src.substring(index, close)
            index = close + 1
            val parts = body.split(':')
            val name = parts[0].trim()
            if (name.isEmpty()) throw TemplateSyntaxException(src, "slot at $open has no name")
            val kind = when (val t = parts.getOrNull(1)?.trim()) {
                null, "", "text", "string" -> SlotKind.TEXT
                "int" -> SlotKind.INT
                "enum" -> SlotKind.ENUM
                else -> throw TemplateSyntaxException(src, "unknown slot type '$t' at $open")
            }
            if (parts.size > 2) throw TemplateSyntaxException(src, "malformed slot '{$body}' at $open")
            return Node.Slot(name, kind)
        }

        private fun word(first: Char): Node.Word {
            val start = index
            index++
            while (!atEnd && !src[index].isWhitespace() && src[index] !in SPECIAL) index++
            val text = src.substring(start, index)
            check(text.isNotEmpty()) { "word parse consumed nothing at $start ('$first')" }
            return Node.Word(text)
        }
    }
}
