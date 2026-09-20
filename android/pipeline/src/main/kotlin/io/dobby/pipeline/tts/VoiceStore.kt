package io.dobby.pipeline.tts

import io.dobby.pipeline.download.Downloader
import io.dobby.pipeline.download.Verified
import io.dobby.pipeline.stt.DownloadProgress
import io.dobby.pipeline.stt.RemoteFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * Where a voice is, or how far along getting it we are.
 *
 * `VoiceModelState` rather than `VoiceState`, which
 * [io.dobby.pipeline.VoiceState] already is — the pipeline's own state and a download's are
 * two different sentences about two different things, and one name for both would make
 * `VoiceIo` read as though `state` and `voiceState` were the same kind of answer.
 */
sealed interface VoiceModelState {
    data object Absent : VoiceModelState

    data class Downloading(val name: String, val percent: Int) : VoiceModelState

    /** Hashing what is already on disk — a second on 114 MB, and silent without a state. */
    data object Verifying : VoiceModelState

    data class Ready(val name: String) : VoiceModelState

    data class Failed(val reason: String) : VoiceModelState
}

/** The two paths a Piper voice is constructed from. */
data class VoiceFiles(val model: File, val tokens: File)

/**
 * Gets a Piper voice onto the device.
 *
 * 114 MB per voice, downloaded the first time it is chosen and then kept — switching back to a
 * voice you have used before is instant and works offline. Same shape as
 * [io.dobby.pipeline.wakeword.WakeWordModelStore] and for the same reasons: a catalogue of
 * options, per-file SHA-256, a [Verified] stamp so the hash is read once per download rather
 * than once per start, and a [StateFlow] the settings row renders instead of a boolean it
 * waits on.
 *
 * **A voice you supply yourself is first-class.** Any directory under `files/voices/` holding
 * one `*.onnx` and a `tokens.txt` is offered in settings, named from the directory. That is how
 * a `medium` export, `thorsten_emotional`, or any other Piper voice gets tried without a
 * rebuild:
 *
 * ```sh
 * adb push vits-piper-de_DE-thorsten-medium /data/local/tmp/
 * adb shell run-as io.dobby.android sh -c \
 *   'mkdir -p files/voices && cp -r /data/local/tmp/vits-piper-de_DE-thorsten-medium files/voices/'
 * ```
 */
class VoiceStore(private val root: File) {

    private val _state = MutableStateFlow<VoiceModelState>(VoiceModelState.Absent)
    val state: StateFlow<VoiceModelState> = _state.asStateFlow()

    private val directory = File(root, VoiceCatalogue.DIRECTORY)

    /**
     * Everything selectable: the catalogue, plus any voice already pushed to the device.
     *
     * Cheap — it lists a directory. The settings screen calls it every time it opens, so a
     * voice pushed while the app was running shows up without a restart.
     */
    fun options(): List<VoiceOption> = VoiceCatalogue.ALL + sideloaded()

    /** Whether [option] is on disk already, without downloading anything. */
    fun isPresent(option: VoiceOption): Boolean {
        if (option.isSystem) return true
        val files = resolve(option) ?: return false
        return files.model.isFile && files.tokens.isFile
    }

