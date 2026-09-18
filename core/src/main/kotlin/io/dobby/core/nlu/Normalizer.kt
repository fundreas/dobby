package io.dobby.core.nlu

import java.util.Locale

/**
 * Turns a raw STT transcript into the shape every template is written against:
 * lowercase, no punctuation, single spaces, German number words as digits.
 *
 * Runs in core, not per Sock, so every Sock sees the same text.
 */
object Normalizer {
    private val KEEP = Regex("[^\\p{L}\\p{N}' ]")

    fun normalize(raw: String): String = tokenize(raw).joinToString(" ")

    /** Same as [normalize] but returns the tokens the matcher works on. */
    fun tokenize(raw: String): List<String> =
        raw.lowercase(Locale.GERMAN)
            .replace(KEEP, " ")
            .split(' ')
            .filter { it.isNotBlank() }
            .map { token -> GermanNumbers.parse(token)?.toString() ?: token }
}
