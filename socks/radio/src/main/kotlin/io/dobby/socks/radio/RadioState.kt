package io.dobby.socks.radio

/**
 * What the Radio Sock is doing, for `activityFor` and for the wall (`radio.specs.md` §7).
 *
 * The chain reads this and nothing else: no player interrogation, no network, no config — a
 * field read off a `StateFlow`, which trivially satisfies the under-5 ms contract.
 */
sealed interface RadioState {

    /**
     * Nothing is playing.
     *
     * [lastStation] is what backs `shared.resume`'s `IDLE`, and it is **session-scoped and
     * never persisted** (`radio.specs.md` §5): after a service restart "weiter" with no history
     * must be `NotForMe`, not a surprise burst of FM4 at 3 a.m. Null is that cold state.
     */
    data class Idle(val lastStation: Station? = null) : RadioState

    data class Buffering(val station: Station) : RadioState

    data class Playing(val station: Station, val nowPlaying: String? = null) : RadioState

    /**
     * The stream failed and the reconnect ladder gave up.
     *
     * `INACTIVE` for both chains: there is nothing to stop, and a station that just failed is
     * not a good answer to "weiter" — the user can name it again in four words.
     */
    data class Error(val station: Station, val reason: String) : RadioState
}

/**
 * The station this state is about, whichever shape it has.
 *
 * An extension rather than a member, because `Idle` calls its own `lastStation` and a member
 * of the same name would shadow it in every subtype.
 */
val RadioState.stationOrNull: Station?
    get() = when (this) {
        is RadioState.Idle -> lastStation
        is RadioState.Buffering -> station
        is RadioState.Playing -> station
        is RadioState.Error -> station
    }
