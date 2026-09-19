package io.dobby.socks.clock

import io.dobby.core.sock.SockContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.Instant

/** What [TimerEngine.cancel] actually did — the three cases of `clock.cancel_timer` (§4). */
enum class CancelOutcome {
    /** The chime was sounding and is now quiet. */
    SILENCED,

    /** A timer was counting down and is now gone. */
    CANCELLED,

    /** There was nothing to cancel. */
    NOTHING,
}

/**
 * One timer: count down, ring, be silenced.
 *
 * The countdown is a coroutine and the [TimerAlarm] is only a backstop — the coroutine drives
 * the UI, the alarm exists so a doze or a process death does not swallow the expiry
 * (`clock.specs.md` §3).
 *
 * v1 holds at most one timer at a time. The model is deliberately shaped so that multi-timer is
 * a change of this field into a list, not a rewrite; the commands simply do not expose it.
 *
 * **Concurrency.** Every state transition happens under [mutex] and stamps a [generation].
 * The running coroutine re-checks that stamp before it touches anything, which is what keeps a
 * timer that was cancelled a millisecond before it fired from ringing anyway.
 */
internal class TimerEngine(
    private val clock: Clock,
    private val alarm: TimerAlarm,
    private val chime: ChimePlayer,
    private val sockId: String,
    private val screenWakeSeconds: Int,
) {
    private val _timer = MutableStateFlow<TimerState?>(null)
    val timer: StateFlow<TimerState?> = _timer.asStateFlow()

    private val mutex = Mutex()

    private var job: Job? = null

    @Volatile
    private var generation: Int = 0

    /** A pure state read: this is what `activityFor` is allowed to do (`socks.specs/README.md` §4). */
    val isRinging: Boolean get() = _timer.value?.isRinging == true

    /**
     * Starts a timer, replacing whatever was running.
     *
     * @return true if it replaced a timer that was still counting down or ringing.
     */
    suspend fun start(ctx: SockContext, durationMs: Long): Boolean = mutex.withLock {
        val previous = _timer.value
        // The replaced timer's own coroutine will see the new stamp and clean up nothing, so
        // whatever it was holding is released here instead.
        if (previous?.isRinging == true) silence(ctx)
        arm(ctx, clock.instant().plusMillis(durationMs), durationMs)
        previous != null
    }

    /**
     * Picks up a timer that outlived the process (§10).
     *
     * The countdown lives in memory, so a killed process would otherwise swallow the timer
     * silently — the one failure a kitchen timer must not have. The deadline is written to
     * config when the timer starts, so a restarted Sock can resume it, and ring straight away
     * if it came due while nobody was home. A timer whose moment passed long ago is dropped
     * rather than announced: a chime an hour late is not news, it is a fright.
     */
    suspend fun restore(ctx: SockContext) = mutex.withLock {
        if (_timer.value != null) return@withLock
        val endsAt = ctx.config.getString(PENDING_ENDS_AT)?.toLongOrNull() ?: return@withLock
        val totalMs = ctx.config.getString(PENDING_TOTAL_MS)?.toLongOrNull() ?: return@withLock
        if (clock.millis() > endsAt + STALE_AFTER_MS) {
            clearPending(ctx)
            return@withLock
        }
        ctx.log.debug("clock: resuming a timer that outlived the process")
        arm(ctx, Instant.ofEpochMilli(endsAt), totalMs)
    }

    /**
     * Starts counting towards [endsAt]. Must be called with [mutex] held.
     *
     * A deadline that has already passed is legal and rings immediately — which is what makes
     * [restore] need no special case of its own.
     */
    private fun arm(ctx: SockContext, endsAt: Instant, totalMs: Long) {
        generation++
        val stamp = generation
        job?.cancel()
        val remaining = (endsAt.toEpochMilli() - clock.millis()).coerceIn(0, totalMs)
        _timer.value = TimerState(endsAt = endsAt, totalMs = totalMs, remainingMs = remaining)
        ctx.config.put(PENDING_ENDS_AT, endsAt.toEpochMilli().toString())
        ctx.config.put(PENDING_TOTAL_MS, totalMs.toString())
        alarm.schedule(endsAt) { ctx.scope.launch { onBackstop(ctx, stamp) } }
        job = ctx.scope.launch { countDownThenRing(ctx, stamp, endsAt) }
    }

    /** "Timer stopp", and the `shared.stop` the Sock consumes while the chime rings. */
    suspend fun cancel(ctx: SockContext): CancelOutcome = mutex.withLock {
        val current = _timer.value ?: return@withLock CancelOutcome.NOTHING
        generation++
        job?.cancel()
        job = null
        alarm.cancel()
        _timer.value = null
        clearPending(ctx)
        if (current.isRinging) {
            silence(ctx)
            CancelOutcome.SILENCED
        } else {
            CancelOutcome.CANCELLED
        }
    }

    /**
     * Service shutdown. Leaves no sound, no alarm and no pending deadline behind.
     *
     * Stopping Dobby stops its timers, deliberately: this is the orderly path — the user
     * tapping "Beenden" — and a panel that was switched off must not ring an hour later. The
     * *disorderly* path, where the process is killed without a word, is [restore]'s job.
     */
    suspend fun shutdown(ctx: SockContext) {
        cancel(ctx)
        chime.stop()
    }

    private suspend fun countDownThenRing(ctx: SockContext, stamp: Int, endsAt: Instant) {
        val deadline = endsAt.toEpochMilli()
        while (true) {
            // Cancellation is cooperative, so a replaced timer can get one more tick in
            // between `job.cancel()` and its own `delay` noticing. Without this it would write
            // its remaining time into the state of the timer that replaced it.
            if (stamp != generation) return
            val remaining = deadline - clock.millis()
            if (remaining <= 0) break
            _timer.update { it?.copy(remainingMs = remaining) }
            delay(minOf(remaining, TICK_MS))
        }
        ring(ctx, stamp)
    }

    /**
     * Expiry: wake the screen, duck the music, say it, then chime until told to stop.
     *
     * Transient focus rather than full focus is the whole point — Spotify ducks and comes back
     * up by itself, instead of being stopped by a kitchen timer (§3).
     */
    private suspend fun ring(ctx: SockContext, stamp: Int) {
        val config = ClockConfig(ctx.config)
        mutex.withLock {
            if (stamp != generation) return
            // The backstop has done its job either way; it must not fire into the next timer.
            alarm.cancel()
            _timer.update { it?.copy(remainingMs = 0, isRinging = true) }
        }

        ctx.screen.wakeFor(screenWakeSeconds)
        ctx.playback.requestTransientFocus(sockId)
        ctx.announce(TIMER_EXPIRED)

        val until = clock.millis() + config.chimeMaxDurationMs
        try {
            while (clock.millis() < until) {
                chime.play(config.chimeSound)
                delay(config.chimeIntervalMs)
            }
        } finally {
            // Reached both by the 60 s auto-stop and by cancellation. On the cancel path the
            // stamp has already moved on and `cancel` did the cleanup itself, so this does
            // nothing — which is why it may run unconditionally.
            withContext(NonCancellable) {
                mutex.withLock {
                    if (stamp == generation) {
                        _timer.value = null
                        job = null
                        // Only now: a process that dies mid-chime should ring again when it
                        // comes back, because nobody has heard this timer yet.
                        clearPending(ctx)
                        silence(ctx)
                    }
                }
            }
        }
    }

    /**
     * The `AlarmManager` backstop fired.
     *
     * In the normal case the coroutine has already rung and this finds nothing to do. It earns
     * its keep exactly when the coroutine did not run: the process was dozing or was killed.
     */
    private suspend fun onBackstop(ctx: SockContext, stamp: Int) = mutex.withLock {
        val current = _timer.value ?: return@withLock
        if (stamp != generation || current.isRinging) return@withLock
        if (clock.millis() < current.endsAt.toEpochMilli()) return@withLock
        ctx.log.debug("clock: timer expiry came from the alarm backstop")
        job?.cancel()
        job = ctx.scope.launch { ring(ctx, stamp) }
    }

    /** [SockConfigStore] has no remove, and a blank value is as absent as it needs to be. */
    private fun clearPending(ctx: SockContext) {
        ctx.config.put(PENDING_ENDS_AT, "")
        ctx.config.put(PENDING_TOTAL_MS, "")
    }

    /** Must be called with [mutex] held. */
    private suspend fun silence(ctx: SockContext) {
        chime.stop()
        ctx.playback.releaseFocus(sockId)
    }

    companion object {
        /** German copy lives in the spec (§3). This is the only thing the Sock announces. */
        const val TIMER_EXPIRED: String = "Der Timer ist abgelaufen."

        /** One countdown tick. The last one is short: it lands exactly on the deadline. */
        private const val TICK_MS = 1000L

        /** Written by [arm], read by [restore]. Not user-facing config — see `ClockConfig`. */
        private const val PENDING_ENDS_AT = "clock.pending_timer_ends_at"
        private const val PENDING_TOTAL_MS = "clock.pending_timer_total_ms"

        /** Past this, a timer that came due while the process was dead is dropped. */
        private const val STALE_AFTER_MS = 60 * 60 * 1000L
    }
}
