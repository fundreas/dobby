package io.dobby.socks.clock

import java.time.LocalDateTime

/**
 * What the Clock Sock shows on the wall panel (`clock.specs.md` §7).
 *
 * [now] ticks once a minute while nothing is running — a per-second redraw of an OLED panel
 * buys nothing but burn-in — and once a second while a timer counts down or
 * `clock.show_seconds` is on.
 */
data class ClockState(
    val now: LocalDateTime,
    val timer: TimerState? = null,
)
