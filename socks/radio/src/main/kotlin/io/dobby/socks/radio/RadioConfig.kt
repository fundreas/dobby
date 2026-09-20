package io.dobby.socks.radio

import io.dobby.core.sock.SockConfigStore

/**
 * The Sock's settings (`radio.specs.md` §9), read through [SockConfigStore].
 *
 * Read at the moment they are used rather than captured at `onStart`, the way `ClockConfig`
 * and `SpotifyConfig` are and for the same reason `AndroidTurnAudio` reads its mode through a
 * lambda: a setting should take effect on the next command, not on the next restart.
 */
class RadioConfig(private val store: SockConfigStore) {

    /** Falls back to the table's own default when the stored id names nothing that exists. */
    val defaultStation: Station
        get() = store.getString(DEFAULT_STATION)?.let { Stations.byId[it] } ?: Stations.default

    /** The spec's buffering budget, as the milliseconds `RadioPlayer.play` wants. */
    val bufferTimeoutMs: Long
        get() = ((store.getInt(BUFFER_TIMEOUT_S) ?: DEFAULT_BUFFER_TIMEOUT_S)
            .coerceAtLeast(1) * MILLIS_PER_SECOND)

    /**
     * Retries **after** the first failure, so the default 3 is the ladder 1 s / 3 s / 9 s.
     *
     * Zero means one attempt and no retry — and the fallback URL disappears with it, because
     * it is the ladder's last rung. That is the right reading of "no retries".
     */
    val reconnectAttempts: Int
        get() = (store.getInt(RECONNECT_ATTEMPTS) ?: DEFAULT_RECONNECT_ATTEMPTS).coerceAtLeast(0)

    companion object {
        const val DEFAULT_STATION: String = "radio.default_station"
        const val BUFFER_TIMEOUT_S: String = "radio.buffer_timeout_s"
        const val RECONNECT_ATTEMPTS: String = "radio.reconnect_attempts"

        const val DEFAULT_BUFFER_TIMEOUT_S: Int = 10
        const val DEFAULT_RECONNECT_ATTEMPTS: Int = 3

        private const val MILLIS_PER_SECOND = 1000L
    }
}
