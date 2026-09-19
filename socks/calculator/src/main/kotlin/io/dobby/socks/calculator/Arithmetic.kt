package io.dobby.socks.calculator

import kotlin.math.abs
import kotlin.math.pow

/**
 * The operators Dobby can be asked for, as the `op` param carries them.
 *
 * The values are single tokens on purpose. They are enum values in a `ParamType.Enumeration`
 * and therefore reachable by an `{op:enum}` slot in principle, and the slot matcher compares
 * one token at a time — so a value spelled "geteilt durch" could never be matched by anything.
 * Nothing captures `op` from a slot today (every template fixes it by its wording), but a value
 * that is unmatchable *by construction* is a trap laid for the next author.
 *
 * [spoken] is how the operator reads back inside the equation, which is a different string:
 * "geteilt durch" is what a person says, `geteilt` is what the param carries.
 */
enum class Operation(val value: String, val spoken: String) {
    PLUS("plus", "plus"),
    MINUS("minus", "minus"),
    TIMES("mal", "mal"),
    DIVIDE("geteilt", "geteilt durch"),
    POWER("hoch", "hoch"),
    MODULO("modulo", "modulo"),

    /**
     * Integer division — "wie oft passt 5 in 17".
     *
     * Its own operator rather than a phrasing of [DIVIDE], because it answers a different
     * question and keeps a different number: the quotient, not the remainder (see
     * [Arithmetic.evaluate]).
     */
    INT_DIVIDE("ganzzahlig", "ganzzahlig geteilt durch"),
    ;

    companion object {
        val VALUES: List<String> = entries.map { it.value }

        fun of(value: String?): Operation? = entries.firstOrNull { it.value == value }
    }
}

/** What came of one calculation. */
sealed interface CalcResult {
    /**
     * @param value what carries into the next utterance ("und jetzt mal 2")
     * @param sentence the whole spoken answer, equation included
     */
    data class Value(val value: Double, val sentence: String) : CalcResult

    /** Arithmetic Dobby will not do, and the German sentence saying so. */
    data class Refused(val message: String) : CalcResult
}

/**
 * One binary operation, its bounds, and the sentence that reads it back.
 *
 * The equation is built here rather than in the Sock because it is not decoration: "8 mal 2 ist
 * 16" is the only way somebody who said just "mal 2" can tell *which* 8 Dobby continued from.
 * The answer without the question in front of it is unverifiable by ear.
 *
 * `a` is a `Double` and `b` a `Long` because they come from different places. `b` is always
 * spoken, so it is whatever an `{b:int}` slot captured; `a` may instead be the previous result,
 * which is where the fractions come from.
 */
object Arithmetic {

    fun evaluate(a: Double, op: Operation, b: Long): CalcResult {
        if (abs(a) > MAX_OPERAND || abs(b) > MAX_OPERAND) return CalcResult.Refused(TOO_BIG_OPERAND)
        return when (op) {
            Operation.PLUS -> value(a + b, equation(a, op, b))
            Operation.MINUS -> value(a - b, equation(a, op, b))
            Operation.TIMES -> value(a * b, equation(a, op, b))
            Operation.DIVIDE ->
                if (b == 0L) CalcResult.Refused(BY_ZERO) else value(a / b, equation(a, op, b))

            Operation.POWER -> power(a, b)
            Operation.MODULO -> modulo(a, b)
            Operation.INT_DIVIDE -> intDivide(a, b)
        }
    }

    /** "8 mal 2" — the left-hand side, read back so the answer can be checked by ear. */
    private fun equation(a: Double, op: Operation, b: Long): String =
        "${CalcNumber.speak(a)} ${op.spoken} ${CalcNumber.speak(b.toDouble())}"

    private fun value(result: Double, equation: String): CalcResult =
        if (!result.isFinite() || abs(result) > MAX_RESULT) {
            CalcResult.Refused(TOO_BIG_RESULT)
        } else {
            // Rounded before it is stored as well as before it is said, so the number the
            // person heard is the number the next utterance continues from.
            CalcResult.Value(CalcNumber.round(result), "$equation ist ${CalcNumber.speak(result)}.")
        }

    /**
     * Guarded by the exponent first, then by the result.
     *
     * 2^10000 is not a large number, it is an overflow to infinity — checking the magnitude
     * afterwards would be checking a value that no longer exists.
     */
    private fun power(a: Double, b: Long): CalcResult {
        if (b < 0 || b > MAX_EXPONENT) return CalcResult.Refused(TOO_BIG_RESULT)
        return value(a.pow(b.toDouble()), equation(a, Operation.POWER, b))
    }

    /**
     * The remainder alone — "17 modulo 5 ist 2".
     *
     * `floorMod`, not `%`: Kotlin's remainder keeps the sign of the dividend, so -17 % 5 is -3,
     * and a remainder you have to think about is not a remainder anybody wanted.
     */
    private fun modulo(a: Double, b: Long): CalcResult {
        if (b == 0L) return CalcResult.Refused(BY_ZERO)
        if (!CalcNumber.isWhole(a)) return CalcResult.Refused(WHOLE_NUMBERS_ONLY)
        val rest = Math.floorMod(a.toLong(), b)
        return value(rest.toDouble(), equation(a, Operation.MODULO, b))
    }

    /**
     * "Wie oft passt 5 in 17?" — "5 passt 3 mal in 17, Rest 2."
     *
     * Both halves of the division in one sentence, which is the whole reason this phrasing
     * exists: somebody portioning dough or cutting boards wants the count *and* the offcut, and
     * asking twice for the two halves of one division is one question too many.
     *
     * What carries into the next utterance is the **quotient**, because "wie oft" is a question
     * about the count. The remainder is spoken but not kept; `modulo` is the operator for
     * somebody who wants to keep it.
     */
    private fun intDivide(a: Double, b: Long): CalcResult {
        if (b == 0L) return CalcResult.Refused(BY_ZERO)
        if (!CalcNumber.isWhole(a)) return CalcResult.Refused(WHOLE_NUMBERS_ONLY)
        val whole = a.toLong()
        val times = Math.floorDiv(whole, b)
        val rest = Math.floorMod(whole, b)
        val divisor = CalcNumber.speak(b.toDouble())
        val dividend = CalcNumber.speak(a)
        val count = CalcNumber.speak(times.toDouble())
        return CalcResult.Value(
            times.toDouble(),
            if (rest == 0L) {
                "$divisor passt genau $count mal in $dividend."
            } else {
                "$divisor passt $count mal in $dividend, Rest ${CalcNumber.speak(rest.toDouble())}."
            },
        )
    }

    /** German copy, verbatim from `socks.specs/calculator.specs.md` §3. */
    const val BY_ZERO: String = "Durch null kann ich nicht teilen."
    const val TOO_BIG_OPERAND: String = "Diese Zahl ist zu groß für mich."
    const val TOO_BIG_RESULT: String = "Das Ergebnis ist zu groß für mich."
    const val WHOLE_NUMBERS_ONLY: String = "Das geht nur mit ganzen Zahlen."

    /** A milliard either way: past this a wall panel is the wrong tool. */
    private const val MAX_OPERAND = 1_000_000_000.0

    /** A billion (10¹²) — still exact in a `Double`, and still a number you can say. */
    private const val MAX_RESULT = 1_000_000_000_000.0

    /** Above this even a base of 2 leaves [MAX_RESULT] behind. */
    private const val MAX_EXPONENT = 64L
}
