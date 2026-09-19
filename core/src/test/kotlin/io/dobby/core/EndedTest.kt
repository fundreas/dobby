package io.dobby.core

import io.dobby.core.registry.SockRegistry
import io.dobby.core.sock.ExclusiveCommandSpec
import io.dobby.core.sock.SockResult
import io.dobby.core.sock.patterns
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The result that closes the microphone.
 *
 * [SockResult.Ended] is the mirror of [SockResult.Asked]: one keeps the floor, the other gives
 * it back. Core's part in both is small but load-bearing — it must hand the result up exactly
 * as the Sock wrote it, because the layer above reads it to decide whether to keep listening,
 * and a result quietly rewritten on the way through is a panel that ignores what a Sock said.
 */
class EndedTest {

    private fun goodnight(result: SockResult) = FixtureSock(
        id = "night",
        commands = listOf(
            ExclusiveCommandSpec(
                id = "night.goodnight",
                templates = patterns("gute nacht"),
                description = "Beendet das Gespräch.",
            ),
        ),
        handler = { result },
    )

    @Test
    fun `a Sock that ends the conversation is understood, and says so unchanged`() = runTest {
        val sock = goodnight(SockResult.Ended("Gute Nacht."))
        val engine = DobbyEngine(SockRegistry.buildOrThrow(listOf(sock)))

        val outcome = engine.handle("Gute Nacht")

        assertTrue(outcome.wasUnderstood, "it matched a command like any other")
        assertEquals("night.goodnight", outcome.invocation?.commandId)
        // Not turned into Spoken on the way through: the difference between the two is the
        // whole message, and it is carried by the type rather than by the sentence.
        assertEquals(SockResult.Ended("Gute Nacht."), outcome.result)
        assertFalse(engine.awaitingAnswer, "ending a turn is the opposite of taking the floor")
    }

    @Test
    fun `it can end the turn without saying anything at all`() = runTest {
        // "Danke" deserves an answer. A panel dismissed with a wave does not, and a Sock has to
        // be able to say so without core inventing a sentence to fill the silence.
        val engine = DobbyEngine(SockRegistry.buildOrThrow(listOf(goodnight(SockResult.Ended()))))

        val outcome = engine.handle("gute nacht")

        assertEquals(SockResult.Ended(null), outcome.result)
    }
}
