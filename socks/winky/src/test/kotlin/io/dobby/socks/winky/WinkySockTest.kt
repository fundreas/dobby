package io.dobby.socks.winky

import io.dobby.core.DobbyEngine
import io.dobby.core.registry.SockRegistry
import io.dobby.core.sock.CommandInvocation
import io.dobby.core.sock.SockResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class WinkySockTest {

    private val winky = WinkySock()

    @Test
    fun `hello answers with the greeting`() = runTest {
        val result = winky.handle(CommandInvocation(WinkySock.HELLO))

        // Spoken, not Silent: core shows it in the chat and says it out loud.
        assertEquals(SockResult.Spoken("Hallo Meister"), result)
    }

    @Test
    fun `registers cleanly`() {
        assertEquals(emptyList(), SockRegistry.build(listOf(winky)).errors)
        assertEquals(emptyList(), SockRegistry.buildOrThrow(listOf(winky)).checkExamples())
    }
}

/** The full Phase A path: raw text → normalize → match → dispatch → answer. */
class WinkyEngineTest {

    private val fellThrough = mutableListOf<String>()
    private val engine = DobbyEngine(
        registry = SockRegistry.buildOrThrow(listOf(WinkySock())),
        // The callback now carries what Tier 2 made of the utterance too; this suite only
        // cares that the utterance reached the flywheel at all.
        onFallthrough = { fellThrough += it.utterance },
    )

    @Test
    fun `hello reaches winky end to end`() = runTest {
        val outcome = engine.handle("Hello!")

        assertEquals("hello", outcome.normalized)
        assertEquals(WinkySock.HELLO, outcome.invocation?.commandId)
        assertEquals(SockResult.Spoken("Hallo Meister"), outcome.result)
    }

    @Test
    fun `accepts the variants`() = runTest {
        for (utterance in listOf("hello", "Hallo Winky", "hello winky", "Hi Winky")) {
            assertEquals(SockResult.Spoken("Hallo Meister"), engine.handle(utterance).result)
        }
    }

    @Test
    fun `tolerates an STT slip in the keyword`() = runTest {
        assertEquals(SockResult.Spoken("Hallo Meister"), engine.handle("hallo winky").result)
    }

    @Test
    fun `an unmatched utterance is logged for the flywheel, not guessed at`() = runTest {
        val outcome = engine.handle("Spiele irgendwas von Queen")

        assertEquals(null, outcome.invocation)
        assertEquals(SockResult.Spoken(DobbyEngine.NOT_UNDERSTOOD), outcome.result)
        assertEquals(listOf("spiele irgendwas von queen"), fellThrough)
    }
}
