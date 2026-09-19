package io.dobby.pipeline.stt

import io.dobby.pipeline.download.Downloader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Where the speech models are, or how far along getting them we are. */
sealed interface ModelState {
    data object Absent : ModelState

    data class Downloading(val percent: Int) : ModelState

    /** Hashing what is already on disk — seconds on 670 MB, and silent without a state. */
    data object Verifying : ModelState

    data class Ready(val models: SpeechModelFiles) : ModelState

    data class Failed(val reason: String) : ModelState
}

/** The paths the recogniser and the VAD are constructed from. */
data class SpeechModelFiles(
    val encoder: File,
    val decoder: File,
    val joiner: File,
    val tokens: File,
    val vad: File,
)

/**
 * Gets Parakeet TDT 0.6B v3 and Silero VAD onto the device, once.
 *
 * ~670 MB, downloaded on first run into the app's files dir next to the LLM GGUF and never
 * bundled (`dobby-plan.md` §4). That is not a close call at this size: the APK is sideloaded
 * over adb, and 670 MB in git, in every build and in every install would buy one network round
 * trip per device.
 *
 * The cost is that Dobby is deaf until the download finishes, which is why [state] is a flow
 * the setup screen renders rather than a boolean it waits on.
 *
 * **Granularity is the file.** A run that dies inside the 622 MB encoder keeps the four small
 * files and re-fetches only that one; there is no byte-range resume, because a failed download
 * costs one retry and resume logic costs a class nobody can test. A file that is present but
 * whose SHA-256 does not match is deleted and fetched again — a half-written encoder is
 * otherwise a native load failure with no Kotlin stack behind it.
 */
class SpeechModelStore(
    private val root: File,
    private val files: List<RemoteFile> = SpeechModels.all,
) {
    private val _state = MutableStateFlow<ModelState>(ModelState.Absent)
    val state: StateFlow<ModelState> = _state.asStateFlow()

    /**
     * Returns the model paths, fetching what is missing first.
     *
     * Returns null on failure — the reason is in [state], because the caller's job is to keep
     * going without speech recognition, not to handle an exception.
     */
    suspend fun ensureAvailable(): SpeechModelFiles? = withContext(Dispatchers.IO) {
        val progress = DownloadProgress(files)
        try {
            root.mkdirs()
            for (file in files) {
                val target = File(root, file.path)
                progress.begin(file)
                if (!isIntact(target, file)) {
                    target.delete()
                    _state.value = ModelState.Downloading(progress.percent)
                    Downloader.fetch(file.url, target, file.sha256) {
                        progress.advance(it)
                        _state.value = ModelState.Downloading(progress.percent)
                    }
                }
                progress.complete(file)
                _state.value = ModelState.Downloading(progress.percent)
            }
            resolved().also { _state.value = ModelState.Ready(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            _state.value = ModelState.Failed(e.message ?: e.toString())
            null
        }
    }

    /** The paths, whether or not they exist yet. */
    fun resolved(): SpeechModelFiles = SpeechModelFiles(
        encoder = File(root, SpeechModels.encoder.path),
        decoder = File(root, SpeechModels.decoder.path),
        joiner = File(root, SpeechModels.joiner.path),
        tokens = File(root, SpeechModels.tokens.path),
        vad = File(root, SpeechModels.vad.path),
    )

    /**
     * Whether [target] is the file [expected] describes.
     *
     * The length check comes first and settles it for a truncated download without reading
     * 622 MB. The hash is only run on a file that is the right size, and only once per start —
     * a few seconds of storage read against the alternative of trusting a file whose download
     * nobody watched.
     */
    private fun isIntact(target: File, expected: RemoteFile): Boolean {
        if (!target.isFile || target.length() != expected.bytes) return false
        _state.value = ModelState.Verifying
        return Downloader.sha256(target).equals(expected.sha256, ignoreCase = true)
    }
}
