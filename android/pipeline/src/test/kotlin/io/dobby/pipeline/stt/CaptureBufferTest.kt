package io.dobby.pipeline.stt

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The arithmetic between the microphone and Parakeet.
 *
 * Neither half can fail loudly. A scale mistake in the PCM conversion gives a recogniser that
 * works and is quietly worse; an off-by-one in the cap gives an utterance that is 10.08 seconds
 * long, which no assertion on a phone would ever notice. Both are pure, so both are tested here
 * rather than by talking to a wall.
 */
class CaptureBufferTest {

    @Test
    fun `full-scale PCM lands inside minus one to one`() {
        val frame = shortArrayOf(0, 1, -1, Short.MAX_VALUE, Short.MIN_VALUE)
        val into = FloatArray(frame.size)

        assertEquals(frame.size, pcmToFloat(frame, frame.size, into))
        assertTrue(into.all { it in -1f..1f }, "outside range: ${into.toList()}")
        assertEquals(0f, into[0])
        assertTrue(abs(into[3] - 0.99997f) < 1e-4, "peak positive was ${into[3]}")
        assertEquals(-1f, into[4])
    }

    @Test
    fun `only the requested samples are converted`() {
        // AudioSource hands a reused array and a length; a sink that reads past the length gets
        // the tail of the previous frame, which is audible as a stutter and invisible in code.
        val frame = shortArrayOf(16384, 16384, 32767, 32767)
        val into = FloatArray(4) { 9f }

        assertEquals(2, pcmToFloat(frame, 2, into))
        assertEquals(0.5f, into[0])
        assertEquals(0.5f, into[1])
        assertEquals(9f, into[2], "wrote past the frame length")
    }

    @Test
    fun `the buffer keeps everything it is given, in order`() {
        val buffer = CaptureBuffer(capacity = 10_000)
        repeat(5) { round ->
            val frame = FloatArray(1280) { round.toFloat() }
            assertEquals(1280, buffer.append(frame, frame.size))
        }

        assertEquals(6400, buffer.size)
        assertFalse(buffer.isFull)

        val samples = buffer.toFloatArray()
        assertEquals(6400, samples.size)
        assertEquals(0f, samples[0])
        assertEquals(4f, samples.last())
    }

    @Test
    fun `the cap truncates inside a frame rather than overrunning it`() {
        // The hard cap is the last defence against a VAD that never finds the endpoint. It has
        // to land mid-frame, because frames are 80 ms and 10 s is not a whole number of them
        // for every sample rate someone might set.
        val buffer = CaptureBuffer(capacity = 2000)
        val frame = FloatArray(1280) { 0.25f }

        assertEquals(1280, buffer.append(frame, frame.size))
        assertFalse(buffer.isFull)

        assertEquals(720, buffer.append(frame, frame.size), "took more than the cap allows")
        assertTrue(buffer.isFull)
        assertEquals(2000, buffer.size)

        assertEquals(0, buffer.append(frame, frame.size), "kept taking after the cap")
        assertEquals(2000, buffer.toFloatArray().size)
    }

    @Test
    fun `clearing lets the buffer be used for the next utterance`() {
        val buffer = CaptureBuffer(capacity = 100)
        buffer.append(FloatArray(100) { 1f }, 100)
        assertTrue(buffer.isFull)

        buffer.clear()

        assertEquals(0, buffer.size)
        assertFalse(buffer.isFull)
        assertEquals(50, buffer.append(FloatArray(50) { 2f }, 50))
    }
}
