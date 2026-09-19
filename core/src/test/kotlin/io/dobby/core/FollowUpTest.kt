package io.dobby.core

import io.dobby.core.registry.SockRegistry
import io.dobby.core.sock.CommandInvocation
import io.dobby.core.sock.ExclusiveCommandSpec
import io.dobby.core.sock.FollowUp
import io.dobby.core.sock.ParamSpec
import io.dobby.core.sock.ParamType
import io.dobby.core.sock.SharedCommands
import io.dobby.core.sock.SharedSubscription
import io.dobby.core.sock.Sock
import io.dobby.core.sock.SockActivity
import io.dobby.core.sock.SockResult
import io.dobby.core.sock.TemplatePattern
import io.dobby.core.sock.patterns
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The dialogue turn: a Sock asks, core holds the floor, the next utterance answers.
 *
 * Everything here is pure JVM, and deliberately so — the whole reason the pending ask lives in
 * [DobbyEngine] rather than in the Android controller is that the awkward cases (a question
 * abandoned mid-way, a Sock that asks forever, a follow-up that does not compile) are the ones
 * nobody would exercise by talking to a wall panel.
 */
class FollowUpTest {

    /** A Sock that answers "kaffee" with a question, and remembers what it asked about. */
    private class AskingSock(
        override val id: String = "quiz",
        private val answer: (CommandInvocation) -> SockResult = { SockResult.Spoken("Schwarz.") },
        private val followTemplates: List<TemplatePattern> = patterns("{choice:enum}"),
        private val followParams: List<ParamSpec> =
            listOf(ParamSpec("choice", ParamType.Enumeration(listOf("milch", "schwarz")))),
        private val followCommandId: String? = null,
    ) : Sock {
        override val displayName: String get() = id

        val cancelled: MutableList<String> = mutableListOf()
        var nextToken: String = "tok-1"

        override val commands: List<ExclusiveCommandSpec> = listOf(
            ExclusiveCommandSpec(
                id = "$id.order",
                templates = patterns("kaffee"),
                description = "Bestellt Kaffee.",
                params = listOf(ParamSpec("choice", ParamType.Enumeration(listOf("milch", "schwarz")), required = false)),
            ),
        )

        override suspend fun handle(invocation: CommandInvocation): SockResult {
            if (invocation.answering != null) return answer(invocation)
            return SockResult.Asked(
                text = "Mit Milch oder schwarz?",
                follow = FollowUp(
                    commandId = followCommandId ?: "$id.order",
                    templates = followTemplates,
                    params = followParams,
                    token = nextToken,
                ),
            )
        }

        override suspend fun onAskCancelled(token: String) {
            cancelled += token
        }
    }

    /** "stopp" — the global escape hatch, present in every one of these scenarios. */
    private fun stopper() = FixtureSock(
        id = "radio",
        shared = listOf(SharedSubscription(SharedCommands.STOP)),
        activity = { SockActivity.ACTIVE },
        handler = { SockResult.Silent },
    )

    private fun engine(vararg socks: Sock) = DobbyEngine(SockRegistry.buildOrThrow(socks.toList()))

    // ---- the happy path ---------------------------------------------------------------------

    @Test
    fun `a question holds the floor and the next utterance answers it`() = runTest {
        val quiz = AskingSock()
        val dobby = engine(quiz, stopper())

        val question = dobby.handle("kaffee")

        assertIs<SockResult.Asked>(question.result)
        assertEquals("Mit Milch oder schwarz?", (question.result as SockResult.Asked).text)
        assertTrue(dobby.awaitingAnswer)

        val answer = dobby.handle("milch")

        assertEquals(SockResult.Spoken("Schwarz."), answer.result)
        assertEquals("quiz.order", answer.invocation?.commandId)
        assertEquals(mapOf("choice" to "milch"), answer.invocation?.params)
        assertEquals("tok-1", answer.invocation?.answering, "the Sock has to know what it is answering")
        assertFalse(dobby.awaitingAnswer, "the floor is given back once the answer lands")
        assertEquals(emptyList(), quiz.cancelled)
    }

