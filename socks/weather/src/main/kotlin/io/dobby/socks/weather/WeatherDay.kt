package io.dobby.socks.weather

import io.dobby.core.sock.Lang

/**
 * The three days a weather question can be about, as the words somebody says.
 *
 * The same rule the Clock's [io.dobby.socks.clock.Weekday] follows and for the same reason: the
 * enum values **are** the `{day:enum}` candidate set, so they have to be the tokens the
 * normalizer produces. Anything further out than the day after tomorrow is deliberately absent
 * — Open-Meteo would happily forecast a fortnight, and a wall panel in a kitchen is asked about
 * this afternoon, tomorrow, and the weekend's barbecue at the outside (`weather.specs.md` §12).
 *
 * `heute` is the default everywhere rather than a value somebody has to say: "wie warm ist es"
 * is a question about now, and making the day optional is what keeps the commonest phrasing of
 * all the shortest one.
 */
enum class WeatherDay(val spoken: String, val offset: Int, private val de: String, private val en: String) {
    HEUTE("heute", 0, "heute", "today"),
    MORGEN("morgen", 1, "morgen", "tomorrow"),
    UEBERMORGEN("übermorgen", 2, "übermorgen", "the day after tomorrow"),

    /**
     * The same day, spelled the way the recogniser writes it when it drops the umlaut.
     *
     * Four edits from `übermorgen`, so the enum slot's fuzzy tier would never find it — the
     * Clock's `jaenner` and `maerz` are the same case, and a second value costs one line.
     */
    UEBERMORGEN_ASCII("uebermorgen", 2, "übermorgen", "the day after tomorrow"),
    ;

    /** The word as it reads inside a sentence. Both spellings of the third day say "übermorgen". */
    fun word(lang: Lang): String = if (lang == Lang.EN) en else de

    /** True for the one day where "jetzt" and "noch" mean anything. */
    val isToday: Boolean get() = offset == 0

    companion object {
        /** The `ParamType.Enumeration` values, in the order the matcher tries them. */
        val SPOKEN: List<String> = entries.map { it.spoken }

        /** The param name every weather command spells its day slot with. */
        const val PARAM: String = "day"

        /** An absent or unrecognised day is today — see the class KDoc. */
        fun of(value: String?): WeatherDay =
            entries.firstOrNull { it.spoken.equals(value, ignoreCase = true) } ?: HEUTE
    }
}
