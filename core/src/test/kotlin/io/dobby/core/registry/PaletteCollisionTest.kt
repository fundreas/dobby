package io.dobby.core.registry

import io.dobby.core.nlu.template.KeywordMatcher
import io.dobby.core.nlu.template.Levenshtein
import io.dobby.core.nlu.template.Phonetics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The structural guarantee: inside the assembled palette, no two keywords can be confused.
 *
 * [NegativeCorpusTest] is the same question asked from outside, against words that are not
 * keywords at all. This one is the cheaper half and the one that cannot be argued with — it
 * needs no corpus, only the palette, and it is what keeps the phonetic tier honest as Socks are
 * added in M4.
 */
class PaletteCollisionTest {

    private val palette = SockRegistry.buildOrThrow(SpecFixtures.all()).palette

    private val literals: List<String> = palette.entries.flatMap { it.template.literals }.distinct()

    @Test
    fun `no two eligible keywords share a code unless they already match anyway`() {
        val eligible = palette.keywords.eligible
        val offences = mutableListOf<String>()
        for ((a, code) in eligible) {
            for ((b, other) in eligible) {
                if (a >= b || code != other) continue
                if (!Levenshtein.fuzzyEquals(a, b) && !Levenshtein.fuzzyEquals(b, a)) {
                    offences += "$a / $b both code to $code"
                }
            }
        }
        assertEquals(emptyList(), offences, "a contested code survived into the eligible set")
    }

    @Test
    fun `an eligible keyword is reachable from a garble of itself and from nothing else`() {
        // The property that matters at dispatch time, stated over the whole palette: a token
        // that phonetically reaches keyword X must not also reach keyword Y.
        for (keyword in palette.keywords.eligible.keys) {
            val reached = literals.filter { palette.keywords.matches(keyword, it) }
            val surprising = reached.filterNot {
                it == keyword || Levenshtein.fuzzyEquals(keyword, it)
            }
            assertEquals(emptyList(), surprising, "\"$keyword\" also reaches $surprising")
        }
    }

    @Test
    fun `every literal is accounted for`() {
        // No literal falls through the classification — if one did, `/keywords` would lie about
        // it and nobody would know which rule had let it in.
        val byTrust = literals.groupingBy { palette.keywords.explain(it).trust }.eachCount()
        assertEquals(literals.size, byTrust.values.sum())
        assertEquals(
            mapOf(
                KeywordMatcher.Trust.PHONETIC to 43,
                KeywordMatcher.Trust.TOO_SHORT to 64,
                KeywordMatcher.Trust.CORPUS to 33,
                KeywordMatcher.Trust.CONTESTED to 6,
                KeywordMatcher.Trust.CODE_TOO_SHORT to 8,
            ),
            byTrust,
            "the shape of the phonetic tier moved; /keywords shows the detail",
        )
    }

    @Test
    fun `the code of every eligible keyword is long enough to mean something`() {
        for ((keyword, code) in palette.keywords.eligible) {
            assertTrue(
                code.length >= KeywordMatcher.MIN_CODE_LENGTH && keyword.length >= KeywordMatcher.MIN_LENGTH,
                "\"$keyword\" ($code) slipped past the length guards",
            )
            assertEquals(code, Phonetics.koelner(keyword))
        }
    }

    @Test
    fun `single-literal templates are matched strictly`() {
        val open = palette.entries.filter { palette.isStrict(it) }
        assertTrue(open.isNotEmpty(), "no single-literal template found — the fixtures changed")
        for (entry in open) {
            assertEquals(1, entry.template.specificity.literalWords, entry.template.source)
            assertTrue(entry.template.slots.isEmpty(), entry.template.source)
        }
        // "aus", "stopp", "radio an" and friends. A phonetic near-miss on one of these has
        // nothing else in the utterance to disagree with it.
        assertTrue(open.any { it.template.source == "aus" })
    }
}
