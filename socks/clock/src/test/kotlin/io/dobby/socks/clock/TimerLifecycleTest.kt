package io.dobby.socks.clock

import io.dobby.core.sock.CommandInvocation
import io.dobby.core.sock.SharedCommands
import io.dobby.core.sock.SockActivity
import io.dobby.core.sock.SockResult
import io.dobby.core.sock.SockStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The timer, end to end, on a virtual clock (`clock.specs.md` §11).
 *
 * No `AlarmManager`, no `SoundPool`, no emulator and no waiting: sixty seconds of chime is one
 * line of `advanceTimeBy`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TimerLifecycleTest {

    private fun setTimer(amount: Int, unit: String) =
        CommandInvocation(ClockSock.SET_TIMER, mapOf("amount" to amount, "unit" to unit))

    private val cancelTimer = CommandInvocation(ClockSock.CANCEL_TIMER)
    private val sharedStop = CommandInvocation(SharedCommands.STOP.id)

    private val tenMinutes = 10 * 60 * 1000L

    @Test
    fun `sets a timer and says so`() = runTest {
        val fixture = TimerFixture(this)
        fixture.start()
        val expiry = fixture.clock.instant().plusMillis(tenMinutes)

        val result = fixture.sock.handle(setTimer(10, "minuten"))
        runCurrent()

        assertEquals(SockResult.Spoken("Timer läuft: 10 Minuten."), result)
        assertEquals(tenMinutes, fixture.timer?.totalMs)
        assertEquals(expiry, fixture.timer?.endsAt)
        // The coroutine is the primary mechanism; the alarm is the backstop behind it (§3).
        assertEquals(expiry, fixture.alarm.scheduledFor)
    }

    @Test
    fun `counts down once a second`() = runTest {
        val fixture = TimerFixture(this)
        fixture.start()
        fixture.sock.handle(setTimer(10, "minuten"))

        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(9 * 60 * 1000L, fixture.timer?.remainingMs)
        assertEquals(0.1f, fixture.timer?.progress)
        assertFalse(fixture.timer!!.isRinging)
    }

    @Test
    fun `the singular is spoken as a singular`() = runTest {
        val fixture = TimerFixture(this)
        fixture.start()

        assertEquals(
            SockResult.Spoken("Timer läuft: 1 Minute."),
            fixture.sock.handle(setTimer(1, "minuten")),
        )
    }

    @Test
    fun `a second timer replaces the first, and says that too`() = runTest {
        val fixture = TimerFixture(this)
        fixture.start()
        fixture.sock.handle(setTimer(10, "minuten"))
        runCurrent()

        val result = fixture.sock.handle(setTimer(5, "minuten"))
        runCurrent()

        assertEquals(SockResult.Spoken("Alter Timer ersetzt. Timer läuft: 5 Minuten."), result)
        assertEquals(5 * 60 * 1000L, fixture.timer?.totalMs)

        // And the replaced one is really gone: nothing rings at its expiry.
        advanceTimeBy(6 * 60 * 1000L)
        runCurrent()
        assertEquals(1, fixture.ctx.announcements.size)
    }

    @Test
    fun `cancelling before expiry stops the countdown and the alarm`() = runTest {
        val fixture = TimerFixture(this)
        fixture.start()
        fixture.sock.handle(setTimer(10, "minuten"))
        runCurrent()

        val result = fixture.sock.handle(cancelTimer)
        runCurrent()

        assertEquals(SockResult.Spoken("Timer abgebrochen."), result)
        assertNull(fixture.timer)
        assertNull(fixture.alarm.scheduledFor)

        advanceTimeBy(11 * 60 * 1000L)
        runCurrent()
        assertEquals(emptyList(), fixture.ctx.announcements)
        assertEquals(emptyList(), fixture.chime.strokes)
    }

    @Test
    fun `cancelling nothing says so`() = runTest {
        val fixture = TimerFixture(this)
        fixture.start()

        assertEquals(
            SockResult.Spoken("Es läuft gerade kein Timer."),
            fixture.sock.handle(cancelTimer),
        )
    }

    @Test
    fun `expiry wakes the screen, ducks the music, announces and chimes`() = runTest {
        val fixture = TimerFixture(this)
        fixture.start()
        fixture.sock.handle(setTimer(1, "minuten"))

        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(listOf(TimerEngine.TIMER_EXPIRED), fixture.ctx.announcements)
        assertEquals(listOf(ClockSock.DEFAULT_SCREEN_WAKE_SECONDS), fixture.ctx.screen.wakeRequests)
        // Transient focus: Spotify ducks, it is not stopped by a kitchen timer (§3).
        assertEquals("clock", fixture.ctx.playback.duckedBy)
        assertEquals(listOf(ChimeSound.GLOCKE), fixture.chime.strokes)
        assertTrue(fixture.timer!!.isRinging)
        assertEquals(0L, fixture.timer?.remainingMs)
    }

    @Test
    fun `the chime repeats every three seconds and gives up after a minute`() = runTest {
        val fixture = TimerFixture(this)
        fixture.start()
        fixture.sock.handle(setTimer(1, "minuten"))
        advanceTimeBy(60_000)
        runCurrent()

        advanceTimeBy(9_000)
        runCurrent()
        assertEquals(4, fixture.chime.strokes.size)

        // 60 s of chime at 3 s intervals, then it stops on its own and lets the music back up.
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(20, fixture.chime.strokes.size)
        assertNull(fixture.timer)
        assertNull(fixture.ctx.playback.duckedBy)
        assertTrue(fixture.chime.stops > 0)
    }

    @Test
    fun `the chime sound and its timing are configurable`() = runTest {
        val fixture = TimerFixture(this)
        fixture.ctx.config.put(ClockConfig.CHIME_SOUND, "gong")
        fixture.ctx.config.put(ClockConfig.CHIME_INTERVAL_S, "5")
        fixture.ctx.config.put(ClockConfig.CHIME_MAX_DURATION_S, "10")
        fixture.start()
        fixture.sock.handle(setTimer(30, "sekunden"))

        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(listOf(ChimeSound.GONG), fixture.chime.strokes)

        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(2, fixture.chime.strokes.size)
        assertNull(fixture.timer)
    }

    @Test
    fun `cancelling while it rings silences it and gives the music back`() = runTest {
        val fixture = TimerFixture(this)
        fixture.start()
        fixture.sock.handle(setTimer(1, "minuten"))
        advanceTimeBy(60_000)
        runCurrent()

        val result = fixture.sock.handle(cancelTimer)
        runCurrent()

        assertEquals(SockResult.Silent, result)
        assertNull(fixture.timer)
        assertNull(fixture.ctx.playback.duckedBy)
        assertTrue(fixture.chime.stops > 0)

        val strokes = fixture.chime.strokes.size
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(strokes, fixture.chime.strokes.size)
    }

    @Test
    fun `the alarm backstop rings a timer the process slept through`() = runTest {
        val fixture = TimerFixture(this)
        fixture.start()
        fixture.sock.handle(setTimer(10, "minuten"))
        runCurrent()

        // Doze: the wall clock moves, no coroutine runs, and then the alarm wakes us.
        fixture.clock.skewMs = 11 * 60 * 1000L
        fixture.alarm.fire()
        runCurrent()

        assertTrue(fixture.timer!!.isRinging)
        assertEquals(listOf(TimerEngine.TIMER_EXPIRED), fixture.ctx.announcements)
    }

    @Test
    fun `a backstop that fires early is ignored`() = runTest {
        val fixture = TimerFixture(this)
        fixture.start()
        fixture.sock.handle(setTimer(10, "minuten"))
        runCurrent()

        fixture.alarm.fire()
        runCurrent()

        assertFalse(fixture.timer!!.isRinging)
        assertEquals(emptyList(), fixture.ctx.announcements)
    }

    @Test
    fun `durations that are not timers are refused`() = runTest {
        val fixture = TimerFixture(this)
        fixture.start()

        for (invocation in listOf(
            setTimer(0, "minuten"),
            setTimer(13, "stunden"),
            setTimer(700, "minuten"),
            setTimer(-5, "minuten"),
        )) {
            assertEquals(
                SockResult.Failed(ClockSock.BAD_DURATION),
                fixture.sock.handle(invocation),
                invocation.toString(),
            )
        }
        assertNull(fixture.timer)
    }

    @Test
    fun `the edges of the range are timers`() = runTest {
        val fixture = TimerFixture(this)
        fixture.start()

        assertEquals(
            SockResult.Spoken("Timer läuft: 1 Sekunde."),
            fixture.sock.handle(setTimer(1, "sekunden")),
        )
        assertEquals(
            SockResult.Spoken("Alter Timer ersetzt. Timer läuft: 12 Stunden."),
            fixture.sock.handle(setTimer(12, "stunden")),
        )
    }

    @Test
    fun `without the exact-alarm permission it still runs, and says it is not guaranteed`() = runTest {
        val fixture = TimerFixture(this, canScheduleExact = false)
        fixture.start()

        val result = fixture.sock.handle(setTimer(10, "minuten"))
        runCurrent()

        assertEquals(
            SockResult.Spoken("Timer läuft: 10 Minuten. Achtung, er ist nicht garantiert genau."),
            result,
        )
        assertEquals(SockStatus.Degraded("Timer nicht garantiert genau"), fixture.sock.status.value)
        assertNotNull(fixture.timer)

        // Degraded means weaker, not broken: the coroutine still fires it.
        advanceTimeBy(tenMinutes)
        runCurrent()
        assertEquals(listOf(TimerEngine.TIMER_EXPIRED), fixture.ctx.announcements)
    }

    @Test
    fun `it is only ACTIVE for stop while the chime is actually ringing`() = runTest {
        val fixture = TimerFixture(this)
        fixture.start()
        assertEquals(SockActivity.INACTIVE, fixture.sock.activityFor(sharedStop))

        fixture.sock.handle(setTimer(1, "minuten"))
        advanceTimeBy(30_000)
        runCurrent()
        // Counting down is not ringing: "stopp" belongs to the music now (§5).
        assertEquals(SockActivity.INACTIVE, fixture.sock.activityFor(sharedStop))
        assertEquals(SockResult.NotForMe, fixture.sock.handle(sharedStop))
        assertNotNull(fixture.timer)

        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(SockActivity.ACTIVE, fixture.sock.activityFor(sharedStop))
    }

    @Test
    fun `stop silences the chime and hands the audio channel back`() = runTest {
        val fixture = TimerFixture(this)
        fixture.start()
        fixture.sock.handle(setTimer(1, "minuten"))
        advanceTimeBy(60_000)
        runCurrent()

        val result = fixture.sock.handle(sharedStop)
        runCurrent()

        assertEquals(SockResult.Silent, result)
        assertNull(fixture.timer)
        assertNull(fixture.ctx.playback.duckedBy)
        assertEquals(SockActivity.INACTIVE, fixture.sock.activityFor(sharedStop))
    }

    @Test
    fun `onStop leaves nothing ringing`() = runTest {
        val fixture = TimerFixture(this)
        fixture.start()
        fixture.sock.handle(setTimer(1, "minuten"))
        advanceTimeBy(60_000)
        runCurrent()

        fixture.sock.onStop()
        runCurrent()

        assertNull(fixture.timer)
        assertNull(fixture.ctx.playback.duckedBy)
        assertTrue(fixture.chime.stops > 0)
    }

    @Test
    fun `the dashboard clock follows the wall clock`() = runTest {
        val fixture = TimerFixture(this)
        fixture.start()
        runCurrent()

        assertEquals(14, fixture.sock.state.value.now.hour)

        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, fixture.sock.state.value.now.minute)
    }

    @Test
    fun `a timer that outlived the process is picked up again`() = runTest {
        val first = TimerFixture(this)
        first.start()
        first.sock.handle(setTimer(10, "minuten"))
        runCurrent()

        // The process dies: no onStop, no cleanup — only what was written to config survives.
        advanceTimeBy(4 * 60 * 1000L)
        runCurrent()
        val restarted = TimerFixture(this)
        restarted.ctx.config.copyFrom(first.ctx.config)
        restarted.start()
        runCurrent()

        val resumed = assertNotNull(restarted.timer)
        assertEquals(first.timer?.endsAt, resumed.endsAt)
        assertEquals(6 * 60 * 1000L, resumed.remainingMs)

        advanceTimeBy(6 * 60 * 1000L)
        runCurrent()
        assertEquals(listOf(TimerEngine.TIMER_EXPIRED), restarted.ctx.announcements)
    }

    @Test
    fun `a timer that came due while the process was dead rings at once`() = runTest {
        val first = TimerFixture(this)
        first.start()
        first.sock.handle(setTimer(1, "minuten"))
        runCurrent()

        advanceTimeBy(90_000)
        val restarted = TimerFixture(this)
        restarted.ctx.config.copyFrom(first.ctx.config)
        restarted.start()
        runCurrent()

        assertEquals(listOf(TimerEngine.TIMER_EXPIRED), restarted.ctx.announcements)
        assertTrue(restarted.timer!!.isRinging)
    }

    @Test
    fun `a timer from hours ago is dropped rather than announced`() = runTest {
        val first = TimerFixture(this)
        first.start()
        first.sock.handle(setTimer(1, "minuten"))
        runCurrent()

        advanceTimeBy(3 * 60 * 60 * 1000L)
        val restarted = TimerFixture(this)
        restarted.ctx.config.copyFrom(first.ctx.config)
        restarted.start()
        runCurrent()

        assertNull(restarted.timer)
        assertEquals(emptyList(), restarted.ctx.announcements)
    }

    @Test
    fun `a cancelled timer leaves nothing behind to be resumed`() = runTest {
        val first = TimerFixture(this)
        first.start()
        first.sock.handle(setTimer(10, "minuten"))
        runCurrent()
        first.sock.handle(cancelTimer)
        runCurrent()

        val restarted = TimerFixture(this)
        restarted.ctx.config.copyFrom(first.ctx.config)
        restarted.start()
        runCurrent()

        assertNull(restarted.timer)
    }
}
