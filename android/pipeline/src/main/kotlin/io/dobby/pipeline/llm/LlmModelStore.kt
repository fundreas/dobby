package io.dobby.pipeline.llm

import io.dobby.pipeline.download.Downloader
import io.dobby.pipeline.download.Verified
import io.dobby.pipeline.stt.RemoteFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Where the LLM weights are, or how far along getting them we are. */
sealed interface LlmState {
    data object Absent : LlmState

    data class Downloading(val percent: Int) : LlmState

    /** Hashing a gigabyte. Once per download, not once per start — see [Verified]. */
    data object Verifying : LlmState

    data class Ready(val gguf: File) : LlmState

    data class Failed(val reason: String) : LlmState
}

/**
 * Gets the Tier 2 GGUF onto the device, once.
 *
 * Mirrors `SpeechModelStore` deliberately — same states, same file-granular retry, same "a file
 * that fails its checksum is deleted and fetched again". It lives in this module rather than in
 * `:android:llama` because [Downloader] is internal here, and because a second downloader would
 * be a second idea of what a failed download leaves behind.
 *
 * One difference worth naming: Tier 2 is **optional**. Speech failing means a panel that cannot
 * hear; this failing means a panel that cannot paraphrase, which is the product as it shipped
 * last milestone. So nothing here blocks startup and nothing here is retried automatically.
 */
class LlmModelStore(
    private val root: File,
    private val file: RemoteFile = LlmModels.gguf,
) {
    private val _state = MutableStateFlow<LlmState>(LlmState.Absent)
    val state: StateFlow<LlmState> = _state.asStateFlow()

    /** The path, whether or not it exists yet. */
    fun resolved(): File = File(root, file.path)

    /** True if the weights are on disk and intact, without downloading anything. */
    suspend fun isPresent(): Boolean = withContext(Dispatchers.IO) { intact(resolved()) }

    /**
     * Returns the GGUF, fetching it if it is missing. Null on failure, with the reason in
     * [state] — the caller's job is to carry on without Tier 2, not to handle an exception.
     */
    suspend fun ensureAvailable(): File? = withContext(Dispatchers.IO) {
        val target = resolved()
        try {
            root.mkdirs()
            if (intact(target)) {
                _state.value = LlmState.Ready(target)
                return@withContext target
            }
            target.delete()
            _state.value = LlmState.Downloading(0)
            Downloader.fetch(file.url, target, file.sha256) {
                _state.value = LlmState.Downloading(it)
            }
            // Downloader hashed it on the way in, so record the stamp rather than reading the
            // gigabyte a second time to learn what we already know.
            Verified.write(target, file.sha256)
            _state.value = LlmState.Ready(target)
            target
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            _state.value = LlmState.Failed(e.message ?: e.toString())
            null
        }
    }

    /** Frees the gigabyte. For a settings screen that offers to turn Tier 2 off for good. */
    fun delete(): Boolean {
        val target = resolved()
        Verified.clear(target)
        _state.value = LlmState.Absent
        return target.delete()
    }

    private fun intact(target: File): Boolean = Verified.isIntact(
        file = target,
        expectedSha256 = file.sha256,
        expectedLength = file.bytes,
        onHashing = { _state.value = LlmState.Verifying },
    )
}
