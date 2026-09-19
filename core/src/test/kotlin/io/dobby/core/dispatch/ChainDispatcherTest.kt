package io.dobby.core.dispatch

import io.dobby.core.FixtureSock
import io.dobby.core.registry.SockRegistry
import io.dobby.core.sock.CommandInvocation
import io.dobby.core.sock.ExclusiveCommandSpec
import io.dobby.core.sock.SharedCommands
import io.dobby.core.sock.SharedSubscription
import io.dobby.core.sock.Sock
import io.dobby.core.sock.SockActivity
import io.dobby.core.sock.SockResult
import io.dobby.core.sock.patterns
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The chain matrix.
 *
 * Asserts *who consumed*, not just the observable result — "the music stopped" is true in
 * several of these scenarios for entirely different reasons.
 */
class ChainDispatcherTest {

    private val stop = CommandInvocation(SharedCommands.STOP.id)

    /** A Sock that subscribes to `shared.stop` and consumes only when it is ACTIVE. */
    private fun subscriber(
        id: String,
        priority: Int,
        activity: SockActivity,
        result: SockResult = SockResult.Silent,
    ) = FixtureSock(
        id = id,
        shared = listOf(SharedSubscription(SharedCommands.STOP, priority = priority)),
        activity = { activity },
        handler = { if (activity == SockActivity.ACTIVE) result else SockResult.NotForMe },
    )

    private fun dispatcher(vararg socks: Sock) = Dispatcher(SockRegistry.buildOrThrow(socks.toList()))

    // ---- ranking -------------------------------------------------------------------------

    @Test
    fun `active beats idle beats inactive, regardless of registration order`() = runTest {
        val inactive = subscriber("radio", priority = 50, activity = SockActivity.INACTIVE)
        val idle = subscriber("spotify", priority = 50, activity = SockActivity.IDLE)
        val active = FixtureSock(
            id = "clock",
            shared = listOf(SharedSubscription(SharedCommands.STOP, priority = 1)),
            activity = { SockActivity.ACTIVE },
            handler = { SockResult.Silent },
        )

        val outcome = dispatcher(inactive, idle, active).dispatch(stop)

        assertEquals("clock", outcome.consumedBy)
        assertEquals(listOf("clock"), outcome.trace.map { it.sockId })
    }

    @Test
    fun `priority breaks a tie between two active socks`() = runTest {
        // A ringing alarm beats a playing radio: both ACTIVE, clock declares 100.
        val radio = subscriber("radio", priority = 50, activity = SockActivity.ACTIVE)
        val clock = subscriber("clock", priority = 100, activity = SockActivity.ACTIVE)

        val outcome = dispatcher(radio, clock).dispatch(stop)

        assertEquals("clock", outcome.consumedBy)
        assertEquals(emptyList(), radio.handled, "FIRST_CONSUMER must short-circuit")
    }

    @Test
    fun `registration order is the final tiebreak`() = runTest {
        val first = subscriber("spotify", priority = 50, activity = SockActivity.ACTIVE)
        val second = subscriber("radio", priority = 50, activity = SockActivity.ACTIVE)

        assertEquals("spotify", dispatcher(first, second).dispatch(stop).consumedBy)
    }

    // ---- passing and the unconsumed case --------------------------------------------------

    @Test
    fun `NotForMe passes to the next sock`() = runTest {
        // Both IDLE, so priority decides who is asked first — the `shared.resume` shape.
        // Spotify has nothing paused and passes; radio has a station from this session.
        val spotify = FixtureSock(
            id = "spotify",
            shared = listOf(SharedSubscription(SharedCommands.STOP, priority = 50)),
            activity = { SockActivity.IDLE },
            handler = { SockResult.NotForMe },
        )
        val radio = FixtureSock(
            id = "radio",
            shared = listOf(SharedSubscription(SharedCommands.STOP, priority = 40)),
            activity = { SockActivity.IDLE },
            handler = { SockResult.Silent },
        )

        val outcome = dispatcher(spotify, radio).dispatch(stop)

        assertEquals("radio", outcome.consumedBy)
        assertEquals(
            listOf("spotify" to StepOutcome.PASSED, "radio" to StepOutcome.CONSUMED),
            outcome.trace.map { it.sockId to it.outcome },
            "the higher-priority sock must be asked first and pass",
        )
    }

