package io.dobby.pipeline.llm

import io.dobby.pipeline.stt.RemoteFile

/**
 * The GGUF Tier 2 runs on (`dobby-plan.md` §5.4).
 *
 * Qwen3 1.7B at Q4_K_M: 1.03 GiB of weights, which are file-backed mmap pages rather than
 * anonymous memory, plus ~270–305 MiB of context and KV cache that are not.
 *
 * **Why this conversion.** Qwen's own `Qwen/Qwen3-1.7B-GGUF` has no Q4_K_M — only Q8_0 at
 * 1.83 GB, which the memory budget cannot take. `ggml-org/Qwen3-1.7B-GGUF` has one at 1.28 GB
 * and better provenance; it is the fallback if unsloth measures worse on the German paraphrase
 * suite. Whatever chat template the file carries is irrelevant: `PromptGenerator` emits the
 * whole scaffold itself and `llama_chat_apply_template` is never called.
 *
 * Pinned to a **revision** rather than to `main`, for the same reason the speech models are: a
 * checksum against a moving branch is a download that breaks the day upstream re-uploads.
 */
object LlmModels {

    const val REPOSITORY: String = "unsloth/Qwen3-1.7B-GGUF"

    private const val HF_REVISION = "d7f544eead698dbd1f15126ef60b45a1e1933222"

    const val FILE_NAME: String = "Qwen3-1.7B-Q4_K_M.gguf"

    /**
     * The one file.
     *
     * The sha256 is from Hugging Face's own `lfs.sha256` for this revision. It has not been
     * verified against a downloaded copy in this environment — the first device run is what
     * confirms it, and a mismatch shows up as "Modell beschädigt" on the setup screen rather
     * than as a native load failure with no Kotlin stack behind it.
     */
    val gguf: RemoteFile = RemoteFile(
        path = FILE_NAME,
        url = "https://huggingface.co/$REPOSITORY/resolve/$HF_REVISION/$FILE_NAME",
        sha256 = "b139949c5bd74937ad8ed8c8cf3d9ffb1e99c866c823204dc42c0d91fa181897",
        bytes = 1_107_409_472,
    )

    val all: List<RemoteFile> = listOf(gguf)
}
