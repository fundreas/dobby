package io.dobby.android

import io.dobby.android.chat.Voice
import io.dobby.core.testing.FakeSockContext
import io.dobby.pipeline.VoiceIo
import io.dobby.pipeline.VoiceState
import io.dobby.pipeline.wakeword.WakeWordOption
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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

        private val _wakeWords = MutableSharedFlow<Float>(extraBufferCapacity = 1)
        override val wakeWords: SharedFlow<Float> = _wakeWords.asSharedFlow()

        private val _handsFree = MutableStateFlow(false)
        override val handsFree: StateFlow<Boolean> = _handsFree.asStateFlow()

        override var wakePhrase: String? = "Hey Dobby"

        override var selectedWakeWordId: String? = null
            private set

        override fun wakeWordOptions(): List<WakeWordOption> = listOf(
            WakeWordOption("hey_dobby", "Hey Dobby", "hey_dobby.onnx", "", ""),
            WakeWordOption("hey_jarvis", "Hey Jarvis", "hey_jarvis_v0.1.onnx", "", ""),
        )

        override suspend fun selectWakeWord(id: String) {
            val option = wakeWordOptions().first { it.id == id }
            selectedWakeWordId = id
            wakePhrase = option.phrase
            if (_handsFree.value) _state.value = VoiceState.Waiting(option.phrase)
        }

        override fun startHandsFree(): Boolean {
            if (wakePhrase == null) return false
            _handsFree.value = true
            _state.value = VoiceState.Waiting(wakePhrase!!)
            return true
        }

        override fun stopHandsFree() {
            _handsFree.value = false
            _state.value = VoiceState.Ready
        }

        /** Pretends someone said the wake phrase. */
        fun sayWakeWord(score: Float = 0.9f) {
            _wakeWords.tryEmit(score)
        }

        /** Drives the pipeline state directly, for the states a whole turn passes through. */
        fun emit(state: VoiceState) {
            _state.value = state
        }

        override suspend fun prepare() {
            prepared = true
            _state.value = VoiceState.Ready
        }

        /** Set while [listen] is running: the wake word is off the stream until [endTurn]. */
        var turnOpen: Boolean = false
            private set

        val turnsEnded: MutableList<Boolean> = mutableListOf()

        override suspend fun listen(): String? {
            turnOpen = true
            _state.value = VoiceState.Listening(speaking = false)
            _state.value = VoiceState.Listening(speaking = true)
            _state.value = VoiceState.Transcribing
            _state.value = VoiceState.Ready
            return heard
        }

        override fun endTurn() {
            turnsEnded += turnOpen
            turnOpen = false
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
     * Runs everything the controller has queued and waits for it to go quiet.
     *
     * The controller does its work in coroutines of its own — the `stateIn` that builds the UI
     * state, and the collector that turns a wake word into a turn. Both live in a background
     * scope, and `advanceUntilIdle` alone does not drive those to completion, so the two are
     * interleaved until nothing is left. Stated once here rather than sprinkled through the
     * tests as scheduler incantations nobody can explain.
     */
    private fun TestScope.settle() {
        repeat(3) {
            testScheduler.runCurrent()
            testScheduler.advanceUntilIdle()
        }
    }

    private fun TestScope.uiState(dobby: DobbyController): DobbyUiState {
        settle()
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
        assertEquals("Hallo, Meister", note.text)
    }

    @Test
    fun `the status line reports what Dobby is doing`() = runTest {
        val voice = FakeVoice(heard = "wie spät ist es")
        val dobby = controller(voice)

        assertEquals(Phase.PREPARING, uiState(dobby).phase)
        dobby.start()
        // Armed on start: the product is a panel you talk to, so WAITING is the resting state.
        assertEquals(Phase.WAITING, uiState(dobby).phase)
        assertTrue(uiState(dobby).canListen)

        dobby.setHandsFree(false)
        assertEquals(Phase.READY, uiState(dobby).phase)

        voice.canListen = false
        dobby.listen().join()
        assertTrue(!uiState(dobby).canListen, "the mic button must go dead without speech input")
    }

    @Test
    fun `the wake word takes the same turn the button takes`() = runTest {
        val voice = FakeVoice(heard = "wie spät ist es")
        val context = FakeSockContext()
        val dobby = controller(voice, context)
        dobby.start()
        // start() launches the wake-word collector, and a SharedFlow with no subscriber yet
        // drops what it is given — so let it subscribe before speaking.
        settle()

        voice.sayWakeWord()
        settle()

        val messages = uiState(dobby).messages
        assertEquals("wie spät ist es", messages.first { it.voice == Voice.USER }.text)
        assertEquals(
            "clock.whats_the_time · clock CONSUMED",
            messages.last { it.voice == Voice.DOBBY }.detail,
        )
        assertEquals(1, voice.spoken.size)
    }

    @Test
    fun `hearing the wake word wakes the screen`() = runTest {
        // The panel's screen is off almost always, so being heard has to become visible. The
        // wake lock lives in SockContext (§7.2), which is why this is the controller's job and
        // not the pipeline's.
        val voice = FakeVoice(heard = "wie spät ist es")
        val context = FakeSockContext()
        val dobby = controller(voice, context)
        dobby.start()
        // start() launches the wake-word collector, and a SharedFlow with no subscriber yet
        // drops what it is given — so let it subscribe before speaking.
        settle()

        voice.sayWakeWord()
        settle()

        assertEquals(
            WAKE_SCREEN_SECONDS,
            context.screen.wakeRequests.first(),
            "the wake word wakes the screen first; Clock's own request follows when it answers",
        )
    }

    @Test
    fun `hands-free is armed on start, and can be switched off`() = runTest {
        val voice = FakeVoice()
        val dobby = controller(voice)
        dobby.start()

        assertTrue(uiState(dobby).handsFree, "the panel should be listening for its name")
        assertEquals(Phase.WAITING, uiState(dobby).phase)
        assertEquals("Hey Dobby", uiState(dobby).wakePhrase)

        dobby.setHandsFree(false)
        assertTrue(!uiState(dobby).handsFree)
        assertEquals(Phase.READY, uiState(dobby).phase)
    }

    @Test
    fun `without a wake word model the panel stays on push-to-talk`() = runTest {
        val voice = FakeVoice().apply { wakePhrase = null }
        val dobby = controller(voice)
        dobby.start()

        assertTrue(!uiState(dobby).handsFree)
        assertEquals(null, uiState(dobby).wakePhrase)
        // Still fully usable by button and by typing.
        assertTrue(uiState(dobby).canListen)
    }

    @Test
    fun `every turn is closed, so the wake word always comes back`() = runTest {
        // The wake word leaves the microphone stream for the duration of a turn (§2 invariant
        // 4). Nothing puts it back but endTurn, so a path that forgets to call it is a panel
        // that stops answering to its name — and says nothing about why.
        val voice = FakeVoice(heard = "wie spät ist es")
        val dobby = controller(voice)
        dobby.start()

        dobby.listen().join()
        assertEquals(listOf(true), voice.turnsEnded)

        // And on the path where nothing was said, which returns early.
        voice.heard = null
        dobby.listen().join()
        assertEquals(listOf(true, true), voice.turnsEnded)
    }

    @Test
    fun `transcription reads as thinking, not as listening`() = runTest {
        // Parakeet is batch: there is a real second between the end of a sentence and the
        // answer. A panel that still says "listening" through it looks like one that did not
        // hear, and the person repeats themselves into a closed microphone.
        val voice = FakeVoice(heard = "wie spät ist es")
        val dobby = controller(voice)
        dobby.start()
        settle()

        voice.emit(VoiceState.Transcribing)
        assertEquals(Phase.THINKING, uiState(dobby).phase)

        voice.emit(VoiceState.Listening(speaking = true))
        assertEquals(Phase.LISTENING, uiState(dobby).phase)
        assertEquals("Ich höre dich…", uiState(dobby).detail)
    }

    @Test
    fun `choosing a phrase in settings switches what the panel answers to`() = runTest {
        val voice = FakeVoice()
        val dobby = controller(voice)
        dobby.start()
        assertEquals("Hey Dobby", uiState(dobby).wakePhrase)

        dobby.selectWakeWord("hey_jarvis").join()

        assertEquals("Hey Jarvis", uiState(dobby).wakePhrase)
        assertEquals("hey_jarvis", uiState(dobby).wakeWordId)
        // The list settings renders comes from the pipeline, so a phrase pushed to the device
        // while the app was running is offered without a restart.
        assertEquals(listOf("Hey Dobby", "Hey Jarvis"), uiState(dobby).wakeWords.map { it.phrase })
    }

    @Test
    fun `switching phrase does not switch listening back on`() = runTest {
        // Someone who turned the microphone off and then browsed the settings list has not
        // asked to be listened to again.
        val voice = FakeVoice()
        val dobby = controller(voice)
        dobby.start()
        dobby.setHandsFree(false)
        assertTrue(!uiState(dobby).handsFree)

        dobby.selectWakeWord("hey_jarvis").join()

        assertTrue(!uiState(dobby).handsFree, "choosing a phrase re-armed the microphone")
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

        /** The controller's own screen wake on detection. */
        const val WAKE_SCREEN_SECONDS = 30
    }
}
