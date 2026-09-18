package io.dobby.pipeline.wakeword

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class FeatureBuffersTest {

    @Test
    fun `the audio window carries the previous frame's tail into the next`() {
        val window = AudioWindow()
        val first = ShortArray(AudioWindow.FRAME) { 1 }
        val second = ShortArray(AudioWindow.FRAME) { 2 }

        // Nothing to overlap with yet, so the first frame primes and produces nothing.
        assertNull(window.push(first, first.size))

        val out = assertNotNull(window.push(second, second.size))
        assertEquals(AudioWindow.FRAME + AudioWindow.OVERLAP, out.size)
        // The first 480 samples are the tail of frame one; the rest is frame two.
        assertEquals(1f, out[0])
        assertEquals(1f, out[AudioWindow.OVERLAP - 1])
        assertEquals(2f, out[AudioWindow.OVERLAP])
        assertEquals(2f, out.last())
    }

    @Test
    fun `a short frame is a programming error, not something to paper over`() {
        // Every sink gets whole frames from AudioSource. A partial one means the contract
        // broke upstream, and silently padding it would produce a spectrogram of a lie.
        val window = AudioWindow()
        assertFailsWith<IllegalArgumentException> { window.push(ShortArray(512), 512) }
    }

    @Test
    fun `the mel buffer yields nothing until it holds a full window`() {
        val mels = MelBuffer()
        val row = { FloatArray(MelBuffer.MEL_BINS) { 1f } }

        repeat(9) { mels.append(List(AudioWindow.ROWS_PER_FRAME) { row() }) }
        assertEquals(72, mels.size)
        assertNull(mels.window(), "72 rows is under the 76 the embedding model needs")

        mels.append(List(AudioWindow.ROWS_PER_FRAME) { row() })
        assertEquals(MelBuffer.WINDOW * MelBuffer.MEL_BINS, assertNotNull(mels.window()).size)
    }

    @Test
    fun `the mel window is the most recent rows, in order`() {
        val mels = MelBuffer()
        var counter = 0f
        repeat(10) {
            mels.append(List(AudioWindow.ROWS_PER_FRAME) { FloatArray(MelBuffer.MEL_BINS) { counter } .also { counter++ } })
        }

        val window = assertNotNull(mels.window())
        // 80 rows appended, the last 76 kept: the window starts at row 4.
        assertEquals(4f, window[0])
        assertEquals(79f, window[(MelBuffer.WINDOW - 1) * MelBuffer.MEL_BINS])
    }

    @Test
    fun `buffers are bounded so a panel can run for weeks`() {
        val mels = MelBuffer(capacity = 16)
        repeat(100) { mels.append(List(8) { FloatArray(MelBuffer.MEL_BINS) }) }
        assertEquals(16, mels.size)

        val embeddings = EmbeddingBuffer(capacity = 20)
        repeat(500) { embeddings.append(FloatArray(EmbeddingBuffer.EMBEDDING_SIZE)) }
        assertEquals(20, embeddings.size)
    }

    @Test
    fun `the classifier window is the newest N embeddings, flattened`() {
        val embeddings = EmbeddingBuffer()
        assertNull(embeddings.window(16))

        repeat(20) { index ->
            embeddings.append(FloatArray(EmbeddingBuffer.EMBEDDING_SIZE) { index.toFloat() })
        }

        val window = assertNotNull(embeddings.window(16))
        assertEquals(16 * EmbeddingBuffer.EMBEDDING_SIZE, window.size)
        // Embeddings 4..19 survive; 0..3 have rolled off.
        assertEquals(4f, window[0])
        assertEquals(19f, window.last())
    }

    @Test
    fun `resetting drops everything heard so far`() {
        val window = AudioWindow()
        window.push(ShortArray(AudioWindow.FRAME) { 5 }, AudioWindow.FRAME)
        window.clear()
        // After a clear the window must prime again rather than splice audio from before the
        // gap onto audio from after it.
        assertNull(window.push(ShortArray(AudioWindow.FRAME) { 6 }, AudioWindow.FRAME))
    }
}
