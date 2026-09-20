package io.dobby.core.nlu

/**
 * Words the template matcher may skip anywhere in an utterance.
 *
 * Parakeet gets the content words right and the function words wrong: "wie spät ist **das**"
 * for "wie spät ist **es**" is a recorded case, and `denn`, `jetzt`, `mal` and `bitte` appear
 * and vanish at random. Anchoring at both ends is what makes Tier 1 safe, and it is also what
 * makes one wrong `es` throw the whole utterance at a 2–4 s LLM round trip.
 *
 * The fix is the one Adapt, Rhasspy and Snips all landed on: **templates name content words,
 * the engine tolerates filler.** A small, language-specific list the matcher may skip, applied
 * only on a second pass ([io.dobby.core.registry.Palette.match]) so nothing that resolves today
 * resolves differently.
 *
 * ### What may be on the list
 *
 * A word belongs here only if **no command's meaning changes when it is dropped**. That is a
 * judgement, so it is also a build-time check: [io.dobby.core.registry.SockRegistry.checkFillers]
 * refuses a filler that is a command on its own, and refuses a pair of templates from different
 * commands that become the same sentence once fillers are ignored. Three words the first draft
 * of this list carried are gone because of exactly that — see [DE].
 *
 * Fillers are compared **exactly**: never Levenshtein, never phonetic. "bitter" is not "bitte",
 * and a fuzzy filler would skip content words.
 *
 * @param language for diagnostics and for the day the engine gets a language setting — the same
 *   setting then chooses [Fillers], [Normalizer]'s locale and
 *   [io.dobby.core.nlu.template.Phonetics], which are German-only today and have to move
 *   together.
 */
class Fillers(val language: String, val words: Set<String>) {

    operator fun contains(token: String): Boolean = token in words

    val isEmpty: Boolean get() = words.isEmpty()

    override fun toString(): String = "Fillers($language, ${words.size} words)"

    companion object {
        /** Skip nothing. Today's matcher, byte for byte, and the A/B baseline in the tests. */
        val NONE: Fillers = Fillers("none", emptySet())

        /**
         * German.
         *
         * Grouped in source for review and flat in use. Three deliberate absences, recorded
         * here so the next reader does not read them as oversights:
         *
         * - **`ein`**, which is an article ("ein Timer") *and* a polarity word ("mach das Licht
         *   ein"). The other article forms stay; this one cannot.
         * - **`halt`**, which is a modal particle ("das ist halt so") *and* `shared.stop` said
         *   in one word. A command is never noise.
         * - **`danke`**, for the same reason: it ends a turn in `conversation.acknowledge`.
         *
         * `jetzt` is a filler in "wie spät ist es jetzt" and would be content in a
         * hypothetical "spiele jetzt {x}" against "spiele später {x}". No such command exists;
         * the day one lands, `checkFillers` fails the build and the author decides.
         */
        val DE: Fillers = Fillers(
            "de",
            buildSet {
                // Pronouns the recogniser swaps freely — the case that motivated all of this.
                addAll(listOf("es", "das", "dies", "mir", "mich", "uns", "du"))
                // Articles. No "ein": see above.
                addAll(listOf("der", "die", "den", "dem", "eine", "einen", "einem", "einer", "nen", "ne"))
                // Copula and auxiliaries.
                addAll(listOf("ist", "sind", "bin", "bist"))
                // Modal particles — the whole point of German, and meaningless to a panel.
                addAll(
                    listOf(
                        "denn", "mal", "doch", "eben", "eigentlich",
                        "gerade", "grad", "jetzt", "so", "auch", "dann", "also",
                    ),
                )
                // Politeness. No "danke": see above.
                add("bitte")
                // Hesitation and address. "dobby" because recording starts when the wake word
                // fires and the tail of it can land in the buffer — speculative until the
                // on-device fallthrough log shows it, and harmless if it never does.
                addAll(listOf("ähm", "äh", "hm", "hey", "he", "dobby"))
            },
        )

        /**
         * English, for the day the engine gets a language setting. Nothing reads it yet.
         *
         * `now` is the English `jetzt`: harmless today, worth re-checking when a scheduling
         * command appears. `do` and `does` are pure question scaffolding ("what time does the
         * train leave"); `can`, `could` and `would` are deliberately absent — "can you" is a
         * paraphrase Tier 2 is for, and dropping them would make "can you hear me" and "hear
         * me" the same sentence.
         *
         * English will also want `'s` handling in [Normalizer] ("what's the time"). That is a
         * separate job; noted here so this list is not blamed for it.
         */
        val EN: Fillers = Fillers(
            "en",
            buildSet {
                addAll(listOf("it", "that", "this", "me", "us", "you"))
                addAll(listOf("the", "a", "an", "some"))
                addAll(listOf("is", "are", "am", "do", "does"))
                addAll(
                    listOf(
                        "just", "now", "then", "well", "so", "actually",
                        "really", "right", "like", "kinda", "please", "thanks",
                    ),
                )
                addAll(listOf("um", "uh", "erm", "hmm", "hey", "dobby"))
            },
        )
    }
}
