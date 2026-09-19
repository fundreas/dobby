package io.dobby.socks.clock

import io.dobby.core.DobbyEngine
import io.dobby.core.dispatch.Dispatcher
import io.dobby.core.registry.SockRegistry
import io.dobby.core.sock.CommandInvocation
import io.dobby.core.sock.ExclusiveCommandSpec
import io.dobby.core.sock.SharedCommands
import io.dobby.core.sock.SharedSubscription
import io.dobby.core.sock.Sock
import io.dobby.core.sock.SockActivity
import io.dobby.core.sock.SockResult
import io.dobby.core.sock.patterns
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `shared.stop` with a second subscriber in the chain (`clock.specs.md` §11).
 *
 * The music Sock here stands in for Spotify and Radio, which do not exist yet: what is being
 * tested is the chain, and the chain only cares that somebody else is `ACTIVE` and has a
 * handler that must not be invoked when Clock wins.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ClockChainTest {

    /** Priority 50, `ACTIVE` while a track plays — Spotify's and Radio's row in the catalog. */
    private class MusicSock : Sock {
        override val id: String = "music"
        override val displayName: String = "Musik"

        var playing: Boolean = true
        var handleCalls: Int = 0

        override val commands: List<ExclusiveCommandSpec> = listOf(
            ExclusiveCommandSpec(
                id = "music.pause",
                templates = patterns("(musik|wiedergabe) (aus|pausieren)"),
                description = "Pausiert die Musik.",
            ),
        )

        override val shared: List<SharedSubscription> = listOf(
            SharedSubscription(SharedCommands.STOP, priority = 50),
        )

        override fun activityFor(invocation: CommandInvocation): SockActivity =
            if (playing) SockActivity.ACTIVE else SockActivity.INACTIVE

        override suspend fun handle(invocation: CommandInvocation): SockResult {
            handleCalls++
            if (!playing) return SockResult.NotForMe
            playing = false
            return SockResult.Silent
        }
    }

    private class Chain(val clock: TimerFixture, val music: MusicSock) {
        val registry: SockRegistry = SockRegistry.buildOrThrow(listOf(clock.sock, music))
        val engine: DobbyEngine = DobbyEngine(registry, Dispatcher(registry))
    }

    private suspend fun chainOf(fixture: TimerFixture): Chain {
        val chain = Chain(fixture, MusicSock())
        chain.engine.start(fixture.ctx)
        return chain
    }

    @Test
    fun `a ringing chime wins stopp, and the music comes back to full volume`() = runTest {
        val fixture = TimerFixture(this)
        val chain = chainOf(fixture)
        chain.engine.handle("Timer eine Minute")
        advanceTimeBy(60_000)
        runCurrent()
        assertTrue(fixture.timer!!.isRinging)
        assertEquals("clock", fixture.ctx.playback.duckedBy)

        val outcome = chain.engine.handle("stopp")
        runCurrent()

        assertEquals("clock", outcome.consumedBy)
        // FIRST_CONSUMER short-circuits: the music Sock is never even asked.
        assertEquals(0, chain.music.handleCalls)
        assertTrue(chain.music.playing)
        assertNull(fixture.ctx.playback.duckedBy)
        assertNull(fixture.timer)
    }

    @Test
    fun `a counting timer does not touch stopp - the music pauses and the timer runs on`() = runTest {
        val fixture = TimerFixture(this)
        val chain = chainOf(fixture)
        chain.engine.handle("Timer zehn Minuten")
        advanceTimeBy(60_000)
        runCurrent()

        val outcome = chain.engine.handle("stopp")
        runCurrent()

        assertEquals("music", outcome.consumedBy)
        assertTrue(!chain.music.playing)
        assertNotNull(fixture.timer)

        // And it still fires, nine minutes later.
        advanceTimeBy(9 * 60 * 1000L)
        runCurrent()
        assertEquals(listOf(TimerEngine.TIMER_EXPIRED), fixture.ctx.announcements)
    }

    @Test
    fun `timer stopp cancels the timer whatever the chain is doing`() = runTest {
        val fixture = TimerFixture(this)
        val chain = chainOf(fixture)
        chain.engine.handle("Timer zehn Minuten")
        runCurrent()

        val outcome = chain.engine.handle("Timer stopp")
        runCurrent()

        assertEquals(ClockSock.CANCEL_TIMER, outcome.invocation?.commandId)
        assertEquals(SockResult.Spoken("Timer abgebrochen."), outcome.result)
        // Never offered to the chain, so the music is untouched.
        assertEquals(0, chain.music.handleCalls)
        assertTrue(chain.music.playing)
        assertNull(fixture.timer)
    }

    @Test
    fun `with nothing running at all, nobody consumes`() = runTest {
        val fixture = TimerFixture(this)
        val chain = chainOf(fixture)
        chain.music.playing = false

        val outcome = chain.engine.handle("stopp")

        assertNull(outcome.consumedBy)
        assertEquals(SharedCommands.STOP.unconsumedResponse, outcome.result)
    }

    @Test
    fun `the chime phrasings ride on the chain and only work while it rings`() = runTest {
        val fixture = TimerFixture(this)
        val chain = chainOf(fixture)
        chain.music.playing = false
        chain.engine.handle("Timer eine Minute")
        advanceTimeBy(60_000)
        runCurrent()

        val outcome = chain.engine.handle("ich hab's gehört")
        runCurrent()

        assertEquals(SharedCommands.STOP.id, outcome.invocation?.commandId)
        assertEquals("clock", outcome.consumedBy)
        assertNull(fixture.timer)

        // Said into a quiet room it reaches the chain and finds nobody.
        assertEquals(SharedCommands.STOP.unconsumedResponse, chain.engine.handle("ja ja").result)
    }

    @Test
    fun `an INACTIVE Sock never touches the audio channel`() = runTest {
        val fixture = TimerFixture(this)
        val chain = chainOf(fixture)
        chain.engine.handle("Timer zehn Minuten")
        runCurrent()

        chain.engine.handle("stopp")
        runCurrent()

        // Clock passed without doing any I/O: it never asked for focus (§4 of the Sock contract).
        assertEquals(emptyList(), fixture.ctx.playback.transientRequests)
        assertEquals(emptyList(), fixture.chime.strokes)
    }
}

/** The engine outcome's consumer, which only the chain trace knows. */
private val io.dobby.core.EngineOutcome.consumedBy: String?
    get() = trace.firstOrNull { it.outcome == io.dobby.core.dispatch.StepOutcome.CONSUMED }?.sockId
