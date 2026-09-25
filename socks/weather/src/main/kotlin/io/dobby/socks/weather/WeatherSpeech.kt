package io.dobby.socks.weather

import io.dobby.core.sock.Lang
import io.dobby.core.sock.Phrase
import java.time.Duration
import java.time.LocalDateTime
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Every sentence this Sock says, and the one place a number becomes German.
 *
 * A [Phrase] and never a `String`, which is the i18n contract (`socks.specs/README.md` §2a) and
 * earns its keep here more than anywhere else: "Morgen wird es bis zu 18 Grad warm" and
 * "Tomorrow will reach 18 degrees" are not the same sentence with the words swapped — the verb
 * moves, the preposition disappears, and the number is the only thing in common.
 *
 * Three rules run through all of it:
 *
 * 1. **Round once.** [Double.degrees] is the only place a temperature loses its decimal, so the
 *    card and the voice can never be one degree apart.
 * 2. **A negative temperature is spoken, not printed.** "minus 3 Grad", because a TTS engine
 *    handed "-3" says "drei" and somebody puts the bike out.
 * 3. **Answer the question that was asked, then say why.** "Ja, ab 17 Uhr" before the
 *    percentage; the yes is what somebody standing in a doorway needs, and the evidence is
 *    what they need if they disagree with it.
 */
object WeatherSpeech {

    // ---------------------------------------------------------------- failures and scaffolding

    /**
     * Nobody has pressed Setup, so there is no place to have weather in.
     *
     * The one failure in this Sock that names a button, because it is the one the user can
     * actually fix, and "ich weiß nicht wo ich bin" without the next step is a dead end.
     */
    val NO_PLACE: Phrase = Phrase.of(
        "Ich weiß noch nicht, wo ich stehe. Tipp auf dem Panel einmal auf „Standort einrichten“.",
        "I don't know where I am yet. Tap \"Standort einrichten\" on the panel once.",
    )

    val OFFLINE: Phrase = Phrase.of(
        "Ich habe gerade keine Internetverbindung.",
        "I have no internet connection right now.",
    )

    val SERVICE_DOWN: Phrase = Phrase.of(
        "Der Wetterdienst antwortet gerade nicht.",
        "The weather service isn't answering right now.",
    )

    val RATE_LIMITED: Phrase = Phrase.of(
        "Der Wetterdienst lässt mich gerade nicht so oft fragen.",
        "The weather service is throttling me right now.",
    )

    /** Asked about a day past the end of the forecast — see [OpenMeteo.FORECAST_DAYS]. */
    val TOO_FAR_OUT: Phrase = Phrase.of(
        "So weit reicht meine Vorhersage nicht.",
        "My forecast doesn't reach that far.",
    )

    val NOT_READY: Phrase = Phrase.of(
        "Ich komme gerade nicht an das Wetter.",
        "I can't get at the weather right now.",
    )

    val LOCATION_SET: Phrase = Phrase.of(
        "Standort gespeichert. Ich hole das Wetter.",
        "Location saved. Fetching the forecast.",
    )

    val NO_FIX: Phrase = Phrase.of(
        "Ich konnte den Standort nicht bestimmen. Ist die Standortfreigabe für Dobby an?",
        "I couldn't work out where we are. Is location access switched on for Dobby?",
    )

    fun failure(error: WeatherError): Phrase = when (error) {
        WeatherError.OFFLINE -> OFFLINE
        WeatherError.RATE_LIMITED -> RATE_LIMITED
        WeatherError.FAILED -> SERVICE_DOWN
    }

    /**
     * Answers from a forecast that is older than it should be, and says so first.
     *
     * The alternative to the caveat is silence about it, and silence is what turns a stale
     * number into a wrong one. The alternative to answering at all is refusing a question the
     * panel can very nearly answer, which is worse — twenty-minute-old weather is still
     * weather.
     */
    fun stale(age: Duration, answer: Phrase): Phrase = Phrase { lang ->
        val minutes = age.toMinutes().coerceAtLeast(1)
        val head = if (lang == Lang.EN) {
            "From $minutes minutes ago:"
        } else {
            "Stand von vor $minutes Minuten:"
        }
        "$head ${answer(lang).replaceFirstChar { it.lowercase() }}"
    }

    // ------------------------------------------------------------------------- temperature

