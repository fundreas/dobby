package io.dobby.pipeline.download

import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import kotlin.coroutines.coroutineContext

/**
 * Fetching a model file, with progress.
 *
 * Both model stores need the same thing and neither needs more: a URL, a destination, and a
 * percentage to show someone waiting. Shared so the Vosk model and the wake word cannot drift
 * into two different ideas of what a failed download leaves behind.
 *
 * Call it from a context where blocking is allowed; it never switches dispatchers itself.
 */
internal object Downloader {

    /** Downloads [url] to [target], reporting 0–100 as it goes. Deletes a partial file on failure. */
    suspend fun fetch(url: String, target: File, onProgress: (Int) -> Unit = {}) {
        target.parentFile?.mkdirs()
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.instanceFollowRedirects = true
        try {
            connection.connect()
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("$url answered ${connection.responseCode}")
            }
            val total = connection.contentLengthLong
            connection.inputStream.use { source ->
                target.outputStream().use { sink -> copy(source, sink, total, onProgress) }
            }
        } catch (e: Throwable) {
            target.delete()
            throw e
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun copy(source: InputStream, sink: OutputStream, total: Long, onProgress: (Int) -> Unit) {
        val buffer = ByteArray(BUFFER)
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
                    onProgress(percent)
                }
            }
        }
    }

    const val BUFFER: Int = 64 * 1024
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val PERCENT = 100
}
