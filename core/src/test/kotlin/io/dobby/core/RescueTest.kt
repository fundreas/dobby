package io.dobby.core

import io.dobby.core.registry.SockRegistry
import io.dobby.core.sock.CommandInvocation
import io.dobby.core.sock.ExclusiveCommandSpec
import io.dobby.core.sock.FollowUp
import io.dobby.core.sock.ParamSpec
import io.dobby.core.sock.ParamType
import io.dobby.core.sock.Sock
import io.dobby.core.sock.SockResult
import io.dobby.core.sock.patterns
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The other end of the flywheel: what the filler-skipping pass rescued, and who is told.
 *
 * A fallthrough is an utterance that needs a template written for it. A rescue is one that did
 * *not*, because the matcher ignored a word that carried nothing — and the count of them is the
 * only evidence that M6c was worth doing. Both are recorded, both are bounded, and both are
 * cleared together.
 */
class RescueTest {

    /** A Sock with one closed template, so the filler pass is the only way past it. */
    private fun clock() = FixtureSock(
        id = "clock",
        commands = listOf(
            ExclusiveCommandSpec(
                id = "clock.whats_the_time",
                templates = patterns("wie (spät|viel uhr)"),
                description = "Sagt die Uhrzeit.",
            ),
        ),
        handler = { SockResult.Spoken("Es ist drei.") },
    )

    @Test
    fun `an utterance the strict pass missed is reported as a rescue`() = runTest {
        val rescues = mutableListOf<Rescue>()
        val dobby = DobbyEngine(SockRegistry.buildOrThrow(listOf(clock())), onRescue = { rescues += it })

        val outcome = dobby.handle("Wie spät ist es denn jetzt?")

        assertEquals("clock.whats_the_time", outcome.invocation?.commandId)
        assertEquals(Tier.TEMPLATE, outcome.tier, "a rescue is still Tier 1 — that is the point")
        assertEquals(
            listOf(Rescue("wie spät ist es denn jetzt", "clock.whats_the_time", "wie (spät|viel uhr)")),
            rescues,
        )
        // The normalized utterance, so the line reads the way the matcher saw it.
        assertEquals(
            "wie spät ist es denn jetzt → clock.whats_the_time via \"wie (spät|viel uhr)\"",
            rescues.single().toString(),
        )
    }

    @Test
    fun `an utterance the strict pass matched is not`() = runTest {
        val rescues = mutableListOf<Rescue>()
        val dobby = DobbyEngine(SockRegistry.buildOrThrow(listOf(clock())), onRescue = { rescues += it })

        dobby.handle("wie spät")

        assertEquals(emptyList(), rescues, "the first pass matched; nothing was skipped")
    }

    @Test
    fun `an utterance nothing matched is a fallthrough, not a rescue`() = runTest {
        val rescues = mutableListOf<Rescue>()
        val missed = mutableListOf<Fallthrough>()
        val dobby = DobbyEngine(
            SockRegistry.buildOrThrow(listOf(clock())),
            onFallthrough = { missed += it },
            onRescue = { rescues += it },
        )

        dobby.handle("wie wird das wetter morgen")

        assertEquals(emptyList(), rescues)
        assertEquals(listOf("wie wird das wetter morgen"), missed.map { it.utterance })
    }

    @Test
    fun `a question hears its answer through the same two passes`() = runTest {
        // "ja bitte" answers a question exactly the way "ja" does — the scoped palette is built
        // with the same filler list, because a question that hears fewer phrasings than the
        // palette around it is a trap.
        val rescues = mutableListOf<Rescue>()
        val dobby = DobbyEngine(SockRegistry.buildOrThrow(listOf(Asker())), onRescue = { rescues += it })

        dobby.handle("kaffee")
        val answer = dobby.handle("ja bitte")

        assertEquals(mapOf("yes" to "ja"), answer.invocation?.params)
        assertEquals("tok-1", answer.invocation?.answering)
        assertEquals(listOf("ja bitte"), rescues.map { it.utterance })
    }

    @Test
    fun `the log keeps both halves, bounded, and clears both at once`() {
        val log = FallthroughLog(capacity = 2)
        repeat(3) { i ->
            log.record(Fallthrough("miss $i", null))
            log.record(Rescue("rescue $i", "clock.whats_the_time", "wie spät"))
        }

        assertEquals(2, log.size)
        assertEquals(2, log.rescueCount)
        assertEquals(listOf("miss 1", "miss 2"), log.entries.value.map { it.utterance })
        assertEquals(listOf("rescue 1", "rescue 2"), log.rescues.value.map { it.utterance })

        log.clear()

        // Both, or it is not "clear" — the settings screen offers one button, not two.
        assertTrue(log.entries.value.isEmpty() && log.rescues.value.isEmpty())
    }

    /** Asks a yes/no question whose templates name only the content word. */
    private class Asker : Sock {
        override val id: String = "quiz"
        override val displayName: String = "Quiz"

        override val commands: List<ExclusiveCommandSpec> = listOf(
            ExclusiveCommandSpec(
                id = "quiz.order",
                templates = patterns("kaffee"),
                description = "Bestellt Kaffee.",
                params = listOf(ParamSpec("yes", ParamType.Text, required = false)),
            ),
        )

        override suspend fun handle(invocation: CommandInvocation): SockResult =
            if (invocation.answering != null) {
                SockResult.Spoken("Kommt sofort.")
            } else {
                SockResult.Asked(
                    text = "Soll ich?",
                    follow = FollowUp(
                        commandId = "quiz.order",
                        templates = patterns("{yes:enum}"),
                        params = listOf(ParamSpec("yes", ParamType.Enumeration(listOf("ja", "nein")))),
                        token = "tok-1",
                    ),
                )
            }
    }
}