    /** „Es sind 14 Grad, gefühlt 12 Grad." — the commonest answer this Sock gives. */
    fun currentTemperature(current: CurrentConditions): Phrase = Phrase { lang ->
        val actual = degrees(current.temperature, lang)
        // Only when they differ by enough to be worth a clause. "Gefühlt 14" beside "14" is a
        // sentence that carries no information and takes a second to say.
        if (abs(current.temperature - current.apparentTemperature) < FELT_THRESHOLD) {
            if (lang == Lang.EN) "It's $actual." else "Es sind $actual."
        } else {
            val felt = degrees(current.apparentTemperature, lang)
            if (lang == Lang.EN) "It's $actual, feels like $felt." else "Es sind $actual, gefühlt $felt."
        }
    }

    /**
     * „Heute wird es noch bis zu 21 Grad, am wärmsten gegen 15 Uhr."
     *
     * The *remaining* hours, which is the whole reason the hourly block is fetched: at two in
     * the afternoon the day's maximum is usually already behind you, and answering "wie warm
     * wird es heute noch" with it is a panel promising warmth that has been and gone.
     */
    fun restOfTodayWarmest(peak: HourSlot, currently: Double): Phrase = Phrase { lang ->
        val value = degrees(peak.temperature, lang)
        when {
            // The peak that is left is not warmer than what is already outside, so the honest
            // answer is the thermometer — quoting the forecast's 17 while the window says 18
            // is a panel arguing with the room.
            peak.temperature <= currently + FELT_THRESHOLD ->
                if (lang == Lang.EN) {
                    "It won't get any warmer today — it's ${degrees(currently, lang)}."
                } else {
                    "Wärmer wird es heute nicht mehr, es sind ${degrees(currently, lang)}."
                }

            lang == Lang.EN -> "Up to $value later today, warmest around ${clockTime(peak.time, lang)}."
            else -> "Heute wird es noch bis zu $value, am wärmsten gegen ${clockTime(peak.time, lang)}."
        }
    }

    /** „Heute fällt es noch auf 8 Grad, am kältesten gegen 23 Uhr." */
    fun restOfTodayColdest(trough: HourSlot, currently: Double): Phrase = Phrase { lang ->
        val value = degrees(trough.temperature, lang)
        when {
            trough.temperature >= currently - FELT_THRESHOLD ->
                if (lang == Lang.EN) {
                    "It won't get any colder today — it's ${degrees(currently, lang)}."
                } else {
                    "Kälter wird es heute nicht mehr, es sind ${degrees(currently, lang)}."
                }

            lang == Lang.EN -> "Down to $value later today, coldest around ${clockTime(trough.time, lang)}."
            else -> "Heute fällt es noch auf $value, am kältesten gegen ${clockTime(trough.time, lang)}."
        }
    }

    /** „Morgen wird es bis zu 18 Grad." */
    fun dayWarmest(day: WeatherDay, forecast: DayForecast): Phrase = Phrase { lang ->
        val value = degrees(forecast.maxTemperature, lang)
        if (lang == Lang.EN) {
            "${day.word(lang).replaceFirstChar { it.uppercase() }} will reach $value."
        } else {
            "${day.word(lang).replaceFirstChar { it.uppercase() }} wird es bis zu $value."
        }
    }

    /** „Morgen sinkt es auf 9 Grad." */
    fun dayColdest(day: WeatherDay, forecast: DayForecast): Phrase = Phrase { lang ->
        val value = degrees(forecast.minTemperature, lang)
        if (lang == Lang.EN) {
            "${day.word(lang).replaceFirstChar { it.uppercase() }} drops to $value."
        } else {
            "${day.word(lang).replaceFirstChar { it.uppercase() }} sinkt es auf $value."
        }
    }

    /** „Morgen zwischen 9 und 18 Grad." — both ends, for a question that named neither. */
    fun dayRange(day: WeatherDay, forecast: DayForecast): Phrase = Phrase { lang ->
        "${day.word(lang).replaceFirstChar { it.uppercase() }} ${range(forecast, lang)}."
    }

    // ---------------------------------------------------------------------------- forecast

