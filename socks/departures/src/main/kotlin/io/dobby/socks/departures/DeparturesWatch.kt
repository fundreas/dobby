package io.dobby.socks.departures

import io.dobby.core.sock.SockContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** What a command found when it went looking for a departure board. */
internal sealed interface Lookup {
    data class Ready(val board: DepartureBoard) : Lookup

    /** Nobody has typed in a station. The one failure the user can fix, and it names settings. */
    data object NoStations : Lookup

    /** Stations are configured and no board could be had. [stale] is the last one, if any. */
    data class Unavailable(val error: DeparturesError, val stale: DepartureBoard?) : Lookup
}

/**
 * The cached board, the poll that keeps it honest, and **every one of the fair-use rules that
 * is about time** (`departures.specs.md` §6).
 *
 * This class is the reason the Sock is allowed to exist at all. The endpoint is free,
 * unauthenticated and easy to hammer; there is no quota that fails gracefully and no key to
 * revoke, so the consequence of a poll loop written without care is an IP block that takes the
 * feature down for good. Three properties, all of them enforced here and not at the call sites:
 *
 * - **A floor of 30 seconds between requests** ([mayFetch]), applied to the ticker, to a spoken
 *   question and to the card's refresh tap alike. There is no bypass and no `force` parameter —
 *   a parameter is a thing somebody passes `true` to at four in the morning.
 * - **Exponential backoff on 5xx and 429** ([backoff]), doubling from the poll interval up to
 *   fifteen minutes and cleared by the first good response. A service that is down does not
 *   want to hear from us every thirty seconds, and that is the failure mode that gets noticed.
 * - **One request per poll**, which is a property of [DepartureSource] taking the whole station
 *   list rather than of anything written here.
 *
 * The fourth rule — poll only while the screen is on — is the ticker's, because it is a
 * question about the panel and not about the cache; it lives in `DeparturesSock.onStart`.
 *
 * Everything that mutates goes through [mutex], the way `WeatherWatch` and `MemoBook` do: a
 * refresh tick and a spoken question really can arrive together, and a half-written board is a
 * very quiet bug.
 *
 * @param clock injected so the poll window and the staleness caveat are reasonable to reason
 *   about, and so a station's countdown is not the only thing in the module that knows the time.
 */