    @Test
    fun `nobody consumes yields the declared response`() = runTest {
        val outcome = dispatcher(
            subscriber("spotify", priority = 50, activity = SockActivity.INACTIVE),
            subscriber("radio", priority = 50, activity = SockActivity.INACTIVE),
        ).dispatch(stop)

        assertEquals(SharedCommands.STOP.unconsumedResponse, outcome.result)
        assertNull(outcome.consumedBy)
        assertTrue(outcome.trace.all { it.outcome == StepOutcome.PASSED })
    }

    @Test
    fun `an inactive sock does no IO`() = runTest {
        val cold = FixtureSock(
            id = "spotify",
            shared = listOf(SharedSubscription(SharedCommands.STOP, priority = 50)),
            activity = { SockActivity.INACTIVE },
            handler = { SockResult.NotForMe },
        )

        dispatcher(cold).dispatch(stop)

        assertFalse(cold.performedIo, "an INACTIVE sock must pass without reconnecting to anything")
    }

    // ---- failure isolation ----------------------------------------------------------------

    @Test
    fun `a throwing sock does not block the chain`() = runTest {
        val broken = FixtureSock(
            id = "spotify",
            shared = listOf(SharedSubscription(SharedCommands.STOP, priority = 99)),
            activity = { SockActivity.ACTIVE },
            handler = { error("app remote exploded") },
        )
        val healthy = subscriber("radio", priority = 1, activity = SockActivity.ACTIVE)
        val dispatcher = dispatcher(broken, healthy)

        val outcome = dispatcher.dispatch(stop)

        assertEquals("radio", outcome.consumedBy)
        assertEquals(StepOutcome.FAILED, outcome.trace.first().outcome)
        assertTrue(dispatcher.health.reasonFor("spotify")!!.contains("app remote exploded"))
    }

    @Test
    fun `a hanging sock times out and the chain continues`() = runTest {
        val slow = FixtureSock(
            id = "spotify",
            shared = listOf(SharedSubscription(SharedCommands.STOP, priority = 99)),
            activity = { SockActivity.ACTIVE },
            handler = {
                delay(60.seconds)
                SockResult.Silent
            },
        )
        val healthy = subscriber("radio", priority = 1, activity = SockActivity.ACTIVE)
        val dispatcher = Dispatcher(
            SockRegistry.buildOrThrow(listOf(slow, healthy)),
            timeout = 5.seconds,
        )

        val outcome = dispatcher.dispatch(stop)

        assertEquals(StepOutcome.TIMED_OUT, outcome.trace.first().outcome)
        assertEquals("radio", outcome.consumedBy)
        assertEquals("Zeitüberschreitung", dispatcher.health.reasonFor("spotify"))
    }

    // ---- the scenarios the design exists for -----------------------------------------------

    @Test
    fun `timer ringing over playing music - clock consumes, spotify is never asked`() = runTest {
        val spotify = subscriber("spotify", priority = 50, activity = SockActivity.ACTIVE)
        val clock = subscriber("clock", priority = 100, activity = SockActivity.ACTIVE)

        val outcome = dispatcher(spotify, clock).dispatch(stop)

        assertEquals("clock", outcome.consumedBy)
        assertEquals(emptyList(), spotify.handled)
    }

    @Test
    fun `timer merely counting down - spotify pauses and the timer survives`() = runTest {
        // Clock reports INACTIVE while counting: "stopp" must not cancel a running timer.
        val clock = subscriber("clock", priority = 100, activity = SockActivity.INACTIVE)
        val spotify = subscriber("spotify", priority = 50, activity = SockActivity.ACTIVE)

        val outcome = dispatcher(clock, spotify).dispatch(stop)

        assertEquals("spotify", outcome.consumedBy)
        // Clock is not merely passed over — being INACTIVE, it is never asked at all, even
        // though it holds the highest priority in this chain.
        assertEquals(emptyList(), clock.handled)
        assertEquals(listOf("spotify"), outcome.trace.map { it.sockId })
    }

    // ---- exclusive dispatch -----------------------------------------------------------------

    @Test
    fun `an exclusive command goes straight to its owner`() = runTest {
        val devi = FixtureSock(
            id = "devi",
            commands = listOf(ExclusiveCommandSpec("devi.hello", patterns("hello"), "test")),
            handler = { SockResult.Spoken("hi") },
        )

        val outcome = dispatcher(devi).dispatch(CommandInvocation("devi.hello"))

        assertEquals(SockResult.Spoken("hi"), outcome.result)
        assertEquals("devi", outcome.consumedBy)
    }