    /**
     * „Gerade bedeckt bei 14 Grad, heute noch bis zu 21. Regenwahrscheinlichkeit 20 Prozent."
     *
     * Today's general answer starts with *now*, because somebody asking "wie ist das Wetter"
     * out loud at three in the afternoon is looking out of a window they cannot see, and the
     * sky they are asking about is the one that is there.
     */
    fun todayOverview(current: CurrentConditions, forecast: DayForecast, peak: HourSlot?): Phrase =
        Phrase { lang ->
            val sky = current.condition.text(lang)
            val now = degrees(current.temperature, lang)
            val head = if (lang == Lang.EN) "Currently $sky at $now." else "Gerade $sky bei $now."
            val later = peak?.takeIf { it.temperature > current.temperature + FELT_THRESHOLD }
                ?.let {
                    val value = degrees(it.temperature, lang)
                    if (lang == Lang.EN) " Up to $value later." else " Heute noch bis zu $value."
                }
                .orEmpty()
            "$head$later${rainClause(forecast, lang)}"
        }

    /** „Morgen Regenschauer, 9 bis 18 Grad. Regenwahrscheinlichkeit 80 Prozent." */
    fun dayOverview(day: WeatherDay, forecast: DayForecast): Phrase = Phrase { lang ->
        val name = day.word(lang).replaceFirstChar { it.uppercase() }
        val sky = forecast.condition.text(lang)
        val head = "$name $sky, ${range(forecast, lang)}."
        "$head${rainClause(forecast, lang)}"
    }

    // -------------------------------------------------------------------------- rain & snow

    /** „Ja, heute ab 17 Uhr, Wahrscheinlichkeit 70 Prozent." */
    fun rainToday(from: HourSlot, probability: Int?): Phrase = Phrase { lang ->
        val at = clockTime(from.time, lang)
        val chance = probability?.let {
            if (lang == Lang.EN) ", $it percent" else ", Wahrscheinlichkeit $it Prozent"
        }.orEmpty()
        if (lang == Lang.EN) "Yes, from $at$chance." else "Ja, heute ab $at$chance."
    }

    /** „Eher nicht, die Wahrscheinlichkeit liegt bei 30 Prozent." */
    fun rainUnlikely(probability: Int): Phrase = Phrase { lang ->
        if (lang == Lang.EN) {
            "Probably not — the chance is $probability percent."
        } else {
            "Eher nicht, die Wahrscheinlichkeit liegt bei $probability Prozent."
        }
    }

    /** „Nein, heute bleibt es trocken." */
    fun stayingDry(day: WeatherDay): Phrase = Phrase { lang ->
        val name = day.word(lang)
        if (lang == Lang.EN) "No, it stays dry $name." else "Nein, $name bleibt es trocken."
    }

    /** „Ja, morgen ist Regen gemeldet, Wahrscheinlichkeit 80 Prozent, etwa 5 Millimeter." */
    fun rainOnDay(day: WeatherDay, forecast: DayForecast): Phrase = Phrase { lang ->
        val name = day.word(lang)
        val sky = forecast.condition.text(lang)
        val chance = forecast.precipitationProbability?.let {
            if (lang == Lang.EN) ", $it percent" else ", Wahrscheinlichkeit $it Prozent"
        }.orEmpty()
        val amount = millimetres(forecast.precipitationSum, lang)
        if (lang == Lang.EN) "Yes, $name: $sky$chance$amount." else "Ja, $name: $sky$chance$amount."
    }

    /** „Ja, morgen ist Schneefall gemeldet." — the snow question, which is not the rain one. */
    fun snowOnDay(day: WeatherDay, forecast: DayForecast): Phrase = Phrase { lang ->
        val name = day.word(lang)
        val sky = forecast.condition.text(lang)
        if (lang == Lang.EN) "Yes, $name: $sky." else "Ja, $name: $sky."
    }

    /** „Nein, Schnee ist morgen nicht zu erwarten." */
    fun noSnow(day: WeatherDay): Phrase = Phrase { lang ->
        val name = day.word(lang)
        if (lang == Lang.EN) "No snow expected $name." else "Nein, Schnee ist $name nicht zu erwarten."
    }

    // -------------------------------------------------------------------------------- sun

    /** „Ja, gerade ist es klar." */
    fun sunNow(current: CurrentConditions): Phrase = Phrase { lang ->
        val sky = current.condition.text(lang)
        if (lang == Lang.EN) "Yes, it's $sky right now." else "Ja, gerade ist es $sky."
    }

    /** „Gerade nicht, aber ab 15 Uhr wird es teils bewölkt." */
    fun sunLater(from: HourSlot): Phrase = Phrase { lang ->
        val at = clockTime(from.time, lang)
        val sky = from.condition.text(lang)
        if (lang == Lang.EN) {
            "Not right now, but from $at it turns $sky."
        } else {
            "Gerade nicht, aber ab $at wird es $sky."
        }
    }

