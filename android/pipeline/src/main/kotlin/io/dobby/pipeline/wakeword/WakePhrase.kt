package io.dobby.pipeline.wakeword

import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The phrase [TranscriptWakeWord] listens for, and the fuzzy match that finds it in a transcript.
 *
 * Exact string equality would be useless here. Parakeet is an open-vocabulary recogniser being
 * handed a German-accented English name it has never been told exists, so "Hey Dobby" comes
 * back as "Hey Dobby", "Hey Dobbi", "Heidobi" or "Hey Toby" depending on the room — and the
 * panel has to answer to all of them without also answering to the television.
 *
 * Two decisions do most of the work:
 *
 *  - **Word boundaries are ignored.** The candidate window and the phrase are both joined
 *    without spaces before they are compared, because where the recogniser puts the spaces in
 *    an unknown name is not information — "hey dobby", "heydobby" and "hei dobi" are the same
 *    sound and only one of them is a match on tokens.
 *  - **The tolerance is a share of the phrase, not a constant.** [TOLERANCE] of the characters
 *    may be wrong, so a short phrase is held tight and a long one is allowed the mistakes a
 *    long one collects. This is the knob: too low and the panel ignores you, too high and it
 *    answers the radio.
 *
 * **And the phrase itself is the real escape hatch.** It is typed by whoever owns the panel, so
 * when the recogniser insists on hearing "Hey Toby", the fix is not to widen the tolerance — it
 * is to type "Hey Toby" into the settings screen and be answered every time.
 */
class WakePhrase(text: String) {

    /** As typed, for the screen and the status line. */
    val text: String = text.trim().ifBlank { DEFAULT }

    private val words: List<String> = tokenize(this.text)

    /** The phrase with its spaces taken out — what a candidate window is measured against. */
    private val joined: String = words.joinToString("")

    /** How many characters of [joined] may be wrong. At least one: no phrase is heard perfectly. */
    private val tolerance: Int = max(1, (joined.length * TOLERANCE).roundToInt())

    /** True when this phrase cannot be matched at all — nothing but punctuation was typed. */
    val isEmpty: Boolean get() = joined.isEmpty()

    /**
     * Looks for the phrase anywhere in [transcript], and returns it with whatever followed.
     *
     * Anywhere rather than only at the start, because "also, hey Dobby, mach das Licht an" is
     * how people talk and the leading word is not worth failing over. The *first* match wins
     * and everything before it is dropped: what matters is where the command begins.
     *
     * Null means the phrase is not in there, which — on a panel that is transcribing every
     * sentence spoken in the room — is the overwhelmingly common answer.
     */
    fun match(transcript: String): WakeWord? {
        if (isEmpty) return null
        val raw = transcript.trim().split(WHITESPACE).filter { it.isNotBlank() }
        if (raw.isEmpty()) return null
        val normalized = raw.map { normalize(it) }

        // Windows are allowed one token more than the phrase has, because a recogniser that
        // does not know the name splits it: "Hey Dobby" arrives as three tokens as readily as
        // two, and a window fixed at the phrase's own length would never see the whole of it.
        val widest = words.size + 1

        for (start in normalized.indices) {
            var best: Int = Int.MAX_VALUE
            var bestEnd = start
            for (end in start + 1..min(start + widest, normalized.size)) {
                val candidate = buildString {
                    for (i in start until end) append(normalized[i])
                }
                // Levenshtein can never be smaller than the difference in length, so a window
                // that is already too long or too short is skipped before the DP runs.
                if (candidate.length - joined.length > tolerance) break
                if (joined.length - candidate.length > tolerance) continue
                val distance = editDistance(candidate, joined)
                if (distance < best) {
                    best = distance
                    bestEnd = end
                }
            }
            if (best <= tolerance) {
                return WakeWord(
                    score = 1f - best.toFloat() / joined.length,
                    rest = raw.drop(bestEnd).joinToString(" "),
                )
            }
        }
        return null
    }

    companion object {
        /**
         * What the panel answers to until somebody types something else.
         *
         * Not in the [WakeWordCatalogue]: no classifier head has been trained for it, which is
         * most of the reason this way of listening exists at all (`dobby-plan.md` §4).
         */
        const val DEFAULT: String = "Hey Dobby"

        /** A quarter of the characters may be wrong. See the class KDoc — this is the knob. */
        private const val TOLERANCE: Double = 0.25

        private val WHITESPACE = Regex("\\s+")

        /** Everything that is not a letter or a digit, which is everything a match must ignore. */
        private val NOISE = Regex("[^\\p{L}\\p{N}]")

        private fun tokenize(text: String): List<String> =
            text.trim().split(WHITESPACE).map { normalize(it) }.filter { it.isNotEmpty() }

        private fun normalize(token: String): String =
            token.lowercase(Locale.GERMAN).replace(NOISE, "")

        /**
         * Levenshtein distance, two rows at a time.
         *
         * Both strings are a handful of characters and this runs once per window of one
         * transcript, so the shape that matters is that it allocates two small arrays rather
         * than a matrix — not its asymptotics.
         */
        internal fun editDistance(a: String, b: String): Int {
            if (a == b) return 0
            if (a.isEmpty()) return b.length
            if (b.isEmpty()) return a.length

            var previous = IntArray(b.length + 1) { it }
            var current = IntArray(b.length + 1)
            for (i in 1..a.length) {
                current[0] = i
                for (j in 1..b.length) {
                    val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                    current[j] = min(substitution, min(previous[j] + 1, current[j - 1] + 1))
                }
                val swap = previous
                previous = current
                current = swap
            }
            return previous[b.length]
        }
    }
}
