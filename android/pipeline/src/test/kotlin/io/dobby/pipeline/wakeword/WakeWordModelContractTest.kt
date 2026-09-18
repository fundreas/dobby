package io.dobby.pipeline.wakeword

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The wake word's inference chain, run against the real openWakeWord graphs.
 *
 * This test exists because of one specific failure mode. Every constant in [FeatureBuffers] —
 * the 480-sample overlap, the 8 mel rows per frame, the 76-row window, `x/10 + 2` — was read
 * off a Python reference and reimplemented in Kotlin. Get any of them wrong and nothing
 * throws: the models still run, the scores are just always near zero, and the only symptom is
 * a panel that does not answer to its name. No amount of unit-testing the buffers in isolation
 * catches that, because the buffers would be self-consistently wrong.
 *
 * So this asserts the arithmetic against the graphs themselves, on the JVM, with no phone
 * involved. The models are ~3.7 MB and cached under `build/` after the first run.
 */
class WakeWordModelContractTest {

    private fun models(): WakeWordFiles? {
        val directory = File("build/oww-models")
        val files = WakeWordFiles(
            melspectrogram = File(directory, "melspectrogram.onnx"),
            embedding = File(directory, "embedding_model.onnx"),
            classifier = File(directory, "hey_jarvis_v0.1.onnx"),
            phrase = "Hey Jarvis",
        )
        val all = listOf(files.melspectrogram, files.embedding, files.classifier)
        if (all.all { it.isFile }) return files

        directory.mkdirs()
        return try {
            for (file in all) {
                if (!file.isFile) {
                    URI_BASE.plus("/${file.name}").let { url ->
                        java.net.URI(url).toURL().openStream().use { input ->
                            file.outputStream().use { input.copyTo(it) }
                        }
                    }
                }
            }
            files
        } catch (e: java.io.IOException) {
            all.forEach { it.delete() }
            null
        }
    }

    private fun session(environment: OrtEnvironment, file: File) =
        environment.createSession(file.absolutePath, OrtSession.SessionOptions())

    @Test
    fun `the melspectrogram model turns one frame plus overlap into exactly 8 rows of 32`() {
        val files = models()
        assumeTrue(files != null, "openWakeWord models unavailable (offline?)")
        val environment = OrtEnvironment.getEnvironment()

        session(environment, files!!.melspectrogram).use { melspectrogram ->
            val samples = AudioWindow.FRAME + AudioWindow.OVERLAP
            val audio = FloatArray(samples) { (Random(1).nextInt(-2000, 2000)).toFloat() }

            OnnxTensor.createTensor(
                environment,
                FloatBuffer.wrap(audio),
                longArrayOf(1, samples.toLong()),
            ).use { input ->
                melspectrogram.run(mapOf(melspectrogram.inputNames.first() to input)).use { result ->
                    val shape = (result[0].info as TensorInfo).shape
                    val values = shape.fold(1L) { a, b -> a * b }

                    assertEquals(
                        MelBuffer.MEL_BINS.toLong(),
                        shape.last(),
                        "mel bins per row",
                    )
                    assertEquals(
                        (AudioWindow.ROWS_PER_FRAME * MelBuffer.MEL_BINS).toLong(),
                        values,
                        "1280 samples plus ${AudioWindow.OVERLAP} of overlap must yield " +
                            "${AudioWindow.ROWS_PER_FRAME} rows — feeding only the new samples yields 5, " +
                            "and a detector built on 5 never fires",
                    )
                }
            }
        }
    }

    @Test
    fun `the embedding model takes a 76x32 window and returns 96 numbers`() {
        val files = models()
        assumeTrue(files != null, "openWakeWord models unavailable (offline?)")
        val environment = OrtEnvironment.getEnvironment()

        session(environment, files!!.embedding).use { embedding ->
            val window = FloatArray(MelBuffer.WINDOW * MelBuffer.MEL_BINS) { 2f }
            OnnxTensor.createTensor(
                environment,
                FloatBuffer.wrap(window),
                longArrayOf(1, MelBuffer.WINDOW.toLong(), MelBuffer.MEL_BINS.toLong(), 1),
            ).use { input ->
                embedding.run(mapOf(embedding.inputNames.first() to input)).use { result ->
                    val values = (result[0].info as TensorInfo).shape.fold(1L) { a, b -> a * b }
                    assertEquals(EmbeddingBuffer.EMBEDDING_SIZE.toLong(), values)
                }
            }
        }
    }

