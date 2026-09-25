package io.dobby.pipeline.wakeword

import io.dobby.pipeline.audio.AudioSource
import io.dobby.pipeline.stt.SpeechCapture
import io.dobby.pipeline.stt.SpeechVad
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The second way of hearing the panel's name: transcribe every sentence in the room and look for
 * the phrase in the text ([WakeMode.TRANSCRIPT]).
 *
 * It runs the same Silero VAD and the same Parakeet that a command goes through — the
 * recogniser is loaded and resident anyway — so the phrase can be anything anybody types
 * instead of anything anybody has trained a classifier for. That is the whole point: there is
 * no `hey_dobby.onnx`, and "the wake word does not work that well" has no answer when the model
 * is somebody else's.
 *
 * **It is not free and it is not meant to be the default.** openWakeWord costs three tiny
 * inferences per 80 ms frame forever; this costs a full ASR pass over every utterance anybody
 * in the room produces, on four threads. On a mains-powered wall panel that is affordable, and
 * it is the honest reason [WakeMode.CLASSIFIER] stays the default.
 *
 * ### How it listens
 *
 * One capture at a time, back to back:
 *
 *  - The VAD ends a capture the moment somebody stops talking, exactly as it does for a
 *    command. That capture is what gets transcribed.
 *  - A capture that has heard nothing is recycled after [QUIET_ROLLOVER] instead of being left
 *    to fill. The rollover only ever happens while the room is silent, which is what keeps it
 *    from falling in the middle of somebody's sentence and cutting the phrase in half.
 *  - Transcription happens off the capture loop, so the next capture is already open while the
 *    last one is being read. Otherwise the second of ASR would be a second of deafness right
 *    after the wake phrase — which is where the command is.
 *
 * ### And then it goes quiet
 *
 * On a detection it disarms itself and stops transcribing until [reset]. It stays on the
 * microphone while it does — dropping off would close the recorder if nothing else held it, and
 * the handover at the start of a turn depends on the mic never closing. Being deaf and present
 * is the state this needs; [reset] is what ends it, and the pipeline calls it at the end of a
 * turn and after anything Dobby says out loud.
 */
class TranscriptWakeWord(
    scope: CoroutineScope,
    /**
     * A VAD of its own, **not** the one the command path uses.
     *
     * `Vad` is a native object with segment state, and [SpeechVad.capture] resets it. Sharing
     * one would mean the utterance capture in the middle of a turn silently resetting the
     * capture this listener is parked on, and the failure would be a wake word that stops
     * working after the first command.
     */
    private val vad: SpeechVad,
    private val wakePhrase: WakePhrase,
    /**
     * Runs the recogniser. Suspending and serialised by the caller: the command path and this
     * one share one `OfflineRecognizer`, and they must not be inside it at the same time.
     */
    private val transcribe: suspend (FloatArray) -> String,
    private val onDetected: (WakeWord) -> Unit,
) : WakeListener {

    override val phrase: String get() = wakePhrase.text

    /** Guards [capture] against [close] and [reset] racing the audio thread. */
    private val gate = Any()

    private var capture: SpeechCapture? = null

    private var closed = false

    /** False from a detection until [reset]: still on the microphone, deliberately not listening. */
    @Volatile
    private var armed = true

    /** Whether the VAD can hear a voice right now — the rollover's "is the room quiet?". */
    @Volatile
    private var speaking = false

    /** Nudges the loop when [reset] re-arms it, so a disarmed listener does not poll. */
    private val rearmed = Channel<Unit>(Channel.CONFLATED)

    private val loop: Job = scope.launch(Dispatchers.Default) {
        while (isActive) {
            if (!armed) {
                rearmed.receive()
                continue
            }
            val current = synchronized(gate) {
                if (closed) return@launch
                vad.capture(HARD_CAP) { hearing -> speaking = hearing }.also { capture = it }
            }
            val samples = try {
                drain(current)
            } finally {
                synchronized(gate) { if (capture === current) capture = null }
                current.close()
            }

            // Three ways to have nothing worth a second of ASR: the room was quiet, the
            // detection already fired while this was in flight, or what was captured is too
            // short to be a phrase — a door, a cough, one syllable of a television.
            if (!armed || !current.heardSpeech || samples.size < MIN_SAMPLES) continue

            val heard = try {
                transcribe(samples)
            } catch (e: CancellationException) {
                throw e
            } catch (e: RuntimeException) {
                // sherpa-onnx reports a decoding failure from JNI as a plain runtime exception.
                // One unreadable utterance is not a reason to stop listening for the next.
                continue
            }
            val detection = wakePhrase.match(heard) ?: continue
            // Before the callback, not after: the callback opens a turn, and a listener that
            // was still transcribing while Dobby answered would hear the answer.
            armed = false
            onDetected(detection)
        }
    }

    /**
     * Waits for the capture to end, recycling it if the room simply never said anything.
     *
     * The VAD ends a capture on speech-then-silence and [SpeechCapture] ends one when its
     * buffer fills. Neither happens in an empty room until the buffer is full of nothing, so
     * this closes it earlier — but only while nothing is being said, so the seam between two
     * captures can never land inside a phrase.
     */
    private suspend fun drain(capture: SpeechCapture): FloatArray {
        while (true) {
            val settled = withTimeoutOrNull(QUIET_ROLLOVER) { capture.await() }
            if (settled != null) return settled
            if (!speaking && !capture.heardSpeech) return capture.flush()
        }
    }

    override fun onFrames(frame: ShortArray, length: Int) {
        // No lock beyond the read: the capture takes its own, and holding this one across a
        // native VAD call would put the audio thread behind whatever the loop is doing.
        val current = synchronized(gate) { if (closed) null else capture } ?: return
        current.onFrames(frame, length)
    }

    /**
     * Listen again from now — and, after a detection, listen at all.
     *
     * Ends whatever capture is open so the next one starts on a clean VAD, which is what the
     * "the stream has a hole in it" case needs: everything before the hole belongs to a
     * different sentence.
     */
    override fun reset() {
        val open = synchronized(gate) {
            if (closed) return
            armed = true
            speaking = false
            capture.also { capture = null }
        }
        open?.close()
        rearmed.trySend(Unit)
    }

    override fun close() {
        val open = synchronized(gate) {
            if (closed) return
            closed = true
            capture.also { capture = null }
        }
        open?.close()
        loop.cancel()
    }

    companion object {
        /**
         * The longest single capture. Speech that runs past it is cut here rather than left to
         * hold the microphone — a wake phrase is a second of audio, and a recogniser handed
         * half a minute of a television answers slowly and says nothing useful.
         */
        val HARD_CAP: Duration = 8.seconds

        /** How long a silent capture is kept before it is thrown away and replaced. */
        val QUIET_ROLLOVER: Duration = 3.seconds

        /** Shorter than this is not a phrase — a door, a cough, one syllable of a television. */
        val MIN_UTTERANCE: Duration = 300.milliseconds

        private val MIN_SAMPLES: Int =
            (MIN_UTTERANCE.inWholeMilliseconds * AudioSource.SAMPLE_RATE / 1000).toInt()
    }
}
