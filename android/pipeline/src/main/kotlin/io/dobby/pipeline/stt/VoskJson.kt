package io.dobby.pipeline.stt

/**
 * Pulls one string field out of a Vosk result.
 *
 * Vosk answers `{"text" : "wie spät ist es"}` or `{"partial" : "wie spät"}` — two flat shapes
 * with one field each. That is not worth a JSON dependency, and `org.json` would be worse than
 * none: it is an Android framework class, so every unit test touching it throws "not mocked"
 * and the parse could only be exercised on a device.
 *
 * So it lives here, pure and unit-tested, and the emulator stays out of the loop.
 */
object VoskJson {

    /** The value of [field], or `""` when it is absent, null, or not a string. */
    fun stringField(json: String, field: String): String {
        val key = "\"$field\""
        var at = json.indexOf(key)
        while (at >= 0) {
            var i = at + key.length
            while (i < json.length && json[i].isWhitespace()) i++
            if (i < json.length && json[i] == ':') {
                i++
                while (i < json.length && json[i].isWhitespace()) i++
                return if (i < json.length && json[i] == '"') readString(json, i + 1) else ""
            }
            at = json.indexOf(key, at + 1)
        }
        return ""
    }

    private fun readString(json: String, start: Int): String {
        val out = StringBuilder()
        var i = start
        while (i < json.length) {
            when (val c = json[i]) {
                '"' -> return out.toString()
                '\\' -> {
                    i++
                    if (i >= json.length) return out.toString()
                    when (val escaped = json[i]) {
                        'n' -> out.append('\n')
                        't' -> out.append('\t')
                        'r' -> out.append('\r')
                        'b' -> out.append('\b')
                        'f' -> out.append(FORM_FEED)
                        'u' -> {
                            val hex = json.substring(i + 1, minOf(i + 1 + HEX_DIGITS, json.length))
                            val code = if (hex.length == HEX_DIGITS) hex.toIntOrNull(HEX_RADIX) else null
                            if (code == null) return out.toString()
                            out.append(code.toChar())
                            i += HEX_DIGITS
                        }

                        else -> out.append(escaped)
                    }
                }

                else -> out.append(c)
            }
            i++
        }
        // Truncated input: hand back what was readable rather than throwing. A clipped
        // transcript is still worth dispatching; an exception on the audio thread is not.
        return out.toString()
    }

    private const val FORM_FEED = ''
    private const val HEX_DIGITS = 4
    private const val HEX_RADIX = 16
}
