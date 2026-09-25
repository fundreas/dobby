package io.dobby.socks.weather

import io.dobby.core.sock.SockConfigStore
import java.time.Duration

/**
 * The Sock's settings and its one piece of saved state (`weather.specs.md` §9).
 *
 * Read at the moment they are used rather than captured at `onStart`, the way `RadioConfig`
 * and `MemoConfig` are: a refresh interval somebody has just changed should apply to the next
 * tick, not to the next boot.
 *
 * The saved **location** lives in here too, and it is not a preference. It is the answer to a
 * question the panel asked the phone once, and it is the reason the Setup button exists at all:
 * without it there is no forecast to fetch and the card has nothing to draw. It is written by
 * [WeatherWatch.locate] and by nothing else.
 */
class WeatherConfig(private val store: SockConfigStore) {

    /**
     * Where the panel is, or null until somebody has pressed Setup.
     *
     * Both halves or neither: a stored latitude with no longitude is a half-written setup, and
     * asking Open-Meteo about the Greenwich meridian would be a forecast for a place nobody is
     * standing in. Unparseable is the same as absent, and the card says so by showing Setup
     * again — which is the one recovery the user can perform without being told anything.
     */
    var place: Coordinates?
        get() {
            val latitude = store.getString(LATITUDE)?.toDoubleOrNull() ?: return null
            val longitude = store.getString(LONGITUDE)?.toDoubleOrNull() ?: return null
            if (latitude !in LATITUDE_RANGE || longitude !in LONGITUDE_RANGE) return null
            return Coordinates(latitude, longitude)
        }
        set(value) {
            store.put(LATITUDE, value?.latitude?.toString().orEmpty())
            store.put(LONGITUDE, value?.longitude?.toString().orEmpty())
        }

    /**
     * How often the forecast is re-fetched while the panel is running.
     *
     * Ten minutes is the spec's number and also Open-Meteo's own update cadence for the
     * `current` block — polling faster would burn a free service's fair use to redraw the same
     * temperature. Zero and negatives are a broken setting, not a preference, and fall back to
     * the default rather than turning the panel into a scraper.
     */
    val refreshInterval: Duration
        get() = store.getInt(REFRESH_MINUTES)
            ?.takeIf { it > 0 }
            ?.let { Duration.ofMinutes(it.toLong()) }
            ?: DEFAULT_REFRESH

    /**
     * How old a cached report may be before a spoken question waits for a fresh one.
     *
     * Twice the refresh interval, so one missed tick is invisible and two are not. The
     * alternative — always fetching on a command — would put a network round trip inside every
     * "wie warm ist es", which is the question that has to be answered instantly or not at all.
     */
    val staleAfter: Duration get() = refreshInterval.multipliedBy(STALE_MULTIPLIER)

    companion object {
        const val LATITUDE: String = "weather.latitude"
        const val LONGITUDE: String = "weather.longitude"
        const val REFRESH_MINUTES: String = "weather.refresh_minutes"

        val DEFAULT_REFRESH: Duration = Duration.ofMinutes(10)

        private const val STALE_MULTIPLIER = 2L

        private val LATITUDE_RANGE = -90.0..90.0
        private val LONGITUDE_RANGE = -180.0..180.0
    }
}
