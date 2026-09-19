package io.dobby.socks.devi

import io.dobby.core.DobbyEngine
import io.dobby.core.registry.SockRegistry
import io.dobby.core.sock.CommandInvocation
import io.dobby.core.sock.SockResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DeviSockTest {

    private val devi = DeviSock()

    @Test
    fun `hello answers with the greeting`() = runTest {
        val result = devi.handle(CommandInvocation(DeviSock.HELLO))

        // Spoken, not Silent: core shows it in the chat and says it out loud.
        assertEquals(SockResult.Spoken("Hallo, Meister"), result)
    }

    @Test
    fun `registers cleanly`() {
        assertEquals(emptyList(), SockRegistry.build(listOf(devi)).errors)
        assertEquals(emptyList(), SockRegistry.buildOrThrow(listOf(devi)).checkExamples())
    }
}

/** The full Phase A path: raw text → normalize → match → dispatch → answer. */
class DeviEngineTest {

    private val fellThrough = mutableListOf<String>()
    private val engine = DobbyEngine(
        registry = SockRegistry.buildOrThrow(listOf(DeviSock())),
        onFallthrough = fellThrough::add,
    )

    @Test
    fun `hello reaches devi end to end`() = runTest {
        val outcome = engine.handle("Hello!")

        assertEquals("hello", outcome.normalized)
        assertEquals(DeviSock.HELLO, outcome.invocation?.commandId)
        assertEquals(SockResult.Spoken("Hallo, Meister"), outcome.result)
    }

    @Test
    fun `accepts the variants`() = runTest {
        for (utterance in listOf("hello", "Hallo Devi", "hello devi", "Hi Devi")) {
            assertEquals(SockResult.Spoken("Hallo, Meister"), engine.handle(utterance).result)
        }
    }

    @Test
    fun `tolerates an STT slip in the keyword`() = runTest {
        assertEquals(SockResult.Spoken("Hallo, Meister"), engine.handle("hallo devi").result)
    }

    @Test
    fun `an unmatched utterance is logged for the flywheel, not guessed at`() = runTest {
        val outcome = engine.handle("Spiele irgendwas von Queen")

        assertEquals(null, outcome.invocation)
        assertEquals(SockResult.Spoken(DobbyEngine.NOT_UNDERSTOOD), outcome.result)
        assertEquals(listOf("spiele irgendwas von queen"), fellThrough)
    }
}
