package io.dobby.socks.weather

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * `GET /v1/forecast` on OkHttp. The only class in this module that knows the internet exists.
 *
 * **No key, no account, no token dance** — which is what made Open-Meteo the choice over every
 * other provider, because the alternative is another `BuildConfig` secret shipped inside the
 * APK (`spotify.specs.md` §1's accepted risk, which is worth taking once and not twice).
 *
 * Three things about the query are decisions rather than transcription:
 *
 * 1. **`timezone=auto`, not a fixed zone.** The panel asks the phone where it is, so pinning
 *    `Europe/Vienna` into the URL would be pinning the answer to a location the user is allowed
 *    to change. With `auto` the response carries the zone the hours were computed in, and
 *    "heute" means the day where the panel is standing (`weather.specs.md` §3).
 * 2. **`forecast_days=3`, not 2.** The commands are specified for *übermorgen*, and two
 *    forecast days is today plus tomorrow. Asking for two and then answering a question about
 *    the day after it is the kind of off-by-one that only shows up as a wrong forecast.
 * 3. **Three blocks in one request.** `current` answers "wie warm ist es", `daily` answers
 *    "wie wird das Wetter morgen", and `hourly` is the only thing that can answer "wie kalt
 *    wird es heute *noch*" — a daily minimum at two in the afternoon is last night's.
 *
 * The timeouts are short on purpose. A command may fall through to a live fetch when the cache
 * is stale, and the dispatcher gives a handler five seconds total
 * ([io.dobby.core.dispatch.Dispatcher.DEFAULT_TIMEOUT]); a panel that spends all five of them
 * holding the microphone open and then says nothing is worse than one that says it is offline.
 */
class OpenMeteo(
    private val client: OkHttpClient = defaultClient(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: Clock = Clock.systemUTC(),
    private val endpoint: HttpUrl = ENDPOINT.toHttpUrl(),
) : WeatherSource {

    override suspend fun fetch(place: Coordinates): WeatherReport {
        val url = endpoint.newBuilder()
            .addPathSegments("v1/forecast")
            .addQueryParameter("latitude", decimal(place.latitude))
            .addQueryParameter("longitude", decimal(place.longitude))
            .addQueryParameter("current", CURRENT_FIELDS)
            .addQueryParameter("daily", DAILY_FIELDS)
            .addQueryParameter("hourly", HOURLY_FIELDS)
            .addQueryParameter("timezone", "auto")
            .addQueryParameter("forecast_days", "$FORECAST_DAYS")
            .build()
        val body = call(Request.Builder().url(url).build())
        val parsed = try {
            JSON.decodeFromString(ForecastJson.serializer(), body)
        } catch (e: IllegalArgumentException) {
            throw WeatherUnavailable(WeatherError.FAILED, e)
        }
        return parsed.toReport(place)
    }

    /** One round trip, off the calling thread, with every HTTP condition mapped to a reason. */
    private suspend fun call(request: Request): String = withContext(io) {
        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw WeatherUnavailable(WeatherError.OFFLINE, e)
        }
        response.use {
            if (it.isSuccessful) return@withContext it.body.string()
            // Open-Meteo does not always say 429. Over its free-tier limits it answers **400**
            // with `{"error":true,"reason":"Minutely API request weight exceeded…"}`, and a
            // panel that reports that as "der Wetterdienst antwortet nicht" sends somebody
            // looking at their router. The body is small and already in hand; reading it is
            // the difference between a true sentence and a plausible one.
            val reason = runCatching { it.body.string() }.getOrDefault("")
            throw when {
                it.code == TOO_MANY_REQUESTS -> WeatherUnavailable(WeatherError.RATE_LIMITED)
                RATE_LIMIT_WORDS.any { word -> reason.contains(word, ignoreCase = true) } ->
                    WeatherUnavailable(WeatherError.RATE_LIMITED)

                else -> WeatherUnavailable(WeatherError.FAILED)
            }
        }
    }

    /**
     * The response, turned into the model the rest of the module speaks.
     *
     * Everything that can be absent is absent here rather than in a handler: Open-Meteo reports
     * `precipitation_probability` as null for hours outside its probabilistic range, and a
     * forecast with fewer days than asked for is a real response from a healthy server near the
     * edge of its grid. A missing `current` block is the one thing that is fatal — see
     * [WeatherSource.fetch].
     */
    private fun ForecastJson.toReport(place: Coordinates): WeatherReport {
        val now = current ?: throw WeatherUnavailable(WeatherError.FAILED)
        val zone = try {
            ZoneId.of(timezone ?: ZoneId.systemDefault().id)
        } catch (e: java.time.DateTimeException) {
            throw WeatherUnavailable(WeatherError.FAILED, e)
        }
        val hours = hourly?.slots().orEmpty()
        val days = daily?.days(hours).orEmpty()
        if (days.isEmpty()) throw WeatherUnavailable(WeatherError.FAILED)
        return WeatherReport(
            place = place,
            zone = zone,
            current = CurrentConditions(
                temperature = now.temperature ?: throw WeatherUnavailable(WeatherError.FAILED),
                // A source that has a temperature and no apparent one is not a failure; the
                // felt temperature is the second half of a sentence, not the sentence.
                apparentTemperature = now.apparentTemperature ?: now.temperature,
                code = now.code ?: UNKNOWN_CODE,
                windSpeed = now.windSpeed ?: 0.0,
            ),
            days = days,
            fetchedAt = clock.instant(),
        )
    }

    private fun HourlyJson.slots(): List<HourSlot> = time.mapIndexedNotNull { index, stamp ->
        val at = parseTime(stamp) ?: return@mapIndexedNotNull null
        val temperature = temperature.getOrNull(index) ?: return@mapIndexedNotNull null
        HourSlot(
            time = at,
            temperature = temperature,
            precipitationProbability = precipitationProbability.getOrNull(index),
            code = code.getOrNull(index) ?: UNKNOWN_CODE,
        )
    }

    private fun DailyJson.days(hours: List<HourSlot>): List<DayForecast> =
        time.mapIndexedNotNull { index, stamp ->
            val date = parseDate(stamp) ?: return@mapIndexedNotNull null
            DayForecast(
                date = date,
                code = code.getOrNull(index) ?: UNKNOWN_CODE,
                maxTemperature = max.getOrNull(index) ?: return@mapIndexedNotNull null,
                minTemperature = min.getOrNull(index) ?: return@mapIndexedNotNull null,
                precipitationProbability = precipitationProbability.getOrNull(index),
                precipitationSum = sum.getOrNull(index) ?: 0.0,
                maxWindSpeed = wind.getOrNull(index),
                sunrise = sunrise.getOrNull(index)?.let(::parseTime),
                sunset = sunset.getOrNull(index)?.let(::parseTime),
                hours = hours.filter { it.time.toLocalDate() == date },
            )
        }

    private fun parseTime(raw: String?): LocalDateTime? = if (raw == null) null else try {
        LocalDateTime.parse(raw)
    } catch (e: DateTimeParseException) {
        null
    }

    private fun parseDate(raw: String): LocalDate? = try {
        LocalDate.parse(raw)
    } catch (e: DateTimeParseException) {
        null
    }

    /** Always a point, never a comma, whatever locale the panel is running under. */
    private fun decimal(value: Double): String = String.format(Locale.ROOT, "%.4f", value)

    // The wire format, and nothing else. Nullable everywhere a healthy server may still leave
    // a hole, because `ignoreUnknownKeys` protects against new fields and not against absent
    // values — a `null` in `precipitation_probability` is documented behaviour, not a fault.
    @Serializable
    private data class ForecastJson(
        val timezone: String? = null,
        val current: CurrentJson? = null,
        val hourly: HourlyJson? = null,
        val daily: DailyJson? = null,
    )

    @Serializable
    private data class CurrentJson(
        @SerialName("temperature_2m") val temperature: Double? = null,
        @SerialName("apparent_temperature") val apparentTemperature: Double? = null,
        @SerialName("weather_code") val code: Int? = null,
        @SerialName("wind_speed_10m") val windSpeed: Double? = null,
    )

    @Serializable
    private data class HourlyJson(
        val time: List<String> = emptyList(),
        @SerialName("temperature_2m") val temperature: List<Double?> = emptyList(),
        @SerialName("precipitation_probability") val precipitationProbability: List<Int?> = emptyList(),
        @SerialName("weather_code") val code: List<Int?> = emptyList(),
    )

    @Serializable
    private data class DailyJson(
        val time: List<String> = emptyList(),
        @SerialName("weather_code") val code: List<Int?> = emptyList(),
        @SerialName("temperature_2m_max") val max: List<Double?> = emptyList(),
        @SerialName("temperature_2m_min") val min: List<Double?> = emptyList(),
        @SerialName("precipitation_probability_max") val precipitationProbability: List<Int?> = emptyList(),
        @SerialName("precipitation_sum") val sum: List<Double?> = emptyList(),
        @SerialName("wind_speed_10m_max") val wind: List<Double?> = emptyList(),
        val sunrise: List<String?> = emptyList(),
        val sunset: List<String?> = emptyList(),
    )

    companion object {
        const val ENDPOINT: String = "https://api.open-meteo.com/"

        /**
         * Today, tomorrow, and the day after — the three [WeatherDay] can name, and no more.
         *
         * The spec this Sock was written from asked for two, which is today plus tomorrow, and
         * then asked for "wie ist das Wetter übermorgen" in the same breath. Three is what the
         * commands need; the response is a few kilobytes either way.
         */
        const val FORECAST_DAYS: Int = 3

        private const val CURRENT_FIELDS =
            "temperature_2m,apparent_temperature,weather_code,wind_speed_10m"
        /**
         * The spec's five daily fields, plus three the questions need and it did not list.
         *
         * `wind_speed_10m_max`, because "wie stark wird der Wind morgen" is a sentence the
         * command surface accepts and the other five cannot answer — the alternative was a
         * Sock that understands a question it then has to refuse.
         *
         * `sunrise` and `sunset`, because "scheint die Sonne" is otherwise wrong twice a day:
         * WMO code 0 is "klar" at two in the morning as much as at noon, and the first live
         * run of this Sock answered a question asked at half past six with "ab 21 Uhr wird es
         * teils bewölkt".
         */
        private const val DAILY_FIELDS =
            "weather_code,temperature_2m_max,temperature_2m_min," +
                "precipitation_probability_max,precipitation_sum,wind_speed_10m_max," +
                "sunrise,sunset"
        private const val HOURLY_FIELDS = "temperature_2m,precipitation_probability,weather_code"

        private const val TOO_MANY_REQUESTS = 429

        /** What Open-Meteo's `reason` says when the limit rather than the request is the problem. */
        private val RATE_LIMIT_WORDS = listOf("limit exceeded", "weight exceeded", "rate limit")

        /** A code nothing maps: [WeatherCondition.Kind.UNKNOWN], and an honest German word. */
        private const val UNKNOWN_CODE = -1

        private const val CONNECT_TIMEOUT_S = 3L
        private const val READ_TIMEOUT_S = 3L

        private val JSON = Json { ignoreUnknownKeys = true }

        /**
         * Short timeouts, for the reason in the class KDoc: a stale cache plus a live fetch has
         * to fit inside one dispatch, and the dispatcher's budget is five seconds for the whole
         * handler.
         */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
            .build()
    }
}
