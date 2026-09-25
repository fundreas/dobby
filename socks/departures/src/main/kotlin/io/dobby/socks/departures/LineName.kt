package io.dobby.socks.departures

/**
 * What somebody said, turned into what the API calls the line (`departures.specs.md` §3).
 *
 * A line name is the one slot in this Sock that a recogniser cannot hand over intact, and it
 * fails in a *predictable* way rather than a random one — which is what makes this a table and
 * not a fuzzy match:
 *
 * | said | heard | wanted |
 * |---|---|---|
 * | U6 | „u sechs", „u 6" | `U6` |
 * | 14A | „14 a", „vierzehn a" | `14A` |
 * | D | „de", „dee" | `D` |
 * | der Sechser | „sechser", „6er" | `6` |
 *
 * `:core`'s normalizer has already turned German number words into digits by the time a
 * template matches, so „u sechs" usually arrives as „u 6" — but "usually" is not "always"
 * (a slot capture is verbatim, and Tier 2 fills slots from the raw transcript), so both
 * spellings are handled here and neither is anybody else's problem.
 *
 * **Nothing is guessed.** The result of [normalize] is a candidate, and [resolve] only returns
 * a line the API actually reported for the configured stations. An unresolvable candidate is
 * not an error — it falls through to the all-lines summary, because somebody who said a line
 * name was asking about departures either way (§3).
 */
object LineName {

    /**
     * "u sechs" → "u6", "14 a" → "14a", "sechser" → "6".
     *
     * Returns null for anything that is left empty by the normalisation, which is the same
     * thing as no line having been said.
     */
    fun normalize(spoken: String): String? {
        val tokens = spoken.lowercase()
            .replace('-', ' ')
            .replace(".", "")
            .split(' ')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .dropWhile { it in LEADING_NOISE }
            .map { token -> NUMBER_WORDS[token] ?: viennese(token) ?: token }
        if (tokens.isEmpty()) return null
        // Joined without separators, which is the whole trick: every way a line name comes
        // apart in a transcript is a space that should not be there. "14 a" and "u 6" are one
        // token that the recogniser split, never two things that were said.
        val joined = tokens.joinToString("").filter { !it.isWhitespace() }
        return joined.ifEmpty { null }.let { candidate ->
            // "de" is how a recogniser writes the letter D, and "D" is a real Wiener Linien
            // line — the only one whose name is a letter that German also spells out loud.
            if (candidate == "de" || candidate == "dee") "d" else candidate
        }
    }

    /**
     * The API's own spelling of a spoken line, or null when nothing it reported matches.
     *
     * Matched against the lines the *configured* stations actually have rather than against a
     * table of every line in Vienna: "wann fährt der 13A" at a station the 13A does not serve
     * is not a resolution failure, it is a question with no answer here, and both end in the
     * same honest fallback.
     */
    fun resolve(spoken: String?, available: List<String>): String? {
        val candidate = spoken?.let(::normalize) ?: return null
        return available.firstOrNull { normalize(it) == candidate }
    }

    /**
     * „der Sechser", „der Dreizehner" — the Viennese way of naming a tram, and the form a
     * recogniser writes as one word.
     *
     * Only when the stem is a number word: "sechser" is the 6, and "wiener" is not a line.
     */
    private fun viennese(token: String): String? {
        if (!token.endsWith("er") || token.length <= 2) return null
        val stem = token.dropLast(2)
        return NUMBER_WORDS[stem] ?: stem.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
    }

    /**
     * Words that can stand in front of a line name without being part of it.
     *
     * Dropped from the **front** only: "linie d" is the D, and a "linie" in the middle of a
     * line name does not happen.
     */
    private val LEADING_NOISE = setOf(
        "linie", "der", "die", "das", "den", "dem", "bus", "bim", "tram", "straßenbahn",
        "strassenbahn", "ubahn", "nächste", "naechste", "nächster", "naechster",
    )

    /**
     * The numbers a line name can start with, spelled out.
     *
     * Up to twenty plus the tens, which covers every Wiener Linien line name that has ever been
     * spoken aloud — the tram numbers stop in the sixties and the buses are reached digit by
     * digit ("vier zwei a") rather than as "zweiundvierzig", because that is what a recogniser
     * produces from somebody reading a number off a pole.
     */
    private val NUMBER_WORDS = mapOf(
        "null" to "0", "eins" to "1", "ein" to "1", "eine" to "1", "zwei" to "2", "zwo" to "2",
        "drei" to "3", "vier" to "4", "fünf" to "5", "fuenf" to "5", "sechs" to "6",
        "sieben" to "7", "acht" to "8", "neun" to "9", "zehn" to "10", "elf" to "11",
        "zwölf" to "12", "zwoelf" to "12", "dreizehn" to "13", "vierzehn" to "14",
        "fünfzehn" to "15", "fuenfzehn" to "15", "sechzehn" to "16", "siebzehn" to "17",
        "achtzehn" to "18", "neunzehn" to "19", "zwanzig" to "20", "dreißig" to "30",
        "dreissig" to "30", "vierzig" to "40", "fünfzig" to "50", "fuenfzig" to "50",
        "sechzig" to "60", "siebzig" to "70",
    )
}
