package io.dobby.socks.calculator

import kotlin.test.Test
import kotlin.test.assertEquals

/** `calculator.specs.md` §3: what each operator computes, and what it says while computing it. */
class ArithmeticTest {

    private fun spoken(a: Double, op: Operation, b: Long): String =
        when (val result = Arithmetic.evaluate(a, op, b)) {
            is CalcResult.Value -> result.sentence
            is CalcResult.Refused -> result.message
        }

    private fun value(a: Double, op: Operation, b: Long): Double? =
        (Arithmetic.evaluate(a, op, b) as? CalcResult.Value)?.value

    @Test
    fun `the answer always reads the question back`() {
        assertEquals("3 plus 5 ist 8.", spoken(3.0, Operation.PLUS, 5))
        assertEquals("12 minus 4 ist 8.", spoken(12.0, Operation.MINUS, 4))
        assertEquals("7 mal 8 ist 56.", spoken(7.0, Operation.TIMES, 8))
        assertEquals("20 geteilt durch 4 ist 5.", spoken(20.0, Operation.DIVIDE, 4))
        assertEquals("2 hoch 10 ist 1024.", spoken(2.0, Operation.POWER, 10))
    }

    @Test
    fun `a negative result is a word, not a hyphen`() {
        assertEquals("3 minus 5 ist minus 2.", spoken(3.0, Operation.MINUS, 5))
    }

    @Test
    fun `German writes the decimal separator as a comma`() {
        assertEquals("17 geteilt durch 2 ist 8,5.", spoken(17.0, Operation.DIVIDE, 2))
    }

    @Test
    fun `a division that does not come out even says so`() {
        // "ungefähr" is the whole difference between an answer and a claim.
        assertEquals("10 geteilt durch 3 ist ungefähr 3,3333.", spoken(10.0, Operation.DIVIDE, 3))
    }

    @Test
    fun `modulo keeps the remainder and nothing else`() {
        assertEquals("17 modulo 5 ist 2.", spoken(17.0, Operation.MODULO, 5))
        assertEquals(2.0, value(17.0, Operation.MODULO, 5))
        assertEquals("15 modulo 5 ist 0.", spoken(15.0, Operation.MODULO, 5))
    }

    @Test
    fun `modulo of a negative number is still a remainder anybody would recognise`() {
        // floorMod, not `%`: Kotlin's remainder would answer "minus 3" here.
        assertEquals(3.0, value(-17.0, Operation.MODULO, 5))
    }

    @Test
    fun `integer division answers both halves of the question at once`() {
        assertEquals("5 passt 3 mal in 17, Rest 2.", spoken(17.0, Operation.INT_DIVIDE, 5))
        assertEquals("250 passt genau 4 mal in 1000.", spoken(1000.0, Operation.INT_DIVIDE, 250))
    }

    @Test
    fun `integer division keeps the count, because that is what was asked`() {
        assertEquals(3.0, value(17.0, Operation.INT_DIVIDE, 5))
    }

    @Test
    fun `what it will not do, and what it says instead`() {
        assertEquals(Arithmetic.BY_ZERO, spoken(10.0, Operation.DIVIDE, 0))
        assertEquals(Arithmetic.BY_ZERO, spoken(10.0, Operation.MODULO, 0))
        assertEquals(Arithmetic.BY_ZERO, spoken(10.0, Operation.INT_DIVIDE, 0))
        assertEquals(Arithmetic.WHOLE_NUMBERS_ONLY, spoken(2.5, Operation.MODULO, 2))
        assertEquals(Arithmetic.WHOLE_NUMBERS_ONLY, spoken(2.5, Operation.INT_DIVIDE, 2))
        assertEquals(Arithmetic.TOO_BIG_OPERAND, spoken(2_000_000_000.0, Operation.PLUS, 1))
        assertEquals(Arithmetic.TOO_BIG_RESULT, spoken(2.0, Operation.POWER, 200))
        // Guarded by the exponent before the value exists — 2^10000 is not a large number,
        // it is an infinity.
        assertEquals(Arithmetic.TOO_BIG_RESULT, spoken(999_999_999.0, Operation.TIMES, 999_999))
    }

    @Test
    fun `what carries into the next utterance is what was said out loud`() {
        // Not 3.3333333333333335: the memory keeps the number the person heard, so "mal 3"
        // answers something they can check against what Dobby just told them.
        assertEquals(3.3333, value(10.0, Operation.DIVIDE, 3))
    }
}
