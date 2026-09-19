package io.dobby.core.nlu

import io.dobby.core.nlu.template.Phonetics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PhoneticsTest {

    @Test
    fun `reference codes from the literature`() {
        // The canonical worked example, and the two that exercise every context rule between
        // them: `sch`, `dt`, `ph`, and a leading vowel that survives.
        assertEquals("65752682", Phonetics.koelner("Müller-Lüdenscheidt"))
        assertEquals("3412", Phonetics.koelner("Wikipedia"))
        assertEquals("17863", Phonetics.koelner("Breschnew"))
    }

    @Test
    fun `the context rules`() {
        // c is hard before a, h, k, o, q, u, x — and always 8 after s or z.
        assertEquals("47", Phonetics.koelner("Chor"))
        // Folding runs first, so the hard-c rule sees the "a" of a folded "ä". Deliberate: the
        // normalizer hands this class umlauts, and a "c" whose code depended on whether the
        // speaker's word survived Unicode would be worse than one that is merely approximate.
        assertEquals("487", Phonetics.koelner("Cäsar"))
        assertEquals("847", Phonetics.koelner("Zucker"))
        // d/t is 8 when it stands immediately before c, s or z, and 2 everywhere else.
        assertEquals("158", Phonetics.koelner("Platz"))
        assertEquals("268", Phonetics.koelner("Tanz"))
        // p before h is 3 (an f sound), 1 otherwise.
        assertEquals("351", Phonetics.koelner("Philipp"))
        // x is two codes, or one after c/k/q.
        assertEquals("4837", Phonetics.koelner("Xaver"))
        assertEquals("048", Phonetics.koelner("Hexe"))
    }

    @Test
    fun `umlauts and eszett are folded, because the normalizer leaves them in`() {
        assertEquals(Phonetics.koelner("spule"), Phonetics.koelner("spüle"))
        assertEquals(Phonetics.koelner("loescher"), Phonetics.koelner("löscher"))
        assertEquals("8278", Phonetics.koelner("straße"))
        assertEquals(Phonetics.koelner("strasse"), Phonetics.koelner("straße"))
    }

    @Test
    fun `digits and punctuation code to nothing`() {
        assertEquals("", Phonetics.koelner("20"))
        assertEquals("", Phonetics.koelner("?!"))
        assertEquals("", Phonetics.koelner(""))
        // A token the normalizer produced from "zehn minuten" is one word at a time, but a
        // number glued to a word must not silently change the word's code.
        assertEquals(Phonetics.koelner("timer"), Phonetics.koelner("timer5"))
    }

    @Test
    fun `a leading vowel is kept and every other zero is dropped`() {
        assertEquals("0782", Phonetics.koelner("uhrzeit"))
        assertEquals("0782", Phonetics.koelner("urzeit"))
        // All-vowel input collapses to the single leading zero rather than to nothing.
        assertEquals("0", Phonetics.koelner("aue"))
    }

    @Test
    fun `vowel blindness, documented as the hazard it is`() {
        // These are not bugs to be fixed. They are why KeywordMatcher exists, and why nothing
        // else in the codebase is allowed to call koelner() and act on the answer.
        assertEquals(Phonetics.koelner("an"), Phonetics.koelner("in"))
        assertEquals(Phonetics.koelner("an"), Phonetics.koelner("ein"))
        assertEquals(Phonetics.koelner("aus"), Phonetics.koelner("es"))
        // Not quite the plan's "aus/es/ist/hat are all 08": a trailing consonant does survive,
        // so `ist` is 082 and `hat` is 02. The hazard is real and one word smaller than stated.
        assertEquals("082", Phonetics.koelner("ist"))
        assertEquals("02", Phonetics.koelner("hat"))
        assertEquals(Phonetics.koelner("spiele"), Phonetics.koelner("spüle"))
        assertEquals(Phonetics.koelner("timer"), Phonetics.koelner("tumor"))
        assertEquals(Phonetics.koelner("minuten"), Phonetics.koelner("monaten"))
        assertEquals(Phonetics.koelner("lauter"), Phonetics.koelner("leiter"))
        assertEquals(Phonetics.koelner("wetter"), Phonetics.koelner("weiter"))
    }

    @Test
    fun `the code catches what Levenshtein cannot`() {
        // The motivating case: three edits apart, one sound.
        assertEquals("815", Phonetics.koelner("spiele"))
        assertEquals("815", Phonetics.koelner("schbiele"))
        assertEquals(Phonetics.koelner("stopp"), Phonetics.koelner("schtopp"))
    }

    @Test
    fun `the skeleton separates a misheard cluster from a different word`() {
        // Same code, same consonants: a different German word. The code is not evidence here.
        assertEquals(Phonetics.skeleton("spiele"), Phonetics.skeleton("spüle"))
        assertEquals(Phonetics.skeleton("timer"), Phonetics.skeleton("tumor"))
        assertEquals(Phonetics.skeleton("minuten"), Phonetics.skeleton("monaten"))
        assertEquals(Phonetics.skeleton("leiser"), Phonetics.skeleton("löser"))
        assertEquals(Phonetics.skeleton("wecker"), Phonetics.skeleton("wacker"))
        assertEquals(Phonetics.skeleton("lauter"), Phonetics.skeleton("leiter"))
        // Doubles collapse, or "wetter" and "weiter" would look like different words.
        assertEquals(Phonetics.skeleton("wetter"), Phonetics.skeleton("weiter"))

        // Same code, different consonants: STT heard the sound and spelled the cluster wrong.
        assertNotEquals(Phonetics.skeleton("spiele"), Phonetics.skeleton("schbiele"))
        assertNotEquals(Phonetics.skeleton("stopp"), Phonetics.skeleton("schtopp"))
    }

    @Test
    fun `the skeleton drops the silent h`() {
        assertEquals("r", Phonetics.skeleton("ohr"))
        assertEquals("r", Phonetics.skeleton("hör"))
        assertTrue(Phonetics.skeleton("uhrzeit").isNotEmpty())
        assertEquals(Phonetics.skeleton("uhrzeit"), Phonetics.skeleton("urzeit"))
    }
}
