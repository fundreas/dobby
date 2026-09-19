package io.dobby.pipeline.stt

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import java.io.Closeable

/**
 * The loaded Parakeet TDT 0.6B v3 recogniser (`dobby-plan.md` §5.2).
 *
 * Loading costs a few seconds and ~700 MB of mapped file, so it happens once at service start
 * and stays resident. sherpa-onnx opens the ONNX graphs through ONNX Runtime, which maps the
 * weights rather than copying them — the encoder's 622 MB is page cache the kernel can evict
 * under pressure, not 622 MB of heap. That is the whole reason the memory budget in §9 works.
 *
 * **Batch, not streaming.** There is no running guess to show while someone speaks: the model
 * sees a finished utterance and answers once. Deciding when the utterance finished is
 * [SpeechCapture]'s job, and the reason a VAD is in the pipeline at all.
 */
class ParakeetRecognizer private constructor(
    private val recognizer: OfflineRecognizer,
    private val sampleRate: Int,
) : Closeable {

    /**
     * Turns one captured utterance into text. Blocking — call it off the audio thread.
     *
     * [samples] is mono PCM in −1..1 at [sampleRate]. Returns "" when the model heard nothing,
     * which is the normal outcome for a wake word that fired at the television.
     */
    fun transcribe(samples: FloatArray): String {
        if (samples.isEmpty()) return ""
        val stream = recognizer.createStream()
        return try {
            stream.acceptWaveform(samples, sampleRate)
            recognizer.decode(stream)
            recognizer.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    override fun close() = recognizer.release()

    companion object {
        /**
         * Four of the Nord CE's eight cores (`dobby-plan.md` §5.2).
         *
         * The budget is ~1.5 s from end-of-speech to transcript for a 3–4 s utterance. More
         * threads than this contend with the wake word, which is still scoring every 80 ms
         * frame on the same CPU and is the component nobody may notice getting slower.
         */
        const val THREADS: Int = 4

        /**
         * Opens the recogniser. Throws [IllegalArgumentException] if a model file is unreadable.
         *
         * `nemo_transducer` is not optional and not guessable: the same encoder/decoder/joiner
         * triple under `modelType = "transducer"` loads, runs, and returns confident nonsense,
         * because the TDT decoding loop is a different loop.
         */
        fun load(models: SpeechModelFiles, sampleRate: Int, threads: Int = THREADS): ParakeetRecognizer {
            val config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = sampleRate, featureDim = FEATURE_DIM),
                modelConfig = OfflineModelConfig(
                    transducer = OfflineTransducerModelConfig(
                        encoder = models.encoder.absolutePath,
                        decoder = models.decoder.absolutePath,
                        joiner = models.joiner.absolutePath,
                    ),
                    tokens = models.tokens.absolutePath,
                    modelType = "nemo_transducer",
                    numThreads = threads,
                    provider = "cpu",
                ),
                // Unconstrained decoding, as §5.2 has always required: song titles and station
                // names are open vocabulary, so a grammar here would be worse than none.
                decodingMethod = "greedy_search",
            )
            return ParakeetRecognizer(OfflineRecognizer(config = config), sampleRate)
        }

        /** NeMo FastConformer's mel bins, and sherpa-onnx's default. */
        private const val FEATURE_DIM = 80
    }
}
