package io.dobby.socks.clock

import java.time.LocalTime

/**
 * Speaks a time the way a person in Vienna would, not the way a clock displays it.
 *
 * The quarter and half forms are 12-hour and **forward-looking**: German "halb drei" is 14:30,
 * not 15:30 — it means "half way to three", not "half past three". Getting that backwards is
 * the classic bug in every naive implementation, so it has its own test.
 *
 * Everything that is not an exact hour, quarter or half falls back to the plain 24-hour form,
 * which is unambiguous and is what people actually say for times like 23:47.
 */
object GermanTime {

    fun speak(time: LocalTime): String = "Es ist ${phrase(time)}."

    private fun phrase(time: LocalTime): String = when (time.minute) {
        0 -> "${time.hour} Uhr"
        15 -> "viertel nach ${hour12(time.hour)}"
        30 -> "halb ${hour12(time.hour + 1)}"
        45 -> "viertel vor ${hour12(time.hour + 1)}"
        else -> "${time.hour} Uhr ${time.minute}"
    }

    /** 24-hour → spoken 12-hour, where 0 and 12 both read as "12". Wraps past midnight. */
    private fun hour12(hour: Int): Int {
        val wrapped = hour % 24 % 12
        return if (wrapped == 0) 12 else wrapped
    }
}
