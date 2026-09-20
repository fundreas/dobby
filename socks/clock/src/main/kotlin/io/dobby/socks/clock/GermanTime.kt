package io.dobby.socks.clock

import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max

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

    private val DATE = DateTimeFormatter.ofPattern("EEEE, d. MMMM", Locale.GERMAN)

    fun speak(time: LocalTime): String = "Es ist ${phrase(time)}."

    /**
     * "10 Minuten", "1 Minute" — the duration in a timer acknowledgement.
     *
     * The spec writes this slot as `{amount} {unit}`; the singular is folded in here because
     * "Timer läuft: 1 Minuten" is not a sentence anyone would say.
     */
    fun duration(amount: Int, unit: TimerUnit): String =
        "$amount ${if (amount == 1) unit.singular else unit.plural}"

    /**
     * "noch 9 Minuten", "noch 1 Stunde und 5 Minuten" — the answer to "wie lange geht der Timer
     * noch" (§6).
     *
     * Two parts at most, and the smaller one only when it is not zero. "Noch 1 Stunde, 5 Minuten
     * und 3 Sekunden" is a stopwatch reading; a person asking how long the noodles have wants
     * the number they can act on and one below it.
     */
    fun remaining(remainingMs: Long): String {
        val total = max(0L, remainingMs + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND // round up
        val hours = (total / SECONDS_PER_HOUR).toInt()
        val minutes = (total / SECONDS_PER_MINUTE % MINUTES_PER_HOUR).toInt()
        val seconds = (total % SECONDS_PER_MINUTE).toInt()
        return when {
            hours > 0 -> join(duration(hours, TimerUnit.STUNDEN), minutes, TimerUnit.MINUTEN)
            minutes > 0 -> join(duration(minutes, TimerUnit.MINUTEN), seconds, TimerUnit.SEKUNDEN)
            else -> duration(seconds, TimerUnit.SEKUNDEN)
        }
    }

    /** "Timer Nudeln", "Timer Nudeln und Timer 2", "A, B und C" — a spoken enumeration. */
    fun list(items: List<String>): String = when (items.size) {
        0 -> ""
        1 -> items.single()
        else -> items.dropLast(1).joinToString(", ") + " und " + items.last()
    }

    private fun join(head: String, rest: Int, unit: TimerUnit): String =
        if (rest == 0) head else "$head und ${duration(rest, unit)}"

    /** "Donnerstag, 18. September" — the dashboard's date line (`clock.specs.md` §9). */
    fun date(date: LocalDate): String = DATE.format(date)

    /** "09:59", or "1:02:03" past the hour — the dashboard's timer countdown. */
    fun countdown(remainingMs: Long): String {
        val total = max(0L, remainingMs + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND // round up
        val seconds = total % SECONDS_PER_MINUTE
        val minutes = total / SECONDS_PER_MINUTE % MINUTES_PER_HOUR
        val hours = total / SECONDS_PER_HOUR
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%02d:%02d".format(minutes, seconds)
        }
    }

    private fun phrase(time: LocalTime): String = when (time.minute) {
        0 -> "${time.hour} Uhr"
        QUARTER -> "viertel nach ${hour12(time.hour)}"
        HALF -> "halb ${hour12(time.hour + 1)}"
        THREE_QUARTERS -> "viertel vor ${hour12(time.hour + 1)}"
        else -> "${time.hour} Uhr ${time.minute}"
    }

    /** 24-hour → spoken 12-hour, where 0 and 12 both read as "12". Wraps past midnight. */
    private fun hour12(hour: Int): Int {
        val wrapped = hour % HOURS_PER_DAY % HOURS_PER_HALF_DAY
        return if (wrapped == 0) HOURS_PER_HALF_DAY else wrapped
    }

    private const val QUARTER = 15
    private const val HALF = 30
    private const val THREE_QUARTERS = 45
    private const val HOURS_PER_DAY = 24
    private const val HOURS_PER_HALF_DAY = 12
    private const val MILLIS_PER_SECOND = 1000L
    private const val SECONDS_PER_MINUTE = 60L
    private const val MINUTES_PER_HOUR = 60L
    private const val SECONDS_PER_HOUR = 3600L
}
