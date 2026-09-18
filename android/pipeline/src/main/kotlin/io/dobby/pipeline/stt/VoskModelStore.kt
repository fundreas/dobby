package io.dobby.pipeline.stt

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
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
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        try {
            connection.connect()
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("the model server answered ${connection.responseCode}")
            }
            val total = connection.contentLengthLong
            connection.inputStream.use { source ->
                into.outputStream().use { sink ->
                    copyReportingProgress(source, sink, total)
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun copyReportingProgress(source: InputStream, sink: java.io.OutputStream, total: Long) {
        val buffer = ByteArray(COPY_BUFFER)
        var written = 0L
        var lastPercent = -1
        while (true) {
            coroutineContext.ensureActive()
            val read = source.read(buffer)
            if (read < 0) break
            sink.write(buffer, 0, read)
            written += read
            if (total > 0) {
                val percent = (written * PERCENT / total).toInt()
                if (percent != lastPercent) {
                    lastPercent = percent
                    _state.value = ModelState.Downloading(percent)
                }
            }
        }
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
                    file.outputStream().use { zip.copyTo(it, COPY_BUFFER) }
                }
                zip.closeEntry()
            }
        }
    }

    private fun marker(target: File) = File(target, ".dobby-complete")

    companion object {
        const val DEFAULT_MODEL: String = "vosk-model-small-de-0.15"
        const val MODEL_BASE_URL: String = "https://alphacephei.com/vosk/models"

        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val COPY_BUFFER = 64 * 1024
        private const val PERCENT = 100
    }
}
