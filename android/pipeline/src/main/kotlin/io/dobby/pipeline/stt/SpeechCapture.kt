package io.dobby.pipeline.stt

import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import io.dobby.pipeline.audio.FrameSink
import kotlinx.coroutines.CompletableDeferred
import java.io.Closeable
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The samples of one utterance, as they arrive.
 *
 * Pure: no VAD, no JNI, no Android. It exists on its own because the two things that can go
 * wrong here — the PCM conversion and the hard cap — are arithmetic, and arithmetic tested on
 * a phone is arithmetic tested once.
 *
 * Growth is amortised doubling up to [capacity]. A wall panel takes a turn every few minutes,
 * so the alternative — one 10-second array allocated per utterance, 320 KB of it — would be
 * fine too; this is simply the version that does not depend on that staying true.
 */
class CaptureBuffer(val capacity: Int) {

    private var samples = FloatArray(minOf(capacity, INITIAL))

    var size: Int = 0
        private set

    val isFull: Boolean get() = size >= capacity

    /**
     * Appends [length] samples of [frame].
     *
     * Returns how many were taken: fewer than [length] means the cap was reached inside this
     * frame, and the caller should end the utterance.
     */
    fun append(frame: FloatArray, length: Int): Int {
        val taken = minOf(length, capacity - size)
        if (taken <= 0) return 0
        ensure(size + taken)
        frame.copyInto(samples, size, 0, taken)
        size += taken
        return taken
    }

    /** A copy of exactly what has been captured. */
    fun toFloatArray(): FloatArray = samples.copyOf(size)

    fun clear() {
        size = 0
    }

    private fun ensure(required: Int) {
        if (samples.size >= required) return
        var grown = maxOf(samples.size * 2, required)
        if (grown > capacity) grown = capacity
        samples = samples.copyOf(grown)
    }

    private companion object {
        /** Half a second at 16 kHz. Most utterances reallocate twice and then stop. */
        const val INITIAL = 8000
    }
}

/**
 * 16-bit PCM to −1..1, which is the only number format anything downstream accepts.
 *
 * Dividing by 32768 rather than 32767 is the convention sherpa-onnx's own wave reader uses,
 * and matching it matters: a 1/32768 scale difference is inaudible and a mismatched one is the
 * kind of thing that quietly costs a percent of recognition accuracy.
 *
 * Writes into [into] and returns how many it wrote, so the audio thread allocates nothing.
 */
fun pcmToFloat(frame: ShortArray, length: Int, into: FloatArray): Int {
    val count = minOf(length, frame.size, into.size)
    for (i in 0 until count) into[i] = frame[i] / PCM_FULL_SCALE
    return count
}

private const val PCM_FULL_SCALE = 32768f

/**
 * Silero VAD, loaded once, asked one question: has the speaker stopped?
 *
 * Parakeet is a batch recogniser (`dobby-plan.md` §5.2), so nothing in the recognition path
 * knows where a sentence ends. Vosk used to decide that itself; now this does, and it is the
 * component that sets how the panel *feels* — too eager and it cuts people off mid-sentence,
 * too patient and every answer arrives a second late.
 */
class SpeechVad private constructor(
    private val vad: Vad,
    private val sampleRate: Int,
) : Closeable {

    /**
     * Starts capturing one utterance. Register the result as a `FrameSink` and await it.
     *
     * The VAD carries state from the last utterance — and from whatever Dobby said into its own
     * open microphone — so it is reset here rather than at the end, where a crash would skip it.
     */
    fun capture(
        maxDuration: Duration = MAX_UTTERANCE,
        onSpeaking: (Boolean) -> Unit = {},
    ): SpeechCapture {
        vad.reset()
        vad.clear()
        return SpeechCapture(vad, (maxDuration.inWholeMilliseconds * sampleRate / 1000).toInt(), onSpeaking)
    }

    override fun close() = vad.release()

    companion object {
        /**
         * ~800 ms of trailing silence ends the utterance (`dobby-plan.md` §5.2).
         *
         * Measured against how people actually talk to a panel: German commands have a pause
         * before the object — "spiele … Blinding Lights" — and 500 ms clips a third of them in
         * half. A second is what Vosk used and it read as sluggish; 800 ms is the compromise,
         * and it is a constant rather than a feeling because it is the first thing to tune if
         * the panel starts interrupting.
         */
        val TRAILING_SILENCE: Duration = 800.milliseconds

        /** §5.2's hard cap. The VAD can miss an endpoint in a noisy room; this cannot. */
        val MAX_UTTERANCE: Duration = 10.seconds

        /** Silero v4 is trained on 512-sample windows at 16 kHz and accepts no other size. */
        const val WINDOW: Int = 512

        fun load(
            model: File,
            sampleRate: Int,
            trailingSilence: Duration = TRAILING_SILENCE,
            maxUtterance: Duration = MAX_UTTERANCE,
        ): SpeechVad {
            val config = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = model.absolutePath,
                    minSilenceDuration = trailingSilence.toSeconds(),
                    // The VAD force-splits a segment at this length. Aligned with the caller's
                    // hard cap so there is one number that decides how long a sentence may be.
                    maxSpeechDuration = maxUtterance.toSeconds(),
                    windowSize = WINDOW,
                ),
                sampleRate = sampleRate,
                // One thread, deliberately: it runs on every 80 ms frame beside the wake word.
                numThreads = 1,
                provider = "cpu",
            )
            return SpeechVad(Vad(config = config), sampleRate)
        }

        private fun Duration.toSeconds(): Float = inWholeMilliseconds / 1000f
    }
}

