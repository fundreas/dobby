package io.dobby.socks.calculator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CalcNumberTest {

    @Test
    fun `whole numbers lose the decimal point entirely`() {
        assertEquals("8", CalcNumber.speak(8.0))
        assertEquals("0", CalcNumber.speak(0.0))
        assertEquals("1024", CalcNumber.speak(1024.0))
    }

    @Test
    fun `a value that is exact at four places is not hedged`() {
        assertEquals("2,5", CalcNumber.speak(2.5))
        assertEquals("3,3333", CalcNumber.speak(3.3333))
        assertEquals("0,125", CalcNumber.speak(0.125))
    }

    @Test
    fun `a value that had to be rounded says so`() {
        assertEquals("ungefähr 3,3333", CalcNumber.speak(10.0 / 3.0))
        assertEquals("ungefähr 0,6667", CalcNumber.speak(2.0 / 3.0))
    }

    @Test
    fun `the sign is spoken, and it comes before the hedge is spent`() {
        assertEquals("minus 4", CalcNumber.speak(-4.0))
        assertEquals("ungefähr minus 3,3333", CalcNumber.speak(-10.0 / 3.0))
    }

    @Test
    fun `round gives back exactly what speak would say`() {
        assertEquals(3.3333, CalcNumber.round(10.0 / 3.0))
        assertEquals(2.5, CalcNumber.round(2.5))
    }

    @Test
    fun `whole is about the value, not the type`() {
        assertTrue(CalcNumber.isWhole(17.0))
        assertTrue(CalcNumber.isWhole(-17.0))
        assertFalse(CalcNumber.isWhole(17.5))
    }
}
