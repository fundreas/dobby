package io.dobby.socks.weather

/**
 * Where a forecast comes from.
 *
 * An interface for the reason every other Sock has one: [OpenMeteo] is the only class in this
 * module that knows HTTP exists, so the German copy, the hour arithmetic and the whole command
 * surface stay reachable with no network — and the day somebody wants a different provider, it
 * is one class and not a Sock.
 */
fun interface WeatherSource {

    /**
     * One round trip: now, plus the three days [WeatherDay] can name.
     *
     * @throws WeatherUnavailable for every condition the caller can act on, which is all of
     *   them. There is no partial forecast: a response that parsed but has no `current` block
     *   is a failure, not a half-answer, because the half that is missing is the one the
     *   commonest question asks about.
     */
    suspend fun fetch(place: Coordinates): WeatherReport

    companion object {
        /** Off-device, and in any build with no network permission. Always offline. */
        val NONE: WeatherSource = WeatherSource { throw WeatherUnavailable(WeatherError.OFFLINE) }
    }
}

/**
 * Why a forecast did not arrive.
 *
 * Three cases and not one, because they are three different German sentences and two of them
 * are the user's to fix (`weather.specs.md` §3).
 */
enum class WeatherError {
    /** No route to the host. The commonest by far, and the one a panel says plainly. */
    OFFLINE,

    /** Open-Meteo answered, and not with a forecast: a 5xx, or a body that did not parse. */
    FAILED,

    /** 429. Open-Meteo is free and fair-use; this is what ignoring that looks like. */
    RATE_LIMITED,
}

class WeatherUnavailable(val error: WeatherError, cause: Throwable? = null) :
    Exception("weather unavailable: $error", cause)