    @Test
    fun `the trace says the utterance was an answer, not a command somebody happened to say`() =
        runTest {
            val dobby = engine(AskingSock())
            dobby.handle("kaffee")

            val answer = dobby.handle("milch")

            assertEquals(listOf("answering tok-1"), answer.trace.map { it.detail })
        }

    // ---- the scoped palette against the global one -------------------------------------------

    @Test
    fun `the scoped palette wins over a global template for the same words`() = runTest {
        // "milch" is a command in its own right here. While a question is open it is the
        // answer to that question — which is the entire reason the scoped palette is tried
        // first, rather than merged into the global one.
        val fridge = FixtureSock(
            id = "fridge",
            commands = listOf(
                ExclusiveCommandSpec(
                    id = "fridge.shopping",
                    templates = patterns("milch"),
                    description = "Setzt Milch auf die Liste.",
                ),
            ),
            handler = { SockResult.Spoken("Auf die Liste.") },
        )
        val quiz = AskingSock()
        val dobby = engine(quiz, fridge)

        assertEquals("fridge.shopping", dobby.handle("milch").invocation?.commandId)
        fridge.handled.clear()

        dobby.handle("kaffee")
        val answer = dobby.handle("milch")

        assertEquals("quiz.order", answer.invocation?.commandId)
        assertEquals(emptyList(), fridge.handled, "the fridge was never asked")
    }

    @Test
    fun `a bare text slot is legal in a scoped palette, where it can only hear one utterance`() =
        runTest {
            // The registry rejects this outright: a global template that matches everything
            // swallows the whole palette. Scoped, it is exactly what "Wie soll er heißen?"
            // needs, and it is only ever tried against the utterance that answers.
            val quiz = AskingSock(
                followTemplates = patterns("{choice}"),
                followParams = listOf(ParamSpec("choice", ParamType.Text)),
                answer = { SockResult.Spoken("Heißt ${it.text("choice")}.") },
            )
            val dobby = engine(quiz)

            dobby.handle("kaffee")

            assertEquals(SockResult.Spoken("Heißt der grosse braune."), dobby.handle("der grosse braune").result)
        }

    // ---- the escape hatch ---------------------------------------------------------------------

    @Test
    fun `a global command still gets through, and abandons the question`() = runTest {
        // Without this the panel is a trap: somebody stuck inside "Meinst du …?" could say
        // nothing — not "stopp", not "lauter" — that got them out of it.
        val quiz = AskingSock()
        val radio = stopper()
        val dobby = engine(quiz, radio)
        dobby.handle("kaffee")

        val escape = dobby.handle("stopp")

        assertEquals(SharedCommands.STOP.id, escape.invocation?.commandId)
        assertEquals(listOf(SharedCommands.STOP.id), radio.handled)
        assertFalse(dobby.awaitingAnswer)
        assertEquals(listOf("tok-1"), quiz.cancelled, "the Sock must be told to drop what it reserved")
    }

    @Test
    fun `an utterance neither palette knows leaves the question standing`() = runTest {
        val quiz = AskingSock()
        val dobby = engine(quiz, stopper())
        dobby.handle("kaffee")

        val lost = dobby.handle("mach mir ein sandwich")

        assertFalse(lost.wasUnderstood)
        assertEquals(SockResult.Spoken(DobbyEngine.NOT_UNDERSTOOD), lost.result)
        assertTrue(dobby.awaitingAnswer, "they are most likely still answering, just not in words we know")
        assertEquals(emptyList(), quiz.cancelled)

        // And the next try still reaches the question.
        assertEquals("quiz.order", dobby.handle("schwarz").invocation?.commandId)
    }

    // ---- the turn is the lifetime --------------------------------------------------------------

    @Test
    fun `the turn ending cancels the question exactly once`() = runTest {
        val quiz = AskingSock()
        val dobby = engine(quiz)
        dobby.handle("kaffee")

        dobby.endTurn()
        dobby.endTurn()

        assertFalse(dobby.awaitingAnswer)
        assertEquals(listOf("tok-1"), quiz.cancelled)
    }

