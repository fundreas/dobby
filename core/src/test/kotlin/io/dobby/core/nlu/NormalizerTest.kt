package io.dobby.core.nlu

import kotlin.test.Test
import kotlin.test.assertEquals

class NormalizerTest {

    @Test
    fun `lowercases, strips punctuation and collapses whitespace`() {
        assertEquals("spiele blinding lights", Normalizer.normalize("  Spiele, Blinding   Lights! "))
        assertEquals("wie spät ist es", Normalizer.normalize("Wie spät ist es?"))
    }

    @Test
    fun `keeps umlauts and apostrophes`() {
        assertEquals("ich hab's gehört", Normalizer.normalize("Ich hab's gehört."))
        assertEquals("nächster song", Normalizer.normalize("Nächster Song"))
    }

    @Test
    fun `splits hyphenated words, so templates must be written without hyphens`() {
        assertEquals("u bahn", Normalizer.normalize("U-Bahn"))
    }

    @Test
    fun `turns number words into digits`() {
        assertEquals("timer 10 minuten", Normalizer.normalize("Timer zehn Minuten"))
        assertEquals("stell einen timer auf 5 minuten", Normalizer.normalize("Stell einen Timer auf fünf Minuten"))
        assertEquals("21 minuten", Normalizer.normalize("einundzwanzig Minuten"))
    }

    @Test
    fun `leaves the article one alone`() {
        // The int slot resolves "eine"; the normalizer must not, or "spiele ein Lied" breaks.
        assertEquals("spiele ein lied", Normalizer.normalize("Spiele ein Lied"))
        assertEquals("timer eine minute", Normalizer.normalize("Timer eine Minute"))
    }

    @Test
    fun `tokenize and normalize agree`() {
        assertEquals(listOf("timer", "10", "minuten"), Normalizer.tokenize("Timer zehn Minuten"))
        assertEquals(emptyList(), Normalizer.tokenize("   "))
    }
}
