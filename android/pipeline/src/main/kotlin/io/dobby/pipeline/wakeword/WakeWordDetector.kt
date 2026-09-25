package io.dobby.pipeline.wakeword

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import io.dobby.pipeline.audio.MicProfile
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer

/** The three ONNX graphs openWakeWord runs, loaded once and kept resident. */
class WakeWordModels private constructor(
    private val environment: OrtEnvironment,
    internal val melspectrogram: OrtSession,
    internal val embedding: OrtSession,
    internal val classifier: OrtSession,
    /** The wake phrase this classifier was trained for, for the UI to name. */
    val phrase: String,
) : Closeable {

    /**
     * How many 96-wide embeddings the classifier expects, read from its own graph.
     *
     * Not hard-coded: official models use 16, a model you train yourself may not, and reading
     * it is both free and the difference between "works with any openWakeWord model" and
     * "works with the three I happened to test".
     */
    internal val featureFrames: Int by lazy {
        val shape = classifier.inputInfo.values.first().info.let { it as ai.onnxruntime.TensorInfo }.shape
        // [batch, frames, 96]
        shape.getOrNull(1)?.toInt()?.takeIf { it > 0 } ?: DEFAULT_FEATURE_FRAMES
    }

    internal val melspectrogramInput: String by lazy { melspectrogram.inputNames.first() }
    internal val embeddingInput: String by lazy { embedding.inputNames.first() }
    internal val classifierInput: String by lazy { classifier.inputNames.first() }

    override fun close() {
        classifier.close()
        embedding.close()
        melspectrogram.close()
    }

    companion object {
        const val DEFAULT_FEATURE_FRAMES: Int = 16

        fun load(files: WakeWordFiles, environment: OrtEnvironment = OrtEnvironment.getEnvironment()): WakeWordModels {
            val options = OrtSession.SessionOptions().apply {
                // One thread. The panel has cores to spare, but this runs forever and every
                // extra thread is a wake-up that keeps the CPU out of its idle states.
                setIntraOpNumThreads(1)
                setInterOpNumThreads(1)
            }
            return WakeWordModels(
                environment = environment,
                melspectrogram = environment.createSession(files.melspectrogram.absolutePath, options),
                embedding = environment.createSession(files.embedding.absolutePath, options),
                classifier = environment.createSession(files.classifier.absolutePath, options),
                phrase = files.phrase,
            )
        }
    }
}

/** Where the three model files live, and what phrase the classifier listens for. */
data class WakeWordFiles(
    val melspectrogram: File,
    val embedding: File,
    val classifier: File,
    val phrase: String,
)

/**
 * openWakeWord, as a microphone sink.
 *
 * Runs melspectrogram → embedding → classifier on every 80 ms frame and reports when the
 * score crosses [threshold]. Cost is three small inferences per frame; the reference measures
 * 15–20 of these running at once in real time on a single Raspberry Pi 3 core, so one on a
 * Nord CE is not a number worth optimising.
 *
 * [patience] and [refractoryFrames] are the two knobs that matter in a room:
 *  - patience requires N consecutive frames over the threshold, trading a little latency for
 *    far fewer false fires from a television;
 *  - the refractory period stops one spoken phrase being detected three times as it passes
 *    through the window.
 *
 * `dobby-plan.md` §9 is explicit that the right values are measured, not reasoned about.
 */
