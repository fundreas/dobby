package io.dobby.core.nlu.llm

/** One decoded model reply: a command id and its flat params. Values are [String] or [Int]. */
data class Tier2Reply(val commandId: String, val params: Map<String, Any>)

/**
 * The one JSON shape the grammar admits, encoded and decoded by hand.
 *
 * `{"c":"clock.set_timer","amount":20,"unit":"minuten"}` — no `params` wrapper, a one-letter
 * command key, params flattened next to it. That is not a style preference: **every token the
 * model emits is a full decode step on the A55 cluster, including the ones the grammar forces**,
 * because llama.cpp does not skip a token just because it is the only legal one. At 8–12 tok/s
 * the plan's own `{"command":…,"params":{…}}` sketch is ~28 tokens, which is 2.5–3.5 s of decode
 * before the model has said anything Dobby did not already know. Command ids stay verbatim
 * because they are what the gate looks up.
 *
 * `:core` has no JSON dependency and this is not a reason to add one. A parser for a grammar
 * with four value shapes is forty lines; a general one brings a dependency into the module that
 * ships inside the APK, and would still need every check below bolted onto it.
 *
 * ### The decoder assumes the grammar failed
 *
 * `:core` cannot verify that the native side applied the grammar at all — a stale program, a
 * sampler that was never installed, a future resolver that calls a different runtime. So
 * [decode] is written against a model that emits whatever it likes: bounded input, prose
 * tolerated around the object, one level of nesting, no floats, no arrays, no duplicate keys,
 * and it never throws. Everything it lets through is then still checked by
 * [Tier2]'s gate against the real command spec.
 */
object Tier2Json {

    /** The key the command id lives under. One character, deliberately. */
    const val COMMAND_KEY: String = "c"

    /** Longer than any legal reply; past this the model is not answering, it is rambling. */
    const val MAX_INPUT: Int = 2048

    /**
     * The longest single string the decoder will build: a key, a command id or a param value.
     *
     * Not [GrammarGenerator.MAX_TEXT_CHARS], deliberately. That 40-char cap is the *grammar's*
     * business — it exists so the worst-case reply fits the decode budget. Re-enforcing it here
     * would mean a command id longer than forty characters could never be decoded, and it would
     * mean that on the day the grammar was not applied, a long but perfectly sensible query
     * gets thrown away in favour of a buzz. The decoder's job is to be *bounded*, which
     * [MAX_INPUT] and this already are, not to second-guess which of two bounds applies.
     */
    const val MAX_STRING: Int = 256

    fun encode(commandId: String, params: Map<String, Any> = emptyMap()): String = buildString {
        append("{\"").append(COMMAND_KEY).append("\":").append(quote(commandId))
        for ((name, value) in params) {
            append(",")
            appendField(name, value)
        }
        append("}")
    }

    fun encode(reply: Tier2Reply): String = encode(reply.commandId, reply.params)

    /**
     * The params object alone, with no command key. What a fill step emits.
     *
     * Same shape and same key order as [encode] minus the head — which is the ten decode steps
     * the two-step split stops spending on restating a command the model was just told.
     */
    fun encodeParams(params: Map<String, Any>): String = buildString {
        append("{")
        for ((index, entry) in params.entries.withIndex()) {
            if (index > 0) append(",")
            appendField(entry.key, entry.value)
        }
        append("}")
    }

    private fun StringBuilder.appendField(name: String, value: Any) {
        append(quote(name)).append(":")
        when (value) {
            is Int -> append(value)
            is Long -> append(value)
            else -> append(quote(value.toString()))
        }
    }

    /**
     * Decodes the first balanced JSON object in [raw], or null.
     *
     * Null for every kind of malformed — that is the contract, and it is why nothing here
     * throws: an exception on this path would have to be caught by the caller and turned into
     * exactly this null, one layer further from the parsing that produced it.
     */
    fun decode(raw: String): Tier2Reply? {
        val fields = decodeObject(raw) ?: return null
        val commandId = fields[COMMAND_KEY] as? String ?: return null
        if (commandId.isEmpty()) return null
        return Tier2Reply(commandId, fields - COMMAND_KEY)
    }

    /** The shared half: the first balanced object in [raw] as a flat map, or null. */
    @Suppress("ReturnCount")
    private fun decodeObject(raw: String): Map<String, Any>? {
        if (raw.length > MAX_INPUT) return null
        val start = raw.indexOf('{')
        if (start < 0) return null
        val body = balanced(raw, start) ?: return null

        val fields = mutableMapOf<String, Any>()
        val cursor = Cursor(body)
        if (!cursor.expect('{')) return null
        // "{}" carries no command, so there is nothing to route and nothing to say about it.
        if (cursor.peekAfterSpace() == '}') return null
        while (true) {
            val key = cursor.string() ?: return null
            if (!cursor.expect(':')) return null
            val value = cursor.value() ?: return null
            // A duplicate key means two different answers in one object, and picking either is
            // guessing. JSON parsers traditionally take the last one; a command router must not.
            if (key in fields) return null
            // A null is the grammar's way of saying "this optional param is absent", so it is
            // dropped here and ParamCoercion applies the spec's own default.
            if (value != NULL) fields[key] = value
            when (cursor.afterSpace()) {
                ',' -> Unit
                '}' -> break
                else -> return null
            }
        }
        if (!cursor.atEnd()) return null
        return fields
    }