    /**
     * Downloads what [option] needs and returns its two paths.
     *
     * Returns null on failure — the reason is in [state], because the caller's job is to carry
     * on in another voice, not to handle an exception. Null for the system voice too: it has no
     * files, and asking this for it is a caller that has not looked at [VoiceOption.isSystem].
     */
    suspend fun ensureAvailable(option: VoiceOption): VoiceFiles? = withContext(Dispatchers.IO) {
        if (option.isSystem) return@withContext null
        val progress = DownloadProgress(option.files)
        try {
            directory.mkdirs()
            for (file in option.files) {
                val target = File(root, file.path)
                progress.begin(file)
                if (!isIntact(target, file)) {
                    target.delete()
                    _state.value = VoiceModelState.Downloading(option.name, progress.percent)
                    Downloader.fetch(file.url, target, file.sha256) {
                        progress.advance(it)
                        _state.value = VoiceModelState.Downloading(option.name, progress.percent)
                    }
                }
                progress.complete(file)
                _state.value = VoiceModelState.Downloading(option.name, progress.percent)
            }
            resolve(option)?.also { _state.value = VoiceModelState.Ready(option.name) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            _state.value = VoiceModelState.Failed(e.message ?: e.toString())
            null
        }
    }

    /**
     * Records that a voice on disk could not be used after all.
     *
     * The download is only half of "getting a voice onto the device": phoneme data that could
     * not be copied and a graph that will not open are the same thing to the settings row, and
     * this is the flow it reads. Said here rather than invented in the pipeline so there is one
     * place a voice's state comes from.
     */
    fun failed(reason: String) {
        _state.value = VoiceModelState.Failed(reason)
    }

    /**
     * Frees the 114 MB a voice costs.
     *
     * Nothing calls it from the UI yet; it exists because a settings screen that offers three
     * voices on a phone with 7.5 GB of storage will want to, and because the alternative —
     * discovering there is no way back from "downloaded" — is a worse place to add it from.
     */
    fun delete(option: VoiceOption) {
        if (option.isSystem) return
        val target = File(directory, option.directory)
        if (target.isDirectory) target.deleteRecursively()
        if (_state.value is VoiceModelState.Ready) _state.value = VoiceModelState.Absent
    }

    /** The paths a voice would have, whether or not they exist yet. */
    fun resolve(option: VoiceOption): VoiceFiles? {
        if (option.isSystem) return null
        val folder = File(directory, option.directory)
        // A catalogue voice knows its own filenames; a sideloaded one is whatever is in there.
        val model = option.files.firstOrNull { it.path.endsWith(ONNX) }
            ?.let { File(root, it.path) }
            ?: folder.listFiles()?.firstOrNull { it.isFile && it.extension == "onnx" }
            ?: return null
        return VoiceFiles(model, File(folder, VoiceCatalogue.TOKENS))
    }

    /**
     * Any directory under `voices/` that holds a graph and a token table and is not ours.
     *
     * No URL and no checksum, because there is nothing to fetch and nothing to compare a
     * sideloaded file against — the same bargain [io.dobby.pipeline.wakeword.WakeWordModelStore]
     * makes for a classifier somebody trained themselves.
     */
    private fun sideloaded(): List<VoiceOption> = directory.listFiles()
        ?.filter { it.isDirectory }
        ?.filter { VoiceCatalogue.byDirectory(it.name) == null }
        ?.filter { folder ->
            File(folder, VoiceCatalogue.TOKENS).isFile &&
                folder.listFiles()?.any { it.isFile && it.extension == "onnx" } == true
        }
        ?.sortedBy { it.name }
        ?.map { folder ->
            VoiceOption(
                id = "dir:${folder.name}",
                name = nameFor(folder.name),
                // Unknowable from a directory, and the tag is now the answer language — so an
                // empty one means German, which is the default a sideload silently inherits.
                // Guessing from the directory name (`vits-piper-en_GB-…`) is a guess about
                // somebody else's file naming; naming it in the UI is the honest fix, and it
                // needs a settings row rather than a regex.
                language = "",
                description = "Eigene Stimme · ${folder.name} · Antwortet auf Deutsch",
                directory = folder.name,
                files = emptyList(),
            )
        }
        .orEmpty()

    private fun isIntact(target: File, expected: RemoteFile): Boolean = Verified.isIntact(
        file = target,
        expectedSha256 = expected.sha256,
        expectedLength = expected.bytes,
        onHashing = { _state.value = VoiceModelState.Verifying },
    )

    private companion object {
        const val ONNX = ".onnx"
    }
}

/**
 * A readable name from a voice directory: `vits-piper-de_DE-thorsten-medium` → "Thorsten Medium".
 *
 * Only used for directories somebody pushed themselves; catalogue entries carry their own
 * wording. The Piper naming convention is `vits-piper-<lang>-<voice>-<quality>`, so dropping
 * the first three segments leaves the two a person would have said out loud. Anything that
 * does not look like that is title-cased as it stands, which is better than blank.
 */
internal fun nameFor(directory: String): String {
    val parts = directory.split('-', '_').filter { it.isNotBlank() }
    val tail = if (parts.size > PREFIX && parts[0] == "vits" && parts[1] == "piper") {
        // vits, piper, <lang>, <region> — `de_DE` splits into two on the underscore.
        parts.drop(PREFIX)
    } else {
        parts
    }
    return tail.joinToString(" ") { word ->
        word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
    }.ifBlank { "Stimme" }
}

/** `vits`, `piper`, and the two halves of a locale tag like `de_DE`. */
private const val PREFIX = 4
