package io.dobby.pipeline.stt

/**
 * One file that has to be on the device before Dobby can hear.
 *
 * [bytes] is not decoration: it is what makes a five-file download show one honest percentage
 * instead of five that each restart at zero. [sha256] is what makes a truncated 622 MB encoder
 * a message on the setup screen instead of a native load failure with no Kotlin stack.
 */
data class RemoteFile(
    /** Path under the model root, `/`-separated. */
    val path: String,
    val url: String,
    val sha256: String,
    val bytes: Long,
)

/**
 * What the speech recogniser is made of (`dobby-plan.md` §4).
 *
 * Parakeet is a NeMo TDT transducer: three ONNX graphs plus a token table, fetched from the
 * conversion the sherpa-onnx author publishes. Silero VAD is the end-of-speech detector that
 * a batch recogniser needs and a streaming one did not (§5.2).
 *
 * The Hugging Face URLs are pinned to a **revision**, not to `main`. A checksum against a
 * moving branch is a download that breaks the day upstream re-uploads a file; against a
 * revision it is a download that keeps working.
 */
object SpeechModels {

    /** Where the Parakeet files live under the model root, and what the recogniser is told. */
    const val PARAKEET_DIRECTORY: String = "sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8"

    const val VAD_FILE: String = "silero_vad.onnx"

    private const val HF_REVISION = "2bda32ec70b097a55adaa07d9a7173915b43cc78"

    private const val HF_BASE =
        "https://huggingface.co/csukuangfj/$PARAKEET_DIRECTORY/resolve/$HF_REVISION"

    private const val SHERPA_MODELS =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models"

    val encoder: RemoteFile = parakeet(
        "encoder.int8.onnx",
        "acfc2b4456377e15d04f0243af540b7fe7c992f8d898d751cf134c3a55fd2247",
        652_184_281,
    )

    val decoder: RemoteFile = parakeet(
        "decoder.int8.onnx",
        "179e50c43d1a9de79c8a24149a2f9bac6eb5981823f2a2ed88d655b24248db4e",
        11_845_275,
    )

    val joiner: RemoteFile = parakeet(
        "joiner.int8.onnx",
        "3164c13fc2821009440d20fcb5fdc78bff28b4db2f8d0f0b329101719c0948b3",
        6_355_277,
    )

    val tokens: RemoteFile = parakeet(
        "tokens.txt",
        "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d",
        93_939,
    )

    /**
     * Silero VAD v4, as sherpa-onnx redistributes it.
     *
     * 640 KB against Parakeet's 670 MB — it is the cheapest part of the pipeline and the one
     * that decides when the sentence ended, which is the part a person actually feels.
     */
    val vad: RemoteFile = RemoteFile(
        path = VAD_FILE,
        url = "$SHERPA_MODELS/$VAD_FILE",
        sha256 = "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6",
        bytes = 643_854,
    )

    /** Biggest first, so the percentage moves slowly and then quickly rather than the reverse. */
    val all: List<RemoteFile> = listOf(encoder, decoder, joiner, tokens, vad)

    private fun parakeet(name: String, sha256: String, bytes: Long) = RemoteFile(
        path = "$PARAKEET_DIRECTORY/$name",
        url = "$HF_BASE/$name",
        sha256 = sha256,
        bytes = bytes,
    )
}

/**
 * One percentage across a multi-file download.
 *
 * Five files reporting 0–100 each is five progress bars that each restart, which reads as a
 * download that is going backwards. This weights every file by its size, so the number only
 * ever goes up and 95 % means 95 % of the bytes — which on a 670 MB first run is the difference
 * between waiting and force-quitting.
 *
 * Pure, so the arithmetic is a unit test rather than something you discover on a phone.
 */
class DownloadProgress(files: List<RemoteFile>) {

    private val total: Long = files.sumOf { it.bytes }

    /** Bytes belonging to files already finished. */
    private var done: Long = 0

    private var current: Long = 0

    private var currentPercent: Int = 0

    /** 0–100 across every file, never decreasing. */
    var percent: Int = 0
        private set

    /** The file [file] is now the one being fetched. */
    fun begin(file: RemoteFile) {
        current = file.bytes
        currentPercent = 0
        recompute()
    }

    /** [value] is 0–100 within the current file. */
    fun advance(value: Int) {
        currentPercent = value.coerceIn(0, FULL)
        recompute()
    }

    /** The current file is on disk — verified, or already there when the run started. */
    fun complete(file: RemoteFile) {
        done += file.bytes
        current = 0
        currentPercent = 0
        recompute()
    }

    private fun recompute() {
        if (total <= 0) {
            percent = FULL
            return
        }
        val bytes = done + current * currentPercent / FULL
        percent = maxOf(percent, (bytes * FULL / total).toInt().coerceIn(0, FULL))
    }

    private companion object {
        const val FULL = 100
    }
}
