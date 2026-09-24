package io.dobby.core.nlu

/**
 * German cardinal number words → integers. Covers 0–9999, which is far more than
 * timers, volumes and transit lines need.
 *
 * Two entry points on purpose:
 *
 * - [parse] is what the [Normalizer] uses. It deliberately refuses the article forms of
 *   "one" (`ein`, `eine`, `einen`, …), because "spiele ein Lied" must not normalize to
 *   "spiele 1 lied".
 * - [parseSlotValue] is what an `{n:int}` slot uses. There the context is unambiguous —
 *   whatever sits in that slot is a number — so "timer eine minute" resolves to 1.
 */
object GermanNumbers {
    private val direct: Map<String, Int> = mapOf(
        "null" to 0,
        "eins" to 1,
        "zwei" to 2,
        "drei" to 3,
        "vier" to 4,
        "fünf" to 5,
        "fuenf" to 5,
        "sechs" to 6,
        "sieben" to 7,
        "acht" to 8,
        "neun" to 9,
        "zehn" to 10,
        "elf" to 11,
        "zwölf" to 12,
        "zwoelf" to 12,
        "dreizehn" to 13,
        "vierzehn" to 14,
        "fünfzehn" to 15,
        "fuenfzehn" to 15,
        "sechzehn" to 16,
        "siebzehn" to 17,
        "achtzehn" to 18,
        "neunzehn" to 19,
        "zwanzig" to 20,
        "dreißig" to 30,
        "dreissig" to 30,
        "vierzig" to 40,
        "fünfzig" to 50,
        "fuenfzig" to 50,
        "sechzig" to 60,
        "siebzig" to 70,
        "achtzig" to 80,
        "neunzig" to 90,
        "hundert" to 100,
        "tausend" to 1000,
    )

    /** Tens a `<unit>und<tens>` compound may end in. */
    private val tens: Map<String, Int> = direct.filterValues { it in 20..90 && it % 10 == 0 }

    /** Units a compound may start with. "ein" is valid here — "einundzwanzig" is not ambiguous. */
    private val compoundUnits: Map<String, Int> =
        mapOf("ein" to 1) + direct.filterValues { it in 1..9 }

    /** Article forms of "one": ambiguous on their own, resolved only inside an int slot. */
    val oneArticles: Set<String> = setOf("ein", "eine", "einen", "einem", "einer")

    /** Strict parse. Returns null for article forms and anything unrecognised. */
    fun parse(word: String): Int? {
        val w = word.lowercase()
        if (w.isEmpty()) return null
        direct[w]?.let { return it }

        // n-hundert(-rest): "zweihundert", "hundertzwanzig", "dreihundertfünf"
        val h = w.indexOf("hundert")
        if (h >= 0) {
            val prefix = w.substring(0, h)
            val suffix = w.substring(h + "hundert".length)
            val hundreds = if (prefix.isEmpty()) 1 else compoundUnits[prefix] ?: return null
            val rest = if (suffix.isEmpty()) 0 else parseCompound(suffix) ?: return null
            return hundreds * 100 + rest
        }
        return parseCompound(w)
    }

    /**
     * Ordinal stems that are not their cardinal plus a suffix.
     *
     * Everything else is regular — "vierzehn" + "te", "zwanzig" + "ste" — and is derived below
     * rather than listed, which is what keeps 1…31 from becoming a table of thirty-one words.
     */
    private val irregularOrdinals: Map<String, Int> =
        mapOf("erste" to 1, "dritte" to 3, "siebte" to 7, "siebente" to 7, "achte" to 8)

    /**
     * German ordinals → integers: "vierzehnte" → 14, "einundzwanzigsten" → 21.
     *
     * Only ever reached from [parseSlotValue], and that restriction is the safety argument:
     * "der Vierzehnte" is a number wherever an `{n:int}` slot is waiting for one, and is a word
     * everywhere else. [parse] — what the [Normalizer] runs over every token of every utterance
     * — is deliberately left alone, so no transcript changes shape because this exists.
     *
     * The declension is trimmed rather than enumerated: "vierzehnte", "vierzehnten",
     * "vierzehnter" and "vierzehntes" are one stem and four endings, and listing the
     * cross-product is what [Fillers] exists to avoid everywhere else in this package.
     */
    fun parseOrdinal(word: String): Int? {
        val stem = word.lowercase().trimEnd('n', 'r', 's', 'm')
        irregularOrdinals[stem]?.let { return it }
        // "-ste" from 20 up ("zwanzigste"), "-te" below it ("vierzehnte"). Both are tried
        // because "sechste" ends in "ste" and is nonetheless "sechs" + "te".
        val fromSte = if (stem.endsWith("ste")) parse(stem.dropLast(3)) else null
        val fromTe = if (stem.endsWith("te")) parse(stem.dropLast(2)) else null
        return fromSte ?: fromTe
    }

    /**
     * Lenient parse for an `{n:int}` slot: digits, number words, ordinals, and the article
     * forms of one.
     */
    fun parseSlotValue(word: String): Int? {
        val w = word.lowercase()
        if (w in oneArticles) return 1
        w.toIntOrNull()?.let { return it }
        return parse(w) ?: parseOrdinal(w)
    }

    /** `<unit>und<tens>`, e.g. "einundzwanzig" → 21. Also accepts a bare direct word. */
    private fun parseCompound(word: String): Int? {
        direct[word]?.let { return it }
        // "hundert" itself contains "und" at index 1, so it must already be handled above.
        val u = word.indexOf("und")
        if (u <= 0) return null
        val unit = compoundUnits[word.substring(0, u)] ?: return null
        val ten = tens[word.substring(u + 3)] ?: return null
        return ten + unit
    }
}
