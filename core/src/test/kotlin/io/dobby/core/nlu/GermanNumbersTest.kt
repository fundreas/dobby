package io.dobby.core.nlu

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GermanNumbersTest {

    @Test
    fun `parses cardinals`() {
        val cases = mapOf(
            "null" to 0,
            "zwei" to 2,
            "zehn" to 10,
            "elf" to 11,
            "zwölf" to 12,
            "dreizehn" to 13,
            "sechzehn" to 16,
            "siebzehn" to 17,
            "neunzehn" to 19,
            "zwanzig" to 20,
            "dreißig" to 30,
            "neunzig" to 90,
            "hundert" to 100,
            "tausend" to 1000,
        )
        for ((word, expected) in cases) {
            assertEquals(expected, GermanNumbers.parse(word), word)
        }
    }

    @Test
    fun `parses und-compounds`() {
        assertEquals(21, GermanNumbers.parse("einundzwanzig"))
        assertEquals(27, GermanNumbers.parse("siebenundzwanzig"))
        assertEquals(45, GermanNumbers.parse("fünfundvierzig"))
        assertEquals(99, GermanNumbers.parse("neunundneunzig"))
    }

    @Test
    fun `parses hundreds`() {
        assertEquals(200, GermanNumbers.parse("zweihundert"))
        assertEquals(120, GermanNumbers.parse("hundertzwanzig"))
        assertEquals(321, GermanNumbers.parse("dreihunderteinundzwanzig"))
    }

    @Test
    fun `accepts umlaut-free spellings, since STT is inconsistent about them`() {
        assertEquals(5, GermanNumbers.parse("fuenf"))
        assertEquals(30, GermanNumbers.parse("dreissig"))
        assertEquals(12, GermanNumbers.parse("zwoelf"))
    }

    @Test
    fun `refuses the article forms of one`() {
        // "spiele ein Lied" must not normalize to "spiele 1 lied".
        for (article in GermanNumbers.oneArticles) {
            assertNull(GermanNumbers.parse(article), article)
        }
        assertEquals(1, GermanNumbers.parse("eins"))
    }

    @Test
    fun `an int slot resolves the article forms, because there the context is unambiguous`() {
        assertEquals(1, GermanNumbers.parseSlotValue("eine"))
        assertEquals(1, GermanNumbers.parseSlotValue("einen"))
        assertEquals(10, GermanNumbers.parseSlotValue("10"))
        assertEquals(10, GermanNumbers.parseSlotValue("zehn"))
    }

    @Test
    fun `rejects non-numbers`() {
        for (word in listOf("timer", "hundertschaft", "undzwanzig", "zweiunddrei", "")) {
            assertNull(GermanNumbers.parse(word), word)
        }
    }
}
