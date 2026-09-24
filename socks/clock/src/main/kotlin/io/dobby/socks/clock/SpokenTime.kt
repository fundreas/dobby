package io.dobby.socks.clock

import io.dobby.core.sock.Lang
import java.time.LocalDate
import java.time.LocalTime
import java.time.Period
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
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

    /** A date with no weekday and no article — the calendar commands decline it themselves. */
    private val DAY_MONTH_DE = DateTimeFormatter.ofPattern("d. MMMM", Locale.GERMAN)

    private val MONTH_EN = DateTimeFormatter.ofPattern("MMMM", Locale.ENGLISH)

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

    /**
     * "26. September" / "September 26th" — a date the way it sits *inside* a sentence.
     *
     * No article and no weekday, because the four calendar commands (§8b–§8e) each need it in a
     * different case: "der 26. September ist ein Samstag", "bis zum 24. Dezember", "Samstag ist
     * heute, der 24. September". German declension is the caller's business; the date is not.
     */
    fun dayAndMonth(date: LocalDate, lang: Lang): String = if (lang == Lang.EN) {
        "${MONTH_EN.format(date)} ${ordinal(date.dayOfMonth)}"
    } else {
        DAY_MONTH_DE.format(date)
    }

    /** "Samstag" / "Saturday" — the weekday on its own. */
    fun weekday(date: LocalDate, lang: Lang): String =
        date.dayOfWeek.getDisplayName(TextStyle.FULL, if (lang == Lang.EN) Locale.ENGLISH else Locale.GERMAN)

    /**
     * "Der 26. September ist ein Samstag." — the answer to "welcher Tag ist der 26.?" (§8b).
     *
     * Today and tomorrow are said as today and tomorrow, with the date in apposition. Somebody
     * who asks which day the 26th is on the 25th is told "morgen" first and the weekday second,
     * because that is the part of the answer they can act on.
     */
    fun speakWeekdayOf(date: LocalDate, today: LocalDate, lang: Lang): String {
        val day = dayAndMonth(date, lang)
        val name = weekday(date, lang)
        val english = lang == Lang.EN
        return when (CalendarDates.daysBetween(today, date)) {
            0L -> if (english) "Today, $day, is a $name." else "Heute, der $day, ist ein $name."
            1L -> if (english) "Tomorrow, $day, is a $name." else "Morgen, der $day, ist ein $name."
            else -> if (english) "$day is a $name." else "Der $day ist ein $name."
        }
    }

    /**
     * "Der nächste Samstag ist der 26. September." — the answer to "wann ist Samstag?" (§8c).
     *
     * The mirror of [speakWeekdayOf], down to the today/tomorrow forms: the weekday is what was
     * asked, so it leads the sentence whatever the date turns out to be.
     */
    fun speakDateOfWeekday(date: LocalDate, today: LocalDate, lang: Lang): String {
        val day = dayAndMonth(date, lang)
        val name = weekday(date, lang)
        val english = lang == Lang.EN
        return when (CalendarDates.daysBetween(today, date)) {
            0L -> if (english) "$name is today, $day." else "$name ist heute, der $day."
            1L -> if (english) "$name is tomorrow, $day." else "$name ist morgen, der $day."
            else -> if (english) "The next $name is $day." else "Der nächste $name ist der $day."
        }
    }

    /**
     * "In 3 Wochen ist Donnerstag, der 15. Oktober." — the answer to a date counted forward (§8d).
     *
     * One and two days out are spoken as "morgen" and "übermorgen" rather than as "in 1 Tag":
     * those are the words, and "welcher Tag ist morgen" reaches this command through a template
     * that fixes the amount, so the answer has to sound like the question.
     */
    fun speakDateIn(date: LocalDate, today: LocalDate, amount: Int, unit: SpanUnit, lang: Lang): String {
        val english = lang == Lang.EN
        val rest = "${weekday(date, lang)}, ${if (english) "" else "der "}${dayAndMonth(date, lang)}"
        val lead = when (CalendarDates.daysBetween(today, date)) {
            1L -> if (english) "Tomorrow is" else "Morgen ist"
            2L -> if (english) "The day after tomorrow is" else "Übermorgen ist"
            // Dative behind "in", which is the one place a German plural needs its -n.
            else -> if (english) {
                "In ${count(amount, unit, lang)} it's"
            } else {
                "In $amount ${unit.nounDative(amount, lang)} ist"
            }
        }
        return "$lead $rest."
    }

    /**
     * "13 Wochen und 2 Tage", "91 Tage", "1 Jahr und 3 Monate" — the distance to a date (§8e).
     *
     * Two parts at most and the smaller one only when it is not zero, which is the rule
     * [remaining] already follows for a running timer and for the same reason: a person asking
     * how long until Christmas wants the number they can act on and one below it, not a
     * stopwatch reading. A whole part of zero is dropped entirely — "0 Monate und 5 Tage" is
     * not an answer anybody gives.
     */
    fun span(from: LocalDate, to: LocalDate, unit: SpanUnit, lang: Lang): String {
        val days = ChronoUnit.DAYS.between(from, to)
        return when (unit) {
            SpanUnit.TAGE -> count(days.toInt(), SpanUnit.TAGE, lang)
            SpanUnit.WOCHEN -> twoPart(
                (days / DAYS_PER_WEEK).toInt(), SpanUnit.WOCHEN,
                (days % DAYS_PER_WEEK).toInt(), SpanUnit.TAGE, lang,
            )

            SpanUnit.MONATE -> Period.between(from, to).let {
                twoPart(it.years * MONTHS_PER_YEAR + it.months, SpanUnit.MONATE, it.days, SpanUnit.TAGE, lang)
            }

            SpanUnit.JAHRE -> Period.between(from, to).let {
                twoPart(it.years, SpanUnit.JAHRE, it.months, SpanUnit.MONATE, lang)
            }
        }
    }

    /** "3 Wochen", "1 Tag", "3 weeks" — a count and its unit, [duration] for the calendar. */
    fun count(amount: Int, unit: SpanUnit, lang: Lang): String = "$amount ${unit.noun(amount, lang)}"

    private fun twoPart(whole: Int, unit: SpanUnit, rest: Int, restUnit: SpanUnit, lang: Lang): String = when {
        whole == 0 -> count(rest, restUnit, lang)
        rest == 0 -> count(whole, unit, lang)
        lang == Lang.EN -> "${count(whole, unit, lang)} and ${count(rest, restUnit, lang)}"
        else -> "${count(whole, unit, lang)} und ${count(rest, restUnit, lang)}"
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
    private const val DAYS_PER_WEEK = 7L
    private const val MONTHS_PER_YEAR = 12
}
