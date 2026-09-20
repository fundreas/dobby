package io.dobby.core.sock

/**
 * The language Dobby *answers* in.
 *
 * Deliberately not the language it understands. Tier 1's palette and Tier 2's few-shots are
 * German and stay German (`m2c-plan.md` Part E): Parakeet's multilingual decoding is what makes
 * an English *title* inside a German command survive, not what makes an English command match.
 * So this enum reaches exactly one half of the panel — everything a Sock says back — and
 * nothing in the matcher, the normalizer or the prompt generator knows it exists.
 *
 * Two values, because two voices ship. A third belongs here the day a third voice does.
 */
enum class Lang(
    /** The primary subtag, which is how a voice's BCP-47 language is matched back to a [Lang]. */
    val tag: String,
) {
    DE("de"),
    EN("en"),
    ;

    companion object {
        /**
         * German, which is what the panel was built to speak (`dobby-plan.md` §1).
         *
         * It is also what a voice with no language of its own falls back to — the phone's
         * `TextToSpeech` entry carries an empty tag, and the device stands in a kitchen in
         * Vienna.
         */
        val DEFAULT: Lang = DE

        /**
         * `de-DE` → [DE], `en-GB` → [EN], anything else → [DEFAULT].
         *
         * Matches on the primary subtag alone: a voice is trained on a locale, an answer is
         * written in a language, and `en-GB` versus `en-US` is a question about pronunciation
         * that has no bearing on which strings get built.
         */
        fun of(tag: String?): Lang {
            val primary = tag?.substringBefore('-')?.substringBefore('_')?.lowercase()
            return entries.firstOrNull { it.tag == primary } ?: DEFAULT
        }
    }
}

/**
 * A sentence Dobby has not written yet.
 *
 * This is the whole of the i18n contract: a [SockResult] does not carry a string, it carries a
 * function from [Lang] to one. Core calls it once, with whatever language is set at the moment
 * of speaking, and the Sock decides how to build the sentence — which is the only place that
 * decision can honestly be made. "Der Timer läuft noch 9 Minuten" and "The timer has 9 minutes
 * left" are not the same sentence with the words swapped: the number moves, the verb moves, and
 * a translation table keyed on fragments would have to be reassembled by something that does
 * not know what it is assembling.
 *
 * It also means the language is resolved **late**. A timer set in German and answered after the
 * voice was switched is answered in English, because nothing was rendered until it was said.
 *
 * Deliberately not a `Map<Lang, String>`: most phrases interpolate, and the ones that do not are
 * one [of] call away.
 */
fun interface Phrase {

    /** Builds the sentence. Must be pure and must not block — core calls it on the speaking path. */
    operator fun invoke(lang: Lang): String

    companion object {
        /** The two wordings of one sentence. */
        fun of(de: String, en: String): Phrase = Phrase { if (it == Lang.EN) en else de }

        /**
         * One wording for every language — a station name, a number, a title.
         *
         * "FM4." is not German, and writing it twice would only invite the two copies to drift.
         */
        fun of(both: String): Phrase = Phrase { both }
    }
}
