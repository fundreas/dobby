package io.dobby.socks.clock

import io.dobby.core.sock.Lang
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Month
import java.time.Year
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * The words a spoken date is made of, and the arithmetic behind them (`clock.specs.md` §8b–§8e).
 *
 * The same rule as [TimerUnit]: **the enum values are the words the user says**, because they
 * are also the `{x:enum}` candidate sets. A slot somebody speaks has to be directly capturable
 * by a Tier 1 template, so the commands take `weekday` + `month` + `day` rather than an ISO
 * date nobody says out loud.
 *
 * Singular and declined forms are folded in by the enum matcher, not by extra values: the
 * candidate set is closed, so "Montags" reaches `montag` and "Woche" reaches `wochen` at
 * Levenshtein tolerance 1 without risking a false positive
 * ([`CompiledTemplate`][io.dobby.core.nlu.template.CompiledTemplate]). What *is* listed is the
 * handful of second names for the same thing — `sonnabend` and `maerz` — which are not one edit
 * from their siblings and would never be reached otherwise.
 */
enum class Weekday(val spoken: String, val day: DayOfWeek) {
    MONTAG("montag", DayOfWeek.MONDAY),
    DIENSTAG("dienstag", DayOfWeek.TUESDAY),
    MITTWOCH("mittwoch", DayOfWeek.WEDNESDAY),
    DONNERSTAG("donnerstag", DayOfWeek.THURSDAY),
    FREITAG("freitag", DayOfWeek.FRIDAY),
    SAMSTAG("samstag", DayOfWeek.SATURDAY),

    /**
     * The north-German name for Saturday, which the recogniser will produce if somebody says it.
     *
     * Not one edit from `samstag`, so the fuzzy pass would never find it: 7 characters against
     * 9, and four of them differ. A second value costs one line and is the only way in.
     */
    SONNABEND("sonnabend", DayOfWeek.SATURDAY),
    SONNTAG("sonntag", DayOfWeek.SUNDAY),
    ;

    companion object {
        /** The `ParamType.Enumeration` values. */
        val SPOKEN: List<String> = entries.map { it.spoken }

        fun of(value: String): Weekday? = entries.firstOrNull { it.spoken.equals(value, ignoreCase = true) }
    }
}

/**
 * A month, as it is spoken in a date.
 *
 * `juni` and `juli` are one edit apart, which is exactly the pair the fuzzy tier would get
 * wrong — and does not, because the enum matcher tries an **exact** match over the whole
 * candidate set before it fuzzes anything. A correctly transcribed "juni" is therefore never
 * read as "juli"; a garbled one is a garble, and there is no repair for that in either
 * direction.
 */
enum class MonthName(val spoken: String, val month: Month) {
    JANUAR("januar", Month.JANUARY),

    /**
     * Austrian, and not optional on a panel in an Austrian kitchen: "Jänner" is what is said
     * there, and it is three edits from "januar" — the fuzzy tier would never find it.
     * "Feber" is the same case for February, rarer but from the same dialect.
     */
    JAENNER("jänner", Month.JANUARY),
    JAENNER_ASCII("jaenner", Month.JANUARY),
    FEBRUAR("februar", Month.FEBRUARY),
    FEBER("feber", Month.FEBRUARY),
    MAERZ("märz", Month.MARCH),

    /** The recogniser transcribes umlauts, but not always; `GermanNumbers` carries `fuenf` for the same reason. */
    MAERZ_ASCII("maerz", Month.MARCH),
    APRIL("april", Month.APRIL),
    MAI("mai", Month.MAY),
    JUNI("juni", Month.JUNE),
    JULI("juli", Month.JULY),
    AUGUST("august", Month.AUGUST),
    SEPTEMBER("september", Month.SEPTEMBER),
    OKTOBER("oktober", Month.OCTOBER),
    NOVEMBER("november", Month.NOVEMBER),
    DEZEMBER("dezember", Month.DECEMBER),
    ;

    companion object {
        val SPOKEN: List<String> = entries.map { it.spoken }

        fun of(value: String): MonthName? = entries.firstOrNull { it.spoken.equals(value, ignoreCase = true) }
    }
}

/**
 * The unit a spoken *span* of days comes in — the calendar's counterpart to [TimerUnit].
 *
 * Deliberately not the same enum: a timer runs in seconds, minutes and hours and never in
 * months, and a calendar question is never about seconds. Sharing one list would mean
 * "erinner mich in 3 Monaten" matching `set_timer` and then failing to coerce, which is the
 * silent kind of wrong.
 */
