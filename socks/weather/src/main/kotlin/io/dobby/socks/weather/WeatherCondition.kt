package io.dobby.socks.weather

import io.dobby.core.sock.Lang

/**
 * What a WMO weather code means, in the two languages and in the one word the panel draws.
 *
 * Open-Meteo reports the sky as a WMO 4677 code, which is an integer from 0 to 99 with gaps in
 * it. Everything a weather question needs to *decide* — is that rain, is that snow, is the sun
 * out — is a question about this number, so it is turned into a [WeatherCondition] once, at the
 * edge, and no handler ever compares an integer to 61 again.
 *
 * [kind] is the part the commands ask questions of and [de] is the part they say out loud, and
 * the split matters: "leichter Regen" and "Regenschauer" are different sentences and the same
 * answer to "regnet es morgen".
 */
data class WeatherCondition(
    val code: Int,
    val kind: Kind,
    /** German, nominative, capitalised — it starts sentences as often as it ends them. */
    val de: String,
    val en: String,
    /** One character for the card. Deliberately not an icon set: see `weather.specs.md` §8. */
    val symbol: String,
) {
    fun text(lang: Lang): String = if (lang == Lang.EN) en else de

    /**
     * What kind of sky this is, for the questions that are about a category rather than a word.
     *
     * Ordered from clear to violent, which is also the order a day is summarised in when two
     * halves of it disagree: [WeatherSpeech] takes the worst hour, and "worst" is this enum's
     * ordinal.
     */
    enum class Kind {
        CLEAR,
        PARTLY_CLOUDY,
        CLOUDY,
        OVERCAST,
        FOG,
        DRIZZLE,
        RAIN,
        FREEZING_RAIN,
        SHOWERS,
        SNOW,
        SNOW_SHOWERS,
        THUNDERSTORM,
        UNKNOWN,
        ;

        /** Water out of the sky in any form. What `weather.rain` is a question about. */
        val isPrecipitation: Boolean
            get() = this in setOf(DRIZZLE, RAIN, FREEZING_RAIN, SHOWERS, SNOW, SNOW_SHOWERS, THUNDERSTORM)

        /** Frozen water out of the sky. "Schneit es morgen" is a different question. */
        val isSnow: Boolean get() = this == SNOW || this == SNOW_SHOWERS

        /** Liquid water out of the sky. A thunderstorm counts; snow does not. */
        val isRain: Boolean
            get() = this in setOf(DRIZZLE, RAIN, FREEZING_RAIN, SHOWERS, THUNDERSTORM)

        /** The sun is actually out. `PARTLY_CLOUDY` counts — "teils sonnig" is sun. */
        val isSunny: Boolean get() = this == CLEAR || this == PARTLY_CLOUDY
    }

    companion object {
        /**
         * The WMO 4677 subset Open-Meteo emits, and nothing else.
         *
         * Written as a `when` over the codes rather than a map, because the table has ranges in
         * it and reads as what it is: three intensities of rain, three of snow, two of freezing
         * drizzle. An unknown code is [Kind.UNKNOWN] with an honest German word rather than a
         * guess — a forecast that invents a sky it was not told about is worse than one that
         * says it does not know.
         */
        @Suppress("CyclomaticComplexMethod", "MagicNumber")
        fun of(code: Int): WeatherCondition = when (code) {
            0 -> WeatherCondition(code, Kind.CLEAR, "klar", "clear", "☀")
            1 -> WeatherCondition(code, Kind.CLEAR, "überwiegend klar", "mostly clear", "🌤")
            2 -> WeatherCondition(code, Kind.PARTLY_CLOUDY, "teils bewölkt", "partly cloudy", "⛅")
            3 -> WeatherCondition(code, Kind.OVERCAST, "bedeckt", "overcast", "☁")
            45, 48 -> WeatherCondition(code, Kind.FOG, "neblig", "foggy", "🌫")
            51 -> WeatherCondition(code, Kind.DRIZZLE, "leichter Nieselregen", "light drizzle", "🌦")
            53 -> WeatherCondition(code, Kind.DRIZZLE, "Nieselregen", "drizzle", "🌦")
            55 -> WeatherCondition(code, Kind.DRIZZLE, "dichter Nieselregen", "dense drizzle", "🌦")
            56, 57 -> WeatherCondition(code, Kind.FREEZING_RAIN, "gefrierender Nieselregen", "freezing drizzle", "🌧")
            61 -> WeatherCondition(code, Kind.RAIN, "leichter Regen", "light rain", "🌧")
            63 -> WeatherCondition(code, Kind.RAIN, "Regen", "rain", "🌧")
            65 -> WeatherCondition(code, Kind.RAIN, "starker Regen", "heavy rain", "🌧")
            66, 67 -> WeatherCondition(code, Kind.FREEZING_RAIN, "gefrierender Regen", "freezing rain", "🌧")
            71 -> WeatherCondition(code, Kind.SNOW, "leichter Schneefall", "light snow", "🌨")
            73 -> WeatherCondition(code, Kind.SNOW, "Schneefall", "snow", "🌨")
            75 -> WeatherCondition(code, Kind.SNOW, "starker Schneefall", "heavy snow", "🌨")
            77 -> WeatherCondition(code, Kind.SNOW, "Schneegriesel", "snow grains", "🌨")
            80 -> WeatherCondition(code, Kind.SHOWERS, "leichte Regenschauer", "light showers", "🌦")
            81 -> WeatherCondition(code, Kind.SHOWERS, "Regenschauer", "showers", "🌦")
            82 -> WeatherCondition(code, Kind.SHOWERS, "heftige Regenschauer", "violent showers", "🌧")
            85 -> WeatherCondition(code, Kind.SNOW_SHOWERS, "leichte Schneeschauer", "light snow showers", "🌨")
            86 -> WeatherCondition(code, Kind.SNOW_SHOWERS, "Schneeschauer", "snow showers", "🌨")
            95 -> WeatherCondition(code, Kind.THUNDERSTORM, "Gewitter", "thunderstorm", "⛈")
            96, 99 -> WeatherCondition(code, Kind.THUNDERSTORM, "Gewitter mit Hagel", "thunderstorm with hail", "⛈")
            else -> WeatherCondition(code, Kind.UNKNOWN, "wechselhaft", "unsettled", "🌡")
        }
    }
}
