package io.dobby.socks.clock

import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals

class GermanTimeTest {

    private fun at(text: String) = GermanTime.speak(LocalTime.parse(text))

    @Test
    fun `the table from the spec`() {
        assertEquals("Es ist 14 Uhr.", at("14:00"))
        assertEquals("Es ist 14 Uhr 5.", at("14:05"))
        assertEquals("Es ist halb 3.", at("14:30"))
        assertEquals("Es ist viertel nach 9.", at("09:15"))
        assertEquals("Es ist viertel vor 10.", at("09:45"))
        assertEquals("Es ist 23 Uhr 47.", at("23:47"))
    }

    @Test
    fun `halb counts forward, not backward`() {
        // "halb drei" is 14:30. Reading it as 15:30 is the classic bug this test exists for.
        assertEquals("Es ist halb 3.", at("14:30"))
        assertEquals("Es ist halb 8.", at("07:30"))
        assertEquals("Es ist halb 1.", at("12:30"))
        assertEquals("Es ist halb 1.", at("00:30"))
        assertEquals("Es ist halb 12.", at("23:30"))
    }

    @Test
    fun `quarters wrap correctly around noon and midnight`() {
        assertEquals("Es ist viertel nach 12.", at("00:15"))
        assertEquals("Es ist viertel nach 12.", at("12:15"))
        assertEquals("Es ist viertel vor 1.", at("12:45"))
        assertEquals("Es ist viertel vor 12.", at("23:45"))
        assertEquals("Es ist viertel vor 1.", at("00:45"))
    }

    @Test
    fun `exact hours stay on the 24-hour clock`() {
        assertEquals("Es ist 0 Uhr.", at("00:00"))
        assertEquals("Es ist 12 Uhr.", at("12:00"))
        assertEquals("Es ist 23 Uhr.", at("23:00"))
    }

    @Test
    fun `everything else falls back to the plain form`() {
        assertEquals("Es ist 9 Uhr 1.", at("09:01"))
        assertEquals("Es ist 9 Uhr 16.", at("09:16"))
        assertEquals("Es ist 9 Uhr 29.", at("09:29"))
        assertEquals("Es ist 9 Uhr 31.", at("09:31"))
        assertEquals("Es ist 9 Uhr 59.", at("09:59"))
    }

    @Test
    fun `seconds are ignored`() {
        assertEquals("Es ist halb 3.", GermanTime.speak(LocalTime.of(14, 30, 59)))
    }

    @Test
    fun `every minute of the day produces a sentence`() {
        for (minuteOfDay in 0 until 24 * 60) {
            val spoken = GermanTime.speak(LocalTime.ofSecondOfDay(minuteOfDay * 60L))
            assert(spoken.startsWith("Es ist ") && spoken.endsWith(".")) { spoken }
        }
    }
}
