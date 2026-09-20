package io.dobby.socks.clock

import java.time.Instant

/**
 * The backstop behind a running timer.
 *
 * The coroutine countdown is the primary mechanism; this exists so a doze or a process death
 * does not swallow the timer (`clock.specs.md` §3). On Android it is `AlarmManager`; here it is
 * an interface so the lifecycle stays testable on a plain JVM.
 */
interface TimerAlarm {

    /**
     * Whether an *exact* alarm can be scheduled right now.
     *
     * False means no `SCHEDULE_EXACT_ALARM` permission: the timer still runs off the coroutine,
     * only the backstop is weaker, and the Sock reports `Degraded` and says so (§12).
     */
    val canScheduleExact: Boolean get() = true

    /** Replaces any previously scheduled alarm. [onFire] may be called on any thread. */
    fun schedule(at: Instant, onFire: () -> Unit)

    fun cancel()

    /** No backstop at all: the coroutine is the whole mechanism. The default off-device. */
    object None : TimerAlarm {
        override fun schedule(at: Instant, onFire: () -> Unit) = Unit

        override fun cancel() = Unit
    }
}
