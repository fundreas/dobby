package io.dobby.pipeline.stt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The first-run download, as a person waiting on it experiences it.
 *
 * 670 MB over a flat's wifi is minutes of somebody watching a number. A number that jumps
 * backwards, or sits at 2 % for four minutes and then finishes, is the difference between
 * waiting and force-quitting — so the weighting is a test, not a hope.
 */
class SpeechModelsTest {

    @Test
    fun `the manifest is the model the recogniser is configured for`() {
        // The paths here and the `modelType` in ParakeetRecognizer describe one model. Swapping
        // the manifest without the config gives a recogniser that loads and returns nonsense.
        val paths = SpeechModels.all.map { it.path }
        assertEquals(
            listOf(
                "${SpeechModels.PARAKEET_DIRECTORY}/encoder.int8.onnx",
                "${SpeechModels.PARAKEET_DIRECTORY}/decoder.int8.onnx",
                "${SpeechModels.PARAKEET_DIRECTORY}/joiner.int8.onnx",
                "${SpeechModels.PARAKEET_DIRECTORY}/tokens.txt",
                SpeechModels.VAD_FILE,
            ),
            paths,
        )
    }

    @Test
    fun `every file is pinned by size and checksum`() {
        for (file in SpeechModels.all) {
            assertTrue(file.bytes > 0, "${file.path} has no expected size")
            assertEquals(64, file.sha256.length, "${file.path} has no SHA-256")
            assertTrue(
                file.sha256.all { it in "0123456789abcdef" },
                "${file.path} checksum is not lowercase hex: ${file.sha256}",
            )
        }
    }

    @Test
    fun `the Hugging Face URLs are pinned to a revision, not to a branch`() {
        // A checksum against `main` is a download that breaks the day upstream re-uploads.
        val hf = SpeechModels.all.filter { it.url.contains("huggingface.co") }
        assertEquals(4, hf.size)
        assertTrue(hf.none { it.url.contains("/resolve/main/") }, "a URL still points at main")
    }

    @Test
    fun `progress is weighted by size, so it never stalls and never jumps back`() {
        val small = RemoteFile("small", "u", "s", bytes = 1_000)
        val huge = RemoteFile("huge", "u", "s", bytes = 99_000)
        val progress = DownloadProgress(listOf(small, huge))

        progress.begin(small)
        progress.advance(100)
        // The small file is 1 % of the work, and saying so is the honest thing: a bar that
        // reaches 50 % on the first of two files is a bar that then appears to hang.
        assertEquals(1, progress.percent)
        progress.complete(small)
        assertEquals(1, progress.percent)

        progress.begin(huge)
        progress.advance(50)
        assertEquals(50, progress.percent)
        progress.complete(huge)
        assertEquals(100, progress.percent)
    }

    @Test
    fun `a retried file does not send the bar backwards`() {
        // A download that fails at 80 % and is re-fetched restarts at 0 within the file. The
        // overall number must not follow it down — that reads as lost work.
        val one = RemoteFile("a", "u", "s", bytes = 500)
        val two = RemoteFile("b", "u", "s", bytes = 500)
        val progress = DownloadProgress(listOf(one, two))

        progress.begin(one)
        progress.advance(80)
        assertEquals(40, progress.percent)

        progress.advance(0)
        assertEquals(40, progress.percent, "the bar went backwards")

        progress.advance(100)
        progress.complete(one)
        progress.begin(two)
        assertEquals(50, progress.percent)
    }
}