class WakeWordDetector(
    private val models: WakeWordModels,
    /**
     * The score a frame must reach. **No default, on purpose.**
     *
     * The right number depends on what the microphone did to the signal before the graphs saw
     * it: openWakeWord was trained on unprocessed audio, and the telephony chain's AGC and
     * noise suppression shift the score distribution. A number measured under one microphone
     * profile is meaningless under the other, so there is nothing sensible to default to and
     * every caller goes through [forProfile].
     */
    val threshold: Float,
    /** Consecutive frames over [threshold] before firing. Per-profile, for the same reason. */
    val patience: Int,
    private val refractoryFrames: Int = DEFAULT_REFRACTORY_FRAMES,
    private val onDetected: (Float) -> Unit,
) : WakeListener {

    /** What this classifier head was trained for — the phrase the status line names. */
    override val phrase: String get() = models.phrase

    private val environment = OrtEnvironment.getEnvironment()
    private val audio = AudioWindow()
    private val mels = MelBuffer()
    private val embeddings = EmbeddingBuffer()

    /** Guards the native sessions and the buffers against [close] racing the audio thread. */
    private val gate = Any()

    private var consecutive = 0
    private var refractory = 0
    private var closed = false

    /** Last score, for a debug readout. Volatile rather than locked: it is only ever displayed. */
    @Volatile
    var lastScore: Float = 0f
        private set

    override fun onFrames(frame: ShortArray, length: Int) {
        synchronized(gate) {
            if (closed) return
            val window = audio.push(frame, length) ?: return
            mels.append(melspectrogram(window))
            val melWindow = mels.window() ?: return
            embeddings.append(embed(melWindow))
            val features = embeddings.window(models.featureFrames) ?: return
            score(classify(features))
        }
    }

    private fun score(value: Float) {
        lastScore = value

        if (refractory > 0) {
            refractory--
            return
        }

        if (value < threshold) {
            consecutive = 0
            return
        }

        consecutive++
        if (consecutive < patience) return

        consecutive = 0
        refractory = refractoryFrames
        onDetected(value)
    }

    private fun melspectrogram(window: FloatArray): List<FloatArray> {
        val shape = longArrayOf(1, window.size.toLong())
        return OnnxTensor.createTensor(environment, FloatBuffer.wrap(window), shape).use { input ->
            models.melspectrogram.run(mapOf(models.melspectrogramInput to input)).use { result ->
                @Suppress("UNCHECKED_CAST")
                val raw = flatten(result[0].value)
                val rows = raw.size / MelBuffer.MEL_BINS
                List(rows) { row ->
                    FloatArray(MelBuffer.MEL_BINS) { bin ->
                        melTransform(raw[row * MelBuffer.MEL_BINS + bin])
                    }
                }
            }
        }
    }

    private fun embed(melWindow: FloatArray): FloatArray {
        val shape = longArrayOf(1, MelBuffer.WINDOW.toLong(), MelBuffer.MEL_BINS.toLong(), 1)
        return OnnxTensor.createTensor(environment, FloatBuffer.wrap(melWindow), shape).use { input ->
            models.embedding.run(mapOf(models.embeddingInput to input)).use { result ->
                flatten(result[0].value)
            }
        }
    }

    private fun classify(features: FloatArray): Float {
        val shape = longArrayOf(1, models.featureFrames.toLong(), EmbeddingBuffer.EMBEDDING_SIZE.toLong())
        return OnnxTensor.createTensor(environment, FloatBuffer.wrap(features), shape).use { input ->
            models.classifier.run(mapOf(models.classifierInput to input)).use { result ->
                flatten(result[0].value).firstOrNull() ?: 0f
            }
        }
    }

    /** Forgets everything heard so far — used when the mic was closed and the stream has a hole. */
    override fun reset() {
        synchronized(gate) {
            audio.clear()
            mels.clear()
            embeddings.clear()
            consecutive = 0
            refractory = 0
        }
    }

    override fun close() {
        synchronized(gate) {
            if (closed) return
            closed = true
        }
    }

    companion object {
        /**
         * The one way to build a detector: with the tuning that belongs to the open microphone.
         *
         * This exists so that the day somebody measures a threshold, there is exactly one place
         * it can be applied and no way to apply the quiet-room number to the processed stream
         * by forgetting a parameter (`m2b-plan.md` B2).
         */
        fun forProfile(
            models: WakeWordModels,
            profile: MicProfile,
            onDetected: (Float) -> Unit,
        ): WakeWordDetector = WakeWordDetector(
            models = models,
            threshold = profile.threshold,
            patience = profile.patience,
            onDetected = onDetected,
        )

        /** ~1.5 s during which one phrase cannot fire twice. */
        const val DEFAULT_REFRACTORY_FRAMES: Int = 19
    }
}

/**
 * ONNX outputs arrive as nested Java arrays whose depth depends on the graph's rank.
 *
 * The three graphs here return rank 2, 3 and 4 tensors, and all this code wants is the values
 * in order, so flattening once beats three shapes of unchecked cast.
 */
private fun flatten(value: Any?): FloatArray {
    val out = ArrayList<Float>()
    fun walk(node: Any?) {
        when (node) {
            is FloatArray -> node.forEach { out += it }
            is Array<*> -> node.forEach { walk(it) }
            is Float -> out += node
            else -> Unit
        }
    }
    walk(value)
    return out.toFloatArray()
}