    /**
     * The flat field map of a fill reply, or null.
     *
     * [decode] without the command key: after step 1 the command is already known, so a reply
     * that names one again is not answering the question it was asked. A stray `"c"` is
     * therefore rejected rather than ignored — quietly dropping it would hide a model that has
     * fallen back to the single-shot shape, which is a prompt bug worth seeing.
     */
    fun decodeParams(raw: String): Map<String, Any>? {
        val reply = decodeObject(raw) ?: return null
        if (COMMAND_KEY in reply) return null
        return reply
    }

    /** The substring from [start] to its matching brace, or null: unbalanced, nested too deep. */
    private fun balanced(raw: String, start: Int): String? {
        var depth = 0
        var inString = false
        var escaped = false
        for (index in start until raw.length) {
            val c = raw[index]
            when {
                escaped -> escaped = false
                inString && c == '\\' -> escaped = true
                c == '"' -> inString = !inString
                inString -> Unit
                c == '{' -> {
                    depth++
                    // Depth 2 is a nested object, which the grammar cannot produce and the flat
                    // shape has no meaning for.
                    if (depth > 1) return null
                }

                c == '}' -> {
                    depth--
                    if (depth == 0) return raw.substring(start, index + 1)
                }

                c == '[' -> return null
            }
        }
        return null
    }

    private fun quote(text: String): String = buildString {
        append('"')
        for (c in text) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }
        append('"')
    }

    /** Stands in for a decoded `null`, so "absent" and "present but null" stay distinguishable. */
    private val NULL = Any()

    private class Cursor(private val src: String) {
        private var index = 0

        fun atEnd(): Boolean {
            skipSpace()
            return index >= src.length
        }

        fun expect(c: Char): Boolean {
            skipSpace()
            if (index >= src.length || src[index] != c) return false
            index++
            return true
        }

        fun afterSpace(): Char? {
            skipSpace()
            return src.getOrNull(index)?.also { index++ }
        }

        fun peekAfterSpace(): Char? {
            skipSpace()
            return src.getOrNull(index)
        }

        fun string(): String? {
            skipSpace()
            if (index >= src.length || src[index] != '"') return null
            index++
            val out = StringBuilder()
            while (index < src.length) {
                when (val c = src[index++]) {
                    '"' -> return if (out.length > MAX_STRING) null else out.toString()
                    // The grammar's `char` class excludes both of these, so an escape means the
                    // grammar was not applied. Handled anyway, for exactly that reason.
                    '\\' -> {
                        when (val escape = src.getOrNull(index++)) {
                            '"', '\\', '/' -> out.append(escape)
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'b' -> out.append('\b')
                            'f' -> out.append('')
                            'u' -> {
                                val hex = src.drop(index).take(4)
                                if (hex.length < 4) return null
                                val code = hex.toIntOrNull(16) ?: return null
                                out.append(code.toChar())
                                index += 4
                            }

                            else -> return null
                        }
                    }

                    else -> out.append(c)
                }
            }
            return null
        }

        /** A string, an integer, `true`/`false`, or `null`. Floats and arrays are rejected. */
        fun value(): Any? {
            skipSpace()
            val c = src.getOrNull(index) ?: return null
            return when {
                c == '"' -> string()
                c == 'n' && src.startsWith("null", index) -> {
                    index += 4
                    NULL
                }

                c == 't' && src.startsWith("true", index) -> {
                    index += 4
                    true
                }

                c == 'f' && src.startsWith("false", index) -> {
                    index += 5
                    false
                }

                c == '-' || c.isDigit() -> integer()
                else -> null
            }
        }

        private fun integer(): Any? {
            val start = index
            if (src.getOrNull(index) == '-') index++
            while (index < src.length && src[index].isDigit()) index++
            // A float is not a param type Dobby has, and truncating one silently would be a
            // timer of the wrong length.
            if (src.getOrNull(index) == '.' || src.getOrNull(index)?.lowercaseChar() == 'e') return null
            val text = src.substring(start, index)
            if (text.isEmpty() || text == "-") return null
            return text.toIntOrNull()
        }

        private fun skipSpace() {
            while (index < src.length && src[index].isWhitespace()) index++
        }
    }
}
