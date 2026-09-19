package io.dobby.llama

import android.util.Log
import io.dobby.core.nlu.llm.GrammarGenerator
import io.dobby.core.nlu.llm.Tier2
import io.dobby.core.nlu.llm.Tier2Program
import io.dobby.core.nlu.llm.Tier2Resolver
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/**
 * [Tier2Resolver] backed by llama.cpp on the device.
 *
 * ### Every failure is a state, never an exception
 *
 * Exactly like `VoicePipeline.prepare()`: CPU feature missing, model missing, download failed,
 * out of memory at load, prompt over the context window, inference timed out, unparsable
 * reply — every one of them leaves [available] false with a reason and the panel behaving
 * exactly as it does today. [prepare] does not throw, and neither does [generate].
 *
 * The one failure that cannot be caught here is a **native crash**, which on a `START_STICKY`
 * service is a boot loop. Two things address it: [Llama.open]'s CPU check removes the
 * predictable cause, and the caller's crash tripwire ([prepare]'s `onAttempt` / `onLoaded`
 * callbacks) covers the rest — set a flag before the first-ever load, clear it after a
 * successful prefill, and if it is still set at the next start, skip Tier 2 permanently.
 *
 * ### One thread, on purpose
 *
 * Load, prefill and generate all run on a single dedicated thread at slightly below normal
 * priority, so they can never overlap and the native context needs no lock. Below normal
 * because the wake word and the recogniser are what a person is actually waiting on; Tier 2 is
 * what they are waiting on only once Tier 1 has already failed.
 */
class LlamaTier2(
    private val gguf: File,
    private val program: Tier2Program,
    private val llama: Llama = Llama(),
    /** True to pin the weights in RAM. See `README.md` — a measurement, not a default. */
    private val mlock: Boolean = false,
    private val log: (String) -> Unit = { Log.i(TAG, it) },
) : Tier2Resolver {

    private val thread: CoroutineDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "dobby-llama").apply {
            priority = Thread.NORM_PRIORITY - 1
            isDaemon = true
        }
    }.asCoroutineDispatcher()

    @Volatile
    private var ready = false

    @Volatile
    private var reason: String? = "Tier 2 lädt noch"

    /** The fingerprint the KV cache was prefilled for. A mismatch re-prefills. */
    @Volatile
    private var prefilledFor: String? = null

    override val available: Boolean get() = ready

    override val unavailableReason: String? get() = reason

    /**
     * Loads the model and prefills the system prefix. Tens of seconds; never throws.
     *
     * Call it from a `scope.launch` of its own *after* `controller.start()`, so the prefill
     * never delays the microphone.
     *
     * @param onAttempt called immediately before the first-ever native load. The crash tripwire
     *   persists a flag here.
     * @param onLoaded called after a successful prefill. The tripwire clears the flag here.
     */
    suspend fun prepare(onAttempt: () -> Unit = {}, onLoaded: () -> Unit = {}): Boolean =
        withContext(thread) {
            try {
                onAttempt()
                if (!llama.loadModel(gguf, mlock)) return@withContext fail(llama.unavailable)
                if (!llama.newContext()) return@withContext fail(llama.unavailable)

                val tokens = llama.prefillSystem(program.systemPrefix)
                if (tokens <= 0) return@withContext fail("Tier 2 deaktiviert (Prompt zu lang)")

                prefilledFor = program.fingerprint
                ready = true
                reason = null
                onLoaded()
                val timings = llama.timings()
                log(
                    "Tier 2 ready: $tokens system tokens, prefill ${timings.prefillMicros / 1000} ms, " +
                        "fingerprint ${program.fingerprint}",
                )
                true
            } catch (e: Throwable) {
                // Including Error: an OOM or a link failure here must cost Tier 2 and nothing
                // else. There is no state in this class a later request could be confused by,
                // because `ready` stays false.
                fail("Tier 2 deaktiviert (${e.javaClass.simpleName})")
            }
        }

    override suspend fun generate(utterance: String, program: Tier2Program): String? =
        withContext(thread) {
            if (!ready) return@withContext null
            try {
                // A palette that changed underneath means the cache holds a prefix for a
                // different set of commands. Re-prefill rather than answer from it: an answer
                // built on the wrong command list is worse than a slow one.
                if (prefilledFor != program.fingerprint) {
                    log("fingerprint changed (${prefilledFor} → ${program.fingerprint}); re-prefilling")
                    if (llama.prefillSystem(program.systemPrefix) <= 0) {
                        return@withContext fail("Tier 2 deaktiviert (Prompt zu lang)").let { null }
                    }
                    prefilledFor = program.fingerprint
                }

                llama.generate(
                    tail = utterance + program.assistantSuffix,
                    grammar = program.grammar,
                    root = GrammarGenerator.ROOT,
                    maxTokens = Tier2.MAX_REPLY_TOKENS,
                )
            } catch (e: Throwable) {
                log("generate failed: ${e.javaClass.simpleName}: ${e.message}")
                null
            }
        }

    /**
     * Asks the running request to stop.
     *
     * Safe from any thread, and deliberately *not* on the single-thread dispatcher — the whole
     * point is to interrupt something that is occupying it.
     */
    fun cancel() {
        llama.cancel()
    }

    /**
     * Gives back the ~280 MiB of KV cache, keeping the mmapped weights.
     *
     * §9's order of retreat, wired to `onTrimMemory(TRIM_MEMORY_RUNNING_CRITICAL)`. Tier 2 goes
     * unavailable until [reprefill] puts it back; STT is never touched, because a panel that
     * cannot hear is broken while a panel that cannot paraphrase is merely simpler.
     */
    suspend fun releaseContext() = withContext(thread) {
        if (!ready) return@withContext
        ready = false
        reason = "Tier 2 pausiert (Speicher knapp)"
        prefilledFor = null
        llama.freeContext()
        log("released the Tier 2 context under memory pressure")
    }

    /** Rebuilds what [releaseContext] gave back. Tens of seconds, in the background. */
    suspend fun reprefill(): Boolean = withContext(thread) {
        if (ready) return@withContext true
        if (!llama.newContext()) return@withContext fail(llama.unavailable)
        if (llama.prefillSystem(program.systemPrefix) <= 0) {
            return@withContext fail("Tier 2 deaktiviert (Prompt zu lang)")
        }
        prefilledFor = program.fingerprint
        ready = true
        reason = null
        true
    }

    suspend fun close() = withContext(thread) {
        ready = false
        llama.close()
    }

    fun timings(): Llama.Timings = llama.timings()

    private fun fail(why: String?): Boolean {
        ready = false
        reason = why ?: "Tier 2 deaktiviert"
        log(reason!!)
        return false
    }

    private companion object {
        const val TAG = "dobby-llama"
    }
}
