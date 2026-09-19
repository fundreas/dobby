package io.dobby.socks.clock

import io.dobby.core.testing.FakeConfigStore
import io.dobby.core.testing.FakeSockContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * A wall clock that follows the test scheduler's virtual time.
 *
 * The timer counts in `delay()` but decides when it is finished by reading the clock, so the
 * two have to move together or the countdown never ends. [skewMs] is the one thing virtual time
 * cannot express: the device dozing, where the wall clock jumps forward while no coroutine
 * runs. That is exactly the case the `AlarmManager` backstop exists for.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TestClock(private val scope: TestScope, private val start: Instant, private val zone: ZoneId) : Clock() {
    var skewMs: Long = 0

    override fun instant(): Instant = start.plusMillis(scope.testScheduler.currentTime + skewMs)

    override fun getZone(): ZoneId = zone

    override fun withZone(zone: ZoneId): Clock = TestClock(scope, start, zone)
}

/** A [TimerAlarm] that records instead of scheduling, and that a test can fire by hand. */
class FakeAlarm(override val canScheduleExact: Boolean = true) : TimerAlarm {
    var scheduledFor: Instant? = null
        private set
    var cancels: Int = 0
        private set

    private var onFire: (() -> Unit)? = null

    override fun schedule(at: Instant, onFire: () -> Unit) {
        scheduledFor = at
        this.onFire = onFire
    }

    override fun cancel() {
        cancels++
        scheduledFor = null
        onFire = null
    }

    /** The device woke up because the alarm fired. */
    fun fire() {
        onFire?.invoke()
    }
}

/** Counts chime strokes, so "every 3 s for 60 s" is an assertion and not a hope. */
class RecordingChime : ChimePlayer {
    val strokes: MutableList<ChimeSound> = mutableListOf()
    var stops: Int = 0
        private set
    var releases: Int = 0
        private set

    override fun play(sound: ChimeSound) {
        strokes += sound
    }

    override fun stop() {
        stops++
    }

    override fun release() {
        releases++
    }
}

/** What survives a process death: the config store, and nothing else. */
fun FakeConfigStore.copyFrom(other: FakeConfigStore) {
    for (key in PERSISTED) other.getString(key)?.let { put(key, it) }
}

private val PERSISTED = listOf(
    "clock.pending_timer_ends_at",
    "clock.pending_timer_total_ms",
)

/** Everything a timer test needs, wired the way the service wires it. */
@OptIn(ExperimentalCoroutinesApi::class)
class TimerFixture(scope: TestScope, canScheduleExact: Boolean = true) {
    val alarm: FakeAlarm = FakeAlarm(canScheduleExact)
    val chime: RecordingChime = RecordingChime()
    val clock: TestClock = TestClock(scope, START, ZoneId.of("Europe/Vienna"))

    /** The Sock's own coroutines run on the test dispatcher and die with the test. */
    val ctx: FakeSockContext = FakeSockContext(scope = scope.backgroundScope)

    val sock: ClockSock = ClockSock(clock, alarm, chime)

    val timer: TimerState? get() = sock.state.value.timer

    suspend fun start() = sock.onStart(ctx)

    companion object {
        /** 13:00 UTC on a Thursday in winter — 14:00 in Vienna. */
        val START: Instant = Instant.parse("2026-01-15T13:00:00Z")
    }
}
