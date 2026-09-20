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
import java.util.concurrent.atomic.AtomicLong

/** What [TimerEngine.start] actually did. */
internal sealed interface StartOutcome {

    /** @param timer the started timer, already carrying the label the whole list agrees on. */
    data class Started(val timer: TimerState, val replaced: Boolean) : StartOutcome

    /** [TimerEngine.MAX_TIMERS] are already running. */
    data object TooMany : StartOutcome
}

/** What [TimerEngine.cancel] actually did — the cases of `clock.cancel_timer` (§4). */
internal sealed interface CancelOutcome {

    /** The chime was sounding and is now quiet. */
    data class Silenced(val timers: List<TimerState>) : CancelOutcome

    /** These timers were counting down and are now gone. */
    data class Cancelled(val timers: List<TimerState>) : CancelOutcome

    /** There was nothing to cancel. */
    data object Nothing : CancelOutcome

    /** A name was spoken and no timer answers to it. */
    data class Unknown(val name: String) : CancelOutcome

    /** No name, several timers, none of them ringing. Only the user can break this tie. */
    data class Ambiguous(val timers: List<TimerState>) : CancelOutcome
}

/**
 * The running timers: count down, ring, be silenced.
 *
 * Each countdown is a coroutine and the [TimerAlarm] is only a backstop — the coroutine drives
 * the UI, the alarm exists so a doze or a process death does not swallow the expiry
 * (`clock.specs.md` §3).
 *
 * **Several timers, one alarm.** [TimerAlarm.schedule] replaces whatever was pending, so the
 * backstop is always armed for the *earliest* deadline still counting and re-armed whenever
 * that changes. [onBackstop] then rings everything that has come due, not one thing, because a
 * process that was asleep may well have slept past two of them.
 *
 * **Concurrency.** Every state transition happens under [mutex]. A timer's [TimerState.id] is
 * its stamp: the countdown coroutine re-checks that its own id is still in the list before it
 * touches anything, which is what keeps a timer that was cancelled a millisecond before it fired
 * from ringing anyway. Ids are never reused, so "still in the list" cannot be answered wrongly.
 */
