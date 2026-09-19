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

    data class Downloading(val phrase: String, val percent: Int) : WakeWordState

    data class Ready(val phrase: String) : WakeWordState

    data class Failed(val reason: String) : WakeWordState
}

/**
 * Gets openWakeWord's three ONNX files onto the device.
 *
 * The 2.4 MB front-end is shared by every phrase and downloaded once. A classifier head is
 * ~200 KB (community) or ~1.3 MB (openWakeWord's own), fetched the first time that phrase is
 * chosen and then kept — switching back to a phrase you have used before is instant and works
 * offline.
 *
 * **A model you supply yourself still wins.** The catalogue is a convenience, not a gate:
 *
 * ```sh
 * adb push hey_dobby.onnx /sdcard/Android/data/io.dobby.android/files/wakeword/
 * ```
 *
 * Anything in the directory that is not a catalogue entry is treated as yours, appears in
 * settings under a name derived from its filename, and is selectable like the rest. That is
 * the path "Hey Dobby" arrives by once it has been trained (`dobby-plan.md` §4).
 */
class WakeWordModelStore(private val root: File) {

    private val _state = MutableStateFlow<WakeWordState>(WakeWordState.Absent)
    val state: StateFlow<WakeWordState> = _state.asStateFlow()

    private val directory = File(root, "wakeword")

    /**
     * Everything selectable: the catalogue, plus any classifier already pushed to the device.
     *
     * Cheap — it lists a directory. The settings screen calls it every time it opens so a file
     * pushed while the app was running shows up without a restart.
     */
    fun options(): List<WakeWordOption> = WakeWordCatalogue.ALL + sideloaded()

    /**
     * Downloads what the chosen phrase needs and returns the three files.
     *
     * Returns null on failure — the reason is in [state], because the caller's job is to carry
     * on without a wake word, not to handle an exception.
     */
    suspend fun ensureAvailable(selected: String?): WakeWordFiles? = withContext(Dispatchers.IO) {
        val option = resolve(selected)
        try {
            directory.mkdirs()

            val melspectrogram = File(directory, MELSPECTROGRAM)
            val embedding = File(directory, EMBEDDING)
            for (shared in listOf(melspectrogram, embedding)) {
                if (!shared.isFile) {
                    Downloader.fetch("$OPENWAKEWORD_RELEASE/${shared.name}", shared) {
                        _state.value = WakeWordState.Downloading(option.phrase, it)
                    }
                }
            }

            val classifier = File(directory, option.file)
            if (!classifier.isFile) {
                Downloader.fetch(option.url, classifier, option.sha256) {
                    _state.value = WakeWordState.Downloading(option.phrase, it)
                }
            }

            _state.value = WakeWordState.Ready(option.phrase)
            WakeWordFiles(melspectrogram, embedding, classifier, option.phrase)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            _state.value = WakeWordState.Failed(e.message ?: e.toString())
            null
        }
    }

    /**
     * The chosen phrase, falling back to the default.
     *
     * A selection can name something that is gone — a sideloaded file deleted between runs, or
     * a settings value from an older build. Falling back to the default beats a panel that
     * silently stops listening because of a stale preference.
     */
    private fun resolve(selected: String?): WakeWordOption =
        options().firstOrNull { it.id == selected } ?: WakeWordCatalogue.DEFAULT

    /** Any `.onnx` in the directory that is neither a shared model nor a catalogue entry. */
    private fun sideloaded(): List<WakeWordOption> = directory.listFiles()
        ?.filter { it.isFile && it.extension == "onnx" }
        ?.filterNot { it.name == MELSPECTROGRAM || it.name == EMBEDDING }
        ?.filter { WakeWordCatalogue.byFile(it.name) == null }
        ?.sortedBy { it.name }
        ?.map { file ->
            WakeWordOption(
                id = "file:${file.name}",
                phrase = phraseFor(file),
                file = file.name,
                // Already on disk, so there is nothing to fetch and nothing to verify against.
                url = "",
                sha256 = "",
            )
        }
        .orEmpty()

    private companion object {
        const val OPENWAKEWORD_RELEASE =
            "https://github.com/dscripka/openWakeWord/releases/download/v0.5.1"
        const val MELSPECTROGRAM = "melspectrogram.onnx"
        const val EMBEDDING = "embedding_model.onnx"
    }
}

/**
 * A readable phrase from a model filename: `hey_dobby.onnx` → "Hey Dobby".
 *
 * Only used for files somebody pushed themselves; catalogue entries carry their own wording.
 * The graph holds no metadata, so the filename is the only thing that knows what the model
 * listens for — which means whoever trains one chooses what the screen says by naming it.
 */
internal fun phraseFor(classifier: File): String = classifier.name
    .removeSuffix(".onnx")
    .replace(Regex("_v\\d+(\\.\\d+)*$"), "")
    .split('_', '-')
    .filter { it.isNotBlank() }
    .joinToString(" ") { word ->
        word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
    }
    .ifBlank { "Weckwort" }
