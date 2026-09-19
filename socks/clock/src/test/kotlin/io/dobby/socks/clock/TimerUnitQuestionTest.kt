package io.dobby.socks.clock

import io.dobby.core.DobbyEngine
import io.dobby.core.registry.SockRegistry
import io.dobby.core.sock.CommandInvocation
import io.dobby.core.sock.SharedCommands
import io.dobby.core.sock.SockResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * "Stell einen Timer auf zehn" — the sentence German people really say, in full.
 *
 * Driven through [DobbyEngine] rather than through the Sock directly, because the interesting
 * half is the join: the amount is stashed on one `handle()` call and read on a second one, and
 * the two are only connected by a token core carries between them (`clock.specs.md` §3).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TimerUnitQuestionTest {

    private fun engineFor(fixture: TimerFixture) =
        DobbyEngine(SockRegistry.buildOrThrow(listOf(fixture.sock)))

    @Test
    fun `an amount with no unit is asked about, then set`() = runTest {
        val fixture = TimerFixture(this)
        fixture.start()
        val dobby = engineFor(fixture)

        val question = dobby.handle("stell einen Timer auf zehn")

        assertEquals(ClockSock.SET_TIMER, question.invocation?.commandId)
        assertEquals(mapOf("amount" to 10), question.invocation?.params)
        val asked = assertIs<SockResult.Asked>(question.result)
        assertEquals("10 was — Sekunden, Minuten oder Stunden?", asked.text)
        assertNull(fixture.timer, "nothing may start before the unit is known")

        val answer = dobby.handle("Minuten")
        runCurrent()

        assertEquals(SockResult.Spoken("Timer läuft: 10 Minuten."), answer.result)
        assertEquals(asked.follow.token, answer.invocation?.answering)
        assertEquals(600_000L, fixture.timer?.totalMs, "ten minutes, as if both had been said at once")
    }

    @Test
    fun `the preposition the question invites is heard too`() = runTest {
        val fixture = TimerFixture(this)
        fixture.start()
        val dobby = engineFor(fixture)
        dobby.handle("Timer 5")

        assertEquals(SockResult.Spoken("Timer läuft: 5 Minuten."), dobby.handle("in Minuten").result)
        runCurrent()
        assertEquals(300_000L, fixture.timer?.totalMs)
    }

    @Test
    fun `the answer obeys the singular rule the Sock already has`() = runTest {
        // "Timer läuft: 1 Minuten" is not a sentence anyone would say, and the two-utterance
        // path must not be the one place that forgets it.
        val fixture = TimerFixture(this)
        fixture.start()
        val dobby = engineFor(fixture)
        dobby.handle("stell einen Timer auf eins")

        assertEquals(SockResult.Spoken("Timer läuft: 1 Minute."), dobby.handle("Minute").result)
        runCurrent()
        assertEquals(60_000L, fixture.timer?.totalMs)
    }

    @Test
    fun `saying stopp instead of answering gets out of the question`() = runTest {
        val fixture = TimerFixture(this)
        fixture.start()
        val dobby = engineFor(fixture)
        dobby.handle("stell einen Timer auf zehn")

        val escape = dobby.handle("stopp")

        // Nothing is ringing, so Clock passes it down the chain and nobody consumes it — which
        // is the ordinary outcome and, crucially, not the timer being set anyway.
        assertEquals(SharedCommands.STOP.id, escape.invocation?.commandId)
        assertEquals(SharedCommands.STOP.unconsumedResponse, escape.result)
        assertFalse(dobby.awaitingAnswer)
        assertNull(fixture.timer)

        // The amount was dropped with the question: "minuten" now means nothing at all.
        assertFalse(dobby.handle("Minuten").wasUnderstood)
    }

    @Test
    fun `the turn ending drops the half-built timer`() = runTest {
        val fixture = TimerFixture(this)
        fixture.start()
        val dobby = engineFor(fixture)
        dobby.handle("Timer 90")

        dobby.endTurn()

        assertFalse(dobby.awaitingAnswer)
        assertFalse(dobby.handle("Sekunden").wasUnderstood)
        assertNull(fixture.timer)
    }

    @Test
    fun `an answer with no stashed amount fails rather than inventing one`() = runTest {
        // Only reachable if core and the Sock disagree about what is outstanding. The timer is
        // a kitchen timer: a duration it cannot reconstruct must not become a guessed one.
        val fixture = TimerFixture(this)
        fixture.start()

        val result = fixture.sock.handle(
            CommandInvocation(ClockSock.SET_TIMER, mapOf("unit" to "minuten"), answering = "nothing-like-it"),
        )

        assertEquals(SockResult.Failed(ClockSock.BAD_DURATION), result)
        assertNull(fixture.timer)
    }

    @Test
    fun `a unit-less amount out of range is still refused, just later`() = runTest {
        val fixture = TimerFixture(this)
        fixture.start()
        val dobby = engineFor(fixture)
        dobby.handle("Timer 20")

        // 20 hours is past the 12-hour kitchen-timer cap (§3).
        assertEquals(SockResult.Failed(ClockSock.BAD_DURATION), dobby.handle("Stunden").result)
        assertNull(fixture.timer)
    }

    @Test
    fun `the fuller phrasing still wins, so nothing is asked when nothing is missing`() = runTest {
        val fixture = TimerFixture(this)
        fixture.start()
        val dobby = engineFor(fixture)

        val outcome = dobby.handle("stell einen Timer auf 5 Minuten")
        runCurrent()

        assertTrue(outcome.result is SockResult.Spoken, "a complete sentence must not be questioned")
        assertFalse(dobby.awaitingAnswer)
        assertEquals(300_000L, fixture.timer?.totalMs)
    }
}
