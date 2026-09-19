package io.dobby.llama

import android.util.Log
import java.io.File

/**
 * The Kotlin side of `libdobby-llama.so`.
 *
 * Ten native functions, all `long` handles and strings. Nothing here is thread-safe and nothing
 * here needs to be: [io.dobby.llama.LlamaTier2] runs every call on one dedicated thread, so
 * load, prefill and generate can never overlap and the context needs no lock.
 *
 * **Nothing here throws.** Every failure is a null or a false with a reason in [unavailable],
 * because Tier 2 is an addition to a panel that already works without it.
 */
class Llama(private val log: (String) -> Unit = { Log.w(TAG, it) }) {

    private var model: Long = 0
    private var context: Long = 0

    /** Why the library or the model is not usable, in German, or null. */
    var unavailable: String? = null
        private set

    /** Tokens of system prefix currently in the KV cache. Zero until [prefillSystem]. */
    var systemTokens: Int = 0
        private set

    val loaded: Boolean get() = context != 0L

    /**
     * Loads the native library, but only after the CPU has been checked.
     *
     * The check is the whole point and the order is the whole point: `System.loadLibrary` on a
     * device without the instructions the library was built for is a SIGILL at the first
     * matmul, which on a `START_STICKY` service is a boot loop rather than an error. Reading
     * `/proc/cpuinfo` first costs microseconds and turns that into a sentence in the settings
     * screen.
     */
    fun open(): Boolean {
        if (libraryLoaded) return true
        val features = cpuFeatures()
        val missing = REQUIRED_CPU_FEATURES.filterNot { it in features }
        if (missing.isNotEmpty()) {
            unavailable = "Tier 2 deaktiviert (CPU ohne ${missing.joinToString(", ")})"
            log("$unavailable; built for ${BuildConfig.CPU_ARCH}, /proc/cpuinfo has $features")
            return false
        }
        return try {
            System.loadLibrary("dobby-llama")
            nativeInit()
            libraryLoaded = true
            true
        } catch (e: UnsatisfiedLinkError) {
            unavailable = "Tier 2 deaktiviert (Bibliothek fehlt)"
            log("loadLibrary failed: ${e.message}")
            false
        }
    }

    /** @param mlock pins the weights in RAM. A measurement, not a default — see `README.md`. */
    fun loadModel(gguf: File, mlock: Boolean = false): Boolean {
        if (!open()) return false
        if (!gguf.isFile) {
            unavailable = "Tier 2 deaktiviert (Modell fehlt)"
            return false
        }
        model = nativeLoadModel(gguf.absolutePath, mlock)
        if (model == 0L) {
            unavailable = "Tier 2 deaktiviert (Modell nicht ladbar)"
            return false
        }
        return true
    }

    fun newContext(
        contextTokens: Int = N_CTX,
        batchTokens: Int = N_BATCH,
        threads: Int = THREADS,
    ): Boolean {
        if (model == 0L) return false
        freeContext()
        context = nativeNewContext(model, contextTokens, batchTokens, threads)
        if (context == 0L) {
            unavailable = "Tier 2 deaktiviert (kein Speicher für den Kontext)"
            return false
        }
        return true
    }

    /** Prefills the system prefix. Returns its token count, or -1. Tens of seconds, once. */
    fun prefillSystem(prefix: String): Int {
        if (context == 0L) return -1
        systemTokens = nativePrefillSystem(context, prefix)
        return systemTokens
    }

    /**
     * One request. [tail] is everything after the system prefix: the utterance and the
     * assistant scaffold.
     */
    fun generate(tail: String, grammar: String, root: String, maxTokens: Int): String? {
        if (context == 0L || systemTokens <= 0) return null
        return nativeGenerate(context, tail, grammar, root, maxTokens)
    }

    /** Asks the current request to stop. Reaches inside `llama_decode`, not just between tokens. */
    fun cancel() {
        if (context != 0L) nativeCancel(context)
    }

    fun tokenCount(text: String): Int = if (context == 0L) -1 else nativeTokenCount(context, text)

