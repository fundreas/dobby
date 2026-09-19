package io.dobby.socks.clock

import io.dobby.core.DobbyEngine
import io.dobby.core.registry.SockRegistry
import io.dobby.core.sock.CommandInvocation
import io.dobby.core.sock.SharedCommands
import io.dobby.core.sock.SockResult
import io.dobby.core.sock.SockStatus
import io.dobby.core.testing.FakeSockContext
import kotlinx.coroutines.test.runTest
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClockSockTest {

    private val vienna = ZoneId.of("Europe/Vienna")

    /** A clock frozen at a wall-clock time in Vienna. */
    private fun frozenAt(iso: String) =
        Clock.fixed(Instant.parse(iso), vienna)

    private val context = FakeSockContext()

    @Test
    fun `answers with the current time`() = runTest {
        // 13:30 UTC in winter is 14:30 in Vienna.
        val sock = ClockSock(frozenAt("2026-01-15T13:30:00Z"))
        sock.onStart(context)

        val result = sock.handle(CommandInvocation(ClockSock.WHATS_THE_TIME))

        assertEquals(SockResult.Spoken("Es ist halb 3."), result)
    }

    @Test
    fun `reads the time in the device's own zone, not UTC`() = runTest {
        val sock = ClockSock(frozenAt("2026-07-15T12:00:00Z")) // summer: Vienna is UTC+2
        sock.onStart(context)

        assertEquals(
            SockResult.Spoken("Es ist 14 Uhr."),
            sock.handle(CommandInvocation(ClockSock.WHATS_THE_TIME)),
        )
    }

    @Test
    fun `wakes the screen, because the dashboard clock is the other half of the answer`() = runTest {
        val sock = ClockSock(frozenAt("2026-01-15T13:00:00Z"))
        sock.onStart(context)

        sock.handle(CommandInvocation(ClockSock.WHATS_THE_TIME))

        assertEquals(listOf(ClockSock.DEFAULT_SCREEN_WAKE_SECONDS), context.screen.wakeRequests)
    }

    @Test
    fun `works before onStart, just without waking the screen`() = runTest {
        // The dispatcher should never call handle() on a Sock that was not started, but a
        // missing context must not take the answer down with it.
        val sock = ClockSock(frozenAt("2026-01-15T13:00:00Z"))

        val result = sock.handle(CommandInvocation(ClockSock.WHATS_THE_TIME))

        assertEquals(SockResult.Spoken("Es ist 14 Uhr."), result)
    }

    @Test
    fun `registers cleanly and every declared example resolves to it`() {
        val registry = SockRegistry.buildOrThrow(listOf(ClockSock()))
        assertEquals(emptyList(), registry.checkExamples())
    }

    @Test
    fun `declares exactly the commands the spec lists`() {
        val sock = ClockSock()
        assertEquals(
            listOf(ClockSock.SET_TIMER, ClockSock.CANCEL_TIMER, ClockSock.WHATS_THE_TIME),
            sock.commands.map { it.id },
        )
        // One chain, at the catalog's highest priority: a ringing alarm outranks the radio (§5).
        assertEquals(listOf(SharedCommands.STOP.id), sock.shared.map { it.command.id })
        assertEquals(listOf(ClockSock.STOP_PRIORITY), sock.shared.map { it.priority })
    }

    @Test
    fun `is never Unavailable`() {
        // A clock with no permissions is still a clock (§10).
        assertEquals(SockStatus.Ready, ClockSock().status.value)
    }
}

/** The full path: raw text → normalize → match → dispatch → spoken answer. */
class ClockEngineTest {

    private val sock = ClockSock(Clock.fixed(Instant.parse("2026-01-15T08:45:00Z"), ZoneId.of("Europe/Vienna")))
    private val engine = DobbyEngine(SockRegistry.buildOrThrow(listOf(sock)))

    @Test
    fun `wie spaet ist es`() = runTest {
        val outcome = engine.handle("Wie spät ist es?")

        assertEquals(ClockSock.WHATS_THE_TIME, outcome.invocation?.commandId)
        assertEquals(SockResult.Spoken("Es ist viertel vor 10."), outcome.result)
    }

    @Test
    fun `all the phrasings reach it`() = runTest {
        for (utterance in listOf(
            "wie spät ist es",
            "Wie viel Uhr ist es",
            "wie spät",
            "Uhrzeit",
            "sag mir die Uhrzeit",
            "sag die Uhrzeit",
            "Was ist die Uhrzeit?",
        )) {
            val outcome = engine.handle(utterance)
            assertEquals(ClockSock.WHATS_THE_TIME, outcome.invocation?.commandId, utterance)
        }
    }

    @Test
    fun `tolerates an STT slip in a keyword`() = runTest {
        assertEquals(ClockSock.WHATS_THE_TIME, engine.handle("wie spet ist es").invocation?.commandId)
        assertEquals(ClockSock.WHATS_THE_TIME, engine.handle("urzeit").invocation?.commandId)
    }

    @Test
    fun `does not answer questions it was not asked`() = runTest {
        for (utterance in listOf("wie warm ist es", "wann fährt der nächste bus")) {
            val outcome = engine.handle(utterance)
            assertTrue(outcome.invocation == null, "\"$utterance\" wrongly reached ${outcome.invocation?.commandId}")
        }
    }

    @Test
    fun `bare stopp goes to the chain, not to the Clock`() = runTest {
        // Clock subscribes to shared.stop, so the utterance is understood — and then passed on,
        // because nothing is ringing.
        val outcome = engine.handle("stopp")

        assertEquals(SharedCommands.STOP.id, outcome.invocation?.commandId)
        assertEquals(SharedCommands.STOP.unconsumedResponse, outcome.result)
    }
}
