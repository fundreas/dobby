package io.dobby.pipeline.wakeword

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The arithmetic behind the SNR sweep: read a fixture, and put a voice over music at a chosen
 * signal-to-noise ratio.
 *
 * Separate from [WakeWordSnrSweepTest] because the sweep skips itself without fixtures, and a
 * mixer that is only ever exercised on somebody's laptop the day they record some is a mixer
 * that is wrong the first time it matters. [SnrMixTest] runs on synthetic audio, always.
 */
internal object SnrMix {

    /** One second of music before the phrase starts, and again after it ends. */
    const val LEAD_IN_SAMPLES: Int = 16_000

    const val SAMPLE_RATE: Int = 16_000
    const val BITS: Int = 16

    /**
     * One phrase over one music bed at [snr] decibels, with music-only lead-in and tail.
     *
     * The lead-in matters: openWakeWord scores a rolling window, and a phrase that begins on the
     * first frame is a phrase whose window is half silence. In a room the music is already
     * playing when somebody starts talking, so the fixture should be too.
     *
     * The bed repeats if it is shorter than the phrase — an eight-second loop of music is a
     * fixture; a rule that every bed must be long enough is a chore.
     */
    fun mix(phrase: ShortArray, bed: ShortArray, snr: Int): ShortArray {
        require(bed.isNotEmpty()) { "empty music bed" }
        val length = phrase.size + 2 * LEAD_IN_SAMPLES
        val gain = rms(phrase) / (rms(bed).coerceAtLeast(1.0) * 10.0.pow(snr / 20.0))
        val out = ShortArray(length)
        for (i in 0 until length) {
            val music = bed[i % bed.size] * gain
            val voice = if (i >= LEAD_IN_SAMPLES && i - LEAD_IN_SAMPLES < phrase.size) {
                phrase[i - LEAD_IN_SAMPLES].toDouble()
            } else {
                0.0
            }
            out[i] = (voice + music)
                .coerceIn(Short.MIN_VALUE.toDouble(), Short.MAX_VALUE.toDouble())
                .roundToInt()
                .toShort()
        }
        return out
    }

    fun rms(samples: ShortArray): Double {
        if (samples.isEmpty()) return 0.0
        var sum = 0.0
        for (sample in samples) sum += sample.toDouble() * sample.toDouble()
        return sqrt(sum / samples.size)
    }

    /** Every WAV in a resource directory, in a stable order. Empty when there is no directory. */
    fun wavsIn(path: String, loader: ClassLoader?): List<ShortArray> {
        val directory = loader?.getResource(path)?.let { File(it.toURI()) }
        if (directory == null || !directory.isDirectory) return emptyList()
        return directory.listFiles().orEmpty()
            .filter { it.isFile && it.extension.lowercase() == "wav" }
            .sortedBy { it.name }
            .map { readWav(it.readBytes(), it.name) }
    }

    /**
     * 16 kHz mono 16-bit PCM, and nothing else.
     *
     * A reader of its own because sherpa-onnx's is native and lives on the device — and because
     * a fixture in the wrong format is otherwise a silent wrong answer: a 44.1 kHz stereo file
     * read as though it were 16 kHz mono is noise, and the whole curve would sit at zero with
     * nothing to explain it. So the format is checked and named in the failure.
     */
    fun readWav(bytes: ByteArray, name: String): ShortArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(buffer.capacity() > HEADER_MINIMUM) { "$name: too short to be a WAV" }
        require(buffer.int == RIFF) { "$name: not a WAV" }
        buffer.int // total size
        require(buffer.int == WAVE) { "$name: not a WAV" }

        var channels = 0
        var rate = 0
        var bits = 0
        while (buffer.remaining() >= CHUNK_HEADER) {
            val id = buffer.int
            val size = buffer.int
            when (id) {
                FMT -> {
                    val end = buffer.position() + size
                    require(buffer.short.toInt() == PCM) { "$name: not uncompressed PCM" }
                    channels = buffer.short.toInt()
                    rate = buffer.int
                    buffer.int // byte rate
                    buffer.short // block align
                    bits = buffer.short.toInt()
                    buffer.position(end)
                }

                DATA -> {
                    require(channels == 1 && rate == SAMPLE_RATE && bits == BITS) {
                        "$name: ${rate}Hz ${channels}ch ${bits}bit — fixtures must be " +
                            "${SAMPLE_RATE}Hz mono $BITS-bit, the format the pipeline runs on"
                    }
                    val samples = ShortArray(minOf(size, buffer.remaining()) / 2)
                    for (i in samples.indices) samples[i] = buffer.short
                    return samples
                }

                // Anything else — LIST, fact, whatever an editor left behind. Chunks are padded
                // to an even length, and a reader that forgets that ends up one byte out.
                else -> buffer.position(buffer.position() + size + (size % 2))
            }
        }
        throw IllegalArgumentException("$name: no data chunk")
    }

    private const val PCM = 1
    private const val RIFF = 0x46464952
    private const val WAVE = 0x45564157
    private const val FMT = 0x20746d66
    private const val DATA = 0x61746164
    private const val CHUNK_HEADER = 8
    private const val HEADER_MINIMUM = 44
}
