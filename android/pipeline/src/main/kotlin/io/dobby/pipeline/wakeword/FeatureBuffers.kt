package io.dobby.pipeline.wakeword

/**
 * The sliding buffers between openWakeWord's three models.
 *
 * Pure, and separated from the ONNX sessions on purpose. Every constant here is a number
 * copied from the reference implementation, and getting one wrong does not throw — it
 * produces a detector that silently never fires, which is the worst possible failure mode
 * because it looks exactly like "the wake word just isn't very good". So the arithmetic is
 * unit-tested on its own, with the model graphs checked separately against the same numbers.
 *
 * The chain, per 80 ms frame:
 *
 * ```
 * 1280 new samples ──► [+480 of overlap] ──► melspectrogram ──► 8 rows of 32 mel bins
 *                                                                      │
 *                                     last 76 rows ──► embedding ──► 96 floats
 *                                                                      │
 *                                   last N vectors ──► classifier ──► score 0…1
 * ```
 */
internal class MelBuffer(private val capacity: Int = MEL_CAPACITY) {
    private val rows = ArrayDeque<FloatArray>()

    val size: Int get() = rows.size

    fun append(frames: List<FloatArray>) {
        rows.addAll(frames)
        while (rows.size > capacity) rows.removeFirst()
    }

    /**
     * The most recent [WINDOW] rows flattened for the embedding model, or null if the buffer
     * has not filled yet.
     *
     * The reference pre-fills this with ones so it can predict from the first frame. Dobby
     * waits instead: ~760 ms of real audio at startup costs nothing on a panel that runs for
     * weeks, and it keeps synthetic values out of a score that decides whether to open the
     * microphone.
     */
    fun window(): FloatArray? {
        if (rows.size < WINDOW) return null
        val out = FloatArray(WINDOW * MEL_BINS)
        var at = 0
        for (i in rows.size - WINDOW until rows.size) {
            rows[i].copyInto(out, at)
            at += MEL_BINS
        }
        return out
    }

    fun clear() = rows.clear()

    companion object {
        /** Mel bins per frame. Fixed by the melspectrogram model. */
        const val MEL_BINS: Int = 32

        /** Mel frames the embedding model consumes at once. */
        const val WINDOW: Int = 76

        /** ~10 s of history, as in the reference (10 × 97 frames per second). */
        const val MEL_CAPACITY: Int = 970
    }
}

/** The rolling window of 96-dimensional speech embeddings the classifier reads. */
internal class EmbeddingBuffer(private val capacity: Int = EMBEDDING_CAPACITY) {
    private val vectors = ArrayDeque<FloatArray>()

    val size: Int get() = vectors.size

    fun append(embedding: FloatArray) {
        vectors.addLast(embedding)
        while (vectors.size > capacity) vectors.removeFirst()
    }

    /** The most recent [frames] embeddings flattened for the classifier, or null if too few. */
    fun window(frames: Int): FloatArray? {
        if (vectors.size < frames) return null
        val out = FloatArray(frames * EMBEDDING_SIZE)
        var at = 0
        for (i in vectors.size - frames until vectors.size) {
            vectors[i].copyInto(out, at)
            at += EMBEDDING_SIZE
        }
        return out
    }

    fun clear() = vectors.clear()

    companion object {
        /** Output width of Google's frozen speech-embedding model. */
        const val EMBEDDING_SIZE: Int = 96

        /** ~10 s of feature history, as in the reference. */
        const val EMBEDDING_CAPACITY: Int = 120
    }
}

/**
 * The raw audio the melspectrogram model sees for one frame.
 *
 * Not just the 1280 new samples: the reference feeds the new chunk plus 480 samples of the
 * previous one (`-n_samples - 160*3`), because a mel frame spans 25 ms with a 10 ms hop and
 * the overlap is what makes the streaming spectrogram continuous with the batch one. Feeding
 * only the new samples yields 5 rows per frame instead of 8 — the detector still runs, and
 * never fires.
 */
internal class AudioWindow(private val frameLength: Int = FRAME, private val overlap: Int = OVERLAP) {
    private val samples = ShortArray(frameLength + overlap)
    private var filled = 0

    /** Appends one frame and returns the window to transform, or null until primed. */
    fun push(frame: ShortArray, length: Int): FloatArray? {
        require(length == frameLength) { "expected $frameLength samples per frame, got $length" }

        samples.copyInto(samples, 0, frameLength, samples.size)
        frame.copyInto(samples, overlap, 0, frameLength)
        if (filled < overlap) {
            // The very first frame has no predecessor to overlap with. Skipping it costs 80 ms
            // once; padding it with zeros would put a transient into the first spectrogram.
            filled = overlap
            return null
        }

        return FloatArray(samples.size) { samples[it].toFloat() }
    }

    fun clear() {
        samples.fill(0)
        filled = 0
    }

    companion object {
        const val FRAME: Int = 1280

        /** 160 samples per 10 ms hop, three hops — the reference's `160*3`. */
        const val OVERLAP: Int = 480

        /** Mel rows the model returns for [FRAME] + [OVERLAP] samples: `ceil(1760/160 - 3)`. */
        const val ROWS_PER_FRAME: Int = 8
    }
}

/**
 * The melspectrogram transform the reference applies to the ONNX model's raw output.
 *
 * `x/10 + 2`, which exists to bring this ONNX graph into line with Google's original
 * TensorFlow implementation. Without it the embedding model is fed values it was never
 * trained on and scores never rise.
 */
internal fun melTransform(value: Float): Float = value / 10f + 2f
