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
}
