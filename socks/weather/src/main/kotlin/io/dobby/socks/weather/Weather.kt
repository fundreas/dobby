package io.dobby.socks.weather

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * Where the panel is standing, to two useful decimals.
 *
 * Deliberately not a street address and deliberately not the phone's best fix: a forecast grid
 * cell is kilometres across, so a coarse location is not a degradation of this feature, it is
 * the whole of what it needs. That is also why the Android half asks for `ACCESS_COARSE_LOCATION`
 * alone (`weather.specs.md` §1) — a wall panel that demanded GPS-grade permission to say whether
 * it will rain would be asking for something it cannot use.
 */
data class Coordinates(val latitude: Double, val longitude: Double) {

    /** "48,21° N · 16,37° O" — what the panel draws under the temperature. */
    val display: String
        get() = "${format(latitude)}° ${if (latitude >= 0) "N" else "S"} · " +
            "${format(longitude)}° ${if (longitude >= 0) "O" else "W"}"

    private fun format(value: Double): String =
        String.format(java.util.Locale.GERMAN, "%.2f", kotlin.math.abs(value))
}

/** One hour of the forecast, as Open-Meteo's `hourly` block hands it over. */
data class HourSlot(
    val time: LocalDateTime,
    val temperature: Double,
    /** Percent. Open-Meteo may report none for an hour, which is a null and not a zero. */
    val precipitationProbability: Int?,
    val code: Int,
) {
    val condition: WeatherCondition get() = WeatherCondition.of(code)
}

/**
 * One whole day, as `daily` hands it over, with the hours belonging to it alongside.
 *
 * The hours are what makes "wie kalt wird es heute noch" answerable: a daily minimum is the
 * night that has already happened by lunchtime, and a panel that answers a question about the
 * rest of the afternoon with last night's number is confidently wrong (`weather.specs.md` §4).
 */
data class DayForecast(
    val date: LocalDate,
    val code: Int,
    val maxTemperature: Double,
    val minTemperature: Double,
    /** Percent, the day's maximum. Null when the source reports none. */
    val precipitationProbability: Int?,
    /** Millimetres over the whole day. */
    val precipitationSum: Double,
    /** km/h, the day's strongest. Null when the source reports none for that day. */
    val maxWindSpeed: Double?,
    /**
     * When the sun comes up and goes down, local to [WeatherReport.zone].
     *
     * Fetched for one question and one question only: "scheint die Sonne". Without it the
     * hourly codes answer it wrongly twice a day — WMO code 0 is "klar" at two in the morning
     * as much as at noon, and a panel that says "ja, gerade ist es klar" into a dark kitchen,
     * or promises sun from nine in the evening, is a panel nobody asks twice
     * (`weather.specs.md` §5).
     */
    val sunrise: LocalDateTime?,
    val sunset: LocalDateTime?,
    val hours: List<HourSlot>,
) {
    val condition: WeatherCondition get() = WeatherCondition.of(code)

    /** The hours still to come on [date], relative to [now]. Empty once the day is over. */
    fun hoursFrom(now: LocalDateTime): List<HourSlot> = hours.filter { !it.time.isBefore(now) }

    /**
     * Whether the sun is above the horizon at [at].
     *
     * True when the source gave no times at all: a missing field must not turn a sunny
     * afternoon into "die Sonne ist schon untergegangen", and the failure of the two answers
     * is not symmetric — being told there is no sun when there is costs somebody a look out of
     * the window, the other way round costs them the washing.
     */
    fun isDaylight(at: LocalDateTime): Boolean {
        val up = sunrise ?: return true
        val down = sunset ?: return true
        return !at.isBefore(up) && at.isBefore(down)
    }
}

/** What the sky is doing right now, from Open-Meteo's `current` block. */
data class CurrentConditions(
    val temperature: Double,
    val apparentTemperature: Double,
    val code: Int,
    /** km/h at 10 m, which is the unit Open-Meteo defaults to and the one a forecast is read in. */
    val windSpeed: Double,
) {
    val condition: WeatherCondition get() = WeatherCondition.of(code)
}

/**
 * One fetch, whole: now, and the three days the commands can ask about.
 *
 * [zone] comes from the response rather than from the device, because `timezone=auto` is what
 * makes "heute" mean the day where the panel is standing. On this panel the two agree; a phone
 * that has been carried across a border and not yet noticed is the case where they do not, and
 * the forecast's own zone is the one the hours were computed in.
 */
data class WeatherReport(
    val place: Coordinates,
    val zone: ZoneId,
    val current: CurrentConditions,
    /** Today first. Three entries when the source is healthy — see [WeatherDay]. */
    val days: List<DayForecast>,
    val fetchedAt: Instant,
) {
    fun day(day: WeatherDay): DayForecast? = days.getOrNull(day.offset)

    /** The local wall-clock time this report is being read at. */
    fun nowAt(instant: Instant): LocalDateTime = LocalDateTime.ofInstant(instant, zone)
}

/**
 * What the wall panel draws (`weather.specs.md` §8), and the only view anybody outside gets.
 *
 * Four states in one object rather than a sealed hierarchy, because three of them can be true
 * at once: a panel that has a location, is holding yesterday evening's report, is refreshing,
 * and failed the last attempt is a real and ordinary state, and a card that could draw only one
 * of those facts would have to pick the least useful.
 */
data class WeatherState(
    /** Null until somebody has pressed Setup. That is the card's whole first screen. */
    val place: Coordinates? = null,
    /** The last successful fetch, which survives a failed one. */
    val report: WeatherReport? = null,
    /** The phone is being asked where it is — Setup, or the card's location chip. */
    val locating: Boolean = false,
    val refreshing: Boolean = false,
    /** German, drawn rather than spoken (`socks.specs/README.md` §2a). Null when all is well. */
    val error: String? = null,
) {
    val isSetUp: Boolean get() = place != null
}

/** "18,3" → "18". Rounding happens once, here, so the panel and the voice never disagree. */
fun Double.degrees(): Int = this.roundToInt()
