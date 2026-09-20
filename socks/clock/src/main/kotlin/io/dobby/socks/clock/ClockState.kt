package io.dobby.socks.clock

import java.time.LocalDateTime

/**
 * What the Clock Sock shows on the wall panel (`clock.specs.md` §9).
 *
 * [now] ticks once a minute while nothing is running — a per-second redraw of an OLED panel
 * buys nothing but burn-in — and once a second while a timer counts down or
 * `clock.show_seconds` is on.
 *
 * [timers] is ordered by deadline, soonest first, so the panel draws them in the order they
 * will go off and Dobby reads them out the same way.
 */
data class ClockState(
    val now: LocalDateTime,
    val timers: List<TimerState> = emptyList(),
) {
    /**
     * The timer the panel leads with: the one ringing, else the one going off next.
     *
     * A convenience for every caller that means "the timer" — most of the time there is exactly
     * one, and that was the whole of v1.
     */
    val timer: TimerState?
        get() = timers.firstOrNull { it.isRinging } ?: timers.firstOrNull()
}
