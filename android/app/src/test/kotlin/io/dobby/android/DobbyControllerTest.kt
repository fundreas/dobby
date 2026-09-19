package io.dobby.android

import io.dobby.android.chat.Voice
import io.dobby.core.testing.FakeSockContext
import io.dobby.pipeline.ListenCue
import io.dobby.pipeline.VoiceState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The join between the microphone and Phase A.
 *
 * This is the one piece of Phase B that neither `:core`'s tests nor the Socks' tests cover,
 * and the one the chat view is a direct rendering of. It is testable at all because the
 * hardware sits behind [io.dobby.pipeline.VoiceIo] and the Sock context behind a factory —
 * which is the same trick Phase A used to test Socks without an emulator, applied one layer up.
 *
 * What a turn does to whatever is playing is [TurnDuckTest]'s.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DobbyControllerTest {

    private fun TestScope.controller(
        voice: FakeVoice,
        context: FakeSockContext = FakeSockContext(),
        tier2: io.dobby.core.nlu.llm.Tier2Resolver? = null,
    ) = DobbyController(backgroundScope, voice, tier2Resolver = tier2) { context }

    /**
     * A resolver that answers one utterance and reports when it started.
     *
     * [onStart] is how `Thinking.MODEL` is set: the real `LlamaTier2` flips the state as
     * `generate` is entered, so the status line is true by construction rather than by a timer
     * that guesses when the model probably began.
     */
    private class ScriptedResolver(
        /** Route answers by utterance: a bare command id, or `none`. */
        private val routes: Map<String, String> = emptyMap(),
        /** Fill answers by utterance: a params-only JSON object, or `none`. */
        private val fills: Map<String, String> = emptyMap(),
        /** Awaited inside `generate`, so a test can inspect the panel mid-thought. */
        private val hold: kotlinx.coroutines.CompletableDeferred<Unit>? = null,
    ) : io.dobby.core.nlu.llm.Tier2Resolver {
        override val available: Boolean = true
        override val unavailableReason: String? = null

        val requests: MutableList<io.dobby.core.nlu.llm.Tier2Request> = mutableListOf()

        val asked: List<String> get() = requests.map { it.utterance }

        /** Completes as soon as `generate` is entered. */
        val started: kotlinx.coroutines.CompletableDeferred<Unit> = kotlinx.coroutines.CompletableDeferred()

        override suspend fun generate(request: io.dobby.core.nlu.llm.Tier2Request): String {
            requests += request
            started.complete(Unit)
            hold?.await()
            return when (request.step) {
                io.dobby.core.nlu.llm.Step.ROUTE -> routes[request.utterance] ?: "none"
                io.dobby.core.nlu.llm.Step.FILL -> fills[request.utterance] ?: "none"
            }
        }
    }

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
        val voice = FakeVoice("wie spät ist es")
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
        val dobby = controller(FakeVoice("wie spät ist es"), context)
        dobby.start()

        dobby.listen().join()

        // Clock wakes the screen when it answers. That it happened here proves the context
        // built in Phase B is the one handed to onStart, not a second one.
        assertEquals(listOf(ClockScreenWakeSeconds), context.screen.wakeRequests)
    }

    @Test
    fun `saying nothing leaves the transcript untouched`() = runTest {
        val voice = FakeVoice(null)
        val dobby = controller(voice)
        dobby.start()

        dobby.listen().join()

        assertEquals(emptyList(), uiState(dobby).messages)
        assertEquals(emptyList(), voice.spoken)
    }

    @Test
    fun `an utterance no Sock claims buzzes, and the microphone stays open`() = runTest {
        // Nothing matched, so there is nothing to say. Saying "Das habe ich nicht verstanden."
        // takes two seconds to deliver one bit of information, and delivers it over the top of
        // the person already repeating themselves. Two buzzes, then listen again.
        val voice = FakeVoice("mach mir ein sandwich", null)
        val dobby = controller(voice)
        dobby.start()

        dobby.listen().join()

        assertEquals(1, voice.buzzes)
        assertEquals(emptyList(), voice.spoken, "the apology must not be spoken")

        // It is still in the chat: the chat is the log of what happened, and a buzz leaves no
        // trace on it. Nothing matched, so there is no command to show under it.
        val answer = uiState(dobby).messages.last { it.voice == Voice.DOBBY }
        assertEquals("Das habe ich nicht verstanden.", answer.text)
        assertEquals(null, answer.detail)
        assertTrue(answer.failed)

        // Both utterances get five seconds to begin: the first because a wake word that fired
        // at the television must not hold the microphone for the full ten-second cap, the
        // second because nobody asked for it at all.
        assertEquals(listOf(5.seconds, 5.seconds), voice.windows)
        // Silence in that window ends the turn, which is what puts the wake word back.
        assertEquals(listOf(true), voice.turnsEnded)
    }

    @Test
    fun `saying it again inside the window is answered, without a new wake word`() = runTest {
        val voice = FakeVoice("mach mir ein sandwich", "wie spät ist es")
        val dobby = controller(voice)
        dobby.start()

        dobby.listen().join()

        assertEquals(1, voice.buzzes)
        val answer = uiState(dobby).messages.last { it.voice == Voice.DOBBY }
        assertTrue(answer.text.startsWith("Es ist"), "the second try was answered: ${answer.text}")
        assertEquals(listOf(answer.text), voice.spoken)
        // Both utterances belong to one turn — the wake word goes back on the stream once.
        assertEquals(listOf(true), voice.turnsEnded)
    }

    @Test
    fun `the panel gives up rather than hold the microphone open all evening`() = runTest {
        // A television is a speaker that never runs out of unmatched sentences.
        val voice = FakeVoice("mach mir ein sandwich", "mach mir ein sandwich", "mach mir ein sandwich")
        val dobby = controller(voice)
        dobby.start()

        dobby.listen().join()

        assertEquals(3, voice.buzzes)
        assertEquals(listOf(5.seconds, 5.seconds, 5.seconds), voice.windows)
        assertEquals(emptyList(), voice.spoken)
        assertEquals(listOf(true), voice.turnsEnded)
    }

    @Test
    fun `a question is spoken before the microphone reopens, and the answer lands`() = runTest {
        // "Stell einen Timer auf zehn" is missing only its unit. Clock asks; core keeps the
        // floor; the next utterance goes straight back to Clock. One turn, two utterances.
        val voice = FakeVoice("stell einen Timer auf zehn", "Minuten")
        val dobby = controller(voice)
        dobby.start()

        dobby.listen().join()

        assertEquals(
            listOf("10 was — Sekunden, Minuten oder Stunden?", "Timer läuft: 10 Minuten."),
            voice.spoken,
        )
        // say() suspends until the sentence has finished playing, so the question is out of the
        // speaker before the microphone is open again — otherwise Dobby answers itself.
        assertEquals(listOf("10 was — Sekunden, Minuten oder Stunden?"), voice.spokenBeforeListen[1])

        // Five seconds to start talking after the wake word; eight to answer a question,
        // because the pause before a reply is the person reading the choice back to themselves;
        // and five again once the answer has landed and nothing is pending any more.
        assertEquals(listOf(5.seconds, 8.seconds, 5.seconds), voice.windows)
        assertEquals(0, voice.buzzes)
        // Both utterances belong to one turn — the wake word goes back on the stream once.
        assertEquals(listOf(true), voice.turnsEnded)
    }

    @Test
    fun `nobody answering the question ends the turn instead of leaving it open`() = runTest {
        val voice = FakeVoice("stell einen Timer auf zehn")
        val dobby = controller(voice)
        dobby.start()

        dobby.listen().join()

        assertEquals(listOf("10 was — Sekunden, Minuten oder Stunden?"), voice.spoken)
        assertEquals(listOf(5.seconds, 8.seconds), voice.windows)
        assertEquals(listOf(true), voice.turnsEnded)

        // The question died with the turn: the next one starts from nothing, and "Minuten" on
        // its own is not a command Dobby knows.
        voice.heard = "Minuten"
        dobby.listen().join()
        assertEquals(1, voice.buzzes)
    }

    @Test
    fun `an answer nobody could place leaves the question standing, and costs a clarify round`() =
        runTest {
            val voice = FakeVoice("stell einen Timer auf zehn", "mach mir ein Sandwich", "Minuten")
            val dobby = controller(voice)
            dobby.start()

            dobby.listen().join()

            assertEquals(1, voice.buzzes, "the unmatched answer is buzzed at like any other")
            assertEquals(
                listOf("10 was — Sekunden, Minuten oder Stunden?", "Timer läuft: 10 Minuten."),
                voice.spoken,
                "the question survived, so the third utterance still answered it",
            )
            // The wider window stays while the question does, and goes back to the ordinary
            // one for the utterance after the answer.
            assertEquals(listOf(5.seconds, 8.seconds, 8.seconds, 5.seconds), voice.windows)
        }

    @Test
    fun `saying something else escapes the question`() = runTest {
        // A panel that can only be answered is a trap. "Wie spät ist es" gets through, and the
        // half-built timer goes with it.
        val voice = FakeVoice("stell einen Timer auf zehn", "wie spät ist es")
        val dobby = controller(voice)
        dobby.start()

        dobby.listen().join()

        val answer = uiState(dobby).messages.last { it.voice == Voice.DOBBY }
        assertTrue(answer.text.startsWith("Es ist"), "the escape was answered: ${answer.text}")
        assertEquals(listOf(true), voice.turnsEnded)
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
    fun `a development Sock answers like any other - in the chat and out loud`() = runTest {
        val voice = FakeVoice()
        val dobby = controller(voice)
        dobby.start()

        dobby.submit("hello").join()

        val answer = uiState(dobby).messages.last { it.voice == Voice.DOBBY }
        assertEquals("Hallo Meister", answer.text)
        assertEquals(listOf("Hallo Meister"), voice.spoken)
    }

    @Test
    fun `the status line reports what Dobby is doing`() = runTest {
        val voice = FakeVoice("wie spät ist es")
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
        val voice = FakeVoice("wie spät ist es")
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
        val voice = FakeVoice("wie spät ist es")
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
        val voice = FakeVoice("wie spät ist es")
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
        val voice = FakeVoice("wie spät ist es")
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
    fun `the acknowledgement is a setting, and it is remembered`() = runTest {
        // A kitchen at midday wants the pip; a bedroom at five in the morning is where a pip is
        // the reason a panel gets unplugged. Neither is the right default for the other, so it
        // is a choice — and one that has to survive the reboot that follows making it.
        val voice = FakeVoice()
        val dobby = controller(voice)
        dobby.start()
        assertEquals(ListenCue.VIBRATE, uiState(dobby).listenCue, "silent, and felt")

        dobby.setListenCue(ListenCue.NONE)

        assertEquals(ListenCue.NONE, voice.listenCue, "the pipeline is what acts on it")
        // And the screen shows what was chosen: the cue is not a flow, so a change to it has to
        // be pushed into the UI state by hand, and forgetting that is invisible in the code.
        assertEquals(ListenCue.NONE, uiState(dobby).listenCue)
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

    @Test
    fun `an utterance only the model can place does not buzz and does not reopen the mic`() = runTest {
        val voice = FakeVoice("weck mich in zwanzig minuten")
        val dobby = controller(
            voice,
            tier2 = ScriptedResolver(
                routes = mapOf("weck mich in 20 minuten" to "clock.set_timer"),
                fills = mapOf("weck mich in 20 minuten" to """{"amount":20,"unit":"minuten"}"""),
            ),
        )
        dobby.start()
        dobby.listen().join()

        // The whole point of putting Tier 2 inside handle(): wasUnderstood is already the
        // post-Tier-2 answer, so `respondTo` never reaches the buzz.
        assertEquals(0, voice.buzzes, "the panel buzzed at a command it went on to execute")
        val answer = uiState(dobby).messages.last { it.voice == Voice.DOBBY }
        assertTrue(answer.text.startsWith("Timer läuft"), answer.text)
        assertTrue(answer.detail!!.startsWith("tier2 "), "no Tier 2 detail line: ${answer.detail}")
        assertTrue("clock.set_timer (amount=20, unit=minuten)" in answer.detail!!, answer.detail!!)
    }

    @Test
    fun `a miss the model was asked about still says so in the detail line`() = runTest {
        val voice = FakeVoice("erzähl mir einen witz")
        val dobby = controller(voice, tier2 = ScriptedResolver())
        dobby.start()
        dobby.listen().join()

        assertTrue(voice.buzzes > 0, "a genuine miss must still buzz")
        // "The model looked and said no" is what tells you the tier is running at all.
        val said = uiState(dobby).messages.last { it.voice == Voice.DOBBY }
        assertTrue(said.failed)
    }

    @Test
    fun `rounds two and three of a turn skip the model`() = runTest {
        val voice = FakeVoice("bla bla", "blub blub", "noch mehr bla")
        val resolver = ScriptedResolver()
        val dobby = controller(voice, tier2 = resolver)
        dobby.start()
        dobby.listen().join()

        // Three unmatched utterances, one consultation. After a buzz the person is rephrasing
        // toward a command they believe exists, which is Tier 1's best case — and three
        // five-second waits in a row is a panel that looks stuck.
        assertEquals(3, voice.buzzes)
        assertEquals(listOf("bla bla"), resolver.asked)
    }

    @Test
    fun `the panel says it is thinking, and the resolver is what makes that true`() = runTest {
        val voice = FakeVoice("weck mich gleich")
        val hold = kotlinx.coroutines.CompletableDeferred<Unit>()
        val resolver = ScriptedResolver(hold = hold)
        val dobby = controller(voice, tier2 = resolver)
        dobby.start()

        val turn = dobby.listen()
        settle()

        // Mid-thought: the model is inside generate() and the panel says so. The state is set
        // by the resolver being entered, not by a timer guessing when it probably started —
        // which is why the text is true rather than merely plausible.
        assertTrue(resolver.started.isCompleted, "the resolver was never reached")
        assertEquals(Phase.THINKING, dobby.state.value.phase)
        assertEquals("Ich denke nach…", dobby.state.value.detail)

        hold.complete(Unit)
        turn.join()
        assertEquals("", uiState(dobby).detail)
    }

    @Test
    fun `with no resolver the panel behaves exactly as it did`() = runTest {
        val voice = FakeVoice("erzähl mir einen witz")
        val dobby = controller(voice)
        dobby.start()
        dobby.listen().join()

        assertTrue(voice.buzzes > 0)
        val said = uiState(dobby).messages.last { it.voice == Voice.DOBBY }
        assertEquals(null, said.detail, "a miss with no Tier 2 has nothing to report")
    }
}
