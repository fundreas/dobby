package io.dobby.socks.radio

/**
 * The ICY `StreamTitle`, turned into something worth putting on a wall.
 *
 * The spec hedged here — *"many do, most are unreliable"* — and the measurement on 2026-09-20
 * says the hedge was wrong in an interesting way. All five stations carry ICY metadata, and
 * carry it reliably. What they do not carry uniformly is a *track*:
 *
 * ```
 * FM4         FM4 Fivas Ponyhof | fm4.orf.at        → "Fivas Ponyhof"
 * Ö1          Jetzt in Ö1: Im Zeit-Raum: Judith…    → "Im Zeit-Raum: Judith Mangelsdorf"
 * Ö3          Anna Naklab feat. … - Supergirl       → unchanged
 * Radio Wien  Desireless - Voyage Voyage            → unchanged
 * Kronehit    bebe rexha & faithless - new religion → unchanged
 * ```
 *
 * Two of the five broadcast the programme name dressed in boilerplate, so the cleanup is a
 * handful of pure string rules — no stream in sight, and readable against the five real
 * strings above.
 *
 * **Capitalising the result is deliberately not done.** Kronehit lower-cases everything, and a
 * title-caser would get "Bebe Rexha & Faithless" right and "Ac/Dc" and "R.e.m." wrong. Render
 * it as it comes.
 */
object NowPlaying {

    /** Null rather than a placeholder, which is §7's rule for this line. */
    fun clean(raw: String?, station: Station): String? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val stripped = text
            // "FM4 Fivas Ponyhof | fm4.orf.at" — the suffix is a URL, not a programme.
            .substringBefore(" | ")
            .removePrefix("Jetzt in ${station.displayName}: ")
            .removePrefix("${station.displayName} ")
            .trim()
        // What is left is sometimes the station's own name and nothing else, which the card
        // already shows in much larger type.
        return stripped.takeIf { it.isNotEmpty() && !it.equals(station.displayName, ignoreCase = true) }
    }

    /**
     * The cleaned line split into the two halves a question can ask about.
     *
     * ICY's one convention is `Artist - Title`, and three of the five stations follow it
     * exactly ("Desireless - Voyage Voyage"). The other two send a programme name, which has
     * no artist at all — so a missing separator is [Track.artist] `null` rather than a guess.
     *
     * **The separator is a hyphen with spaces around it, and the first one wins.** Ö1's
     * "Im Zeit-Raum: Judith Mangelsdorf" is a hyphen inside a word and must not split, and
     * "bebe rexha & faithless - new religion" has exactly one candidate. A title with a second
     * " - " in it keeps the rest — "Artist - Live - 1978" is one title, not two.
     */
    fun split(cleaned: String): Track {
        for (separator in SEPARATORS) {
            val at = cleaned.indexOf(separator)
            if (at <= 0) continue
            val artist = cleaned.take(at).trim()
            val title = cleaned.substring(at + separator.length).trim()
            // A line that is all separator and no halves is not a split worth having.
            if (artist.isNotEmpty() && title.isNotEmpty()) return Track(artist, title)
        }
        return Track(artist = null, title = cleaned)
    }

    /** The en dash is second because Kronehit and the ORF both send the plain hyphen. */
    private val SEPARATORS = listOf(" - ", " – ")

    /**
     * What the stream says is on, as far as it can be told apart.
     *
     * [artist] is null for the two stations that broadcast a programme name: there is no
     * artist in "Fivas Ponyhof", and inventing one from the station name would be worse than
     * saying so.
     */
    data class Track(val artist: String?, val title: String)
}
