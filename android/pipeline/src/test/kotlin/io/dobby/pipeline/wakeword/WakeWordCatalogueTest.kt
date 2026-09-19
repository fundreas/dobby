package io.dobby.pipeline.wakeword

import io.dobby.pipeline.audio.MicProfile
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every phrase the settings screen offers, run through the real detector.
 *
 * The catalogue mixes two kinds of model: openWakeWord's own `hey_jarvis` at 1.3 MB, and
 * community heads at ~200 KB trained by strangers with a different architecture. They are not
 * interchangeable by assumption — a head that wants a different number of feature frames, or
 * returns a differently shaped output, would load fine and then never fire. Offering a phrase
 * in settings that cannot possibly work is worse than not offering it, so each one is loaded
 * and driven here.
 */
class WakeWordCatalogueTest {

    private val cache = File("build/oww-models")

    private fun download(url: String, target: File): Boolean {
        if (target.isFile) return true
        target.parentFile?.mkdirs()
        return try {
            URI(url).toURL().openStream().use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
            true
        } catch (e: IOException) {
            target.delete()
            false
        }
    }

    private fun shared(): Pair<File, File>? {
        val base = "https://github.com/dscripka/openWakeWord/releases/download/v0.5.1"
        val melspectrogram = File(cache, "melspectrogram.onnx")
        val embedding = File(cache, "embedding_model.onnx")
        val ok = download("$base/melspectrogram.onnx", melspectrogram) &&
            download("$base/embedding_model.onnx", embedding)
        return if (ok) melspectrogram to embedding else null
    }

    @Test
    fun `every offered phrase loads, reports its own shape, and scores real audio`() {
        val front = shared()
        assumeTrue(front != null, "openWakeWord front-end unavailable (offline?)")
        val (melspectrogram, embedding) = front!!

        for (option in WakeWordCatalogue.ALL) {
            val classifier = File(cache, option.file)
            assumeTrue(download(option.url, classifier), "${option.phrase} unavailable (offline?)")

            val files = WakeWordFiles(melspectrogram, embedding, classifier, option.phrase)
            WakeWordModels.load(files).use { loaded ->
                assertTrue(
                    loaded.featureFrames in 1..MAX_FEATURE_FRAMES,
                    "${option.phrase} wants ${loaded.featureFrames} feature frames",
                )

                val detections = mutableListOf<Float>()
                val detector = WakeWordDetector.forProfile(loaded, MicProfile.RECOGNITION) { detections += it }
                val scores = mutableListOf<Float>()
                val random = Random(3)
                val frame = ShortArray(AudioWindow.FRAME)

                // Six seconds alternating quiet and loud noise: enough to fill every buffer
                // several times over, and nothing in it is a wake word.
                repeat(FRAMES_PER_SECOND * 6) { index ->
                    val amplitude = if (index % 20 < 10) 200 else 12000
                    for (i in frame.indices) frame[i] = random.nextInt(-amplitude, amplitude).toShort()
                    detector.onFrames(frame, frame.size)
                    scores += detector.lastScore
                }
                detector.close()

                assertTrue(
                    scores.all { it in 0f..1f },
                    "${option.phrase} produced a score outside 0..1: ${scores.filter { it !in 0f..1f }}",
                )
                // A head wired to a front-end it does not match returns a constant. That the
                // score moves at all is the evidence the three graphs are talking to each
                // other. Compared on the raw floats, not rounded: a well-behaved model sits
                // near 1e-4 on noise, and rounding that to three places hides the signal —
                // which is exactly the false alarm this assertion raised the first time.
                assertTrue(
                    scores.distinct().size > MIN_DISTINCT_SCORES,
                    "${option.phrase} never changed its score — the chain is not connected",
                )
                assertTrue(detections.isEmpty(), "${option.phrase} fired on noise")
                assertTrue(
                    scores.max() < NOISE_CEILING,
                    "${option.phrase} scored ${scores.max()} on noise, close enough to fire",
                )
            }
        }
    }

    @Test
    fun `the download is pinned, because the source can change under a fixed url`() {
        val front = shared()
        assumeTrue(front != null, "openWakeWord front-end unavailable (offline?)")

        for (option in WakeWordCatalogue.ALL) {
            val classifier = File(cache, option.file)
            assumeTrue(download(option.url, classifier), "${option.phrase} unavailable (offline?)")

            val digest = MessageDigest.getInstance("SHA-256")
            classifier.inputStream().buffered().use { stream ->
                val buffer = ByteArray(BUFFER)
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            assertEquals(option.sha256, actual, "${option.phrase} is not the pinned file")
        }
    }

    @Test
    fun `ids are unique, and the default is one of them`() {
        val ids = WakeWordCatalogue.ALL.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "duplicate ids: $ids")
        assertTrue(WakeWordCatalogue.DEFAULT in WakeWordCatalogue.ALL)
        assertEquals(WakeWordCatalogue.DEFAULT, WakeWordCatalogue.byId(null) ?: WakeWordCatalogue.DEFAULT)

        // Ids are persisted in settings, so renaming one silently resets somebody's choice.
        assertEquals(
            listOf("hey_jarvis", "dumbledore", "hola_casita", "scooby", "wall_e", "janet"),
            ids,
        )
    }

    private companion object {
        const val FRAMES_PER_SECOND = 12
        const val MAX_FEATURE_FRAMES = 64
        const val MIN_DISTINCT_SCORES = 5

        /** Noise should not get anywhere near the 0.5 default detection threshold. */
        const val NOISE_CEILING = 0.2f
        const val BUFFER = 64 * 1024
    }
}