    @Test
    fun `the classifier declares how many feature frames it wants`() {
        val files = models()
        assumeTrue(files != null, "openWakeWord models unavailable (offline?)")

        WakeWordModels.load(files!!).use { loaded ->
            assertEquals(
                WakeWordModels.DEFAULT_FEATURE_FRAMES,
                loaded.featureFrames,
                "the stock models use 16; a custom model may differ, which is why it is read",
            )
            assertEquals("Hey Jarvis", loaded.phrase)
        }
    }

    @Test
    fun `silence and noise do not wake the panel`() {
        val files = models()
        assumeTrue(files != null, "openWakeWord models unavailable (offline?)")

        WakeWordModels.load(files!!).use { loaded ->
            val detections = mutableListOf<Float>()
            val detector = WakeWordDetector(loaded) { detections += it }

            // Four seconds of silence, then four of white noise. Neither is a wake word, and a
            // panel that fires on either is a panel nobody leaves switched on.
            val silence = ShortArray(AudioWindow.FRAME)
            repeat(FRAMES_PER_SECOND * 4) { detector.onFrames(silence, silence.size) }
            assertTrue(detections.isEmpty(), "silence fired the wake word")
            assertTrue(detector.lastScore < 0.1f, "silence scored ${detector.lastScore}")

            val random = Random(7)
            val noise = ShortArray(AudioWindow.FRAME)
            repeat(FRAMES_PER_SECOND * 4) {
                for (i in noise.indices) noise[i] = random.nextInt(-8000, 8000).toShort()
                detector.onFrames(noise, noise.size)
            }
            assertTrue(detections.isEmpty(), "white noise fired the wake word")

            detector.close()
        }
    }

    @Test
    fun `the chain produces real, varying scores rather than a constant`() {
        val files = models()
        assumeTrue(files != null, "openWakeWord models unavailable (offline?)")

        // The failure this catches: a pipeline wired so the classifier sees the same buffer
        // every time still returns a plausible-looking number — it just never changes. If the
        // score does not move as the audio changes, nothing downstream can work.
        WakeWordModels.load(files!!).use { loaded ->
            val detector = WakeWordDetector(loaded) { }
            val scores = mutableListOf<Float>()
            val random = Random(11)
            val frame = ShortArray(AudioWindow.FRAME)

            repeat(FRAMES_PER_SECOND * 6) { index ->
                val amplitude = if (index % 20 < 10) 200 else 12000
                for (i in frame.indices) frame[i] = random.nextInt(-amplitude, amplitude).toShort()
                detector.onFrames(frame, frame.size)
                scores += detector.lastScore
            }

            val distinct = scores.map { (it * 1000).toInt() }.distinct()
            assertTrue(distinct.size > 5, "score never moved: ${distinct.take(5)}")
            assertTrue(scores.all { it in 0f..1f }, "scores outside 0..1: ${scores.filter { it !in 0f..1f }}")
            detector.close()
        }
    }

    @Test
    fun `the mel transform matches the reference`() {
        // x/10 + 2. Cheap to assert, and its absence is invisible: without it the embedding
        // model is fed values it was never trained on and every score stays flat.
        assertTrue(abs(melTransform(0f) - 2f) < 1e-6)
        assertTrue(abs(melTransform(10f) - 3f) < 1e-6)
        assertTrue(abs(melTransform(-20f) - 0f) < 1e-6)
    }

    private companion object {
        const val URI_BASE = "https://github.com/dscripka/openWakeWord/releases/download/v0.5.1"

        /** 1000 ms / 80 ms. */
        const val FRAMES_PER_SECOND = 12
    }
}
