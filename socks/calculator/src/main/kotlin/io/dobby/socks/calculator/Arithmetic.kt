package io.dobby.socks.calculator

import io.dobby.core.sock.Lang
import io.dobby.core.sock.Phrase
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
enum class Operation(val value: String, private val de: String, private val en: String) {
    PLUS("plus", "plus", "plus"),
    MINUS("minus", "minus", "minus"),
    TIMES("mal", "mal", "times"),
    DIVIDE("geteilt", "geteilt durch", "divided by"),
    POWER("hoch", "hoch", "to the power of"),
    MODULO("modulo", "modulo", "modulo"),

    /**
     * Integer division — "wie oft passt 5 in 17".
     *
     * Its own operator rather than a phrasing of [DIVIDE], because it answers a different
     * question and keeps a different number: the quotient, not the remainder (see
     * [Arithmetic.evaluate]).
     */
    INT_DIVIDE("ganzzahlig", "ganzzahlig geteilt durch", "divided evenly by"),
    ;

    /** How the operator reads back inside the equation, in the language being spoken. */
    fun spoken(lang: Lang): String = if (lang == Lang.EN) en else de

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
    data class Value(val value: Double, val sentence: Phrase) : CalcResult

    /** Arithmetic Dobby will not do, and the sentence saying so. */
    data class Refused(val message: Phrase) : CalcResult
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
            Operation.PLUS -> value(a + b, op, a, b)
            Operation.MINUS -> value(a - b, op, a, b)
            Operation.TIMES -> value(a * b, op, a, b)
            Operation.DIVIDE ->
                if (b == 0L) CalcResult.Refused(BY_ZERO) else value(a / b, op, a, b)

            Operation.POWER -> power(a, b)
            Operation.MODULO -> modulo(a, b)
            Operation.INT_DIVIDE -> intDivide(a, b)
        }
    }

    /** "8 mal 2" / "8 times 2" — the left-hand side, read back so the answer checks by ear. */
    private fun equation(a: Double, op: Operation, b: Long, lang: Lang): String =
        "${CalcNumber.speak(a, lang)} ${op.spoken(lang)} ${CalcNumber.speak(b.toDouble(), lang)}"

    private fun value(result: Double, op: Operation, a: Double, b: Long): CalcResult =
        if (!result.isFinite() || abs(result) > MAX_RESULT) {
            CalcResult.Refused(TOO_BIG_RESULT)
        } else {
            // Rounded before it is stored as well as before it is said, so the number the
            // person heard is the number the next utterance continues from.
            CalcResult.Value(CalcNumber.round(result)) { lang ->
                val verb = if (lang == Lang.EN) "is" else "ist"
                "${equation(a, op, b, lang)} $verb ${CalcNumber.speak(result, lang)}."
            }
        }

    /**
     * Guarded by the exponent first, then by the result.
     *
     * 2^10000 is not a large number, it is an overflow to infinity — checking the magnitude
     * afterwards would be checking a value that no longer exists.
     */
    private fun power(a: Double, b: Long): CalcResult {
        if (b < 0 || b > MAX_EXPONENT) return CalcResult.Refused(TOO_BIG_RESULT)
        return value(a.pow(b.toDouble()), Operation.POWER, a, b)
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
        return value(rest.toDouble(), Operation.MODULO, a, b)
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
        return CalcResult.Value(times.toDouble()) { lang ->
            val divisor = CalcNumber.speak(b.toDouble(), lang)
            val dividend = CalcNumber.speak(a, lang)
            val count = CalcNumber.speak(times.toDouble(), lang)
            when {
                lang == Lang.EN && rest == 0L -> "$divisor goes into $dividend exactly $count times."
                lang == Lang.EN ->
                    "$divisor goes into $dividend $count times, remainder " +
                        "${CalcNumber.speak(rest.toDouble(), lang)}."

                rest == 0L -> "$divisor passt genau $count mal in $dividend."
                else ->
                    "$divisor passt $count mal in $dividend, Rest " +
                        "${CalcNumber.speak(rest.toDouble(), lang)}."
            }
        }
    }

    /** Spec copy (`socks.specs/calculator.specs.md` §3), German verbatim, English alongside. */
    val BY_ZERO: Phrase = Phrase.of("Durch null kann ich nicht teilen.", "I can't divide by zero.")
    val TOO_BIG_OPERAND: Phrase =
        Phrase.of("Diese Zahl ist zu groß für mich.", "That number is too big for me.")
    val TOO_BIG_RESULT: Phrase =
        Phrase.of("Das Ergebnis ist zu groß für mich.", "That result is too big for me.")
    val WHOLE_NUMBERS_ONLY: Phrase =
        Phrase.of("Das geht nur mit ganzen Zahlen.", "That only works with whole numbers.")

    /** A milliard either way: past this a wall panel is the wrong tool. */
    private const val MAX_OPERAND = 1_000_000_000.0

    /** A billion (10¹²) — still exact in a `Double`, and still a number you can say. */
    private const val MAX_RESULT = 1_000_000_000_000.0

    /** Above this even a base of 2 leaves [MAX_RESULT] behind. */
    private const val MAX_EXPONENT = 64L
}
