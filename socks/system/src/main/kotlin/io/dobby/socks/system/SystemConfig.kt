package io.dobby.socks.system

import io.dobby.core.sock.SockConfigStore

/**
 * The Sock's settings (`system.specs.md` §8), read through [SockConfigStore].
 *
 * Every value is read where it is used rather than cached, so a changed setting takes effect on
 * the next "lauter" instead of on the next restart — the same rule `ClockConfig` follows.
 */
class SystemConfig(private val store: SockConfigStore) {

    /** How many steps a bare "lauter" moves. `2` × [stepPercent] is the 10 % the spec promises. */
    val defaultSteps: Int
        get() = (store.getInt(VOLUME_DEFAULT_STEPS) ?: DEFAULT_STEPS).coerceIn(1, MAX_STEPS)

    /**
     * What one step is worth, in percentage points.
     *
     * Five, so the three step sizes the templates bind come out as the round numbers a person
     * would name: "ein bisschen lauter" 5 %, "lauter" 10 %, "viel lauter" 25 %.
     */
    val stepPercent: Int
        get() = (store.getInt(VOLUME_STEP_PERCENT) ?: DEFAULT_STEP_PERCENT).coerceIn(1, MAX_STEP_PERCENT)

    /** A ceiling, so "volle Lautstärke" at three in the morning is still the house's decision. */
    val maxVolumePercent: Int
        get() = (store.getInt(MAX_VOLUME_PERCENT) ?: DEFAULT_MAX_VOLUME_PERCENT)
            .coerceIn(MIN_MAX_VOLUME_PERCENT, FULL)

    /** Where "Lautstärke wieder an" lands when nothing was remembered — a fresh start, say. */
    val unmutePercent: Int
        get() = (store.getInt(UNMUTE_PERCENT) ?: DEFAULT_UNMUTE_PERCENT).coerceIn(1, FULL)

    companion object {
        const val VOLUME_DEFAULT_STEPS: String = "system.volume_default_steps"
        const val VOLUME_STEP_PERCENT: String = "system.volume_step_percent"
        const val MAX_VOLUME_PERCENT: String = "system.max_volume_percent"
        const val UNMUTE_PERCENT: String = "system.unmute_percent"

        const val DEFAULT_STEPS: Int = 2
        const val DEFAULT_STEP_PERCENT: Int = 5
        const val DEFAULT_MAX_VOLUME_PERCENT: Int = 100
        const val DEFAULT_UNMUTE_PERCENT: Int = 30

        /** The spec's `steps` range (§3). Past ten a "step" is not a step any more. */
        const val MAX_STEPS: Int = 10

        const val FULL: Int = 100

        private const val MAX_STEP_PERCENT = 50

        // A ceiling under a tenth is a broken setting, not a preference: it would make every
        // volume command a no-op and every one of them say "schon ganz laut".
        private const val MIN_MAX_VOLUME_PERCENT = 10
    }
}
