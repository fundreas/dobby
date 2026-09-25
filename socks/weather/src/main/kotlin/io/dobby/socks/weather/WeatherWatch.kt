package io.dobby.socks.weather

import io.dobby.core.sock.SockContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Duration

/** What a command found when it went looking for a forecast. */
internal sealed interface Lookup {
    data class Ready(val report: WeatherReport) : Lookup

    /** Nobody has pressed Setup. The one failure the user can fix from the panel. */
    data object NoPlace : Lookup

    /** A location is saved and no forecast could be had. [stale] is the last one, if any. */
    data class Unavailable(val error: WeatherError, val stale: WeatherReport?) : Lookup
}

/** What asking the phone where it is produced. */
sealed interface LocateOutcome {
    /**
     * Saved, and a forecast for it is **on its way** rather than in hand.
     *
     * The fetch is deliberately not waited for. A spoken "aktualisiere den Standort" runs
     * inside the dispatcher's five-second budget, and a location fix plus an HTTP round trip
     * does not reliably fit in it — so the fix is the answer and the forecast arrives on the
     * card a moment later, which is where somebody is looking anyway.
     */
    data class Located(val place: Coordinates) : LocateOutcome

    /** Permission refused, location off, or no fix inside the platform's own timeout. */
    data object NoFix : LocateOutcome
}

/**
 * The saved location, the cached forecast, and the ten-minute tick that keeps it honest.
 *
 * Two pieces of state with deliberately different lifetimes, which is the same split `MemoBook`
 * makes:
 *
 * - **The location is durable.** It is written to the config store the one time somebody
 *   presses Setup and read back on every start. A wall panel does not travel, so asking the
 *   phone again on every boot would be a permission prompt and a radio wake-up for a number
 *   that has not changed since it was screwed to the wall.
 * - **The forecast is not.** It is a ten-minute-old fact about the sky, and a ten-minute-old
 *   fact is worth caching and worth nothing after a restart — the first tick fetches it again
 *   before anybody has finished reading the clock. Persisting it would mean a panel that comes
 *   up showing yesterday evening's temperature as though it were now, which is the one way a
 *   weather card can actively mislead.
 *
 * Everything that mutates goes through [mutex], the way `MemoBook` and `TimerEngine` do:
 * concurrency by type rather than by need, because a refresh tick and a spoken question really
 * can arrive together and a half-written report is a very quiet bug.
 *
 * @param clock injected so staleness is testable without waiting ten minutes for it.
 */
