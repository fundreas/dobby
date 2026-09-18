package io.dobby.pipeline.stt

import io.dobby.pipeline.audio.FrameSink
import kotlinx.coroutines.CompletableDeferred
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.Closeable
import java.io.File

/**
 * The loaded German acoustic model.
 *
 * Loading costs a second or two and ~40 MB, so it happens once at service start and the model
 * stays resident. Each utterance gets its own cheap [Utterance] on top of it.
 */
class VoskEngine private constructor(
    private val model: Model,
    private val sampleRate: Int,
) : Closeable {

    /**
     * Starts one utterance. Register the returned object as a [FrameSink] and await its result.
     *
     * Decoding is deliberately unconstrained (`dobby-plan.md` §5.2): song titles and station
     * names are open vocabulary, so a grammar here would be worse than none.
     */
    fun listen(onPartial: (String) -> Unit = {}): Utterance =
        Utterance(Recognizer(model, sampleRate.toFloat()), onPartial)

    override fun close() = model.close()

    companion object {
        fun load(modelDirectory: File, sampleRate: Int): VoskEngine {
            LibVosk.setLogLevel(LogLevel.WARNINGS)
            return VoskEngine(Model(modelDirectory.absolutePath), sampleRate)
        }
    }
}

/**
 * One utterance being recognised.
 *
 * Frames arrive on the audio thread; [await] resumes on whatever thread the caller is on when
 * Vosk's own endpointing decides the speaker has stopped (~1 s of silence). The caller is
 * expected to also impose a hard cap — endpointing on a noisy wall panel can miss.
 */
class Utterance internal constructor(
    private val recognizer: Recognizer,
    private val onPartial: (String) -> Unit,
) : FrameSink, Closeable {

    private val transcript = CompletableDeferred<String>()

    /**
     * Guards every call into the native recogniser.
     *
     * Frames arrive on the audio thread and [close] comes from whoever ended the utterance.
     * `Recognizer` is a pointer into native memory: touching it after it is freed is a process
     * crash with no Kotlin stack, not an exception. One frame is ~32 ms of audio and well
     * under a millisecond of decoding, so the lock costs nothing worth measuring.
     */
    private val gate = Any()

    @Volatile
    private var settled: String? = null

    private var closed = false

    override fun onFrames(frame: ShortArray, length: Int) {
        synchronized(gate) {
            if (closed || settled != null) return
            if (recognizer.acceptWaveForm(frame, length)) {
                settle(VoskJson.stringField(recognizer.getResult(), "text"))
            } else {
                onPartial(VoskJson.stringField(recognizer.getPartialResult(), "partial"))
            }
        }
    }

    /** Resumes with the transcript once the speaker stops. */
    suspend fun await(): String = transcript.await()

    /**
     * Ends the utterance now and returns whatever Vosk has.
     *
     * This is the hard-cap path: the silence that ends an utterance never came, so take the
     * partial rather than listen forever. Safe to call after [await] has already resumed, in
     * which case it returns the same transcript.
     */
    fun flush(): String = synchronized(gate) {
        settled?.let { return it }
        if (closed) return ""
        return settle(VoskJson.stringField(recognizer.getFinalResult(), "text"))
    }

    override fun close() {
        synchronized(gate) {
            if (closed) return
            closed = true
            settle("")
            recognizer.close()
        }
    }

    private fun settle(text: String): String {
        val existing = settled
        if (existing != null) return existing
        settled = text
        transcript.complete(text)
        return text
    }
}
