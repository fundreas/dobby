package io.dobby.socks.calculator

import io.dobby.core.DobbyEngine
import io.dobby.core.registry.SockRegistry
import io.dobby.core.sock.SockResult
import io.dobby.core.testing.FakeSockContext
import io.dobby.socks.clock.ClockSock
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Sock as somebody actually meets it: typed German in, spoken German out.
 *
 * Everything between is real — normalizer, palette ordering, dispatcher, and core's own
 * follow-up floor. These are the sequences from `calculator.specs.md` §4, and they are the
 * only tests that prove the memory and the question hold across utterances rather than
 * within one call.
 */
class CalculatorConversationTest {

    private val engine = DobbyEngine(
        SockRegistry.buildOrThrow(listOf(CalculatorSock(MutableClock()), ClockSock())),
    )

    private suspend fun say(utterance: String): String =
        when (val result = engine.handle(utterance).result) {
            is SockResult.Spoken -> result.text
            is SockResult.Asked -> result.text
            is SockResult.Failed -> result.userMessage
            else -> error("nothing was said in reply to \"$utterance\": $result")
        }

    private fun conversation(block: suspend () -> Unit) = runTest {
        engine.start(FakeSockContext())
        block()
        engine.stop()
    }

    @Test
    fun `scaling a recipe, one operand at a time`() = conversation {
        assertEquals("250 mal 4 ist 1000.", say("Wie viel ist 250 mal 4"))
        assertEquals("1000 geteilt durch 2 ist 500.", say("Und davon die Hälfte"))
        assertEquals("500 plus 125 ist 625.", say("Plus 125"))
        assertEquals("Das Ergebnis war 625.", say("Was war das Ergebnis"))
    }

    @Test
    fun `portioning, which is what modulo is actually for`() = conversation {
        // The question people really have is not "what is the remainder" but "how many whole
        // ones, and what is left over" — and it is one question, so it gets one answer.
        assertEquals("250 passt 4 mal in 1050, Rest 50.", say("Wie oft passt 250 in 1050"))
        assertEquals("Das Ergebnis war 4.", say("Was kam raus"))
    }

    @Test
    fun `the remainder on its own, when that is the number you want to keep`() = conversation {
        assertEquals("17 modulo 5 ist 2.", say("Was ist 17 modulo 5"))
        assertEquals("2 mal 3 ist 6.", say("Mal 3"))
    }

    @Test
    fun `a sum with its last number missing is finished in the next breath`() = conversation {
        assertEquals("3 plus was?", say("Wie viel ist 3 plus"))
        assertTrue(engine.awaitingAnswer)
        assertEquals("3 plus 5 ist 8.", say("5"))
        assertFalse(engine.awaitingAnswer)
    }

    @Test
    fun `a continuation with no memory behind it asks where to start`() = conversation {
        assertEquals("mal 2 — von welcher Zahl?", say("Mal 2"))
        assertTrue(engine.awaitingAnswer)
        assertEquals("8 mal 2 ist 16.", say("von 8"))
        // And the answer is itself a result, so the chain carries on from there.
        assertEquals("16 plus 4 ist 20.", say("Plus 4"))
    }

    @Test
    fun `an open question is never a trap`() = conversation {
        say("Wie viel ist 3 plus")
        assertTrue(engine.awaitingAnswer)

        // Saying something else entirely wins, the question is abandoned, and nothing
        // half-built fires later (`socks.specs/README.md` §5).
        assertEquals("20 geteilt durch 4 ist 5.", say("20 geteilt durch 4"))
        assertFalse(engine.awaitingAnswer)
        assertEquals("5 mal 3 ist 15.", say("Mal 3"))
    }

    @Test
    fun `a question Dobby asked does not survive the turn`() = conversation {
        say("Mal 2")
        assertTrue(engine.awaitingAnswer)

        engine.endTurn()

        assertFalse(engine.awaitingAnswer)
        // The wake word plus "8" a minute later must not complete a sum nobody remembers
        // starting. It is a fresh utterance, and on its own it is not one.
        assertEquals(DobbyEngine.NOT_UNDERSTOOD, say("8"))
    }

    @Test
    fun `what it will not do, it says in one sentence`() = conversation {
        assertEquals(Arithmetic.BY_ZERO, say("10 geteilt durch 0"))
        assertEquals(Arithmetic.TOO_BIG_RESULT, say("2 hoch 500"))
    }

    @Test
    fun `it does not answer for the clock`() = conversation {
        assertEquals("Timer läuft: 10 Minuten.", say("Stell einen Timer auf 10 Minuten"))
        assertEquals("10 was — Sekunden, Minuten oder Stunden?", say("Timer auf 10"))
    }
}
