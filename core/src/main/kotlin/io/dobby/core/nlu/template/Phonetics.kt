package io.dobby.core.nlu.template

/**
 * Kölner Phonetik — a German-language sound code.
 *
 * Two words that sound alike reduce to the same string of digits: `spiele` and `schbiele` are
 * both `815`, which is the one class of STT damage [Levenshtein] cannot reach ("schbiele" is
 * three edits from "spiele", and no tolerance that catches it is safe for a six-letter word).
 *
 * **This is a hazard as much as a tool, and the hazard is vowels.** Every vowel codes to `0` and
 * every `0` is then dropped, so the code says nothing at all about them: `spiele`, `spüle` and
 * `spule` are one code, and so are `an`, `in` and `ein` (`06` → `6`). A code is therefore never
 * enough on its own to decide that two words are the same word — see [KeywordMatcher], which is
 * the only thing in the codebase allowed to act on one, and which spends most of its length on
 * the guards that make acting on one safe.
 *
 * Implements the standard table (Postel 1969), including the context rules for `c`, `d/t`, `p`
 * and `x`. Umlauts and `ß` are folded first, because [io.dobby.core.nlu.Normalizer] leaves them
 * intact and an uncoded `ü` would silently truncate the word.
 */
object Phonetics {

    /**
     * The sound code of one word: digits only, possibly empty.
     *
     * Empty for input with no codeable letters at all — a number, punctuation, `"h"` alone.
     * Callers must treat an empty or very short code as "no opinion" rather than as a match;
     * [KeywordMatcher.MIN_CODE_LENGTH] is where that judgement is written down.
     */
    fun koelner(word: String): String {
        val letters = fold(word)
        val coded = StringBuilder(letters.length + 1)
        for (index in letters.indices) {
            coded.append(codeAt(letters, index) ?: continue)
        }
        return trim(coded)
    }

    /**
     * The consonants of a word, in order, with doubles collapsed: `spiele` → `spl`.
     *
     * Two words with the same code *and* the same skeleton differ only in their vowels — which
     * is the one thing a Kölner code is structurally unable to see, so the code is not evidence
     * about them at all. `spiele`/`spüle`, `timer`/`tumor`, `minuten`/`monaten`, `lauter`/
     * `leiter`, `leiser`/`löser`, `wecker`/`wacker` and `wetter`/`weiter` are all this shape:
     * every one of them is the plan's §A2 hazard list, and every one of them is a different
     * German word rather than a mishearing of the keyword.
     *
     * STT damage does not look like that. "schbiele" for "spiele" is a *consonant* substitution
     * (`scbl` against `spl`) — the recogniser heard the sound and spelled the cluster wrong. So
     * the skeleton is what separates the case this tier exists for from the case it must never
     * touch. `h` is dropped with the vowels: it is silent, uncoded, and `hör`/`öhr` is not a
     * distinction anybody makes out loud.
     */
    fun skeleton(word: String): String {
        val out = StringBuilder(word.length)
        for (c in fold(word)) {
            if (c in VOWELS || c == 'h' || !c.isLetter()) continue
            if (out.isNotEmpty() && out.last() == c) continue
            out.append(c)
        }
        return out.toString()
    }

    /** Folds what the normalizer leaves behind: the table has no row for `ä`, `ö`, `ü` or `ß`. */
    private fun fold(word: String): String {
        val out = StringBuilder(word.length + 2)
        for (raw in word.lowercase()) {
            when (raw) {
                'ä' -> out.append('a')
                'ö' -> out.append('o')
                'ü' -> out.append('u')
                'ß' -> out.append("ss")
                else -> out.append(raw)
            }
        }
        return out.toString()
    }

    /**
     * The code of the letter at [index], or null when it contributes nothing.
     *
     * Null covers `h` — which is never coded but still counts as context for the letter before
     * it — and everything that is not a letter of the table at all, digits included.
     */
    @Suppress("CyclomaticComplexMethod")
    private fun codeAt(letters: String, index: Int): String? {
        val c = letters[index]
        val previous = letters.getOrNull(index - 1)
        val next = letters.getOrNull(index + 1)
        return when (c) {
            'a', 'e', 'i', 'j', 'o', 'u', 'y' -> "0"
            'h' -> null
            'b' -> "1"
            'p' -> if (next == 'h') "3" else "1"
            'd', 't' -> if (next in C_S_Z) "8" else "2"
            'f', 'v', 'w' -> "3"
            'g', 'k', 'q' -> "4"
            'l' -> "5"
            'm', 'n' -> "6"
            'r' -> "7"
            's', 'z' -> "8"
            'x' -> if (previous in C_K_Q) "8" else "48"
            'c' -> codeC(previous, next)
            else -> null
        }
    }

    /**
     * `c` is the whole reason this table needs context at all.
     *
     * At the start of a word it is `4` before the letters German pronounces hard (`ck`, `chor`,
     * `computer`) and `8` otherwise. Inside a word the same rule applies, except after `s` or
     * `z`, where `sch` and `cz` are always `8` — which is exactly the case that makes `schbiele`
     * and `spiele` agree.
     */
    private fun codeC(previous: Char?, next: Char?): String = when {
        previous == null -> if (next in C_INITIAL_HARD) "4" else "8"
        previous in S_Z -> "8"
        next in C_HARD -> "4"
        else -> "8"
    }

    /** Collapses adjacent repeats, then drops every `0` but a leading one. */
    private fun trim(coded: CharSequence): String {
        val out = StringBuilder(coded.length)
        var last = ' '
        for (digit in coded) {
            if (digit != last) out.append(digit)
            last = digit
        }
        if (out.isEmpty()) return ""
        val head = out[0]
        val tail = out.substring(1).filter { it != '0' }
        return if (head == '0' && tail.isEmpty()) "0" else head + tail
    }

    private val VOWELS = setOf('a', 'e', 'i', 'o', 'u', 'y')
    private val S_Z = setOf('s', 'z')
    private val C_S_Z = setOf('c', 's', 'z')
    private val C_K_Q = setOf('c', 'k', 'q')
    private val C_HARD = setOf('a', 'h', 'k', 'o', 'q', 'u', 'x')
    private val C_INITIAL_HARD = setOf('a', 'h', 'k', 'l', 'o', 'q', 'r', 'u', 'x')
}
