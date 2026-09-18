package io.dobby.core.nlu.template

import kotlin.math.abs
import kotlin.math.min

/**
 * Edit distance for fuzzy keyword matching.
 *
 * STT mangles command words predictably ("spiele" → "schbiele"), so literal keywords tolerate
 * a slip or two. Slot *content* is never fuzzy-matched — only keywords are.
 */
object Levenshtein {
    /**
     * How many edits a keyword of this length tolerates.
     *
     * Short words get zero: at distance 1, "an" also matches "in", "am" and "n", which turns
     * every third utterance into a false positive.
     */
    fun tolerance(keywordLength: Int): Int = when {
        keywordLength <= 3 -> 0
        keywordLength <= 7 -> 1
        else -> 2
    }

    fun fuzzyEquals(token: String, keyword: String): Boolean {
        if (token == keyword) return true
        val max = tolerance(keyword.length)
        if (max == 0) return false
        if (abs(token.length - keyword.length) > max) return false
        return atMost(token, keyword, max)
    }

    /** True if `distance(a, b) <= max`. Bails out as soon as every cell in a row exceeds `max`. */
    fun atMost(a: String, b: String, max: Int): Boolean {
        if (abs(a.length - b.length) > max) return false
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            var rowMin = current[0]
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = min(
                    min(current[j - 1] + 1, previous[j] + 1),
                    previous[j - 1] + cost,
                )
                rowMin = min(rowMin, current[j])
            }
            if (rowMin > max) return false
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length] <= max
    }

    fun distance(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = min(
                    min(current[j - 1] + 1, previous[j] + 1),
                    previous[j - 1] + cost,
                )
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }
}