    @Test
    fun `NotForMe from an exclusive command is a bug, not a routing hint`() = runTest {
        val devi = FixtureSock(
            id = "devi",
            commands = listOf(ExclusiveCommandSpec("devi.hello", patterns("hello"), "test")),
            handler = { SockResult.NotForMe },
        )

        val outcome = dispatcher(devi).dispatch(CommandInvocation("devi.hello"))

        assertTrue(outcome.result is SockResult.Failed)
        assertEquals(StepOutcome.FAILED, outcome.trace.single().outcome)
    }

    @Test
    fun `an unknown command fails rather than throwing`() = runTest {
        val outcome = dispatcher(subscriber("radio", 1, SockActivity.ACTIVE))
            .dispatch(CommandInvocation("ghost.command"))

        assertTrue(outcome.result is SockResult.Failed)
    }

    // ---- answers ----------------------------------------------------------------------------

    @Test
    fun `an answer reaches the Sock that asked, not the Sock that owns the id`() = runTest {
        // Two Socks, and the command id belongs to the one that did not ask. Ordinary dispatch
        // would resolve the owner and deliver the answer to a Sock with no idea what question
        // it is answering — so the answer path skips ownerOf entirely.
        val owner = FixtureSock(
            id = "radio",
            commands = listOf(ExclusiveCommandSpec("radio.play", patterns("radio"), "test")),
            handler = { SockResult.Spoken("owner") },
        )
        val asker = FixtureSock(
            id = "spotify",
            commands = listOf(ExclusiveCommandSpec("spotify.play_music", patterns("musik"), "test")),
            handler = { SockResult.Spoken("asker") },
        )
        val dispatcher = dispatcher(owner, asker)

        val outcome = dispatcher.dispatchAnswer(
            asker,
            CommandInvocation("radio.play", answering = "tok-1"),
        )

        assertEquals(SockResult.Spoken("asker"), outcome.result)
        assertEquals("spotify", outcome.consumedBy)
        assertEquals(emptyList(), owner.handled)
        assertEquals("answering tok-1", outcome.trace.single().detail)
    }

    @Test
    fun `an answer onto a shared id does not walk the chain`() = runTest {
        // Even a `shared.*` id goes straight back to the asker. The chain decides who a
        // *command* belongs to; an answer already has an addressee.
        val asker = FixtureSock(
            id = "clock",
            shared = listOf(SharedSubscription(SharedCommands.STOP, priority = 1)),
            activity = { SockActivity.INACTIVE },
            handler = { SockResult.Spoken("asker") },
        )
        val other = subscriber("radio", priority = 100, activity = SockActivity.ACTIVE)

        val outcome = dispatcher(other, asker)
            .dispatchAnswer(asker, stop.copy(answering = "tok-1"))

        assertEquals("clock", outcome.consumedBy)
        assertEquals(emptyList(), other.handled, "the chain was never offered the answer")
    }

    @Test
    fun `an asker that throws degrades exactly like any other handler`() = runTest {
        val asker = FixtureSock(
            id = "spotify",
            commands = listOf(ExclusiveCommandSpec("spotify.play_music", patterns("musik"), "test")),
            handler = { error("app remote exploded") },
        )
        val dispatcher = dispatcher(asker)

        val outcome = dispatcher.dispatchAnswer(
            asker,
            CommandInvocation("spotify.play_music", answering = "tok-1"),
        )

        assertTrue(outcome.result is SockResult.Failed)
        assertEquals(StepOutcome.FAILED, outcome.trace.single().outcome)
        assertTrue(outcome.trace.single().detail!!.startsWith("answering tok-1: "))
        assertTrue(dispatcher.health.reasonFor("spotify")!!.contains("app remote exploded"))
    }

    @Test
    fun `an asker that hangs times out under the same deadline`() = runTest {
        val asker = FixtureSock(
            id = "spotify",
            commands = listOf(ExclusiveCommandSpec("spotify.play_music", patterns("musik"), "test")),
            handler = {
                delay(60.seconds)
                SockResult.Silent
            },
        )
        val dispatcher = Dispatcher(SockRegistry.buildOrThrow(listOf(asker)), timeout = 5.seconds)

        val outcome = dispatcher.dispatchAnswer(
            asker,
            CommandInvocation("spotify.play_music", answering = "tok-1"),
        )

        assertEquals(StepOutcome.TIMED_OUT, outcome.trace.single().outcome)
        assertEquals("answering tok-1", outcome.trace.single().detail)
        assertEquals("Zeitüberschreitung", dispatcher.health.reasonFor("spotify"))
    }
}
