package io.dobby.core.nlu

import io.dobby.core.nlu.template.KeywordMatcher
import io.dobby.core.nlu.template.Levenshtein
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KeywordMatcherTest {

    /** A small palette with the shapes that matter, rather than the whole catalog. */
    private val matcher = KeywordMatcher.over(
        listOf("spiele", "stoppen", "bildschirm", "abbrechen", "lautstärke", "überspringen", "an", "aus"),
    )

    @Test
    fun `the motivating case`() {
        // "schbiele" is 3 edits from "spiele". No Levenshtein tolerance reaches it, and none
        // that did would be safe for a six-letter word.
        assertFalse(Levenshtein.fuzzyEquals("schbiele", "spiele"))
        assertTrue(matcher.matches("schbiele", "spiele"))
    }

    @Test
    fun `exact and Levenshtein still work with no phonetics involved`() {
        assertTrue(matcher.matches("spiele", "spiele"))
        assertTrue(matcher.matches("spiel", "spiele"))
        assertTrue(KeywordMatcher.STRICT.matches("spiele", "spiele"))
        assertTrue(KeywordMatcher.STRICT.matches("spielr", "spiele"))
        // …and STRICT adds nothing beyond them.
        assertFalse(KeywordMatcher.STRICT.matches("schbiele", "spiele"))
    }

    @Test
    fun `a different German word with the same code does not match`() {
        // Same code as "spiele", same consonants: this is the kitchen sink, not a mishearing.
        assertFalse(matcher.matches("spüle", "spiele"))
        assertFalse(matcher.matches("spule", "spiele"))
    }

    @Test
    fun `short words are never phonetic, in either position`() {
        // The whole `06` / `08` family. If any of these matched, every third utterance would.
        val pairs = listOf(
            "an" to "aus", "in" to "an", "ein" to "an", "es" to "aus", "ist" to "aus",
            "hat" to "aus", "ton" to "tun", "dem" to "den", "hör" to "uhr", "das" to "des",
            "am" to "im", "man" to "mein", "und" to "ende", "nun" to "nein", "wir" to "wer",
            "bei" to "bau", "gut" to "gott", "mal" to "mehl", "sehr" to "sahr", "ab" to "ob",
        )
        for ((token, keyword) in pairs) {
            val matcher = KeywordMatcher.over(listOf(keyword))
            assertFalse(
                matcher.matches(token, keyword) && !Levenshtein.fuzzyEquals(token, keyword),
                "\"$token\" phonetically reached \"$keyword\"",
            )
        }
    }

    @Test
    fun `a contested code retires both sides, not one`() {
        // "kommt" and "kennst" are 4628 and 4668 — not contested. "breche" and "brich" are, and
        // neither may win: picking one is picking which Sock a misheard word goes to.
        val contested = KeywordMatcher.over(listOf("breche", "brauche"))
        assertEquals(emptyMap(), contested.eligible)
        assertEquals(KeywordMatcher.Trust.CONTESTED, contested.explain("breche").trust)
        assertEquals(KeywordMatcher.Trust.CONTESTED, contested.explain("brauche").trust)
    }

    @Test
    fun `keywords Levenshtein already conflates are not contested`() {
        // "stell"/"stelle" share a code, but they already match each other, so the phonetic
        // tier opens no new route and retiring them would buy nothing.
        val matcher = KeywordMatcher.over(listOf("stelle", "stellen"))
        assertTrue(matcher.eligible.isNotEmpty(), "a Levenshtein-equivalent pair was retired")
    }

    @Test
    fun `the palette-wide contested list is pinned by name`() {
        // The day a new Sock retires one of these — or stops retiring it — this test says so,
        // rather than the phonetic tier quietly changing shape.
        val registry = io.dobby.core.registry.SockRegistry.buildOrThrow(
            io.dobby.core.registry.SpecFixtures.all(),
        )
        val literals = registry.palette.entries.flatMap { it.template.literals }.distinct()
        val contested = literals
            .filter { registry.palette.keywords.explain(it).trust == KeywordMatcher.Trust.CONTESTED }
            .sorted()
        assertEquals(
            listOf("breche", "brich", "stelle", "still", "weiter", "wieder"),
            contested,
        )
    }

    @Test
    fun `the eligible list is pinned by name too`() {
        val registry = io.dobby.core.registry.SockRegistry.buildOrThrow(
            io.dobby.core.registry.SpecFixtures.all(),
        )
        val eligible = registry.palette.keywords.eligible.keys.sorted()
        // 43 of 155 distinct literals. The rest are too short (64), retired by the corpus (34),
        // contested (6) or have a code under three digits (8) — `/keywords` prints the whole
        // breakdown. `spiele` is in here, which is the point of the milestone.
        assertEquals(43, eligible.size, "eligible keywords: $eligible")
        assertTrue("spiele" in eligible)
        assertTrue("bildschirm" in eligible)
        assertTrue("überspringen" in eligible)
    }

    @Test
    fun `explain says why, for the CLI and for whoever is confused`() {
        assertEquals(KeywordMatcher.Trust.PHONETIC, matcher.explain("spiele").trust)
        assertEquals(KeywordMatcher.Trust.TOO_SHORT, matcher.explain("an").trust)
        assertEquals(KeywordMatcher.Trust.CORPUS, matcher.explain("uhrzeit").trust)
        assertEquals(KeywordMatcher.Trust.STRICT_MATCHER, KeywordMatcher.STRICT.explain("spiele").trust)
        assertEquals("815", matcher.explain("spiele").code)
    }
}