    fun timings(): Timings {
        if (context == 0L) return Timings(0, 0, 0, 0)
        val parts = nativeTimings(context).split(",").mapNotNull { it.toLongOrNull() }
        if (parts.size != 4) return Timings(0, 0, 0, 0)
        return Timings(parts[0], parts[1], parts[2].toInt(), parts[3].toInt())
    }

    /**
     * Gives back the context — about 280 MiB — and keeps the model mapped.
     *
     * This is §9's "order of retreat" as a real operation: `onTrimMemory(RUNNING_CRITICAL)`
     * calls it, and the context re-prefills in the background afterwards. The weights stay
     * mmapped because they are file-backed pages the kernel can drop by itself.
     */
    fun freeContext() {
        if (context != 0L) nativeFreeContext(context)
        context = 0
        systemTokens = 0
    }

    fun close() {
        freeContext()
        if (model != 0L) nativeFreeModel(model)
        model = 0
    }

    /** What one request cost. `resamples` is the early warning for the grammar-filtering risk. */
    data class Timings(
        val prefillMicros: Long,
        val decodeMicros: Long,
        val tokens: Int,
        /** How many tokens needed the full grammar-filtered resample. See the shim's WHY. */
        val resamples: Int,
    ) {
        val tokensPerSecond: Double
            get() = if (decodeMicros <= 0) 0.0 else tokens * 1_000_000.0 / decodeMicros
    }

    private external fun nativeInit()

    private external fun nativeLoadModel(path: String, mlock: Boolean): Long

    private external fun nativeFreeModel(handle: Long)

    private external fun nativeNewContext(model: Long, nCtx: Int, nBatch: Int, nThreads: Int): Long

    private external fun nativeFreeContext(handle: Long)

    private external fun nativePrefillSystem(handle: Long, prefix: String): Int

    private external fun nativeGenerate(
        handle: Long,
        tail: String,
        grammar: String,
        root: String,
        maxTokens: Int,
    ): String?

    private external fun nativeCancel(handle: Long)

    private external fun nativeTokenCount(handle: Long, text: String): Int

    private external fun nativeTimings(handle: Long): String

    companion object {
        private const val TAG = "dobby-llama"

        @Volatile
        private var libraryLoaded = false

        /**
         * The `Features` entries the library's `-march` requires.
         *
         * Must agree with `cpuArch` in this module's `build.gradle.kts`; `CpuFeatureGateTest`
         * asserts that it does. `asimddp` is the kernel's name for `+dotprod`, and it is the
         * one that is optional on an A55 — which is the cluster that binds here.
         */
        val REQUIRED_CPU_FEATURES: List<String> = listOf("asimddp")

        /** `n_ctx = 2048`: the prompt is ~1500, the utterance is short, the reply is 40. */
        const val N_CTX: Int = 2048

        const val N_BATCH: Int = 256

        /**
         * Threads for decode. **Measured, not inherited.**
         *
         * `ParakeetRecognizer.THREADS = 4` is right for a workload that shares the CPU with the
         * wake word. Tier 2 never runs concurrently with STT, and ggml synchronises at a
         * barrier per op — so four threads spread over 2×A77 + 2×A55 can be *slower* than two
         * pinned to the big cores, because every op waits for the slowest thread.
         *
         * `Tier2ModelTest` sweeps {2, 4, 8} and the winner belongs here with its measured
         * tok/s. Until that has run on a real 750G this is the conservative guess, and it is
         * labelled as one.
         */
        const val THREADS: Int = 2

        /** Reads the `Features` line of `/proc/cpuinfo`. Empty if it cannot be read. */
        fun cpuFeatures(source: () -> String? = ::readCpuInfo): Set<String> {
            val text = source() ?: return emptySet()
            val line = text.lineSequence().firstOrNull { it.startsWith("Features") } ?: return emptySet()
            return line.substringAfter(':', "").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.toSet()
        }

        private fun readCpuInfo(): String? = try {
            File("/proc/cpuinfo").readText()
        } catch (_: Exception) {
            null
        }
    }
}
