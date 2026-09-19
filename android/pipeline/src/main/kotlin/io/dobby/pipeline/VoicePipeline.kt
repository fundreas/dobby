package io.dobby.pipeline

import android.content.Context
import io.dobby.pipeline.audio.AudioSource
import io.dobby.pipeline.audio.MicrophoneUnavailableException
import io.dobby.pipeline.stt.ModelState
import io.dobby.pipeline.stt.ParakeetRecognizer
import io.dobby.pipeline.stt.SpeechModelStore
import io.dobby.pipeline.stt.SpeechVad
import io.dobby.pipeline.tts.Earcon
import io.dobby.pipeline.tts.Speaker
import io.dobby.pipeline.wakeword.WakeWordDetector
import io.dobby.pipeline.wakeword.WakeWordOption
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
import kotlin.time.Duration

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

    /**
     * The mic is open for an utterance. [speaking] is what the VAD hears right now.
     *
     * There is no running transcript to show: Parakeet is a batch recogniser and sees the
     * finished utterance or nothing (`dobby-plan.md` §5.2). "I can hear a voice" is the only
     * live signal the panel honestly has, and it is the one that matters — it is how someone
     * standing three metres away learns the microphone is picking them up.
     */
    data class Listening(val speaking: Boolean) : VoiceState

    /**
     * The utterance is captured and the recogniser is running. About a second.
     *
     * A state of its own because a batch recogniser puts a real gap between the end of a
     * sentence and the answer, and a panel that still says "listening" through it looks like a
     * panel that did not hear.
     */
    data object Transcribing : VoiceState

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
    /** The wake phrase id chosen in settings, or null for the catalogue default. */
    private var selectedWakeWord: String? = null,
    modelRoot: File = context.filesDir,
    private val utteranceTimeout: Duration = UTTERANCE_TIMEOUT,
) : VoiceIo {
    private val appContext = context.applicationContext
    private val audio = AudioSource(scope)
    private val models = SpeechModelStore(modelRoot)
    private val wakeWordModels = WakeWordModelStore(modelRoot)
    private val speaker = Speaker(appContext)
    private val earcon = Earcon()

    /** One utterance at a time: the microphone is not shareable and neither is the transcript. */
    private val turn = Mutex()

    private var recognizer: ParakeetRecognizer? = null
    private var vad: SpeechVad? = null
    private var wakeWord: WakeWordModels? = null
    private var detector: WakeWordDetector? = null

    /** The detector while a turn is in flight: off the stream, still ours, not discarded. */
    private var pausedWakeWord: WakeWordDetector? = null

    private val _state = MutableStateFlow<VoiceState>(VoiceState.Preparing("Starte…"))
    override val state: StateFlow<VoiceState> = _state.asStateFlow()

    private val _wakeWords = MutableSharedFlow<Float>(extraBufferCapacity = 1)

    /** Fires when the wake phrase is heard. The value is the detection score. */
    override val wakeWords: SharedFlow<Float> = _wakeWords.asSharedFlow()

    private val _handsFree = MutableStateFlow(false)

    /** Whether the wake word is currently armed. */
    override val handsFree: StateFlow<Boolean> = _handsFree.asStateFlow()

    override val canListen: Boolean get() = recognizer != null && vad != null

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
        val files = try {
            models.ensureAvailable()
        } finally {
            progress.cancel()
        }

        if (files == null) {
            val reason = (models.state.value as? ModelState.Failed)?.reason ?: "unbekannt"
            _state.value = VoiceState.Unavailable("Sprachmodell fehlt ($reason)")
            return
        }

        _state.value = VoiceState.Preparing("Lade Sprachmodell…")
        try {
            // Both are loaded once and stay resident: Parakeet costs seconds to open, and a
            // VAD reloaded per utterance would be a fresh ONNX session on the audio thread.
            withContext(Dispatchers.IO) {
                recognizer = ParakeetRecognizer.load(files, audio.sampleRate)
                vad = SpeechVad.load(files.vad, audio.sampleRate, maxUtterance = utteranceTimeout)
            }
        } catch (e: RuntimeException) {
            // sherpa-onnx reports an unloadable graph as a plain runtime exception from JNI.
            releaseStt()
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
                    _state.value = VoiceState.Preparing("Lade ${it.phrase}… ${it.percent} %")
                }
            }
        }
        val files = try {
            wakeWordModels.ensureAvailable(selectedWakeWord)
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

    /** Everything settings can offer: the catalogue plus anything pushed to the device. */
    override fun wakeWordOptions(): List<WakeWordOption> = wakeWordModels.options()

    override val selectedWakeWordId: String? get() = selectedWakeWord

    /**
     * Switches the phrase the panel answers to, downloading the new head if this is its first use.
     *
     * Rearms afterwards only if it was armed before: choosing a phrase in settings should not
     * quietly switch listening on for someone who had deliberately turned it off.
     */
    override suspend fun selectWakeWord(id: String) {
        if (id == selectedWakeWord && wakeWord != null) return
        val wasArmed = detector != null

        stopHandsFree()
        wakeWord?.close()
        wakeWord = null
        wakePhrase = null
        selectedWakeWord = id

        prepareWakeWord()
        if (wasArmed) startHandsFree() else _state.value = idleState()
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
        // A turn in flight has the detector parked off the stream. Disarming during one must
        // still take, or [endTurn] would put it back after the user asked for it to stop.
        pausedWakeWord?.let {
            pausedWakeWord = null
            it.close()
        }
        val listener = detector
        detector = null
        _handsFree.value = false
        if (listener != null) {
            audio.removeSink(listener)
            listener.close()
        }
        if (_state.value is VoiceState.Waiting) _state.value = VoiceState.Ready
    }

    private fun onWakeWord(score: Float) {
        // On the audio thread: beep now, and hand the turn to whoever is collecting. Doing the
        // work here would block the microphone.
        earcon.play()
        _wakeWords.tryEmit(score)
    }

    /**
     * Opens the microphone for one utterance and returns the raw transcript.
     *
     * Three steps (`dobby-plan.md` §5.2): capture every frame into a buffer while Silero VAD
     * watches the same stream; end on ~800 ms of trailing silence, or on the
     * [utteranceTimeout] hard cap when that silence never comes; hand the finished buffer to
     * Parakeet.
     *
     * The wake word is **detached** for the duration and stays detached until [endTurn]
     * (§2 invariant 4). It has nothing to contribute to command audio, and leaving it attached
     * means every utterance and every spoken answer is also scored against the wake phrase —
     * which is one detection threshold away from the panel waking itself in a loop.
     *
     * Returns null when nothing was said, the mic is unavailable, or the recogniser heard only
     * noise — all three are the same thing to the caller: no turn to take.
     */
    override suspend fun listen(): String? = turn.withLock {
        val recognizer = this.recognizer ?: return@withLock null
        val detector = vad ?: return@withLock null

        val capture = detector.capture(utteranceTimeout) { speaking ->
            _state.value = VoiceState.Listening(speaking)
        }
        _state.value = VoiceState.Listening(speaking = false)

        val samples = try {
            // Order is the invariant: the capture joins before the wake word leaves, so the
            // sink list is never empty and the microphone never closes between the two.
            audio.addSink(capture)
            detachWakeWord()
            withTimeoutOrNull(utteranceTimeout) { capture.await() } ?: capture.flush()
        } catch (e: MicrophoneUnavailableException) {
            _state.value = VoiceState.Unavailable(e.message ?: "Mikrofon nicht verfügbar")
            endTurn()
            return@withLock null
        } finally {
            // Removing the sink closes the mic only if the wake word is not also holding it.
            audio.removeSink(capture)
            capture.close()
        }

        // A wake word that fired at the television leaves ten seconds of room tone. Running
        // the recogniser over it costs a second and can only return "".
        if (!capture.heardSpeech) {
            endTurn()
            return@withLock null
        }

        _state.value = VoiceState.Transcribing
        val transcript = withContext(Dispatchers.Default) { recognizer.transcribe(samples) }
        _state.value = idleState()
        transcript.ifBlank { null }
    }

    /**
     * The turn is over — re-arm the wake word.
     *
     * Separate from [listen] because a turn does not end when the microphone closes: the
     * command still has to be dispatched and answered, and re-arming before Dobby has finished
     * speaking is how a panel hears its own voice say its own name. The caller owns the turn
     * (`DobbyController`), so the caller says when it ended; calling this twice, or without a
     * turn, does nothing.
     */
    override fun endTurn() {
        val paused = pausedWakeWord ?: run {
            if (_state.value !is VoiceState.Unavailable) _state.value = idleState()
            return
        }
        pausedWakeWord = null
        try {
            audio.addSink(paused)
            detector = paused
            // Whatever the detector's window holds is the tail of the turn it just sat out.
            paused.reset()
            _handsFree.value = true
        } catch (e: MicrophoneUnavailableException) {
            paused.close()
            _state.value = VoiceState.Unavailable(e.message ?: "Mikrofon nicht verfügbar")
            return
        }
        if (_state.value !is VoiceState.Unavailable) _state.value = idleState()
    }

    /** Takes the wake word off the stream for the duration of a turn. */
    private fun detachWakeWord() {
        val listener = detector ?: return
        detector = null
        pausedWakeWord = listener
        audio.removeSink(listener)
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
            // sit in the window scoring against the next one. (During a turn the detector is
            // parked and [endTurn] does this; this covers announce() outside a turn.)
            detector?.reset()
        }
    }

    private fun idleState(): VoiceState =
        wakeWord?.takeIf { detector != null }?.let { VoiceState.Waiting(it.phrase) } ?: VoiceState.Ready

    override fun shutdown() {
        stopHandsFree()
        pausedWakeWord?.close()
        pausedWakeWord = null
        audio.stop()
        earcon.close()
        speaker.shutdown()
        releaseStt()
        wakeWord?.close()
        wakeWord = null
    }

    private fun releaseStt() {
        recognizer?.close()
        recognizer = null
        vad?.close()
        vad = null
    }

    companion object {
        /** §5.2's hard cap: the VAD can miss an endpoint in a noisy room, and then this fires. */
        val UTTERANCE_TIMEOUT: Duration = SpeechVad.MAX_UTTERANCE
    }
}

private fun ModelState.describe(): String = when (this) {
    ModelState.Absent -> "Starte…"
    is ModelState.Downloading -> "Lade Sprachmodell… $percent %"
    ModelState.Verifying -> "Prüfe Sprachmodell…"
    is ModelState.Ready -> "Lade Sprachmodell…"
    is ModelState.Failed -> "Sprachmodell fehlgeschlagen: $reason"
}
