package io.dobby.socks.clock

import io.dobby.core.sock.Lang
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max

/**
 * Speaks a time the way a person reads one out, not the way a clock displays it.
 *
 * Was `GermanTime`, and the rename is the whole of what i18n did to this file: the German
 * forms are unchanged and the English ones sit beside them. An object called `GermanTime`
 * returning "It's half past two" would be a name that lies.
 *
 * The German quarter and half forms are 12-hour and **forward-looking**: "halb drei" is 14:30,
 * not 15:30 — it means "half way to three", not "half past three". Getting that backwards is
 * the classic bug in every naive implementation, so it has its own test. English is the other
 * way round and reads "half past two" for the same instant, which is exactly why this cannot
 * be a lookup table over translated fragments.
 *
 * Everything that is not an exact hour, quarter or half falls back to the plain 24-hour form in
 * German, which is unambiguous and is what people actually say for times like 23:47. English
 * gets the 12-hour form with an am/pm tail, for the same reason: it is what people say.
 */
object SpokenTime {

    /** The dashboard's date line is part of the panel's German UI (`clock.specs.md` §9). */
    private val DATE = DateTimeFormatter.ofPattern("EEEE, d. MMMM", Locale.GERMAN)

    /**
     * The spoken date, which is the dashboard's line with an article in it (`clock.specs.md` §8a).
     *
     * "der" is quoted rather than assembled around the formatter so the whole German form is one
     * pattern and reads as the sentence it becomes.
     */
    private val SPOKEN_DATE_DE = DateTimeFormatter.ofPattern("EEEE, 'der' d. MMMM", Locale.GERMAN)

    /** The month and weekday names only; the day number is spelled with its ordinal below. */
    private val SPOKEN_DATE_EN = DateTimeFormatter.ofPattern("EEEE, MMMM", Locale.ENGLISH)

    fun speak(time: LocalTime, lang: Lang): String {
        if (lang != Lang.EN) return "Es ist ${german(time)}."
        // The English forms end in "a.m."/"p.m.", which is already the full stop — a second
        // one is a stutter in the audio, not a typo nobody hears.
        return "It's ${english(time)}".let { if (it.endsWith('.')) it else "$it." }
    }

    /**
     * "10 Minuten", "1 Minute", "10 minutes", "1 minute" — the duration in an acknowledgement.
     *
     * The spec writes this slot as `{amount} {unit}`; the singular is folded in here because
     * "Timer läuft: 1 Minuten" is not a sentence anyone would say, in either language.
     */
    fun duration(amount: Int, unit: TimerUnit, lang: Lang): String =
        "$amount ${unit.noun(amount, lang)}"

