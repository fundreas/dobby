package io.dobby.pipeline.wakeword

import io.dobby.pipeline.download.Downloader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Locale

sealed interface WakeWordState {
    data object Absent : WakeWordState

    data class Downloading(val percent: Int) : WakeWordState

    data class Ready(val phrase: String) : WakeWordState

    data class Failed(val reason: String) : WakeWordState
}

/**
 * Gets openWakeWord's three ONNX files onto the device.
 *
 * ~3.7 MB in total — the 2.4 MB shared front-end plus ~1.3 MB for one wake phrase — against
 * 46 MB for the Vosk model, so the same download-on-first-run treatment costs almost nothing
 * here.
 *
 * **"Hey Dobby" does not exist yet, and cannot be downloaded.** Custom phrases are trained
 * from synthetic Piper speech in openWakeWord's Colab notebook (`dobby-plan.md` §4), which is
 * an hour of somebody's attention and a GPU — not something an app can do at startup. So this
 * store looks for a classifier you have supplied before it falls back to a stock one:
 *
 * ```sh
 * adb push hey_dobby.onnx /sdcard/Android/data/io.dobby.android/files/wakeword/
 * ```
 *
 * Drop the file in and restart; no rebuild, no code change. Until then Dobby answers to
 * [DEFAULT_PHRASE], which is enough to prove the whole path works end to end.
 */
class WakeWordModelStore(
    private val root: File,
    private val releaseUrl: String = RELEASE_URL,
) {
    private val _state = MutableStateFlow<WakeWordState>(WakeWordState.Absent)
    val state: StateFlow<WakeWordState> = _state.asStateFlow()

    private val directory = File(root, "wakeword")

    /** Returns the three model files, downloading the shared pair and a fallback if needed. */
    suspend fun ensureAvailable(): WakeWordFiles? = withContext(Dispatchers.IO) {
        try {
            directory.mkdirs()

            val melspectrogram = File(directory, MELSPECTROGRAM)
            val embedding = File(directory, EMBEDDING)
            for (shared in listOf(melspectrogram, embedding)) {
                if (!shared.isFile) {
                    Downloader.fetch("$releaseUrl/${shared.name}", shared) {
                        _state.value = WakeWordState.Downloading(it)
                    }
                }
            }

            val classifier = findCustomClassifier() ?: run {
                val fallback = File(directory, DEFAULT_MODEL)
                if (!fallback.isFile) {
                    Downloader.fetch("$releaseUrl/$DEFAULT_MODEL", fallback) {
                        _state.value = WakeWordState.Downloading(it)
                    }
                }
                fallback
            }

            val phrase = phraseFor(classifier)
            _state.value = WakeWordState.Ready(phrase)
            WakeWordFiles(melspectrogram, embedding, classifier, phrase)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            _state.value = WakeWordState.Failed(e.message ?: e.toString())
            null
        }
    }

    /**
     * Any `.onnx` in the wake word directory that is not one of the two shared models.
     *
     * A file you pushed yourself wins over the downloaded fallback — that is the whole
     * mechanism by which "Hey Dobby" replaces the stock phrase.
     */
    private fun findCustomClassifier(): File? = directory.listFiles()
        ?.filter { it.isFile && it.extension == "onnx" }
        ?.filterNot { it.name == MELSPECTROGRAM || it.name == EMBEDDING || it.name == DEFAULT_MODEL }
        ?.minByOrNull { it.name }

    private companion object {
        const val RELEASE_URL = "https://github.com/dscripka/openWakeWord/releases/download/v0.5.1"
        const val MELSPECTROGRAM = "melspectrogram.onnx"
        const val EMBEDDING = "embedding_model.onnx"
        const val DEFAULT_MODEL = "hey_jarvis_v0.1.onnx"
        const val DEFAULT_PHRASE = "Hey Jarvis"
    }
}

/**
 * A readable phrase from a model filename: `hey_dobby.onnx` → "Hey Dobby".
 *
 * The file is the only thing that knows which phrase it detects — there is no metadata in the
 * graph — so the panel reads the name. That means whoever trains a model chooses what the
 * screen says by what they call the file, which is the least surprising rule available.
 */
internal fun phraseFor(classifier: File): String = classifier.name
    .removeSuffix(".onnx")
    .replace(Regex("_v\\d+(\\.\\d+)*$"), "")
    .split('_', '-')
    .filter { it.isNotBlank() }
    .joinToString(" ") { word ->
        word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
    }
    .ifBlank { "Hey Dobby" }
