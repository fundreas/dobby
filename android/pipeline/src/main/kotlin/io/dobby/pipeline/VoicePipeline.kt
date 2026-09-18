package io.dobby.pipeline

import android.content.Context
import io.dobby.pipeline.audio.AudioSource
import io.dobby.pipeline.audio.MicrophoneUnavailableException
import io.dobby.pipeline.stt.ModelState
import io.dobby.pipeline.stt.VoskEngine
import io.dobby.pipeline.stt.VoskModelStore
import io.dobby.pipeline.tts.Earcon
import io.dobby.pipeline.tts.Speaker
import io.dobby.pipeline.wakeword.WakeWordDetector
import io.dobby.pipeline.wakeword.WakeWordModelStore
import io.dobby.pipeline.wakeword.WakeWordModels
import io.dobby.pipeline.wakeword.WakeWordState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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
    /** Fetching or loading a model. [detail] is fit to show a person. */
    data class Preparing(val detail: String) : VoiceState

    /** No microphone, no model, or no German voice. Dobby still works by typing. */
    data class Unavailable(val reason: String) : VoiceState

    /** Idle, and not listening for anything. Press to talk. */
    data object Ready : VoiceState

    /** Hands-free: the microphone is open and the wake word is armed. */
    data class Waiting(val phrase: String) : VoiceState

    /** The mic is open for an utterance. [partial] is Vosk's running guess. */
    data class Listening(val partial: String) : VoiceState

    data class Speaking(val text: String) : VoiceState
}

/**
 * Microphone in, German text out; German text in, sound out.
 *
 * Deliberately knows nothing about Socks, commands or the registry — it is the bottom of
 * `dobby-plan.md` §2's "everything below the microphone", and the service above it is what
 * joins it to the engine.
 *
 * Two ways in, one way through. Push-to-talk calls [listen] directly; hands-free arms the wake
 * word and emits on [wakeWords], and whoever is listening calls the same [listen]. There is no
 * second path for the hands-free case, because a second path is how the two drift apart.
 */
class VoicePipeline(
    context: Context,
    private val scope: CoroutineScope,
    modelRoot: File = context.filesDir,
    private val utteranceTimeout: Duration = UTTERANCE_TIMEOUT,
) : VoiceIo {
    private val appContext = context.applicationContext
    private val audio = AudioSource(scope)
    private val models = VoskModelStore(modelRoot)
    private val wakeWordModels = WakeWordModelStore(modelRoot)
    private val speaker = Speaker(appContext)
    private val earcon = Earcon()

    /** One utterance at a time: the microphone is not shareable and neither is the transcript. */
    private val turn = Mutex()

    private var engine: VoskEngine? = null
    private var wakeWord: WakeWordModels? = null
    private var detector: WakeWordDetector? = null

    private val _state = MutableStateFlow<VoiceState>(VoiceState.Preparing("Starte…"))
    override val state: StateFlow<VoiceState> = _state.asStateFlow()

    private val _wakeWords = MutableSharedFlow<Float>(extraBufferCapacity = 1)

    /** Fires when the wake phrase is heard. The value is the detection score. */
    override val wakeWords: SharedFlow<Float> = _wakeWords.asSharedFlow()

    private val _handsFree = MutableStateFlow(false)

    /** Whether the wake word is currently armed. */
    override val handsFree: StateFlow<Boolean> = _handsFree.asStateFlow()

    override val canListen: Boolean get() = engine != null

    /** The phrase the panel answers to, once the wake word models are loaded. */
    override var wakePhrase: String? = null
        private set

    init {
        audio.onError = { _state.value = VoiceState.Unavailable("Mikrofon abgebrochen") }
    }

    /**
     * Downloads what is missing, loads it, and warms up the voice.
     *
     * Never throws: a Dobby that cannot hear is still a Dobby you can type at, and that is a
     * much better failure than a service that dies on first run behind a captive portal. The
     * wake word is optional in the same way — without it the panel is push-to-talk, which is
     * exactly what it was before this milestone.
     */
    override suspend fun prepare() {
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

        prepareWakeWord()

        if (!speaker.awaitReady()) {
            // Hearing without speaking is still useful: the chat view shows every answer.
            _state.value = VoiceState.Unavailable("Keine deutsche Stimme installiert")
            return
        }
        _state.value = VoiceState.Ready
    }

    private suspend fun prepareWakeWord() {
        _state.value = VoiceState.Preparing("Lade Weckwort…")
        val progress = scope.launch {
            wakeWordModels.state.collect {
                if (it is WakeWordState.Downloading) {
                    _state.value = VoiceState.Preparing("Lade Weckwort… ${it.percent} %")
                }
            }
        }
        val files = try {
            wakeWordModels.ensureAvailable()
        } finally {
            progress.cancel()
        }

        if (files == null) return
        wakeWord = try {
            withContext(Dispatchers.IO) { WakeWordModels.load(files) }
        } catch (e: RuntimeException) {
            // ONNX Runtime reports a bad graph as a plain runtime exception. A wake word that
            // will not load costs hands-free, not the panel.
            null
        }
        wakePhrase = wakeWord?.phrase
    }

    /**
     * Arms the wake word: the microphone stays open and every 80 ms frame is scored.
     *
     * Returns false when there is no wake word model, which leaves the panel on push-to-talk.
     */
    override fun startHandsFree(): Boolean {
        val models = wakeWord ?: return false
        if (detector != null) return true

        val listener = WakeWordDetector(models) { score -> onWakeWord(score) }
        detector = listener
        return try {
            audio.addSink(listener)
            _handsFree.value = true
            if (_state.value is VoiceState.Ready) _state.value = VoiceState.Waiting(models.phrase)
            true
        } catch (e: MicrophoneUnavailableException) {
            detector = null
            _state.value = VoiceState.Unavailable(e.message ?: "Mikrofon nicht verfügbar")
            false
        }
    }

    /** Disarms the wake word and closes the microphone if nothing else is listening. */
    override fun stopHandsFree() {
        val listener = detector ?: return
        detector = null
        audio.removeSink(listener)
        listener.close()
        _handsFree.value = false
        if (_state.value is VoiceState.Waiting) _state.value = VoiceState.Ready
    }

    private fun onWakeWord(score: Float) {
        // On the audio thread: beep now, and hand the turn to whoever is collecting. Doing the
        // work here would block the microphone.
        earcon.play()
        _wakeWords.tryEmit(score)
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

        val transcript = try {
            audio.addSink(utterance)
            withTimeoutOrNull(utteranceTimeout) { utterance.await() } ?: utterance.flush()
        } catch (e: MicrophoneUnavailableException) {
            _state.value = VoiceState.Unavailable(e.message ?: "Mikrofon nicht verfügbar")
            return@withLock null
        } finally {
            // Removing the sink closes the mic only if the wake word is not also holding it.
            audio.removeSink(utterance)
            utterance.close()
        }

        _state.value = idleState()
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
            _state.value = if (previous is VoiceState.Unavailable) previous else idleState()
            // Dobby has been talking into an open microphone. Whatever the detector heard of
            // its own voice is not a wake word, and keeping it would let the tail of an answer
            // sit in the window scoring against the next one.
            detector?.reset()
        }
    }

    private fun idleState(): VoiceState =
        wakeWord?.takeIf { detector != null }?.let { VoiceState.Waiting(it.phrase) } ?: VoiceState.Ready

    override fun shutdown() {
        stopHandsFree()
        audio.stop()
        earcon.close()
        speaker.shutdown()
        engine?.close()
        engine = null
        wakeWord?.close()
        wakeWord = null
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