enum class SpanUnit(
    val spoken: String,
    private val singular: String,
    private val plural: String,
    private val dativePlural: String,
    private val singularEn: String,
    private val pluralEn: String,
) {
    TAGE("tage", "Tag", "Tage", "Tagen", "day", "days"),
    WOCHEN("wochen", "Woche", "Wochen", "Wochen", "week", "weeks"),
    MONATE("monate", "Monat", "Monate", "Monaten", "month", "months"),
    JAHRE("jahre", "Jahr", "Jahre", "Jahren", "year", "years"),
    ;

    /** The unit as it reads next to [amount], in [lang] — the shape [TimerUnit.noun] already has. */
    fun noun(amount: Int, lang: Lang): String = when {
        lang == Lang.EN -> if (amount == 1) singularEn else pluralEn
        amount == 1 -> singular
        else -> plural
    }

    /**
     * The same, behind "in": "in 3 **Tagen**", not "in 3 Tage".
     *
     * German is the whole of the reason this exists — "in" takes the dative and the plural
     * takes an -n, which `Wochen` already has and `Tage`, `Monate` and `Jahre` do not.
     * `TimerUnit` never needed it because a timer is announced as "Timer läuft: 10 Minuten",
     * with no preposition in front of the number. The singular is unchanged, so "in 1 Tag" —
     * which a TTS engine reads "in einem Tag" — stays right.
     */
    fun nounDative(amount: Int, lang: Lang): String = when {
        lang == Lang.EN -> if (amount == 1) singularEn else pluralEn
        amount == 1 -> singular
        else -> dativePlural
    }

    /** The unit one step down, for the remainder in a two-part answer. Null for the smallest. */
    val smaller: SpanUnit?
        get() = when (this) {
            TAGE -> null
            WOCHEN -> TAGE
            MONATE -> TAGE
            JAHRE -> MONATE
        }

    companion object {
        val SPOKEN: List<String> = entries.map { it.spoken }

        fun of(value: String): SpanUnit? = entries.firstOrNull { it.spoken.equals(value, ignoreCase = true) }
    }
}

/**
 * Turning what somebody said into the one date they meant (`clock.specs.md` §8b).
 *
 * **Everything here resolves forwards, today included.** "Der 14." is the next 14th there is,
 * "Samstag" is the next Saturday, and if that is today then the answer is *today*. No year is
 * ever spoken and none is ever asked for, so the only question a partial date leaves open is
 * which direction to look in — and the asymmetry decides it: somebody standing in a kitchen
 * asking which day the 14th is has the coming one in mind, and the panel telling them "today"
 * when it is today is never the surprising answer. The backward forms ("welcher Tag **war** der
 * 1. September") are out of scope for the same reason, not forgotten — see §14.
 */
object CalendarDates {

    /**
     * The next date whose day-of-month is [day], today included.
     *
     * Months without a 31st are skipped rather than clamped: "der 31." asked on 1 April means
     * 31 May, and a panel that answered "30. April" would have answered a different question.
     * Null for a day no month has.
     */
    fun nextWithDay(today: LocalDate, day: Int): LocalDate? {
        if (day !in MIN_DAY..MAX_DAY) return null
        var month = today.withDayOfMonth(1)
        repeat(MONTHS_TO_SCAN) {
            if (day <= month.lengthOfMonth()) {
                val candidate = month.withDayOfMonth(day)
                if (!candidate.isBefore(today)) return candidate
            }
            month = month.plusMonths(1)
        }
        return null
    }

    /**
     * The next date that falls on [day] of [month], today included.
     *
     * Years are scanned rather than computed because of the 29th of February: the next one may
     * be up to eight years out (2096 → 2104), and a formula that assumes four would be wrong
     * roughly once a century, which is exactly the kind of bug nobody finds.
     */
    fun nextWithMonthDay(today: LocalDate, month: Month, day: Int): LocalDate? {
        if (day !in MIN_DAY..MAX_DAY) return null
        var year = today.year
        repeat(YEARS_TO_SCAN) {
            if (day <= month.length(Year.isLeap(year.toLong()))) {
                val candidate = LocalDate.of(year, month, day)
                if (!candidate.isBefore(today)) return candidate
            }
            year++
        }
        return null
    }

    /** The next [weekday], today included — "Samstag" asked on a Saturday is today. */
    fun nextWeekday(today: LocalDate, weekday: DayOfWeek): LocalDate =
        today.with(TemporalAdjusters.nextOrSame(weekday))

    /**
     * [today] plus [amount] × [unit], or null if that is past the horizon.
     *
     * The cap is a guard against a misheard sentence rather than a limit of the arithmetic, the
     * way `set_timer`'s 12 hours is: "in 3000 Jahren" is a transcript, not a question.
     */
    fun plus(today: LocalDate, amount: Int, unit: SpanUnit): LocalDate? {
        if (amount !in MIN_SPAN..MAX_SPAN) return null
        val count = amount.toLong()
        return when (unit) {
            SpanUnit.TAGE -> today.plusDays(count)
            SpanUnit.WOCHEN -> today.plusWeeks(count)
            SpanUnit.MONATE -> today.plusMonths(count)
            SpanUnit.JAHRE -> today.plusYears(count)
        }
    }

    /** Whole days from [from] to [to]. Negative never happens — everything here looks forwards. */
    fun daysBetween(from: LocalDate, to: LocalDate): Long = ChronoUnit.DAYS.between(from, to)

    private const val MIN_DAY = 1
    private const val MAX_DAY = 31

    /** A day-of-month recurs within a year, or it is not a day-of-month. */
    private const val MONTHS_TO_SCAN = 12

    /** 29 February 2096 → 2104: eight years is the widest real gap, and this is one more. */
    private const val YEARS_TO_SCAN = 9

    private const val MIN_SPAN = 1
    private const val MAX_SPAN = 500
}
