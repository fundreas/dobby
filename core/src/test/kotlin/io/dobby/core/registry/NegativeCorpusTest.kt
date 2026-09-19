package io.dobby.core.registry

import io.dobby.core.nlu.template.KeywordMatcher
import io.dobby.core.nlu.template.Levenshtein
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The phonetic tier against five thousand words of ordinary German.
 *
 * [PaletteCollisionTest] can only see collisions *between keywords*. This is the other half, and
 * the more dangerous one: `spüle`, `tumor`, `monaten`, `löser` and `wacker` are not keywords, so
 * nothing inside the palette knows they exist, and every one of them shares a Kölner code with
 * something a kitchen panel would act on.
 *
 * A frequency list rather than a hand-picked table on purpose — a hand-picked table only ever
 * contains the collisions somebody already thought of, which are by definition not the ones that
 * ship.
 */
class NegativeCorpusTest {

    private val literals: List<String> =
        SockRegistry.buildOrThrow(SpecFixtures.all()).palette.entries.flatMap { it.template.literals }

    private val corpus: List<String> = NegativeCorpus.words()

    @Test
    fun `the corpus is actually loaded`() {
        // A resource that silently fails to load would turn this whole file into a green light.
        assertTrue(corpus.size > 4000, "corpus has only ${corpus.size} words")
        assertTrue("wetter" in corpus && "leiter" in corpus, "corpus is missing everyday words")
        // The plan's own named hazards (§A2) are appended by hand to the frequency list. A
        // corpus that lacks them passes for the wrong reason.
        assertTrue("spüle" in corpus && "tumor" in corpus, "corpus is missing the named hazards")
    }

    @Test
    fun `no frequent german word phonetically reaches a keyword it is not already close to`() {
        val offences = offences(KeywordMatcher.over(literals))
        assertEquals(
            emptyList(),
            offences,
            "phonetics would route ordinary German into a command; retire the keyword in " +
                "KeywordMatcher.RETIRED_BY_CORPUS",
        )
    }

    @Test
    fun `RETIRED_BY_CORPUS is exactly what the corpus retires`() {
        // Pinned by name, both ways. Adding a Sock whose keyword collides with a frequent word
        // fails here with the word in the message; so does leaving a name in the list after the
        // keyword that needed it is gone.
        assertEquals(
            KeywordMatcher.RETIRED_BY_CORPUS.sorted(),
            fixpoint().sorted(),
            "KeywordMatcher.RETIRED_BY_CORPUS no longer matches the corpus",
        )
    }

    /**
     * The set of keywords the corpus retires, iterated to a fixpoint.
     *
     * One pass is not enough: retiring a keyword can make a *different* one eligible, because
     * the contested-code rule counts only keywords that are still in the running. `wetter` is
     * the live example — it is eligible today only because `wieder` retires `weiter`, and if
     * `wieder` ever leaves the palette, `weiter` comes back and `wetter` has to go.
     */
    private fun fixpoint(): Set<String> {
        var retired = emptySet<String>()
        repeat(literals.size) {
            val offenders = offences(KeywordMatcher.over(literals, retired))
                .map { it.substringAfterLast("→ ").trim() }
                .toSet()
            if (offenders.isEmpty()) return retired
            retired = retired + offenders
        }
        error("phonetic retirement did not converge")
    }

    /**
     * `"<word> → <keyword>"` for every corpus word that would reach a command it should not.
     *
     * Asks [KeywordMatcher.matches] rather than comparing codes by hand: the question is what
     * the palette will actually do with the word, and a test that reimplements the decision can
     * only ever agree with itself. Words Levenshtein already conflates are not offences — they
     * match today, with or without phonetics, and that judgement belongs to
     * [SockRegistry.checkSingleKeywordTemplates] and the spec suite.
     */
    private fun offences(matcher: KeywordMatcher): List<String> =
        corpus.asSequence()
            .filter { it.length >= KeywordMatcher.MIN_LENGTH }
            .flatMap { word ->
                matcher.eligible.keys.asSequence()
                    .filter { keyword -> matcher.matches(word, keyword) }
                    .filterNot { keyword -> Levenshtein.fuzzyEquals(word, keyword) }
                    .map { keyword -> "$word → $keyword" }
            }
            .distinct()
            .sorted()
            .toList()
}

/** Loads `de-frequent.txt`. Shared with [PaletteCollisionTest]'s sanity checks. */
object NegativeCorpus {
    fun words(): List<String> {
        val stream = checkNotNull(NegativeCorpus::class.java.getResourceAsStream("/de-frequent.txt")) {
            "de-frequent.txt is missing from core's test resources"
        }
        return stream.bufferedReader().useLines { lines ->
            lines.map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .toList()
        }
    }
}
