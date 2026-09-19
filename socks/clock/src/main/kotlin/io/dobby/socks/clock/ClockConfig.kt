package io.dobby.socks.clock

import io.dobby.core.sock.SockConfigStore

/**
 * The Sock's settings (`clock.specs.md` §9), read through [SockConfigStore].
 *
 * Every value is read at the moment it is used rather than cached, so changing a setting takes
 * effect on the next chime instead of on the next restart.
 */
class ClockConfig(private val store: SockConfigStore) {

    val chimeSound: ChimeSound get() = ChimeSound.of(store.getString(CHIME_SOUND))

    val chimeMaxDurationMs: Long
        get() = (store.getInt(CHIME_MAX_DURATION_S) ?: DEFAULT_CHIME_MAX_DURATION_S)
            .coerceIn(1, MAX_CHIME_DURATION_S) * MILLIS_PER_SECOND

    val chimeIntervalMs: Long
        get() = (store.getInt(CHIME_INTERVAL_S) ?: DEFAULT_CHIME_INTERVAL_S)
            .coerceIn(1, MAX_CHIME_INTERVAL_S) * MILLIS_PER_SECOND

    val showSeconds: Boolean get() = store.getBoolean(SHOW_SECONDS) ?: false

    companion object {
        const val CHIME_SOUND: String = "clock.chime_sound"
        const val CHIME_MAX_DURATION_S: String = "clock.chime_max_duration_s"
        const val CHIME_INTERVAL_S: String = "clock.chime_interval_s"
        const val SHOW_SECONDS: String = "clock.show_seconds"

        const val DEFAULT_CHIME_MAX_DURATION_S: Int = 60
        const val DEFAULT_CHIME_INTERVAL_S: Int = 3

        // A chime that outlasts these is a broken setting, not a preference.
        private const val MAX_CHIME_DURATION_S = 600
        private const val MAX_CHIME_INTERVAL_S = 60
        private const val MILLIS_PER_SECOND = 1000L
    }
}