    @Test
    fun `a question never survives its turn`() = runTest {
        // The wake word plus "ja" three minutes later must not fire whatever was half-built
        // when the conversation was abandoned.
        val quiz = AskingSock()
        val dobby = engine(quiz)
        dobby.handle("kaffee")
        dobby.endTurn()

        val later = dobby.handle("milch")

        assertFalse(later.wasUnderstood, "the scoped palette died with the turn")
    }

    @Test
    fun `an answered question is not cancelled as well`() = runTest {
        val quiz = AskingSock()
        val dobby = engine(quiz)
        dobby.handle("kaffee")
        dobby.handle("milch")

        dobby.endTurn()

        assertEquals(emptyList(), quiz.cancelled)
    }

    // ---- bounds and malformed questions --------------------------------------------------------

    @Test
    fun `a Sock cannot interrogate the room`() = runTest {
        // Every answer produces another question. Legitimate twice; past that it is a bug in
        // the Sock, and the failure mode is a panel that will not let a kitchen go.
        val quiz = object : Sock {
            override val id: String = "quiz"
            override val displayName: String = "Quiz"
            var asks = 0

            override val commands: List<ExclusiveCommandSpec> = listOf(
                ExclusiveCommandSpec(
                    id = "quiz.order",
                    templates = patterns("kaffee"),
                    description = "Fragt endlos.",
                    params = listOf(ParamSpec("choice", ParamType.Enumeration(listOf("ja")), required = false)),
                ),
            )

            val cancelled: MutableList<String> = mutableListOf()

            override suspend fun handle(invocation: CommandInvocation): SockResult {
                asks++
                return SockResult.Asked(
                    "Und dann?",
                    FollowUp(
                        commandId = "quiz.order",
                        templates = patterns("{choice:enum}"),
                        params = listOf(ParamSpec("choice", ParamType.Enumeration(listOf("ja")))),
                        token = "tok-$asks",
                    ),
                )
            }

            override suspend fun onAskCancelled(token: String) {
                cancelled += token
            }
        }
        val dobby = engine(quiz)

        assertIs<SockResult.Asked>(dobby.handle("kaffee").result)
        assertIs<SockResult.Asked>(dobby.handle("ja").result)

        val third = dobby.handle("ja")

        assertEquals(SockResult.Spoken("Und dann?"), third.result, "still said out loud, just not holding the floor")
        assertFalse(dobby.awaitingAnswer)
        assertEquals(listOf("tok-3"), quiz.cancelled)
    }

    @Test
    fun `a follow-up that does not compile degrades to plain speech`() = runTest {
        val quiz = AskingSock(followTemplates = listOf(TemplatePattern("(milch|schwarz")))
        val dobby = engine(quiz)

        val question = dobby.handle("kaffee")

        assertEquals(SockResult.Spoken("Mit Milch oder schwarz?"), question.result)
        assertFalse(dobby.awaitingAnswer, "a malformed question is asked, it just holds no floor")
        assertEquals(listOf("tok-1"), quiz.cancelled)
    }

    @Test
    fun `a follow-up onto somebody else's command is refused`() = runTest {
        // The answer is routed straight back to the asker, so a foreign command id would
        // deliver it to a Sock that never asked anything.
        val quiz = AskingSock(followCommandId = "radio.play")
        val dobby = engine(quiz, stopper())

        val question = dobby.handle("kaffee")

        assertEquals(SockResult.Spoken("Mit Milch oder schwarz?"), question.result)
        assertFalse(dobby.awaitingAnswer)
        assertEquals(listOf("tok-1"), quiz.cancelled)
    }

    @Test
    fun `an asker that blows up leaves nothing wedged`() = runTest {
        val quiz = AskingSock(answer = { error("boom") })
        val dobby = engine(quiz)
        dobby.handle("kaffee")

        val answer = dobby.handle("milch")

        assertIs<SockResult.Failed>(answer.result)
        assertFalse(dobby.awaitingAnswer, "a broken answer still ends the question")
        // Nothing to cancel: the ask was consumed by the utterance that blew up.
        assertEquals(emptyList(), quiz.cancelled)
    }
}
