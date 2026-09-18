package io.dobby.socks.help

import io.dobby.core.DobbyEngine
import io.dobby.core.registry.Introspection
import io.dobby.core.registry.SockRegistry
import io.dobby.core.sock.CommandInvocation
import io.dobby.core.sock.Example
import io.dobby.core.sock.ExclusiveCommandSpec
import io.dobby.core.sock.Sock
import io.dobby.core.sock.SockResult
import io.dobby.core.sock.patterns
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A stand-in product Sock, so these tests do not depend on the real ones. */
private class StubSock(
    override val id: String,
    override val displayName: String,
    override val commands: List<ExclusiveCommandSpec> = emptyList(),
) : Sock {
    override suspend fun handle(invocation: CommandInvocation) = SockResult.Silent
}

private fun clockLike() = StubSock(
    id = "clock",
    displayName = "Uhr",
    commands = listOf(
        ExclusiveCommandSpec(
            "clock.whats_the_time",
            patterns("wie spät ist es"),
            "Sagt die aktuelle Uhrzeit.",
            examples = listOf(Example("wie spät ist es")),
        ),
        ExclusiveCommandSpec(
            "clock.set_timer",
            patterns("timer 10 minuten"),
            "Stellt einen Timer.",
            examples = listOf(Example("timer zehn minuten")),
        ),
    ),
)

private fun deviLike() = StubSock(
    id = "devi",
    displayName = "Devi",
    commands = listOf(
        ExclusiveCommandSpec(
            "devi.hello",
            patterns("hello"),
            "Begrüßt Devi.",
            examples = listOf(Example("hello")),
        ),
    ),
)

/** Builds a registry, then binds the help Sock to it — the chicken-and-egg wiring. */
private fun wire(vararg others: Sock): Pair<DobbyEngine, HelpSock> {
    var directory: Introspection? = null
    val help = HelpSock { directory }
    val registry = SockRegistry.buildOrThrow(others.toList() + help)
    directory = Introspection(registry)
    return DobbyEngine(registry) to help
}

class HelpSockTest {

    @Test
    fun `overview names the areas and suggests a next question`() = runTest {
        val (engine, _) = wire(clockLike(), deviLike())

        val outcome = engine.handle("Was kannst du?")

        assertEquals(HelpSock.OVERVIEW, outcome.invocation?.commandId)
        val spoken = (outcome.result as SockResult.Spoken).text
        assertEquals(
            "Ich habe 3 Bereiche: Devi, Hilfe und Uhr. Frag zum Beispiel: Was kann Devi?",
            spoken,
        )
    }

    @Test
    fun `overview copes with a single area`() = runTest {
        // Help is always present, so one area means help is the only Sock installed.
        val (engine, _) = wire()

        val spoken = (engine.handle("hilfe").result as SockResult.Spoken).text

        assertEquals("Ich habe einen Bereich: Hilfe. Frag: Was kann Hilfe?", spoken)
    }

    @Test
    fun `a sock's detail offers things to say, not command ids`() = runTest {
        val (engine, _) = wire(clockLike(), deviLike())

        val outcome = engine.handle("Was kann die Uhr?")

        assertEquals(HelpSock.SOCK_COMMANDS, outcome.invocation?.commandId)
        assertEquals("uhr", outcome.invocation?.textOrNull("sock"))
        assertEquals(
            "Uhr hat 2 Befehle. Sag zum Beispiel: ‚wie spät ist es‘ und " +
                "‚timer zehn minuten‘.",
            (outcome.result as SockResult.Spoken).text,
        )
    }

    @Test
    fun `singular reads naturally`() = runTest {
        val (engine, _) = wire(clockLike(), deviLike())

        val spoken = (engine.handle("was kann devi").result as SockResult.Spoken).text

        assertContains(spoken, "Devi hat einen Befehl.")
    }

    @Test
    fun `a long sock is capped, because reading twenty commands aloud is useless`() = runTest {
        val many = StubSock(
            id = "many",
            displayName = "Viele",
            commands = (1..7).map {
                ExclusiveCommandSpec(
                    "many.cmd$it",
                    patterns("befehl nummer $it"),
                    "Test $it.",
                    examples = listOf(Example("befehl nummer $it")),
                )
            },
        )
        val (engine, _) = wire(many)

        val spoken = (engine.handle("was kann viele").result as SockResult.Spoken).text

        assertContains(spoken, "Viele hat 7 Befehle.")
        assertContains(spoken, "Und 4 weitere.")
    }

    @Test
    fun `an unknown area is answered with what does exist`() = runTest {
        val (engine, _) = wire(clockLike(), deviLike())

        val spoken = (engine.handle("was kann das wetter").result as SockResult.Spoken).text

        assertEquals("Den Bereich kenne ich nicht. Ich habe: Devi, Hilfe und Uhr.", spoken)
    }

    @Test
    fun `resolves an area through articles and STT slips`() = runTest {
        val (engine, _) = wire(clockLike(), deviLike())

        for (utterance in listOf("was kann die uhr", "was kann uhr", "was kann der uhr", "hilfe zu uhr")) {
            val spoken = (engine.handle(utterance).result as SockResult.Spoken).text
            assertContains(spoken, "Uhr hat 2 Befehle.", message = "for \"$utterance\"")
        }
    }

    @Test
    fun `overview and detail do not shadow each other`() = runTest {
        val (engine, _) = wire(clockLike(), deviLike())

        assertEquals(HelpSock.OVERVIEW, engine.handle("was kannst du").invocation?.commandId)
        assertEquals(HelpSock.OVERVIEW, engine.handle("was kannst du alles").invocation?.commandId)
        assertEquals(HelpSock.SOCK_COMMANDS, engine.handle("was kann die uhr").invocation?.commandId)
        assertEquals(HelpSock.SOCK_COMMANDS, engine.handle("was kann die uhr alles").invocation?.commandId)
    }

    @Test
    fun `does not swallow ordinary utterances`() = runTest {
        val (engine, _) = wire(clockLike(), deviLike())

        for (utterance in listOf("wie spät ist es", "hello", "spiele musik")) {
            val id = engine.handle(utterance).invocation?.commandId
            assertTrue(id == null || !id.startsWith("help."), "\"$utterance\" was swallowed by help: $id")
        }
    }

    @Test
    fun `reports honestly when it has not been bound to a registry`() = runTest {
        val unbound = HelpSock { null }

        val result = unbound.handle(CommandInvocation(HelpSock.OVERVIEW))

        assertTrue(result is SockResult.Failed)
    }

    @Test
    fun `registers cleanly and its own examples resolve to it`() {
        var directory: Introspection? = null
        val registry = SockRegistry.buildOrThrow(listOf(clockLike(), HelpSock { directory }))
        directory = Introspection(registry)

        assertEquals(emptyList(), registry.checkExamples())
        assertTrue(directory.sockIds().contains("help"))
    }
}
