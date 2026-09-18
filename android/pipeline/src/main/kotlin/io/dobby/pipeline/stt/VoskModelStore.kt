package io.dobby.pipeline.stt

import io.dobby.pipeline.download.Downloader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

/** Where the German acoustic model is, or how far along getting it we are. */
sealed interface ModelState {
    data object Absent : ModelState

    data class Downloading(val percent: Int) : ModelState

    data object Unpacking : ModelState

    data class Ready(val directory: File) : ModelState

    data class Failed(val reason: String) : ModelState
}

/**
 * Gets `vosk-model-small-de-0.15` onto the device, once.
 *
 * The plan allows bundling it in assets or downloading on first run (§4). Downloading wins:
 * 46 MB of model in the APK is 46 MB in git, in every build and in every install, to save one
 * network round trip that happens exactly once per device.
 *
 * The cost is that Dobby is deaf until the download finishes, which is why [state] is a flow
 * the UI renders rather than a boolean it waits on.
 */
class VoskModelStore(
    private val root: File,
    private val modelName: String = DEFAULT_MODEL,
    private val url: String = "$MODEL_BASE_URL/$modelName.zip",
) {
    private val _state = MutableStateFlow<ModelState>(ModelState.Absent)
    val state: StateFlow<ModelState> = _state.asStateFlow()

    val directory: File get() = File(root, modelName)

    /**
     * Returns the unpacked model directory, fetching it first if this is a fresh install.
     *
     * Returns null on failure — the reason is in [state], because the caller's job is to keep
     * going without speech recognition, not to handle an exception.
     */
    suspend fun ensureAvailable(): File? = withContext(Dispatchers.IO) {
        val target = directory
        if (marker(target).isFile) {
            _state.value = ModelState.Ready(target)
            return@withContext target
        }

        // A directory without the marker is a download that died halfway. Start over.
        target.deleteRecursively()
        val archive = File(root, "$modelName.zip.part")
        try {
            root.mkdirs()
            download(archive)
            _state.value = ModelState.Unpacking
            unzip(archive, root)
            if (!File(target, "am").isDirectory && !File(target, "conf").isDirectory) {
                throw IOException("the archive did not contain $modelName")
            }
            marker(target).writeText(modelName)
            _state.value = ModelState.Ready(target)
            target
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            target.deleteRecursively()
            _state.value = ModelState.Failed(e.message ?: e.toString())
            null
        } finally {
            archive.delete()
        }
    }

    private suspend fun download(into: File) {
        _state.value = ModelState.Downloading(0)
        Downloader.fetch(url, into) { _state.value = ModelState.Downloading(it) }
    }

    private suspend fun unzip(archive: File, into: File) {
        val destination = into.canonicalFile
        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            while (true) {
                coroutineContext.ensureActive()
                val entry = zip.nextEntry ?: break
                val file = File(destination, entry.name).canonicalFile
                // Zip slip: an entry named "../../x" would otherwise write outside filesDir.
                if (!file.path.startsWith(destination.path + File.separator)) {
                    throw IOException("archive entry escapes the model directory: ${entry.name}")
                }
                if (entry.isDirectory) {
                    file.mkdirs()
                } else {
                    file.parentFile?.mkdirs()
                    file.outputStream().use { zip.copyTo(it, Downloader.BUFFER) }
                }
                zip.closeEntry()
            }
        }
    }

    private fun marker(target: File) = File(target, ".dobby-complete")

    companion object {
        const val DEFAULT_MODEL: String = "vosk-model-small-de-0.15"
        const val MODEL_BASE_URL: String = "https://alphacephei.com/vosk/models"

    }
}
