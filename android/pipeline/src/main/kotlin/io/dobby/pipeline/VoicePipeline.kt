package io.dobby.pipeline

import android.content.Context
import io.dobby.pipeline.audio.AudioSource
import io.dobby.pipeline.audio.MicrophoneUnavailableException
import io.dobby.pipeline.stt.ModelState
import io.dobby.pipeline.stt.VoskEngine
import io.dobby.pipeline.stt.VoskModelStore
import io.dobby.pipeline.tts.Speaker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** What the voice side of Dobby is doing. The chat view renders this as its status line. */
sealed interface VoiceState {
    /** Fetching or loading the acoustic model. [detail] is fit to show a person. */
    data class Preparing(val detail: String) : VoiceState

    /** No microphone, no model, or no German voice. Dobby still works by typing. */
    data class Unavailable(val reason: String) : VoiceState

    data object Ready : VoiceState

    /** The mic is open. [partial] is Vosk's running guess, and changes several times a second. */
    data class Listening(val partial: String) : VoiceState

    data class Speaking(val text: String) : VoiceState
}

/**
 * Microphone in, German text out; German text in, sound out.
 *
 * Deliberately knows nothing about Socks, commands or the registry — it is the bottom of
 * `dobby-plan.md` §2's "everything below the microphone", and the service above it is what
 * joins it to the engine. That seam is why this module can be built and reasoned about
 * without any of Phase A being involved.
 *
 * The microphone is opened per utterance rather than held open, because until the wake word
 * lands in M2 there is nothing to listen *for* — an always-open mic would be battery and
 * privacy cost with no feature attached.
 */
class VoicePipeline(
    context: Context,
    private val scope: CoroutineScope,
    private val audio: AudioSource = AudioSource(),
    modelRoot: File = context.filesDir,
    private val utteranceTimeout: Duration = UTTERANCE_TIMEOUT,
) : VoiceIo {
    private val appContext = context.applicationContext
    private val models = VoskModelStore(modelRoot)
    private val speaker = Speaker(appContext)

    /** One utterance at a time: the microphone is not shareable and neither is the transcript. */
    private val turn = Mutex()

    init {
        // A microphone that dies mid-utterance has nobody to throw to — the failure happens on
        // the audio thread. It surfaces as state instead.
        audio.onError = { _state.value = VoiceState.Unavailable("Mikrofon abgebrochen") }
    }

    private var engine: VoskEngine? = null

    private val _state = MutableStateFlow<VoiceState>(VoiceState.Preparing("Starte…"))
    override val state: StateFlow<VoiceState> = _state.asStateFlow()

    /** True once speech input is possible. Typed input works regardless. */
    override val canListen: Boolean get() = engine != null

    /**
     * Downloads the model if needed, loads it, and warms up the voice.
     *
     * Never throws: a Dobby that cannot hear is still a Dobby you can type at, and that is a
     * much better failure than a service that dies on first run behind a captive portal.
     */
    override suspend fun prepare() {
        // Mirror the download into the UI state while it runs. The first launch of a fresh
        // install spends a minute here, and a silent minute reads as a hang.
        val progress = scope.launch {
            models.state.collect { _state.value = VoiceState.Preparing(it.describe()) }
        }
        val directory = try {
            models.ensureAvailable()
        } finally {
            progress.cancel()
        }

        if (directory == null) {
            val reason = (models.state.value as? ModelState.Failed)?.reason ?: "unbekannt"
            _state.value = VoiceState.Unavailable("Sprachmodell fehlt ($reason)")
            return
        }

        _state.value = VoiceState.Preparing("Lade Sprachmodell…")
        engine = try {
            withContext(Dispatchers.IO) { VoskEngine.load(directory, audio.sampleRate) }
        } catch (e: IOException) {
            _state.value = VoiceState.Unavailable("Sprachmodell nicht lesbar (${e.message})")
            return
        }

        if (!speaker.awaitReady()) {
            // Hearing without speaking is still useful: the chat view shows every answer.
            _state.value = VoiceState.Unavailable("Keine deutsche Stimme installiert")
            return
        }
        _state.value = VoiceState.Ready
    }

    /**
     * Opens the microphone for one utterance.
     *
     * Returns the raw transcript, or null when nothing was said, the mic is unavailable, or
     * the [utteranceTimeout] hard cap fired before Vosk found the end of the sentence.
     */
    override suspend fun listen(): String? = turn.withLock {
        val vosk = engine ?: return@withLock null

        val utterance = vosk.listen { partial -> _state.value = VoiceState.Listening(partial) }
        _state.value = VoiceState.Listening("")
        audio.addSink(utterance)

        val transcript = try {
            audio.start(scope)
            withTimeoutOrNull(utteranceTimeout) { utterance.await() } ?: utterance.flush()
        } catch (e: MicrophoneUnavailableException) {
            _state.value = VoiceState.Unavailable(e.message ?: "Mikrofon nicht verfügbar")
            return@withLock null
        } finally {
            audio.removeSink(utterance)
            // Nothing else holds the mic yet. In M2 the wake word does, and this becomes a
            // check of whether any sink is left rather than an unconditional stop.
            audio.stop()
            utterance.close()
        }

        _state.value = VoiceState.Ready
        transcript.ifBlank { null }
    }

    /** Speaks [text], returning once it has finished playing. */
    override suspend fun say(text: String) {
        if (text.isBlank()) return
        val previous = _state.value
        _state.value = VoiceState.Speaking(text)
        try {
            speaker.say(text)
        } finally {
            _state.value = if (previous is VoiceState.Unavailable) previous else VoiceState.Ready
        }
    }

    override fun shutdown() {
        audio.stop()
        speaker.shutdown()
        engine?.close()
        engine = null
    }

    companion object {
        /** §5.2's hard cap: endpointing on a noisy wall panel can miss, and then this fires. */
        val UTTERANCE_TIMEOUT: Duration = 10.seconds
    }
}

private fun ModelState.describe(): String = when (this) {
    ModelState.Absent -> "Starte…"
    is ModelState.Downloading -> "Lade Sprachmodell… $percent %"
    ModelState.Unpacking -> "Entpacke Sprachmodell…"
    is ModelState.Ready -> "Lade Sprachmodell…"
    is ModelState.Failed -> "Sprachmodell fehlgeschlagen: $reason"
}