    /**
     * "noch 9 Minuten", "noch 1 Stunde und 5 Minuten" — the answer to "wie lange geht der Timer
     * noch" (§6), and "9 minutes", "1 hour and 5 minutes".
     *
     * Two parts at most, and the smaller one only when it is not zero. "Noch 1 Stunde, 5 Minuten
     * und 3 Sekunden" is a stopwatch reading; a person asking how long the noodles have wants
     * the number they can act on and one below it.
     */
    fun remaining(remainingMs: Long, lang: Lang): String {
        val total = max(0L, remainingMs + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND // round up
        val hours = (total / SECONDS_PER_HOUR).toInt()
        val minutes = (total / SECONDS_PER_MINUTE % MINUTES_PER_HOUR).toInt()
        val seconds = (total % SECONDS_PER_MINUTE).toInt()
        return when {
            hours > 0 -> join(duration(hours, TimerUnit.STUNDEN, lang), minutes, TimerUnit.MINUTEN, lang)
            minutes > 0 -> join(duration(minutes, TimerUnit.MINUTEN, lang), seconds, TimerUnit.SEKUNDEN, lang)
            else -> duration(seconds, TimerUnit.SEKUNDEN, lang)
        }
    }

    /** "Timer Nudeln", "Timer Nudeln und Timer 2", "A, B und C" — a spoken enumeration. */
    fun list(items: List<String>, lang: Lang): String = when (items.size) {
        0 -> ""
        1 -> items.single()
        else -> items.dropLast(1).joinToString(", ") +
            (if (lang == Lang.EN) " and " else " und ") + items.last()
    }

    private fun join(head: String, rest: Int, unit: TimerUnit, lang: Lang): String = when {
        rest == 0 -> head
        lang == Lang.EN -> "$head and ${duration(rest, unit, lang)}"
        else -> "$head und ${duration(rest, unit, lang)}"
    }

    /** "Donnerstag, 18. September" — the dashboard's date line (`clock.specs.md` §9). */
    fun date(date: LocalDate): String = DATE.format(date)

    /**
     * "Heute ist Samstag, der 14. Juli." — the answer to "welcher Tag ist heute" (§8a).
     *
     * No year, in either language: somebody asking across a kitchen wants the weekday and the
     * date, and "2026" at the end of every answer is the part nobody was asking about.
     *
     * The English day is an ordinal ("July 14th") rather than a bare number, because a TTS
     * engine handed "July 14" may read it as "July fourteen", which is not a date anybody says.
     */
    fun speakDate(date: LocalDate, lang: Lang): String = if (lang == Lang.EN) {
        "Today is ${SPOKEN_DATE_EN.format(date)} ${ordinal(date.dayOfMonth)}."
    } else {
        "Heute ist ${SPOKEN_DATE_DE.format(date)}."
    }

    /** 1 → "1st", 14 → "14th". Days only, so the teens are the whole of the special case. */
    private fun ordinal(day: Int): String {
        val suffix = when {
            day in TEENS -> "th"
            day % DECIMAL == 1 -> "st"
            day % DECIMAL == 2 -> "nd"
            day % DECIMAL == 3 -> "rd"
            else -> "th"
        }
        return "$day$suffix"
    }

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

    private fun german(time: LocalTime): String = when (time.minute) {
        0 -> "${time.hour} Uhr"
        QUARTER -> "viertel nach ${hour12(time.hour)}"
        // Forward-looking: 14:30 is "halb drei", half way *to* three.
        HALF -> "halb ${hour12(time.hour + 1)}"
        THREE_QUARTERS -> "viertel vor ${hour12(time.hour + 1)}"
        else -> "${time.hour} Uhr ${time.minute}"
    }

    /**
     * The English forms, which are backward-looking where the German ones are forward-looking.
     *
     * 14:30 is "half past two"; 14:45 is "quarter to three" and agrees with the German. The
     * am/pm tail is on every form because a 12-hour clock without one is ambiguous, and this
     * is an answer somebody acts on.
     */
    private fun english(time: LocalTime): String {
        val meridiem = if (time.hour < HOURS_PER_HALF_DAY) "a.m." else "p.m."
        return when (time.minute) {
            0 -> "${hour12(time.hour)} o'clock $meridiem"
            QUARTER -> "quarter past ${hour12(time.hour)} $meridiem"
            HALF -> "half past ${hour12(time.hour)} $meridiem"
            // The hour rolls over, and so may the meridiem — 11:45 a.m. is "quarter to twelve
            // p.m.", which is why this one is computed from the next hour and not from [time].
            THREE_QUARTERS -> {
                val next = time.plusMinutes(MINUTES_PER_HOUR - THREE_QUARTERS)
                val tail = if (next.hour < HOURS_PER_HALF_DAY) "a.m." else "p.m."
                "quarter to ${hour12(next.hour)} $tail"
            }

            else -> "${hour12(time.hour)} ${"%02d".format(time.minute)} $meridiem"
        }
    }

    /** 24-hour → spoken 12-hour, where 0 and 12 both read as "12". Wraps past midnight. */
    private fun hour12(hour: Int): Int {
        val wrapped = hour % HOURS_PER_DAY % HOURS_PER_HALF_DAY
        return if (wrapped == 0) HOURS_PER_HALF_DAY else wrapped
    }

    /** 11th, 12th, 13th — the days whose ordinal does not follow from their last digit. */
    private val TEENS = 11..13

    private const val DECIMAL = 10
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
