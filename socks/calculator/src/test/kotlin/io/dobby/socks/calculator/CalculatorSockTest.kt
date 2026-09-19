package io.dobby.socks.calculator

import io.dobby.core.registry.SockRegistry
import io.dobby.core.sock.CommandInvocation
import io.dobby.core.sock.SockResult
import io.dobby.core.testing.FakeConfigStore
import io.dobby.core.testing.FakeSockContext
import kotlinx.coroutines.test.runTest
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `calculator.specs.md` §3–§6: what the handler does once a template has resolved. */
class CalculatorSockTest {

    private val clock = MutableClock()
    private val context = FakeSockContext()

    private fun calculate(a: Int, op: Operation, b: Int? = null) = CommandInvocation(
        CalculatorSock.CALCULATE,
        buildMap {
            put("a", a)
            put("op", op.value)
            if (b != null) put("b", b)
        },
    )

    private fun step(op: Operation, b: Int) =
        CommandInvocation(CalculatorSock.CONTINUE_WITH, mapOf("op" to op.value, "b" to b))

    private suspend fun started(): CalculatorSock =
        CalculatorSock(clock).also { it.onStart(context) }

    @Test
    fun `speaks the equation back with its answer`() = runTest {
        val sock = started()

        assertEquals(
            SockResult.Spoken("3 plus 5 ist 8."),
            sock.handle(calculate(3, Operation.PLUS, 5)),
        )
    }

    @Test
    fun `the result carries into the next utterance`() = runTest {
        val sock = started()
        sock.handle(calculate(250, Operation.TIMES, 4))

        // The answer names the number it continued from, which is the only way somebody who
        // said two words can tell that it continued from the right one.
        assertEquals(
            SockResult.Spoken("1000 geteilt durch 2 ist 500."),
            sock.handle(step(Operation.DIVIDE, 2)),
        )
        assertEquals(
            SockResult.Spoken("150 passt 3 mal in 500, Rest 50."),
            sock.handle(step(Operation.INT_DIVIDE, 150)),
        )
    }

    @Test
    fun `a continuation with nothing to continue from asks instead of refusing`() = runTest {
        val sock = started()

        val asked = sock.handle(step(Operation.TIMES, 2))
        assertIs<SockResult.Asked>(asked)
        assertEquals("mal 2 — von welcher Zahl?", asked.text)
        assertEquals(CalculatorSock.CONTINUE_WITH, asked.follow.commandId)

        val answer = CommandInvocation(
            CalculatorSock.CONTINUE_WITH,
            mapOf("a" to 8),
            answering = asked.follow.token,
        )
        assertEquals(SockResult.Spoken("8 mal 2 ist 16."), sock.handle(answer))
    }

    @Test
    fun `a sum missing its last number asks for it`() = runTest {
        val sock = started()

        val asked = sock.handle(calculate(3, Operation.PLUS))
        assertIs<SockResult.Asked>(asked)
        assertEquals("3 plus was?", asked.text)
        assertEquals(CalculatorSock.CALCULATE, asked.follow.commandId)

        val answer = CommandInvocation(
            CalculatorSock.CALCULATE,
            mapOf("b" to 5),
            answering = asked.follow.token,
        )
        assertEquals(SockResult.Spoken("3 plus 5 ist 8."), sock.handle(answer))
    }

    @Test
    fun `an answer to a question that was already dropped does not invent a sum`() = runTest {
        val sock = started()
        val asked = sock.handle(calculate(3, Operation.PLUS))
        assertIs<SockResult.Asked>(asked)

        sock.onAskCancelled(asked.follow.token)

        val late = CommandInvocation(
            CalculatorSock.CALCULATE,
            mapOf("b" to 5),
            answering = asked.follow.token,
        )
        assertEquals(SockResult.Failed(CalculatorSock.LOST_THE_THREAD), sock.handle(late))
    }

    @Test
    fun `a stale result is asked about rather than used`() = runTest {
        val sock = started()
        sock.handle(calculate(3, Operation.PLUS, 5))

        clock.advance(ResultMemory.DEFAULT_TTL.plusSeconds(1))

        // The number is an hour old and nobody in the room is still thinking about it. One
        // short question can never be wrong; confidently multiplying it can.
        assertIs<SockResult.Asked>(sock.handle(step(Operation.TIMES, 2)))
    }