/**
 * One utterance being captured.
 *
 * Frames arrive on the audio thread; [await] resumes on whatever thread the caller is on, once
 * the VAD has seen [SpeechVad.TRAILING_SILENCE] of quiet after speech. The caller is still
 * expected to impose the hard cap from outside — a VAD that never hears speech never ends a
 * segment, and that is exactly the case a wall panel meets every time it wakes at the
 * television.
 *
 * Note what is *not* here: partial results. Parakeet sees the finished buffer or nothing, so
 * the only honest live signal is [onSpeaking] — whether the VAD can hear a voice right now.
 */
class SpeechCapture internal constructor(
    private val vad: Vad,
    maxSamples: Int,
    private val onSpeaking: (Boolean) -> Unit,
) : FrameSink, Closeable {

    private val buffer = CaptureBuffer(maxSamples)
    private val captured = CompletableDeferred<FloatArray>()

    /**
     * Resolves the moment the VAD first hears a voice — true — or when the capture ends
     * without one, false.
     *
     * Separate from [captured] because "has anyone started talking?" and "has the utterance
     * finished?" are two different questions with two different deadlines. The follow-up
     * window after a not-understood buzz asks the first one: five seconds to *begin* speaking,
     * and then however long the sentence takes.
     */
    private val started = CompletableDeferred<Boolean>()

    /**
     * Guards every call into the native VAD.
     *
     * Frames arrive on the audio thread and [close] comes from whoever ended the utterance.
     * `Vad` is a pointer into native memory: touching it after it is freed is a process crash
     * with no Kotlin stack, not an exception. One frame is 80 ms of audio and a fraction of a
     * millisecond of Silero, so the lock costs nothing worth measuring.
     */
    private val gate = Any()

    private var scratch = FloatArray(0)

    private var closed = false

    private var settled: FloatArray? = null

    @Volatile
    private var speaking = false

    /**
     * Whether the VAD ever heard a voice.
     *
     * False means there is nothing to transcribe, and skipping the recogniser saves the ~1.5 s
     * it would spend confidently returning "" — which is most of what a false wake costs.
     */
    @Volatile
    var heardSpeech: Boolean = false
        private set

    override fun onFrames(frame: ShortArray, length: Int) {
        synchronized(gate) {
            if (closed || settled != null) return

            if (scratch.size < length) scratch = FloatArray(length)
            val converted = pcmToFloat(frame, length, scratch)
            val taken = buffer.append(scratch, converted)
            if (taken > 0) {
                // The VAD sees exactly what was captured. Feeding it the samples the cap threw
                // away would have it end a segment that is not in the buffer.
                vad.acceptWaveform(if (taken == scratch.size) scratch else scratch.copyOf(taken))
            }

            val hearing = vad.isSpeechDetected()
            if (hearing) {
                heardSpeech = true
                started.complete(true)
            }
            if (hearing != speaking) {
                speaking = hearing
                onSpeaking(hearing)
            }

            // A segment in the queue means speech started and then stopped for long enough.
            // That is the end of the utterance, and the whole reason the VAD is here.
            if (!vad.empty()) {
                vad.pop()
                settle(buffer.toFloatArray())
                return
            }

            // The cap landed inside this frame. Take what there is rather than listen forever.
            if (buffer.isFull) settle(buffer.toFloatArray())
        }
    }

    /** Resumes with the captured samples once the speaker stops. */
    suspend fun await(): FloatArray = captured.await()

    /**
     * Resumes as soon as the VAD hears a voice, or with false if the capture ends first.
     *
     * Never resumes on its own in a silent room — silence is the absence of an event, so the
     * deadline belongs to the caller, exactly as the hard cap on [await] does.
     */
    suspend fun awaitSpeech(): Boolean = started.await()

    /**
     * Ends the capture now and returns whatever was heard.
     *
     * This is the hard-cap path: the silence that ends an utterance never came. Safe to call
     * after [await] has already resumed, in which case it returns the same samples.
     */
    fun flush(): FloatArray = synchronized(gate) {
        settled?.let { return it }
        if (closed) return EMPTY
        return settle(buffer.toFloatArray())
    }

    override fun close() {
        synchronized(gate) {
            if (closed) return
            closed = true
            settle(EMPTY)
            if (speaking) {
                speaking = false
                onSpeaking(false)
            }
        }
    }

    private fun settle(samples: FloatArray): FloatArray {
        val existing = settled
        if (existing != null) return existing
        settled = samples
        captured.complete(samples)
        // Whatever it is waiting for, a capture that has ended will not hear it now.
        started.complete(heardSpeech)
        return samples
    }

    private companion object {
        val EMPTY = FloatArray(0)
    }
}
