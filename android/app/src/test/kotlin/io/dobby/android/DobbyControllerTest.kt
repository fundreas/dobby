package io.dobby.android

import io.dobby.android.chat.Voice
import io.dobby.core.testing.FakeSockContext
import io.dobby.pipeline.VoiceIo
import io.dobby.pipeline.VoiceState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The join between the microphone and Phase A.
 *
 * This is the one piece of Phase B that neither `:core`'s tests nor the Socks' tests cover,
 * and the one the chat view is a direct rendering of. It is testable at all because the
 * hardware sits behind [VoiceIo] and the Sock context behind a factory — which is the same
 * trick Phase A used to test Socks without an emulator, applied one layer up.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DobbyControllerTest {

    private class FakeVoice(var heard: String? = null) : VoiceIo {
        // Starts where the real pipeline starts: nothing is loaded yet.
        private val _state = MutableStateFlow<VoiceState>(VoiceState.Preparing("Starte…"))
        override val state: StateFlow<VoiceState> = _state.asStateFlow()
        override var canListen: Boolean = true

        val spoken: MutableList<String> = mutableListOf()
        var prepared: Boolean = false
        var shutdownCalls: Int = 0

        override suspend fun prepare() {
            prepared = true
            _state.value = VoiceState.Ready
        }

        override suspend fun listen(): String? {
            _state.value = VoiceState.Listening("")
            _state.value = VoiceState.Ready
            return heard
        }

        override suspend fun say(text: String) {
            spoken += text
        }

        override fun shutdown() {
            shutdownCalls++
        }
    }

    private fun TestScope.controller(voice: FakeVoice, context: FakeSockContext = FakeSockContext()) =
        DobbyController(backgroundScope, voice) { context }

    /**
     * The UI state, after letting the flow that builds it actually run.
     *
     * `state` is a `stateIn` over three flows, so it is produced by a coroutine of its own;
     * on a test dispatcher that coroutine only runs when the scheduler is told to.
     */
    private fun TestScope.uiState(dobby: DobbyController): DobbyUiState {
        testScheduler.runCurrent()
        return dobby.state.value
    }

    @Test
    fun `an utterance reaches the Sock and its answer is both shown and spoken`() = runTest {
        val voice = FakeVoice(heard = "wie spät ist es")
        val dobby = controller(voice)
        dobby.start()

        dobby.listen().join()

        val messages = uiState(dobby).messages
        assertEquals("wie spät ist es", messages.first { it.voice == Voice.USER }.text)

        val answer = messages.last { it.voice == Voice.DOBBY }
        assertTrue(answer.text.startsWith("Es ist"), "Clock answered: ${answer.text}")
        assertEquals("clock.whats_the_time · clock CONSUMED", answer.detail)

        // Shown and spoken must be the same sentence — a chat view that paraphrases what came
        // out of the speaker is worse than no chat view.
        assertEquals(listOf(answer.text), voice.spoken)
    }

    @Test
    fun `the Sock reaches the real SockContext through the join`() = runTest {
        val context = FakeSockContext()
        val dobby = controller(FakeVoice(heard = "wie spät ist es"), context)
        dobby.start()

        dobby.listen().join()

        // Clock wakes the screen when it answers. That it happened here proves the context
        // built in Phase B is the one handed to onStart, not a second one.
        assertEquals(listOf(ClockScreenWakeSeconds), context.screen.wakeRequests)
    }

    @Test
    fun `saying nothing leaves the transcript untouched`() = runTest {
        val voice = FakeVoice(heard = null)
        val dobby = controller(voice)
        dobby.start()

        dobby.listen().join()

        assertEquals(emptyList(), uiState(dobby).messages)
        assertEquals(emptyList(), voice.spoken)
    }

    @Test
    fun `an utterance no Sock claims is answered, not swallowed`() = runTest {
        val voice = FakeVoice(heard = "mach mir ein sandwich")
        val dobby = controller(voice)
        dobby.start()

        dobby.listen().join()

        val answer = uiState(dobby).messages.last { it.voice == Voice.DOBBY }
        assertEquals("Das habe ich nicht verstanden.", answer.text)
        // Nothing matched, so there is no command to show under it.
        assertEquals(null, answer.detail)
        assertEquals(listOf(answer.text), voice.spoken)
    }

    @Test
    fun `typing takes exactly the same path as speaking`() = runTest {
        val voice = FakeVoice()
        val dobby = controller(voice)
        dobby.start()

        dobby.submit("  wie spät ist es  ").join()

        val messages = uiState(dobby).messages
        assertEquals("wie spät ist es", messages.first { it.voice == Voice.USER }.text)
        assertEquals(
            "clock.whats_the_time · clock CONSUMED",
            messages.last { it.voice == Voice.DOBBY }.detail,
        )
        assertEquals(1, voice.spoken.size)
    }

    @Test
    fun `a Sock's own output lands in the chat, since there is no stdout on a wall`() = runTest {
        // Devi prints its greeting through the `out` callback rather than returning it.
        val dobby = controller(FakeVoice())
        dobby.start()

        dobby.submit("hello").join()

        val note = uiState(dobby).messages.firstOrNull { it.voice == Voice.SYSTEM }
        assertNotNull(note, "the development Sock's output should be visible")
        assertEquals("hello Devi", note.text)
    }

    @Test
    fun `the status line reports what Dobby is doing`() = runTest {
        val voice = FakeVoice(heard = "wie spät ist es")
        val dobby = controller(voice)

        assertEquals(Phase.PREPARING, uiState(dobby).phase)
        dobby.start()
        assertEquals(Phase.READY, uiState(dobby).phase)
        assertTrue(uiState(dobby).canListen)

        voice.canListen = false
        dobby.listen().join()
        assertTrue(!uiState(dobby).canListen, "the mic button must go dead without speech input")
    }

    @Test
    fun `stopping releases the hardware`() = runTest {
        val voice = FakeVoice()
        val dobby = controller(voice)
        dobby.start()
        assertTrue(voice.prepared)

        dobby.stop()

        assertEquals(1, voice.shutdownCalls)
    }

    private companion object {
        /** ClockSock's default; asserted rather than imported to keep this a black-box test. */
        const val ClockScreenWakeSeconds = 30
    }
}
