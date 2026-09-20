package io.dobby.socks.calculator

import io.dobby.core.sock.Lang
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs

/**
 * Speaks a number the way a person reads one out, not the way a `Double` prints.
 *
 * Three things are wrong with `toString()` here and all three are audible. German writes the
 * decimal separator as a comma, so "2.5" is read back as two numbers. A negative sign in front
 * of a digit is not a word, so it is spelled "minus". And a division that does not come out
 * even has no last digit at all — 10 ÷ 3 is not 3.3333333333333335, it is *about* a third of
 * ten, and saying "ungefähr" is the difference between an answer and a claim.
 *
 * [DECIMALS] is where that last point is decided: four places is more than a kitchen ever
 * needs and few enough to say out loud in one breath.
 */
object CalcNumber {

    /** Decimal places Dobby is willing to pronounce. */
    const val DECIMALS: Int = 4

    /** "8", "minus 4", "2,5", "ungefähr 3,3333" — and "8", "minus 4", "2.5", "about 3.3333". */
    fun speak(value: Double, lang: Lang = Lang.DEFAULT): String {
        val rounded = round(value)
        val plain = BigDecimal(abs(rounded))
            .setScale(DECIMALS, RoundingMode.HALF_UP)
            .stripTrailingZeros()
            .toPlainString()
        // The separator is the whole reason this function takes a language: a German voice
        // reading "2.5" says two numbers, and an English one reading "2,5" does the same.
        val digits = if (lang == Lang.EN) plain else plain.replace('.', ',')
        return buildString {
            // The hedge and the memory are the same question asked once: "ungefähr" appears
            // exactly when the number Dobby keeps differs from the number it just said.
            if (rounded != value) append(if (lang == Lang.EN) "about " else "ungefähr ")
            if (rounded < 0) append("minus ")
            append(digits)
        }
    }

    /**
     * The value [speak] would say, as a number.
     *
     * This is what goes into the memory, so that "und jetzt mal 3" continues from the number
     * the person actually heard. Keeping the full `Double` instead would mean Dobby says
     * "ungefähr 3,3333" and then multiplies by something else — defensible to a floating-point
     * library, indefensible to somebody standing at a worktop with a pencil.
     *
     * Rounds to the nearest `Double`, not to a decimal: `3,3333` has no exact binary form, so
     * "did this have to be rounded" can only be asked of doubles, never of the literal.
     */
    fun round(value: Double): Double =
        BigDecimal(value).setScale(DECIMALS, RoundingMode.HALF_UP).toDouble()

    /** True when [value] is a whole number — what `modulo` and `ganzzahlig` need. */
    fun isWhole(value: Double): Boolean = value.isFinite() && value == Math.rint(value)
}
