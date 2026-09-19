package io.dobby.socks.devi

import io.dobby.core.DobbyEngine
import io.dobby.core.registry.SockRegistry
import io.dobby.core.sock.CommandInvocation
import io.dobby.core.sock.SockResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeviSockTest {

    private val printed = mutableListOf<String>()
    private val devi = DeviSock(printed::add)

    @Test
    fun `hello prints the greeting`() = runTest {
        val result = devi.handle(CommandInvocation(DeviSock.HELLO))

        assertEquals(listOf("Hallo, Meister"), printed)
        // Silent, not Spoken: the printing is the feedback.
        assertEquals(SockResult.Silent, result)
    }

    @Test
    fun `registers cleanly`() {
        assertEquals(emptyList(), SockRegistry.build(listOf(devi)).errors)
        assertEquals(emptyList(), SockRegistry.buildOrThrow(listOf(devi)).checkExamples())
    }
}

/** The full Phase A path: raw text → normalize → match → dispatch → side effect. */
class DeviEngineTest {

    private val printed = mutableListOf<String>()
    private val fellThrough = mutableListOf<String>()
    private val engine = DobbyEngine(
        registry = SockRegistry.buildOrThrow(listOf(DeviSock(printed::add))),
        onFallthrough = fellThrough::add,
    )

    @Test
    fun `hello reaches devi end to end`() = runTest {
        val outcome = engine.handle("Hello!")

        assertEquals("hello", outcome.normalized)
        assertEquals(DeviSock.HELLO, outcome.invocation?.commandId)
        assertEquals(listOf("Hallo, Meister"), printed)
        assertEquals(SockResult.Silent, outcome.result)
    }

    @Test
    fun `accepts the variants`() = runTest {
        for (utterance in listOf("hello", "Hallo Devi", "hello devi", "Hi Devi")) {
            engine.handle(utterance)
        }
        assertEquals(List(4) { "Hallo, Meister" }, printed)
    }

    @Test
    fun `tolerates an STT slip in the keyword`() = runTest {
        engine.handle("hallo devi")
        assertEquals(listOf("Hallo, Meister"), printed)
    }

    @Test
    fun `an unmatched utterance is logged for the flywheel, not guessed at`() = runTest {
        val outcome = engine.handle("Spiele irgendwas von Queen")

        assertEquals(null, outcome.invocation)
        assertEquals(SockResult.Spoken(DobbyEngine.NOT_UNDERSTOOD), outcome.result)
        assertEquals(listOf("spiele irgendwas von queen"), fellThrough)
        assertTrue(printed.isEmpty())
    }
}
