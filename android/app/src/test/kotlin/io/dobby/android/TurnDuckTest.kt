package io.dobby.android

import io.dobby.core.audio.TurnAudio
import io.dobby.core.testing.FakeSockContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a turn does to whatever is playing.
 *
 * The failure being fixed: say the wake phrase over music and the microphone hears the music
 * too. Silero VAD, watching that stream, never finds its ~800 ms of trailing silence, so every
 * turn runs to the ten-second hard cap and Parakeet is handed ten seconds of music with a
 * sentence somewhere inside. Ducking for the length of the turn is what puts the endpoint back
 * within reach (`m2b-plan.md` Part A).
 *
 * The endpoint itself belongs to a room with a speaker in it and is measured there. What can be
 * asserted here is everything the duck has to get right for that measurement to mean anything:
 * that it is taken before the first microphone opens, held across every utterance of the turn,
 * released exactly once however the turn ended — and that it leaves the Socks' own audio focus
 * exactly as it found it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TurnDuckTest {

    /**
     * The turn duck, recording into the same timeline the microphone writes to.
     *
     * Ordering is the whole of what this has to get right, and counters cannot express it.
     */
    private class FakeTurnAudio(private val events: MutableList<String>) : TurnAudio {
        var ducks: Int = 0
            private set

        var releases: Int = 0
            private set

        /** True exactly while a turn holds it. What the room would actually sound like. */
        val isDucked: Boolean get() = ducks > releases

        override suspend fun duck() {
            ducks++
            events += DUCK
        }

        override suspend fun release() {
            // Suspends, as a real release may: abandoning audio focus and restoring an
            // ExoPlayer's volume are main-thread work, and a turn ends wherever it ended. A
            // suspension point here is also what makes the cancellation test mean something —
            // without it, nothing in a `finally` ever notices that the turn was cancelled.
            yield()
            releases++
            events += RELEASE
        }

        companion object {
            const val DUCK = "duck"
            const val RELEASE = "release"
        }
    }

    private fun TestScope.controller(
        voice: FakeVoice,
        audio: FakeTurnAudio,
        context: FakeSockContext = FakeSockContext(),
        scope: CoroutineScope = backgroundScope,
    ) = DobbyController(scope, voice, turnAudio = audio) { context }

    /**
     * A scope that swallows what a turn throws, so the throw itself can be the subject.
     *
     * `backgroundScope` reports an uncaught exception as a test failure, which is the right
     * default and the wrong one here: what is being asserted is precisely that a turn which
     * blows up still hands the music back.
     */
    private fun TestScope.forgivingScope(): CoroutineScope = CoroutineScope(
        StandardTestDispatcher(testScheduler) + SupervisorJob() + CoroutineExceptionHandler { _, _ -> },
    )

    /** See [DobbyControllerTest.settle] — the controller works in coroutines of its own. */
    private fun TestScope.settle() {
        repeat(3) {
            testScheduler.runCurrent()
            testScheduler.advanceUntilIdle()
        }
    }

    @Test
    fun `the music is ducked before the microphone opens, and released once it closes`() = runTest {
        val voice = FakeVoice("wie spät ist es")
        val audio = FakeTurnAudio(voice.events)
        val dobby = controller(voice, audio)
        dobby.start()

        dobby.listen().join()

        // The whole order, in one assertion: duck, then the microphone — twice, because the
        // floor stays open after an answer — then release, and only then the wake word back on
        // the stream. A duck taken after the microphone opened would leave the first second of
        // every command sitting under the music, which is the second the command is usually in.
        assertEquals(
            listOf(
                FakeTurnAudio.DUCK,
                FakeVoice.LISTEN,
                FakeVoice.LISTEN,
                FakeTurnAudio.RELEASE,
                FakeVoice.END_TURN,
            ),
            voice.events,
        )
        assertTrue(!audio.isDucked, "the music never came back up")
    }

    @Test
    fun `a turn of three utterances ducks once, not three times`() = runTest {
        // Turn-scoped, not utterance-scoped. Un-ducking between utterances would let the music
        // swell back up in the gaps — which is worse than not ducking at all, because it happens
        // exactly while the person is waiting to speak again.
        val voice = FakeVoice("stell einen Timer auf zehn", "mach mir ein Sandwich", "Minuten")
        val audio = FakeTurnAudio(voice.events)
        val dobby = controller(voice, audio)
        dobby.start()

        dobby.listen().join()

        assertEquals(1, audio.ducks)
        assertEquals(1, audio.releases)
        // Every listen falls inside the one duck: nothing between the first and the last.
        val duckAt = voice.events.indexOf(FakeTurnAudio.DUCK)
        val releaseAt = voice.events.indexOf(FakeTurnAudio.RELEASE)
        val listens = voice.events.withIndex().filter { it.value == FakeVoice.LISTEN }
        assertEquals(4, listens.size, "the turn's utterances: three heard, one silent tail")
        assertTrue(listens.all { it.index in (duckAt + 1) until releaseAt }, "$listens")
    }

    @Test
    fun `the wake word path ducks exactly as the button path does`() = runTest {
        val byWakeWord = FakeVoice("wie spät ist es")
        val wakeAudio = FakeTurnAudio(byWakeWord.events)
        val dobby = controller(byWakeWord, wakeAudio)
        dobby.start()
        // start() launches the wake-word collector, and a SharedFlow with no subscriber yet
        // drops what it is given — so let it subscribe before speaking.
        settle()

        byWakeWord.sayWakeWord()
        settle()

        val byButton = FakeVoice("wie spät ist es")
        val buttonAudio = FakeTurnAudio(byButton.events)
        val second = controller(byButton, buttonAudio)
        second.start()
        second.listen().join()

        // Hanging the duck off onWakeWord would have half-fixed it: the button opens the same
        // microphone into the same room, and a panel that only ducks for one of them is a panel
        // whose behaviour depends on how you started talking to it.
        assertEquals(byButton.events, byWakeWord.events)
        assertEquals(1, wakeAudio.ducks)
        assertEquals(1, buttonAudio.ducks)
    }

    @Test
    fun `typing does not duck`() = runTest {
        // No microphone is open, so there is nothing the music can drown out. Quietening the
        // kitchen because somebody typed in the text box is a bug with a very clear symptom.
        val voice = FakeVoice()
        val audio = FakeTurnAudio(voice.events)
        val dobby = controller(voice, audio)
        dobby.start()

        dobby.submit("wie spät ist es").join()

        assertEquals(0, audio.ducks)
        assertEquals(1, voice.spoken.size, "the typed turn still answered")
    }

    @Test
    fun `the duck is released when nothing was said`() = runTest {
        val voice = FakeVoice(null)
        val audio = FakeTurnAudio(voice.events)
        val dobby = controller(voice, audio)
        dobby.start()

        dobby.listen().join()

        assertEquals(1, audio.ducks)
        assertEquals(1, audio.releases)
    }

    @Test
    fun `the duck is released when a Sock ends the turn`() = runTest {
        // "OK" is the Conversation Sock: the one result that closes the microphone early. It
        // returns out of the loop rather than falling off the end of it, which is exactly the
        // shape of path that forgets to undo things.
        val voice = FakeVoice("ok")
        val audio = FakeTurnAudio(voice.events)
        val dobby = controller(voice, audio)
        dobby.start()

        dobby.listen().join()

        assertEquals(1, audio.releases)
        assertTrue(!audio.isDucked)
    }

    @Test
    fun `the duck is released when the microphone fails mid-turn`() = runTest {
        val voice = FakeVoice("wie spät ist es")
        voice.failNextListen = IllegalStateException("the microphone is held by something else")
        val audio = FakeTurnAudio(voice.events)
        val dobby = controller(voice, audio, scope = forgivingScope())
        dobby.start()

        dobby.listen().join()
        settle()

        // A duck that outlived a thrown turn is a panel that permanently quietened the music,
        // which is a more annoying failure than the one being fixed.
        assertEquals(1, audio.ducks)
        assertEquals(1, audio.releases)
    }

    @Test
    fun `the duck is released when the turn is cancelled`() = runTest {
        // The scope a turn runs in is cancelled when the service dies. release() suspends, and a
        // suspending call in a plain finally is cancelled before it does anything — so this is
        // the case that needs NonCancellable, and the one whose absence leaves the music quiet
        // with nothing left running to put it back.
        val voice = FakeVoice("wie spät ist es")
        val audio = FakeTurnAudio(voice.events)
        val dobby = controller(voice, audio)
        dobby.start()

        voice.holdListen = CompletableDeferred()
        val turn = dobby.listen()
        settle()
        assertTrue(audio.isDucked, "the turn was not in flight yet — nothing was cancelled")

        turn.cancel()
        turn.join()
        settle()

        assertEquals(1, audio.releases, "the turn was cancelled with the music still ducked")
    }

    @Test
    fun `a turn duck leaves a Sock's own focus exactly as it found it`() = runTest {
        // The trap in the obvious implementation. PlaybackCoordinator arbitrates *between
        // Socks*, is keyed by sock id and holds exactly one request: a turn-level duck routed
        // through it would abandon whatever the Radio Sock was holding and never give it back.
        // That is the entire reason TurnAudio is a separate interface, and this is the test that
        // fails loudly if somebody later routes it through the coordinator after all.
        val context = FakeSockContext()
        context.playback.requestFocus("radio")
        context.playback.requestTransientFocus("clock")

        val voice = FakeVoice("wie spät ist es")
        val audio = FakeTurnAudio(voice.events)
        val dobby = controller(voice, audio, context)
        dobby.start()

        dobby.listen().join()

        assertEquals("radio", context.playback.holder, "the Sock's focus was taken by the turn")
        assertEquals("clock", context.playback.duckedBy)
        assertEquals(
            listOf("clock"),
            context.playback.transientRequests,
            "the turn asked the Sock-level coordinator for focus instead of taking its own",
        )
        assertEquals(1, audio.ducks, "the turn ducked through its own channel")
    }
}