internal class WeatherWatch(
    private val source: WeatherSource,
    private val location: DeviceLocation,
    private val clock: Clock,
) {

    private val mutex = Mutex()

    private val _state = MutableStateFlow(WeatherState())

    /** What the panel draws (§8), and the only view anybody outside this class gets. */
    val state: StateFlow<WeatherState> = _state.asStateFlow()

    /**
     * Why the last fetch failed, kept beside the German the card draws.
     *
     * [WeatherState.error] is panel copy and deliberately a rendered string; this is the same
     * fact in the shape a handler can branch on. Reconstructing the one from the other would
     * be parsing our own user interface.
     */
    @Volatile
    private var lastError: WeatherError = WeatherError.FAILED

    /** Picks up the location somebody set before the last restart. */
    fun restore(ctx: SockContext) {
        val place = WeatherConfig(ctx.config).place
        _state.update { it.copy(place = place) }
        ctx.log.debug(if (place == null) "weather: no location set" else "weather: location restored")
    }

    /**
     * Asks the phone where it is, saves the answer, and fetches a forecast for it.
     *
     * Setup and the card's location chip are the same call, and that is deliberate: "set this
     * up" and "the panel has moved" are the same operation, and a second code path for the
     * second one would be a second thing that can go subtly out of step with the first.
     *
     * **A refused fix leaves the saved location alone.** Somebody who taps the chip in a flat
     * with location services switched off should end up with the forecast they already had, not
     * with an unconfigured panel — losing a working setup is a worse outcome than not updating
     * it (`weather.specs.md` §6).
     */
    suspend fun locate(ctx: SockContext): LocateOutcome {
        _state.update { it.copy(locating = true) }
        val place = try {
            location.current()
        } finally {
            _state.update { it.copy(locating = false) }
        }
        if (place == null) {
            ctx.log.warn("weather: no location fix")
            return LocateOutcome.NoFix
        }
        mutex.withLock {
            WeatherConfig(ctx.config).place = place
            // The old forecast goes with the old location: a card that keeps drawing Vienna's
            // afternoon under a new set of coordinates is lying with a straight face.
            _state.value = _state.value.copy(place = place, report = null, error = null)
        }
        ctx.log.debug("weather: location set to ${place.display}")
        // Not awaited — see [LocateOutcome.Located]. The card shows the spinner and then the
        // forecast; the sentence does not wait for the network.
        ctx.scope.launch { refresh(ctx) }
        return LocateOutcome.Located(place)
    }

    /**
     * One fetch, if there is anywhere to fetch for.
     *
     * Returns the new report, or null when there was none to be had — and in that case the
     * previous one is **kept**, with the error alongside it. A panel that blanks its card the
     * first time a kitchen's Wi-Fi hiccups is a panel that looks broken twice a day; twenty
     * minutes of staleness is a caveat, not a lie, and the card carries the caveat (§8).
     */
    suspend fun refresh(ctx: SockContext): WeatherReport? {
        val place = _state.value.place ?: return null
        _state.update { it.copy(refreshing = true) }
        return try {
            val report = source.fetch(place)
            mutex.withLock {
                lastError = WeatherError.FAILED
                _state.value = _state.value.copy(report = report, refreshing = false, error = null)
            }
            ctx.log.debug("weather: forecast for ${place.display}, ${report.days.size} day(s)")
            report
        } catch (e: WeatherUnavailable) {
            mutex.withLock {
                lastError = e.error
                _state.value = _state.value.copy(refreshing = false, error = panelReason(e.error))
            }
            ctx.log.warn("weather: fetch failed (${e.error})", e)
            null
        }
    }

    /**
     * The forecast a command is about to speak from.
     *
     * Fresh enough is answered from the cache, which is what makes "wie warm ist es" instant —
     * the whole point of the background tick. Stale or absent is fetched here and now, inside
     * the turn, because the alternative is answering a direct question with a number from an
     * hour ago and saying nothing about it.
     *
     * Failing that, a stale report is still returned as [Lookup.Unavailable] rather than
     * thrown away, and the Sock decides whether to use it with a caveat. That decision is not
     * this class's to make: how old is too old is a sentence, and sentences live in
     * [WeatherSpeech].
     */
    suspend fun lookup(ctx: SockContext): Lookup {
        if (_state.value.place == null) return Lookup.NoPlace
        val held = _state.value.report
        if (held != null && !isStale(ctx, held)) return Lookup.Ready(held)
        val fresh = refresh(ctx)
        if (fresh != null) return Lookup.Ready(fresh)
        return Lookup.Unavailable(lastError, held)
    }

    /** How old the cached report is right now — what the card's caveat line is built from. */
    fun age(report: WeatherReport): Duration = Duration.between(report.fetchedAt, clock.instant())

    private fun isStale(ctx: SockContext, report: WeatherReport): Boolean =
        age(report) > WeatherConfig(ctx.config).staleAfter

    /**
     * The panel's German for a failed fetch (`socks.specs/README.md` §2a).
     *
     * Rendered rather than deferred because this one is *drawn*, never spoken: the spoken
     * failures are [WeatherSpeech]'s and carry both languages. A status line on a German panel
     * is German whatever voice is selected.
     */
    private fun panelReason(error: WeatherError): String = when (error) {
        WeatherError.OFFLINE -> "Keine Verbindung"
        WeatherError.RATE_LIMITED -> "Wetterdienst überlastet"
        WeatherError.FAILED -> "Wetterdienst antwortet nicht"
    }
}
