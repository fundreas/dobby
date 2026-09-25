package io.dobby.pipeline.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

/**
 * Dobby's voice, whichever one is speaking.
 *
 * Two implementations: [PlatformSpeaker] over Android's `TextToSpeech`, and [PiperSpeaker] over
 * a Piper voice through sherpa-onnx. The seam exists so that swapping one for the other is a
 * field assignment in [io.dobby.pipeline.VoicePipeline] rather than a branch at every call site.
 *
 * [say] suspending until the sentence has finished *playing* is the whole contract, and the
 * reason it is a suspend function rather than a callback: the microphone must not reopen while
 * Dobby is still talking, and the controller relies on the return to know that it has stopped.
 */
interface Speaker {

    /** False when this voice cannot speak at all — no German voice, or no graph. */
    suspend fun awaitReady(): Boolean

    /**
     * Speaks [text] and returns once it has finished playing.
     *
     * **False means nothing was said**, and that is the fallback's cue. A platform engine with
     * no German voice and a Piper graph that was freed under memory pressure are the same
     * situation to the caller: this sentence needs another voice. Returning a boolean rather
     * than throwing keeps that an ordinary answer, because it is one.
     *
     * [onAudible] is called once, from whatever thread the voice happens to be on, at the
     * moment the first sample is handed to the hardware — not when the sentence was accepted.
     * The gap between the two is a second of Piper synthesis, and it is the second the music
     * used to spend ducked with nothing over it. It must not block, and an implementation that
     * says nothing must never call it.
     */
    suspend fun say(text: String, onAudible: () -> Unit = {}): Boolean

    /** Stops whatever is playing now. Safe from any thread. */
    fun stop()

    fun shutdown()
}

/**
 * The phone's own `TextToSpeech`.
 *
 * What spoke before M2c, unchanged apart from the interface and the name: it is still the
 * first-run voice while Thorsten downloads, the fallback when a Piper voice will not load, and
 * the way back if espeak-ng reads something worse than Google did.
 *
 * Android's `TextToSpeech` is callback-shaped and initialises asynchronously, so everything
 * above it would otherwise have to be callback-shaped too.
 */
class PlatformSpeaker(
    context: Context,
    private val preferred: List<Locale> = DEFAULT_LOCALES,
) : Speaker {
    private val initialised = CompletableDeferred<Boolean>()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    /**
     * What to run when an utterance actually starts playing, by id.
     *
     * Removed as it fires, so a duck is taken once and an utterance that errored before it
     * started never takes one at all.
     */
    private val starting = ConcurrentHashMap<String, () -> Unit>()
    private val nextId = AtomicLong()

    private val tts = TextToSpeech(context.applicationContext) { status ->
        initialised.complete(status == TextToSpeech.SUCCESS && chooseLocale())
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            // The engine has begun producing audio for this utterance, which is exactly the
            // question the duck is asking. Queued utterances get theirs when their turn comes.
            override fun onStart(utteranceId: String?) {
                starting.remove(utteranceId)?.invoke()
            }

            override fun onDone(utteranceId: String?) {
                starting.remove(utteranceId)
                pending.remove(utteranceId)?.complete(Unit)
            }

            // Abstract in the framework and deprecated in the same breath: the platform calls
            // the errorCode overload below, but this one still has to exist.
            @Suppress("OVERRIDE_DEPRECATION")
            override fun onError(utteranceId: String?) {
                starting.remove(utteranceId)
                pending.remove(utteranceId)?.complete(Unit)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                starting.remove(utteranceId)
                pending.remove(utteranceId)?.complete(Unit)
            }
        })
    }

    /** False when no German voice could be found — Dobby is then mute rather than English. */
    override suspend fun awaitReady(): Boolean = initialised.await()

    /** Speaks [text] and returns once it has finished playing. */
    override suspend fun say(text: String, onAudible: () -> Unit): Boolean {
        if (text.isBlank() || !awaitReady()) return false

        val id = "dobby-${nextId.incrementAndGet()}"
        val done = CompletableDeferred<Unit>()
        pending[id] = done
        starting[id] = onAudible

        val queued = tts.speak(text, TextToSpeech.QUEUE_ADD, null, id)
        if (queued != TextToSpeech.SUCCESS) {
            pending.remove(id)
            starting.remove(id)
            return false
        }

        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation {
                pending.remove(id)
                starting.remove(id)
                tts.stop()
            }
            done.invokeOnCompletion { continuation.resume(Unit) }
        }
        return true
    }

    override fun stop() {
        tts.stop()
        starting.clear()
        pending.keys.forEach { pending.remove(it)?.complete(Unit) }
    }

    override fun shutdown() {
        stop()
        tts.shutdown()
    }

    /**
     * Austrian German first, German German second.
     *
     * The device is in Vienna and the departures Sock will read Wiener Linien stop names; a
     * de-DE voice says them acceptably, an en-US voice does not say them at all.
     */
    private fun chooseLocale(): Boolean {
        for (locale in preferred) {
            val availability = tts.isLanguageAvailable(locale)
            if (availability >= TextToSpeech.LANG_AVAILABLE) {
                tts.language = locale
                return true
            }
        }
        return false
    }

    companion object {
        val DEFAULT_LOCALES: List<Locale> = listOf(
            Locale.forLanguageTag("de-AT"),
            Locale.forLanguageTag("de-DE"),
        )
    }
}
