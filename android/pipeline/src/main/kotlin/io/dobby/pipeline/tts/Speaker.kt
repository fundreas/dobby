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
 * Dobby's voice.
 *
 * Android's [TextToSpeech] is callback-shaped and initialises asynchronously, so everything
 * above it would otherwise have to be callback-shaped too. [say] is a suspend function that
 * returns when the sentence has actually finished playing — which the caller needs, because
 * the microphone must not reopen while Dobby is still talking.
 */
class Speaker(
    context: Context,
    private val preferred: List<Locale> = DEFAULT_LOCALES,
) {
    private val initialised = CompletableDeferred<Boolean>()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val nextId = AtomicLong()

    private val tts = TextToSpeech(context.applicationContext) { status ->
        initialised.complete(status == TextToSpeech.SUCCESS && chooseLocale())
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) {
                pending.remove(utteranceId)?.complete(Unit)
            }

            // Abstract in the framework and deprecated in the same breath: the platform calls
            // the errorCode overload below, but this one still has to exist.
            @Suppress("OVERRIDE_DEPRECATION")
            override fun onError(utteranceId: String?) {
                pending.remove(utteranceId)?.complete(Unit)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                pending.remove(utteranceId)?.complete(Unit)
            }
        })
    }

    /** False when no German voice could be found — Dobby then runs mute rather than in English. */
    suspend fun awaitReady(): Boolean = initialised.await()

    /** Speaks [text] and returns once it has finished playing. */
    suspend fun say(text: String) {
        if (text.isBlank() || !awaitReady()) return

        val id = "dobby-${nextId.incrementAndGet()}"
        val done = CompletableDeferred<Unit>()
        pending[id] = done

        val queued = tts.speak(text, TextToSpeech.QUEUE_ADD, null, id)
        if (queued != TextToSpeech.SUCCESS) {
            pending.remove(id)
            return
        }

        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation {
                pending.remove(id)
                tts.stop()
            }
            done.invokeOnCompletion { continuation.resume(Unit) }
        }
    }

    fun stop() {
        tts.stop()
        pending.keys.forEach { pending.remove(it)?.complete(Unit) }
    }

    fun shutdown() {
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