internal class DeparturesWatch(
    private val source: DepartureSource,
    private val clock: Clock,
) {

    private val mutex = Mutex()

    private val _state = MutableStateFlow(DeparturesState())

    /** What the panel draws (§5), and the only view anybody outside this class gets. */
    val state: StateFlow<DeparturesState> = _state.asStateFlow()

    /** When the last request went out — **attempted**, not succeeded. See [mayFetch]. */
    @Volatile
    private var lastAttempt: Instant? = null

    /** How long the current backoff is. Zero until something 5xx-shaped happens. */
    @Volatile
    private var backoff: Duration = Duration.ZERO

    @Volatile
    private var lastError: DeparturesError = DeparturesError.FAILED

    /** Picks up the stations somebody typed in before the last restart. */
    fun restore(ctx: SockContext) {
        val stations = DeparturesConfig(ctx.config).stations
        _state.update { it.copy(stations = stations) }
        ctx.log.debug(
            if (stations.isEmpty()) {
                "departures: no station configured"
            } else {
                "departures: ${stations.size} station(s) restored"
            },
        )
    }

    /**
     * The station list changed under us — the settings screen wrote a new one.
     *
     * The held board goes with it, and that is not tidiness: it was fetched for a different set
     * of stations, and a card that keeps drawing the old stop under the new name is lying with
     * a straight face. Nothing is fetched here. The floor in [mayFetch] has no exception for a
     * settings change (§7), and with the screen on the next tick is at most one interval away.
     */
    suspend fun stationsChanged(ctx: SockContext) {
        val stations = DeparturesConfig(ctx.config).stations
        mutex.withLock {
            _state.value = _state.value.copy(stations = stations, board = null, error = null)
        }
        ctx.log.debug("departures: station list changed, now ${stations.size}")
    }

    /**
     * Whether a request may go out right now.
     *
     * The floor is the larger of the configured interval and the hard 30 s, plus whatever
     * backoff a failure has earned. Note that it is measured from the **attempt** and not from
     * the last success: a run of failures that reset the window on every error would be a
     * retry loop with extra steps, which is exactly the shape of traffic that gets an IP
     * blocked.
     */
    private fun mayFetch(ctx: SockContext): Boolean {
        val last = lastAttempt ?: return true
        val window = DeparturesConfig(ctx.config).pollInterval.plus(backoff)
        return !Duration.between(last, clock.instant()).minus(window).isNegative
    }

    /**
     * One fetch, if there is anywhere to fetch for and the window is open.
     *
     * Returns the new board, or null when there was none to be had — and in that case the
     * previous one is **kept**, with the error alongside it (§5). A panel that blanks its board
     * the first time a kitchen's Wi-Fi hiccups is a panel that looks broken twice a day.
     */
    suspend fun refresh(ctx: SockContext): DepartureBoard? {
        val stations = _state.value.stations
        if (stations.isEmpty() || !mayFetch(ctx)) return null
        lastAttempt = clock.instant()
        _state.update { it.copy(refreshing = true) }
        return try {
            val board = source.fetch(stations)
            mutex.withLock {
                backoff = Duration.ZERO
                lastError = DeparturesError.FAILED
                _state.value = _state.value.copy(board = board, refreshing = false, error = null)
            }
            ctx.log.debug("departures: ${board.boards.size} direction(s) for ${stations.size} station(s)")
            board
        } catch (e: DeparturesUnavailable) {
            mutex.withLock {
                lastError = e.error
                if (e.error.backsOff) backoff = nextBackoff(ctx)
                _state.value = _state.value.copy(refreshing = false, error = panelReason(e.error))
            }
            ctx.log.warn("departures: fetch failed (${e.error}), backoff ${backoff.seconds}s", e)
            null
        }
    }

    /**
     * Doubling, from one poll interval up to fifteen minutes (fair-use rule 6).
     *
     * Starting at the interval rather than at one second, because the first retry is already
     * the second request inside a minute and the point of backing off is to stop being part of
     * whatever is wrong at the other end.
     */
    private fun nextBackoff(ctx: SockContext): Duration {
        val interval = DeparturesConfig(ctx.config).pollInterval
        val doubled = if (backoff.isZero) interval else backoff.multipliedBy(2)
        return if (doubled > DeparturesConfig.MAX_BACKOFF) DeparturesConfig.MAX_BACKOFF else doubled
    }

    /**
     * The board a command is about to speak from.
     *
     * Fresh enough is answered from the cache, which is fair-use rule 2 seen from the other
     * side: a voice request inside the poll window costs no request at all. Outside it, the
     * fetch happens here and now inside the turn, because a countdown from three minutes ago
     * is not a countdown.
     *
     * Failing that, a stale board is still handed back as [Lookup.Unavailable] rather than
     * thrown away, and the Sock decides whether to use it with a caveat. That decision is not
     * this class's to make: how old is too old is a sentence, and sentences live in
     * [DeparturesSpeech].
     */
    suspend fun lookup(ctx: SockContext): Lookup {
        if (_state.value.stations.isEmpty()) return Lookup.NoStations
        val held = _state.value.board
        if (held != null && !isStale(ctx, held)) return Lookup.Ready(held)
        val fresh = refresh(ctx)
        if (fresh != null) return Lookup.Ready(fresh)
        return Lookup.Unavailable(lastError, held)
    }

    /** How old the cached board is right now — what the card's freshness line is built from. */
    fun age(board: DepartureBoard): Duration = Duration.between(board.fetchedAt, clock.instant())

    /**
     * Stale is one poll interval, not two.
     *
     * The Weather Sock allows two, and departures are the case where that would be wrong: a
     * forecast does not change in twenty minutes and a countdown changes by the minute — an
     * answer built from a board that is one interval old is already off by the interval.
     */
    private fun isStale(ctx: SockContext, board: DepartureBoard): Boolean =
        age(board) > DeparturesConfig(ctx.config).pollInterval

    /**
     * The panel's German for a failed fetch (`socks.specs/README.md` §2a).
     *
     * Rendered rather than deferred because this one is *drawn*, never spoken: the spoken
     * failures are [DeparturesSpeech]'s and carry both languages. A status line on a German
     * panel is German whatever voice is selected.
     */
    private fun panelReason(error: DeparturesError): String = when (error) {
        DeparturesError.OFFLINE -> "Keine Verbindung"
        DeparturesError.RATE_LIMITED -> "Zu viele Anfragen"
        DeparturesError.SERVER, DeparturesError.FAILED -> "Wiener Linien antworten nicht"
    }
}