    /**
     * „Die Sonne ist um 18:52 Uhr untergegangen." / „Die Sonne geht erst um 6:45 Uhr auf."
     *
     * The answer "scheint die Sonne" needs when it is dark, and the hourly weather code cannot
     * give it: WMO 0 is "klar" at two in the morning as much as at noon. Without this the Sock
     * answered a question asked at half past six in September with "ab 21 Uhr wird es teils
     * bewölkt", which is a forecast of moonlight.
     */
    fun sunIsDown(sunrise: LocalDateTime?, sunset: LocalDateTime?, now: LocalDateTime): Phrase =
        Phrase { lang ->
            val beforeDawn = sunrise != null && now.isBefore(sunrise)
            when {
                beforeDawn && lang == Lang.EN -> "The sun doesn't come up until ${exactTime(sunrise, lang)}."
                beforeDawn -> "Die Sonne geht erst um ${exactTime(sunrise, lang)} auf."
                sunset != null && lang == Lang.EN -> "The sun went down at ${exactTime(sunset, lang)}."
                sunset != null -> "Die Sonne ist um ${exactTime(sunset, lang)} untergegangen."
                lang == Lang.EN -> "It's dark out."
                else -> "Es ist schon dunkel."
            }
        }

    /** „Nein, heute bleibt es bedeckt." */
    fun noSunToday(current: CurrentConditions): Phrase = Phrase { lang ->
        val sky = current.condition.text(lang)
        if (lang == Lang.EN) "No, it stays $sky today." else "Nein, heute bleibt es $sky."
    }

    /** „Ja, morgen wird es überwiegend klar." / „Nein, morgen wird es bedeckt." */
    fun sunOnDay(day: WeatherDay, forecast: DayForecast): Phrase = Phrase { lang ->
        val name = day.word(lang)
        val sky = forecast.condition.text(lang)
        val yes = forecast.condition.kind.isSunny
        if (lang == Lang.EN) {
            "${if (yes) "Yes" else "No"}, $name will be $sky."
        } else {
            "${if (yes) "Ja" else "Nein"}, $name wird es $sky."
        }
    }

    // ------------------------------------------------------------------------------- wind

    /** „Schwacher Wind, 12 Kilometer pro Stunde." */
    fun windNow(current: CurrentConditions): Phrase = Phrase { lang ->
        val force = WindForce.of(current.windSpeed)
        val speed = speed(current.windSpeed, lang)
        val word = force.text(lang).replaceFirstChar { it.uppercase() }
        if (force == WindForce.CALM) {
            if (lang == Lang.EN) "It's calm, $speed." else "Es ist windstill, $speed."
        } else {
            "$word, $speed."
        }
    }

    /** „Morgen frischer Wind, bis zu 34 Kilometer pro Stunde." */
    fun windOnDay(day: WeatherDay, kilometresPerHour: Double): Phrase = Phrase { lang ->
        val name = day.word(lang).replaceFirstChar { it.uppercase() }
        val force = WindForce.of(kilometresPerHour)
        val speed = speed(kilometresPerHour, lang)
        if (lang == Lang.EN) {
            "$name: ${force.text(lang)}, up to $speed."
        } else {
            "$name ${force.text(lang)}, bis zu $speed."
        }
    }

    /** The source gave no wind for that day — rare, and better said than guessed at. */
    val NO_WIND_FORECAST: Phrase = Phrase.of(
        "Für den Tag habe ich keine Windvorhersage.",
        "I have no wind forecast for that day.",
    )

    // ------------------------------------------------------------------------- the panel

    /**
     * "vor 4 Minuten" — the card's freshness line, rendered rather than deferred.
     *
     * The panel's own UI is German whatever voice is selected (`socks.specs/README.md` §2a),
     * and this is drawn, never spoken.
     */
    fun panelAge(age: Duration): String {
        val minutes = age.toMinutes()
        return when {
            minutes < 1 -> "gerade eben"
            minutes == 1L -> "vor 1 Minute"
            minutes < MINUTES_PER_HOUR -> "vor $minutes Minuten"
            minutes < 2 * MINUTES_PER_HOUR -> "vor 1 Stunde"
            else -> "vor ${minutes / MINUTES_PER_HOUR} Stunden"
        }
    }

