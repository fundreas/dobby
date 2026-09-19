package io.dobby.core.nlu.template

/**
 * Decides whether a spoken token is a template's keyword.
 *
 * Three layers, cheapest first: exact equality, then [Levenshtein.fuzzyEquals], then
 * [Phonetics]. The first two are unconditional. The third is the one that needs permission,
 * because a Kölner code is vowel-blind and a vowel-blind comparison of short German words is a
 * misroute generator: `an`/`in`/`ein` are one code, and so are `aus`/`es`/`ist`/`hat`.
 *
 * ### What a keyword must survive to be matched phonetically
 *
 * 1. **Length.** Keyword and token are both at least [MIN_LENGTH] characters and the keyword's
 *    code is at least [MIN_CODE_LENGTH] digits. Short words carry too little signal.
 * 2. **Uncontested.** No *other* palette keyword shares the code unless the two are already
 *    Levenshtein-equivalent. A contested code retires **both** sides, not one: if `kommt` and
 *    `kennst` collide there is no principled way to choose, and choosing wrong is a command
 *    routed to the wrong Sock. Computed from the palette's own literals in [over], so a Sock
 *    added later that collides with an existing keyword retires both automatically rather than
 *    quietly creating a misroute.
 * 3. **Not retired by the corpus.** See [RETIRED_BY_CORPUS] — the collisions that are invisible
 *    from inside the palette, because the other word is not a keyword at all.
 * 4. **A consonant actually moved.** Two words with one code and one consonant skeleton differ
 *    only in their vowels, and the code cannot see vowels — so it is not evidence about them.
 *    See [Phonetics.skeleton]. This is the guard that separates `schbiele` (a misheard cluster,
 *    which is what this tier is for) from `spüle` (a different word, which is what it must never
 *    touch), and without it the corpus below retires `spiele` itself.
 *
 * And one guard that lives outside this class, in [io.dobby.core.registry.Palette]: a template
 * whose cheapest path is a single literal word with no slots is matched with [STRICT]. Anchoring
 * is what protects everything else — `leiter` cannot reach `(mach)? {n} lauter` because the
 * other tokens are missing, but it would walk straight into a bare `lauter`.
 */
