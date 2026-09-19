package io.dobby.pipeline.wakeword

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The SNR harness's own arithmetic, on synthetic audio, always.
 *
 * [WakeWordSnrSweepTest] skips itself without recordings, which means its mixer would otherwise
 * first be exercised on the evening somebody sits down to take the measurement — and a mixer
 * that is quietly wrong produces a curve that looks like a finding. This is the part that can
 * be checked without a microphone, so it is.
 */
class SnrMixTest {

    @Test
    fun `a 16 kHz mono fixture round-trips`() {
        val samples = ShortArray(1000) { Random(3).nextInt(-8000, 8000).toShort() }
        val read = SnrMix.readWav(wav(samples), "synthetic.wav")
        assertEquals(samples.toList(), read.toList())
    }

    @Test
    fun `a fixture in the wrong format is rejected by name`() {
        // The failure mode this prevents: 44.1 kHz stereo read as 16 kHz mono is noise, the
        // whole curve sits at zero, and nothing says why.
        val wrong = wav(ShortArray(1000), rate = 44_100, channels = 2)
        val message = try {
            SnrMix.readWav(wrong, "stereo.wav")
            fail("a 44.1 kHz stereo file was accepted as a fixture")
        } catch (e: IllegalArgumentException) {
            e.message.orEmpty()
        }
        assertTrue("stereo.wav" in message, message)
        assertTrue("44100Hz" in message, message)
    }

    @Test
    fun `a chunk an editor left behind is skipped rather than read as audio`() {
        val samples = ShortArray(64) { 1000 }
        val read = SnrMix.readWav(wav(samples, extraChunk = true), "tagged.wav")
        assertEquals(samples.toList(), read.toList())
    }

    @Test
    fun `at 0 dB the music is as loud as the voice, and 20 dB quieter at 20`() {
        val voice = ShortArray(16_000) { Random(5).nextInt(-6000, 6000).toShort() }
        val bed = ShortArray(16_000) { Random(9).nextInt(-6000, 6000).toShort() }

        // Measured in the lead-in, which is music and nothing else — the one window where the
        // two are separable after mixing.
        val at0 = SnrMix.rms(SnrMix.mix(voice, bed, 0).copyOfRange(0, SnrMix.LEAD_IN_SAMPLES))
        val at20 = SnrMix.rms(SnrMix.mix(voice, bed, 20).copyOfRange(0, SnrMix.LEAD_IN_SAMPLES))

        assertTrue(abs(at0 - SnrMix.rms(voice)) / SnrMix.rms(voice) < 0.05, "0 dB: $at0")
        val ratio = at0 / at20
        assertTrue(abs(ratio - 10.0) < 0.5, "20 dB should be a factor of ten quieter, was $ratio")
    }

    @Test
    fun `the voice sits between one second of music on each side`() {
        val voice = ShortArray(800) { 5000 }
        val bed = ShortArray(400) { 100 }

        val mixed = SnrMix.mix(voice, bed, 20)

        assertEquals(voice.size + 2 * SnrMix.LEAD_IN_SAMPLES, mixed.size)
        // Loud in the middle, quiet at both ends: the phrase is where it was put.
        val middle = SnrMix.rms(mixed.copyOfRange(SnrMix.LEAD_IN_SAMPLES, SnrMix.LEAD_IN_SAMPLES + voice.size))
        val head = SnrMix.rms(mixed.copyOfRange(0, SnrMix.LEAD_IN_SAMPLES))
        val tail = SnrMix.rms(mixed.copyOfRange(mixed.size - SnrMix.LEAD_IN_SAMPLES, mixed.size))
        assertTrue(middle > head * 5, "middle $middle, head $head")
        assertTrue(middle > tail * 5, "middle $middle, tail $tail")
    }

    @Test
    fun `a short music bed repeats instead of running out`() {
        // A bed that stopped would turn the second half of every long phrase into a clean
        // recording, and the curve would say the detector copes with music better than it does.
        val voice = ShortArray(32_000) { 4000 }
        val bed = ShortArray(160) { 2000 }

        val mixed = SnrMix.mix(voice, bed, 0)

        val lastSecond = mixed.copyOfRange(mixed.size - SnrMix.LEAD_IN_SAMPLES, mixed.size)
        assertTrue(SnrMix.rms(lastSecond) > 100, "the bed ran out: ${SnrMix.rms(lastSecond)}")
    }

    /** A minimal RIFF/WAVE file, optionally with a junk chunk between `fmt ` and `data`. */
    private fun wav(
        samples: ShortArray,
        rate: Int = SnrMix.SAMPLE_RATE,
        channels: Int = 1,
        extraChunk: Boolean = false,
    ): ByteArray {
        val extra = if (extraChunk) EXTRA_CHUNK_BYTES else 0
        val data = samples.size * 2
        val buffer = ByteBuffer.allocate(44 + extra + data).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray())
        buffer.putInt(36 + extra + data)
        buffer.put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray())
        buffer.putInt(16)
        buffer.putShort(1) // PCM
        buffer.putShort(channels.toShort())
        buffer.putInt(rate)
        buffer.putInt(rate * channels * 2)
        buffer.putShort((channels * 2).toShort())
        buffer.putShort(16)
        if (extraChunk) {
            // An odd payload, so the pad byte every RIFF chunk is aligned with is exercised:
            // a reader that forgets it ends up one byte into the audio and reads garbage.
            buffer.put("LIST".toByteArray())
            buffer.putInt(EXTRA_CHUNK_PAYLOAD)
            repeat(EXTRA_CHUNK_PAYLOAD + 1) { buffer.put(0) }
        }
        buffer.put("data".toByteArray())
        buffer.putInt(data)
        for (sample in samples) buffer.putShort(sample)
        return buffer.array()
    }

    private companion object {
        const val EXTRA_CHUNK_PAYLOAD = 7

        /** Chunk header, odd payload, and the pad byte that follows it. */
        const val EXTRA_CHUNK_BYTES = 8 + EXTRA_CHUNK_PAYLOAD + 1
    }
}