    /** "14°" — the card's own rounding, which is [Double.degrees] and therefore the voice's. */
    fun panelDegrees(value: Double): String = "${value.degrees()}°"

    // ------------------------------------------------------------------------------ bits

    /**
     * "14 Grad", and "minus 3 Grad" below zero.
     *
     * The minus is a **word**, not a sign: Piper reads "-3" as "drei", and a panel that says it
     * is three degrees on a night it is minus three is the one weather error with a cost
     * attached to it.
     */
    fun degrees(value: Double, lang: Lang): String {
        val rounded = value.degrees()
        val number = if (rounded < 0) "minus ${-rounded}" else "$rounded"
        return if (lang == Lang.EN) "$number degrees" else "$number Grad"
    }

    /**
     * "zwischen 10 und 21 Grad" — the unit once, on the end where it belongs.
     *
     * "Zwischen 10 Grad und 21 Grad" is what the first draft said, and it is the kind of
     * sentence that is correct, obviously generated, and three syllables too long every time
     * somebody asks.
     */
    private fun range(forecast: DayForecast, lang: Lang): String {
        val low = forecast.minTemperature.degrees()
        val lowWord = if (low < 0) "minus ${-low}" else "$low"
        val high = degrees(forecast.maxTemperature, lang)
        return if (lang == Lang.EN) "between $lowWord and $high" else "zwischen $lowWord und $high"
    }

    private fun speed(kilometresPerHour: Double, lang: Lang): String {
        val rounded = kilometresPerHour.roundToInt()
        return if (lang == Lang.EN) "$rounded kilometres per hour" else "$rounded Kilometer pro Stunde"
    }

    /** "17 Uhr". Minutes are dropped: an hourly forecast has no business being precise. */
    private fun clockTime(at: LocalDateTime, lang: Lang): String =
        if (lang == Lang.EN) "${at.hour}:00" else "${at.hour} Uhr"

    /**
     * "6:45 Uhr" — the same thing with its minutes, for sunrise and sunset.
     *
     * The rounding in [clockTime] is right for a forecast column, which is an hour wide and
     * knows nothing finer. It is wrong for a sunset, which is a fact to the minute and the only
     * number in the sentence: "die Sonne geht erst um 6 Uhr auf" is three quarters of an hour of
     * darkness the panel invented.
     */
    private fun exactTime(at: LocalDateTime, lang: Lang): String {
        val minute = at.minute.toString().padStart(2, '0')
        return if (lang == Lang.EN) "${at.hour}:$minute" else "${at.hour}:$minute Uhr"
    }

    /** ", etwa 5 Millimeter" — left out entirely below a millimetre, which is drizzle nobody feels. */
    private fun millimetres(sum: Double, lang: Lang): String {
        if (sum < MIN_NOTABLE_MM) return ""
        val rounded = sum.roundToInt()
        return if (lang == Lang.EN) ", about $rounded millimetres" else ", etwa $rounded Millimeter"
    }

    /**
     * " Regenwahrscheinlichkeit 80 Prozent." — appended to an overview, and only when it matters.
     *
     * A twenty-percent chance of rain is not news; it is the background hum of an Austrian
     * afternoon. Below [RAIN_WORTH_MENTIONING] the clause is left off rather than said, for the
     * reason the Memo Sock leaves out a zero: a sentence only a computer would produce.
     */
    private fun rainClause(forecast: DayForecast, lang: Lang): String {
        val probability = forecast.precipitationProbability ?: return ""
        if (probability < RAIN_WORTH_MENTIONING) return ""
        return if (lang == Lang.EN) {
            " Chance of rain $probability percent."
        } else {
            " Regenwahrscheinlichkeit $probability Prozent."
        }
    }

    /**
     * How likely rain has to be before "ja" is the honest answer to "regnet es morgen".
     *
     * Fifty, and the asymmetry below it is deliberate: between [RAIN_WORTH_MENTIONING] and this
     * the answer is "eher nicht" with the number attached, because a flat "nein" at forty-five
     * percent is a panel that will be wrong twice a week and sound certain both times.
     */
    const val RAIN_LIKELY: Int = 50

    /** Below this a probability is not worth a clause in an overview. */
    const val RAIN_WORTH_MENTIONING: Int = 30

    /** Degrees of difference below which "gefühlt" is the same number said twice. */
    private const val FELT_THRESHOLD = 1.0

    private const val MIN_NOTABLE_MM = 1.0

    private const val MINUTES_PER_HOUR = 60L
}