class KeywordMatcher private constructor(
    /** Keyword → its Kölner code, for the keywords that survived every guard. */
    val eligible: Map<String, String>,
    /** True for [STRICT] alone, so `/keywords` can say "off" rather than "contested". */
    private val strict: Boolean = false,
) {

    /**
     * True if [token] is [keyword], allowing for STT damage.
     *
     * [keyword] is a template literal; [token] is what was actually heard. The relation is not
     * symmetric — tolerance is chosen from the keyword's length, and only the keyword has to be
     * eligible for the phonetic tier.
     */
    fun matches(token: String, keyword: String): Boolean {
        if (token == keyword) return true
        if (Levenshtein.fuzzyEquals(token, keyword)) return true
        if (token.length < MIN_LENGTH) return false
        val code = eligible[keyword] ?: return false
        if (Phonetics.koelner(token) != code) return false
        // Same code and the same consonants: the two words disagree about vowels only, and
        // vowels are precisely what the code threw away. See [Phonetics.skeleton].
        return Phonetics.skeleton(token) != Phonetics.skeleton(keyword)
    }

    /** Why [keyword] is or is not matched phonetically. For `/keywords` and for the tests. */
    fun explain(keyword: String): Verdict {
        val code = Phonetics.koelner(keyword)
        return when {
            strict -> Verdict(keyword, code, Trust.STRICT_MATCHER)
            eligible.containsKey(keyword) -> Verdict(keyword, code, Trust.PHONETIC)
            keyword.length < MIN_LENGTH -> Verdict(keyword, code, Trust.TOO_SHORT)
            code.length < MIN_CODE_LENGTH -> Verdict(keyword, code, Trust.CODE_TOO_SHORT)
            keyword in RETIRED_BY_CORPUS -> Verdict(keyword, code, Trust.CORPUS)
            else -> Verdict(keyword, code, Trust.CONTESTED)
        }
    }

    /** Why the phonetic tier does or does not trust a keyword. */
    enum class Trust {
        /** Matched phonetically. */
        PHONETIC,

        /** Shorter than [MIN_LENGTH]. */
        TOO_SHORT,

        /** Code shorter than [MIN_CODE_LENGTH] digits — mostly vowels, and they all code to 0. */
        CODE_TOO_SHORT,

        /** Another palette keyword has the same code. Both sides are retired. */
        CONTESTED,

        /** An ordinary German word has the same code. See [RETIRED_BY_CORPUS]. */
        CORPUS,

        /** This matcher is [STRICT]: no keyword is phonetic, whatever its code. */
        STRICT_MATCHER,
    }

    data class Verdict(val keyword: String, val code: String, val trust: Trust) {
        val phonetic: Boolean get() = trust == Trust.PHONETIC
    }

    companion object {
        /**
         * The shortest word the phonetic tier will look at, keyword or token.
         *
         * Four characters is already `08` territory. Five is where a code has enough consonants
         * to mean something, and it is also where [Levenshtein] is doing a decent job anyway.
         */
        const val MIN_LENGTH: Int = 5

        /** Fewer digits than this and the word is mostly vowels, which the code cannot see. */
        const val MIN_CODE_LENGTH: Int = 3

        /**
         * Pre-M6 behaviour: exact, then Levenshtein, and nothing else.
         *
         * Two jobs. It is the A/B baseline `SpecPaletteTest` resolves every spec utterance
         * against with phonetics off, and it is what single-literal templates are matched with
         * for real — see the class doc.
         */
        val STRICT: KeywordMatcher = KeywordMatcher(emptyMap(), strict = true)

        /**
         * Keywords an ordinary German word collides with, retired by hand.
         *
         * The contested-code rule in [over] can only see the palette. It cannot see that
         * `spüle`, `tumor`, `monaten` and `löser` exist, because none of them is a keyword —
         * and a panel that hears "spüle" and starts playing music is a worse failure than one
         * that hears "schbiele" and does nothing.
         *
         * This list is not written by hand from intuition. It is the output of
         * `NegativeCorpusTest` over `core/src/test/resources/de-frequent.txt`, which asserts
         * that this set is *exactly* the set of keywords the corpus retires — so adding a Sock
         * whose keyword collides with a frequent German word fails the build with the word in
         * the failure message, and removing one that no longer collides fails it too.
         */
        val RETIRED_BY_CORPUS: Set<String> = setOf(
            "anhalten",
            "bisschen",
            "deutlich",
            "einen",
            "erinner",
            "erinnere",
            "fährt",
            "gehört",
            "hab's",
            "jetzt",
            "kommt",
            "löschen",
            "nacht",
            "nicht",
            "nächste",
            "pausier",
            "pausiere",
            "pausieren",
            "schalt",
            "schalte",
            "schirm",
            "sender",
            "skippen",
            "stell",
            "stopp",
            "stoppe",
            "stumm",
            "stück",
            "timer",
            "track",
            "ubahn",
            "uhrzeit",
            "wecker",
            "what's",
        )

        /**
         * Builds the matcher for a palette from every literal in it.
         *
         * Every literal, not every distinct one: the contested rule is about codes, and a
         * keyword repeated across five templates is still one keyword with one code.
         *
         * @param retired keywords the corpus has already disqualified. A parameter rather than a
         *   straight read of [RETIRED_BY_CORPUS] because retiring one keyword can make another
         *   eligible — `weiter` is contested by `wieder` today, and the day it stops being, the
         *   corpus has an opinion about it — so `NegativeCorpusTest` has to iterate to a
         *   fixpoint, and it can only do that if it can supply the set.
         */
        fun over(
            keywords: Collection<String>,
            retired: Set<String> = RETIRED_BY_CORPUS,
        ): KeywordMatcher {
            val candidates = keywords.distinct()
                .filter { it.length >= MIN_LENGTH && it !in retired }
                .associateWith { Phonetics.koelner(it) }
                .filterValues { it.length >= MIN_CODE_LENGTH }

            val byCode = candidates.entries.groupBy({ it.value }, { it.key })
            val eligible = candidates.filterKeys { keyword ->
                val sharing = byCode.getValue(candidates.getValue(keyword))
                sharing.none { other -> other != keyword && !alike(keyword, other) }
            }
            return KeywordMatcher(eligible)
        }

        /**
         * Whether two keywords already match each other anyway.
         *
         * A shared code between `stell` and `stelle` is not a collision worth retiring over:
         * Levenshtein conflates them already, so the phonetic tier adds no new route. Checked
         * in both directions because tolerance comes from the keyword's length, and the pair is
         * only harmless if neither direction is a surprise.
         */
        private fun alike(a: String, b: String): Boolean =
            Levenshtein.fuzzyEquals(a, b) || Levenshtein.fuzzyEquals(b, a)
    }
}
