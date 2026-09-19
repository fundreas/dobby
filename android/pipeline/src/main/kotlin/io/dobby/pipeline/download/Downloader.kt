package io.dobby.pipeline.download

import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * Fetching a model file, with progress and a checksum.
 *
 * Both model stores need the same thing and neither needs more: a URL, a destination, and a
 * percentage to show someone waiting. Shared so the speech model and the wake word cannot drift
 * into two different ideas of what a failed download leaves behind.
 *
 * Call it from a context where blocking is allowed; it never switches dispatchers itself.
 */
internal object Downloader {

    /**
     * Downloads [url] to [target], reporting 0–100 as it goes. Deletes a partial file on failure.
     *
     * When [sha256] is given the file is hashed as it is written and discarded if it does not
     * match — a 622 MB encoder that arrived through a captive portal or a truncated CDN response
     * is otherwise a recogniser that fails to load with a native error and no explanation.
     */
    suspend fun fetch(
        url: String,
        target: File,
        sha256: String? = null,
        onProgress: (Int) -> Unit = {},
    ) {
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
            val digest = sha256?.let { MessageDigest.getInstance("SHA-256") }
            connection.inputStream.use { source ->
                target.outputStream().use { sink -> copy(source, sink, total, digest, onProgress) }
            }
            if (digest != null) {
                val actual = digest.digest().toHex()
                if (!actual.equals(sha256, ignoreCase = true)) {
                    throw IOException("$url is not the expected file (sha256 $actual)")
                }
            }
        } catch (e: Throwable) {
            target.delete()
            throw e
        } finally {
            connection.disconnect()
        }
    }

    /** The SHA-256 of a file already on disk, as lowercase hex. */
    fun sha256(file: File): String = file.inputStream().buffered().use { stream ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        digest.digest().toHex()
    }

    private suspend fun copy(
        source: InputStream,
        sink: OutputStream,
        total: Long,
        digest: MessageDigest?,
        onProgress: (Int) -> Unit,
    ) {
        val buffer = ByteArray(BUFFER)
        var written = 0L
        var lastPercent = -1
        while (true) {
            coroutineContext.ensureActive()
            val read = source.read(buffer)
            if (read < 0) break
            sink.write(buffer, 0, read)
            digest?.update(buffer, 0, read)
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

    private fun ByteArray.toHex(): String {
        val out = StringBuilder(size * 2)
        for (byte in this) {
            val value = byte.toInt() and BYTE_MASK
            out.append(HEX[value ushr NIBBLE]).append(HEX[value and NIBBLE_MASK])
        }
        return out.toString()
    }

    const val BUFFER: Int = 64 * 1024
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val PERCENT = 100
    private const val BYTE_MASK = 0xff
    private const val NIBBLE = 4
    private const val NIBBLE_MASK = 0x0f
    private const val HEX = "0123456789abcdef"
}