internal class TimerEngine(
    private val clock: Clock,
    private val alarm: TimerAlarm,
    private val chime: ChimePlayer,
    private val sockId: String,
    private val screenWakeSeconds: Int,
) {
    private val _running = MutableStateFlow<List<TimerState>>(emptyList())

    /** Ordered by deadline, soonest first — the order the panel draws and Dobby speaks. */
    val running: StateFlow<List<TimerState>> = _running.asStateFlow()

    private val mutex = Mutex()

    /** Countdown-then-ring coroutines by timer id. Touched only under [mutex]. */
    private val jobs = mutableMapOf<Long, Job>()

    private val ids = AtomicLong()

    /** A pure state read: this is what `activityFor` is allowed to do (`socks.specs/README.md` §4). */
    val isRinging: Boolean get() = _running.value.any { it.isRinging }

    /** A pure state read, for the questions that only report. */
    fun snapshot(): List<TimerState> = _running.value

    /**
     * Starts a timer.
     *
     * A *named* timer replaces the one that already carries its name — saying "Timer Nudeln auf
     * 10 Minuten" twice means one pot of noodles, not two. An unnamed one never replaces
     * anything; that is the whole of what "multiple timers" changed about v1.
     */
    suspend fun start(ctx: SockContext, durationMs: Long, name: String?): StartOutcome = mutex.withLock {
        val replaced = name?.let { wanted -> _running.value.firstOrNull { it.name.equals(wanted, ignoreCase = true) } }
        if (replaced != null) forget(ctx, replaced.id, cancelJob = true)
        if (_running.value.size >= MAX_TIMERS) return@withLock StartOutcome.TooMany
        val started = TimerState(
            id = ids.incrementAndGet(),
            endsAt = clock.instant().plusMillis(durationMs),
            totalMs = durationMs,
            remainingMs = durationMs,
            name = name,
            ordinal = if (name == null) freeOrdinal() else 0,
        )
        arm(ctx, started)
        // Re-read: the label depends on how many timers there now are, and publishing is what
        // settles that. Speaking the pre-publish state would say "Timer" where the panel says
        // "Timer 1".
        StartOutcome.Started(current(started.id) ?: started, replaced != null)
    }

    /**
     * Picks up the timers that outlived the process (§12).
     *
     * The countdowns live in memory, so a killed process would otherwise swallow them silently —
     * the one failure a kitchen timer must not have. The deadlines are written to config
     * whenever the list changes, so a restarted Sock can resume them, and ring straight away for
     * any that came due while nobody was home. A timer whose moment passed long ago is dropped
     * rather than announced: a chime an hour late is not news, it is a fright.
     */
    suspend fun restore(ctx: SockContext) = mutex.withLock {
        if (_running.value.isNotEmpty()) return@withLock
        val saved = decode(ctx.config.getString(PENDING_TIMERS))
        val fresh = saved.filter { clock.millis() <= it.endsAt.toEpochMilli() + STALE_AFTER_MS }
        if (fresh.isEmpty()) {
            if (saved.isNotEmpty()) ctx.config.put(PENDING_TIMERS, "")
            return@withLock
        }
        ctx.log.debug("clock: resuming ${fresh.size} timer(s) that outlived the process")
        for (timer in fresh) {
            arm(
                ctx,
                timer.copy(
                    id = ids.incrementAndGet(),
                    remainingMs = (timer.endsAt.toEpochMilli() - clock.millis()).coerceIn(0, timer.totalMs),
                ),
            )
        }
    }

    /**
     * "Timer stopp", and the `shared.stop` the Sock consumes while the chime rings.
     *
     * With no name, a ringing timer wins: the chime is the thing demanding attention, and
     * silencing it is unambiguously what the sentence meant. Only when nothing rings and several
     * timers are counting is the utterance genuinely ambiguous, and then nothing is cancelled —
     * cancelling the wrong timer is not undoable by saying it again.
     */
    suspend fun cancel(ctx: SockContext, name: String?): CancelOutcome = mutex.withLock {
        val timers = _running.value
        if (timers.isEmpty()) return@withLock CancelOutcome.Nothing
        val targets = when {
            name != null -> listOf(TimerNames.find(timers, name) ?: return@withLock CancelOutcome.Unknown(name))
            timers.any { it.isRinging } -> timers.filter { it.isRinging }
            timers.size == 1 -> timers
            else -> return@withLock CancelOutcome.Ambiguous(timers)
        }
        for (target in targets) forget(ctx, target.id, cancelJob = true)
        if (targets.any { it.isRinging }) {
            CancelOutcome.Silenced(targets)
        } else {
            CancelOutcome.Cancelled(targets)
        }
    }

    /** "Brich alle Timer ab" — the one command that needs no name and no tie to break. */
    suspend fun cancelAll(ctx: SockContext): List<TimerState> = mutex.withLock {
        val all = _running.value
        for (timer in all) forget(ctx, timer.id, cancelJob = true)
        all
    }

    /**
     * Service shutdown. Leaves no sound, no alarm and no pending deadline behind.
     *
     * Stopping Dobby stops its timers, deliberately: this is the orderly path — the user
     * tapping "Beenden" — and a panel that was switched off must not ring an hour later. The
     * *disorderly* path, where the process is killed without a word, is [restore]'s job.
     */
    suspend fun shutdown(ctx: SockContext) {
        cancelAll(ctx)
        chime.stop()
    }

    /**
     * Starts counting towards [TimerState.endsAt]. Must be called with [mutex] held.
     *
     * A deadline that has already passed is legal and rings immediately — which is what makes
     * [restore] need no special case of its own.
     */
    private fun arm(ctx: SockContext, timer: TimerState) {
        jobs[timer.id]?.cancel()
        publish(ctx, _running.value + timer)
        rearmAlarm(ctx)
        jobs[timer.id] = ctx.scope.launch { countDownThenRing(ctx, timer.id, timer.endsAt) }
    }

    /**
     * The one place the list changes. Must be called with [mutex] held.
     *
     * Relabelling happens here rather than at each call site because it is a property of the
     * *list*: the sole default timer is "Timer" and the moment a second one exists it is
     * "Timer 1" (`TimerState.numbered`). Persistence happens here for the same reason — every
     * structural change is one, and a countdown tick is not.
     */
    private fun publish(ctx: SockContext, timers: List<TimerState>) {
        val numbered = timers.size > 1
        _running.value = timers
            .map { if (it.numbered == numbered) it else it.copy(numbered = numbered) }
            .sortedBy { it.endsAt }
        ctx.config.put(PENDING_TIMERS, encode(_running.value))
    }

    /** Drops one timer, and with it the chime and the audio focus if it was the last ringing one. */
    private suspend fun forget(ctx: SockContext, id: Long, cancelJob: Boolean): TimerState? {
        val gone = current(id) ?: return null
        val job = jobs.remove(id)
        // Never on the ring coroutine's own `finally` path: it is the one calling this, and it
        // is already finishing.
        if (cancelJob) job?.cancel()
        publish(ctx, _running.value.filterNot { it.id == id })
        rearmAlarm(ctx)
        if (gone.isRinging && _running.value.none { it.isRinging }) silence(ctx)
        return gone
    }

    /** The backstop is armed for the next deadline only. Must be called with [mutex] held. */
    private fun rearmAlarm(ctx: SockContext) {
        val next = _running.value.filterNot { it.isRinging }.minByOrNull { it.endsAt }
        if (next == null) {
            alarm.cancel()
        } else {
            alarm.schedule(next.endsAt) { ctx.scope.launch { onBackstop(ctx) } }
        }
    }

    private suspend fun countDownThenRing(ctx: SockContext, id: Long, endsAt: Instant) {
        val deadline = endsAt.toEpochMilli()
        while (true) {
            // Cancellation is cooperative, so a cancelled timer can get one more tick in between
            // `job.cancel()` and its own `delay` noticing. Without this it would write its
            // remaining time back into a list it is no longer part of.
            if (current(id) == null) return
            val remaining = deadline - clock.millis()
            if (remaining <= 0) break
            _running.update { timers ->
                timers.map { if (it.id == id) it.copy(remainingMs = remaining) else it }
            }
            delay(minOf(remaining, TICK_MS))
        }
        ring(ctx, id)
    }

    /**
     * Expiry: wake the screen, duck the music, say which timer it was, then chime until told to
     * stop.
     *
     * Transient focus rather than full focus is the whole point — Spotify ducks and comes back
     * up by itself, instead of being stopped by a kitchen timer (§3).
     */
    private suspend fun ring(ctx: SockContext, id: Long) {
        val config = ClockConfig(ctx.config)
        val timer = mutex.withLock {
            val current = current(id) ?: return
            if (current.isRinging) return
            val ringing = current.copy(remainingMs = 0, isRinging = true)
            publish(ctx, _running.value.map { if (it.id == id) ringing else it })
            // This one no longer needs a backstop; whatever is counting behind it still does.
            rearmAlarm(ctx)
            ringing
        }

        ctx.screen.wakeFor(screenWakeSeconds)
        ctx.playback.requestTransientFocus(sockId)
        ctx.announce(expired(timer))

        val until = clock.millis() + config.chimeMaxDurationMs
        try {
            while (clock.millis() < until) {
                chime.play(config.chimeSound)
                delay(config.chimeIntervalMs)
            }
        } finally {
            // Reached both by the 60 s auto-stop and by cancellation. On the cancel path the
            // timer is already gone and `forget` finds nothing, so this may run unconditionally.
            withContext(NonCancellable) {
                mutex.withLock { forget(ctx, id, cancelJob = false) }
            }
        }
    }

    /**
     * The `AlarmManager` backstop fired.
     *
     * In the normal case the coroutines have already rung and this finds nothing to do. It earns
     * its keep exactly when they did not run: the process was dozing or was killed. Several
     * timers may have come due in that gap, so this rings every one of them rather than the one
     * the alarm was armed for.
     */
    private suspend fun onBackstop(ctx: SockContext) {
        val due = mutex.withLock {
            val now = clock.millis()
            val due = _running.value.filter { !it.isRinging && now >= it.endsAt.toEpochMilli() }
            for (timer in due) {
                jobs[timer.id]?.cancel()
                jobs[timer.id] = ctx.scope.launch { ring(ctx, timer.id) }
            }
            due
        }
        if (due.isNotEmpty()) ctx.log.debug("clock: ${due.size} timer expiry came from the alarm backstop")
    }

    private fun current(id: Long): TimerState? = _running.value.firstOrNull { it.id == id }

    /** The lowest number no default timer is using. Must be called with [mutex] held. */
    private fun freeOrdinal(): Int {
        val taken = _running.value.filter { it.name == null }.map { it.ordinal }.toSet()
        return generateSequence(1) { it + 1 }.first { it !in taken }
    }

    /** Must be called with [mutex] held. */
    private suspend fun silence(ctx: SockContext) {
        chime.stop()
        ctx.playback.releaseFocus(sockId)
    }

    /**
     * One line per timer, `endsAt|totalMs|ordinal|name`.
     *
     * A name is Normalizer output with its first letters titlecased, so it holds letters, digits,
     * apostrophes and spaces and nothing else — the separator cannot appear inside one.
     */
    private fun encode(timers: List<TimerState>): String = timers.joinToString("\n") {
        "${it.endsAt.toEpochMilli()}|${it.totalMs}|${it.ordinal}|${it.name.orEmpty()}"
    }

    /** A line that does not parse is dropped, not fatal: a timer is not worth a crash loop. */
    private fun decode(raw: String?): List<TimerState> = raw.orEmpty().lineSequence()
        .mapNotNull { line ->
            val parts = line.split('|')
            if (parts.size != PERSISTED_FIELDS) return@mapNotNull null
            val endsAt = parts[0].toLongOrNull() ?: return@mapNotNull null
            val totalMs = parts[1].toLongOrNull() ?: return@mapNotNull null
            val ordinal = parts[2].toIntOrNull() ?: return@mapNotNull null
            TimerState(
                id = 0,
                endsAt = Instant.ofEpochMilli(endsAt),
                totalMs = totalMs,
                remainingMs = 0,
                name = parts[3].ifEmpty { null },
                ordinal = ordinal,
            )
        }
        .toList()

    companion object {
        /**
         * German copy lives in the spec (§3). What the sole default timer announces, and the
         * shape every other one follows: "Timer 2 ist abgelaufen", "Timer Nudeln ist abgelaufen".
         */
        const val TIMER_EXPIRED: String = "Der Timer ist abgelaufen."

        fun expired(timer: TimerState): String = "${timer.subject} ist abgelaufen."

        /**
         * More than this is not a kitchen, it is a stress test.
         *
         * The limit exists so a misheard sentence cannot fill the panel with timers nobody can
         * name their way back out of, not because the engine would struggle.
         */
        const val MAX_TIMERS: Int = 8

        /** One countdown tick. The last one is short: it lands exactly on the deadline. */
        private const val TICK_MS = 1000L

        /** Written by [publish], read by [restore]. Not user-facing config — see `ClockConfig`. */
        private const val PENDING_TIMERS = "clock.pending_timers"

        private const val PERSISTED_FIELDS = 4

        /** Past this, a timer that came due while the process was dead is dropped. */
        private const val STALE_AFTER_MS = 60 * 60 * 1000L
    }
}