    @Test
    fun `a fresh result is still there after a pause`() = runTest {
        val sock = started()
        sock.handle(calculate(3, Operation.PLUS, 5))

        clock.advance(Duration.ofMinutes(9))

        assertEquals(SockResult.Spoken("8 mal 2 ist 16."), sock.handle(step(Operation.TIMES, 2)))
    }

    @Test
    fun `the memory's lifetime is configurable`() = runTest {
        val sock = CalculatorSock(clock)
        sock.onStart(FakeSockContext(config = FakeConfigStore(mapOf(ResultMemory.CONFIG_KEY to "1"))))
        sock.handle(calculate(3, Operation.PLUS, 5))

        clock.advance(Duration.ofMinutes(2))

        assertIs<SockResult.Asked>(sock.handle(step(Operation.TIMES, 2)))
    }

    @Test
    fun `a refused sum costs nothing that was already remembered`() = runTest {
        val sock = started()
        sock.handle(calculate(3, Operation.PLUS, 5))

        assertEquals(
            SockResult.Failed(Arithmetic.BY_ZERO),
            sock.handle(step(Operation.DIVIDE, 0)),
        )

        // Still 8: a division by zero must not also take away the number somebody was
        // working with.
        assertEquals(SockResult.Spoken("Das Ergebnis war 8."), sock.handle(recall()))
    }

    @Test
    fun `repeating and forgetting the last result`() = runTest {
        val sock = started()

        assertEquals(SockResult.Spoken(CalculatorSock.NOTHING_YET), sock.handle(recall()))
        assertEquals(SockResult.Spoken(CalculatorSock.NOTHING_TO_FORGET), sock.handle(clear()))

        sock.handle(calculate(10, Operation.DIVIDE, 3))
        assertEquals(SockResult.Spoken("Das Ergebnis war 3,3333."), sock.handle(recall()))

        assertEquals(SockResult.Spoken("Vergessen."), sock.handle(clear()))
        assertEquals(SockResult.Spoken(CalculatorSock.NOTHING_YET), sock.handle(recall()))
        assertNull(sock.state.value.result)
    }

    @Test
    fun `what the panel draws is the sentence that was spoken`() = runTest {
        val sock = started()
        sock.handle(calculate(7, Operation.TIMES, 8))

        assertEquals("7 mal 8 ist 56.", sock.state.value.equation)
        assertEquals(56.0, sock.state.value.result)
        assertEquals(listOf(CalculatorSock.DEFAULT_SCREEN_WAKE_SECONDS), context.screen.wakeRequests)
    }

    @Test
    fun `works before onStart, just without waking the screen`() = runTest {
        // The dispatcher should never call handle() on a Sock it did not start, but a missing
        // context must not take the answer down with it.
        val sock = CalculatorSock(clock)

        assertEquals(
            SockResult.Spoken("3 plus 5 ist 8."),
            sock.handle(calculate(3, Operation.PLUS, 5)),
        )
    }

    @Test
    fun `refuses a command it does not own`() = runTest {
        val sock = started()
        assertEquals(SockResult.NotForMe, sock.handle(CommandInvocation("clock.whats_the_time")))
    }

    @Test
    fun `declares exactly the commands the spec lists`() {
        assertEquals(
            listOf(
                CalculatorSock.CALCULATE,
                CalculatorSock.CONTINUE_WITH,
                CalculatorSock.LAST_RESULT,
                CalculatorSock.CLEAR,
            ),
            CalculatorSock().commands.map { it.id },
        )
    }

    @Test
    fun `subscribes to no chain, because it is never the thing that is running`() {
        assertTrue(CalculatorSock().shared.isEmpty())
        // And so a bare "stopp" never reaches it.
        assertNull(SockRegistry.buildOrThrow(listOf(CalculatorSock())).chainFor("shared.stop"))
    }

    private fun recall() = CommandInvocation(CalculatorSock.LAST_RESULT)

    private fun clear() = CommandInvocation(CalculatorSock.CLEAR)
}
