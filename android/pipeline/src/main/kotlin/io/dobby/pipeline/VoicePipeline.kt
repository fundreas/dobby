package io.dobby.pipeline

import android.content.Context
import io.dobby.pipeline.audio.AudioSource
import io.dobby.pipeline.audio.MicProfile
import io.dobby.pipeline.audio.MicrophoneUnavailableException
import io.dobby.pipeline.haptics.Haptics
import io.dobby.pipeline.stt.ModelState
import io.dobby.pipeline.stt.ParakeetRecognizer
import io.dobby.pipeline.stt.SpeechModelFiles
import io.dobby.pipeline.stt.SpeechModelStore
import io.dobby.pipeline.stt.SpeechVad
import io.dobby.pipeline.tts.Earcon
import io.dobby.pipeline.tts.EspeakData
import io.dobby.pipeline.tts.PiperSpeaker
import io.dobby.pipeline.tts.PlatformSpeaker
import io.dobby.pipeline.tts.Speaker
import io.dobby.pipeline.tts.VoiceCatalogue
import io.dobby.pipeline.tts.VoiceModelState
import io.dobby.pipeline.tts.VoiceOption
import io.dobby.pipeline.tts.VoiceStore
import io.dobby.pipeline.wakeword.TranscriptWakeWord
import io.dobby.pipeline.wakeword.WakeListener
import io.dobby.pipeline.wakeword.WakeMode
import io.dobby.pipeline.wakeword.WakePhrase
import io.dobby.pipeline.wakeword.WakeWord
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
 *
 * *Hearing* the wake word, on the other hand, has two implementations ([WakeMode]) — a trained
 * classifier and the command recogniser reading a typed phrase — and they meet at
 * [WakeListener] well below this. Everything here arms, parks and re-arms one of those, and
 * cannot tell which.
 */
class VoicePipeline(
    context: Context,
    private val scope: CoroutineScope,
    /** The wake phrase id chosen in settings, or null for the catalogue default. */
    private var selectedWakeWord: String? = null,
    /** Which of the two ways of hearing the panel's name is in use ([WakeMode]). */
    private var mode: WakeMode = WakeMode.DEFAULT,
    /** The phrase [WakeMode.TRANSCRIPT] listens for, as typed in settings. */
    private var spokenPhrase: WakePhrase = WakePhrase(WakePhrase.DEFAULT),
    /** How the wake word is acknowledged, as chosen in settings. */
    initialCue: ListenCue = ListenCue.DEFAULT,
    /**
     * Which microphone to open, as chosen in settings.
     *
     * Fixed for the life of the pipeline: [AudioSource] is the only thing allowed to open the
     * recorder, and changing the source means re-opening it — which is a deaf gap in the middle
     * of whatever was listening. Changing the setting takes effect on the next start, which for
     * a panel that is measured by standing in front of it is often enough.
     */
    private val micProfile: MicProfile = MicProfile.DEFAULT,
    /** The voice chosen in settings, or null for the catalogue default. */
    private var selectedVoice: String? = null,
    private val modelRoot: File = context.filesDir,
    private val utteranceTimeout: Duration = UTTERANCE_TIMEOUT,
) : VoiceIo {
    private val appContext = context.applicationContext
    private val audio = AudioSource(scope, micProfile)
    private val models = SpeechModelStore(modelRoot)
    private val wakeWordModels = WakeWordModelStore(modelRoot)
    private val voices = VoiceStore(modelRoot)

    /**
     * The phone's own voice: the first-run voice, the fallback, and the way back.
     *
     * Constructed once and kept for the life of the pipeline even when a Piper voice is
     * speaking. It costs an idle `TextToSpeech` connection, and it buys a sentence that is
     * never dropped — a graph freed under memory pressure or a download behind a captive
     * portal is a voice that changes, not a panel that goes quiet.
     */
    private val platformSpeaker = PlatformSpeaker(appContext)

    @Volatile
    private var speaker: Speaker = platformSpeaker

    /** The loaded Piper voice, when one is speaking. Null while the system voice is selected. */
    private var piper: PiperSpeaker? = null

    /**
     * Held for the length of a sentence, and taken again to swap voices.
     *
     * So a sentence in flight finishes in the voice it started in and the next one starts in
     * the new one — rather than a swap landing between two `AudioTrack` writes.
     */
    private val speaking = Mutex()

    private val earcon = Earcon()
    private val haptics = Haptics(appContext)

    /** One utterance at a time: the microphone is not shareable and neither is the transcript. */
    private val turn = Mutex()

    private var recognizer: ParakeetRecognizer? = null
    private var vad: SpeechVad? = null

    /**
     * Where the speech models ended up, kept so a later switch to [WakeMode.TRANSCRIPT] can
     * open its own VAD without going back through the store.
     */
    private var speechFiles: SpeechModelFiles? = null

    /**
     * A second Silero session, for [WakeMode.TRANSCRIPT] only.
     *
     * Not the command path's: `Vad` carries segment state across calls and
     * [SpeechVad.capture] resets it, so one shared instance would have the utterance capture
     * in the middle of a turn quietly resetting the capture the wake listener is parked on.
     * A Silero graph is ~2 MB, which is a cheap price for the two never touching.
     */
    private var wakeVad: SpeechVad? = null

    private var wakeWord: WakeWordModels? = null
    private var detector: WakeListener? = null

    /** The detector while a turn is in flight: off the stream, still ours, not discarded. */
    private var pausedWakeWord: WakeListener? = null

    /**
     * One utterance inside the recogniser at a time.
     *
     * [WakeMode.TRANSCRIPT] puts a second caller on the one `OfflineRecognizer` — the wake
     * listener is reading the room while a turn may be reading a command — and the cost of
     * being wrong about whether sherpa-onnx minds is a native crash rather than an exception.
     * A wake-phrase utterance decodes in a fraction of a second, so the turn rarely waits.
     */
    private val recognition = Mutex()

    private val _state = MutableStateFlow<VoiceState>(VoiceState.Preparing("Starte…"))
    override val state: StateFlow<VoiceState> = _state.asStateFlow()

    private val _wakeWords = MutableSharedFlow<WakeWord>(extraBufferCapacity = 1)

    /** Fires when the wake phrase is heard, with the score and anything said after it. */
    override val wakeWords: SharedFlow<WakeWord> = _wakeWords.asSharedFlow()

    private val _handsFree = MutableStateFlow(false)

    /** Whether the wake word is currently armed. */
    override val handsFree: StateFlow<Boolean> = _handsFree.asStateFlow()

    override val canListen: Boolean get() = recognizer != null && vad != null

    /**
     * The phrase the panel answers to, or null when it cannot answer to one.
     *
     * Derived rather than stored, because the two modes know it in two different places: a
     * classifier head carries the phrase it was trained for, and a typed phrase is only a
     * phrase at all once there is a recogniser to read it with.
     */
    override val wakePhrase: String?
        get() = when (mode) {
            WakeMode.CLASSIFIER -> wakeWord?.phrase
            WakeMode.TRANSCRIPT -> spokenPhrase.text.takeIf { !spokenPhrase.isEmpty && canListen }
        }

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
            speechFiles = files
        } catch (e: RuntimeException) {
            // sherpa-onnx reports an unloadable graph as a plain runtime exception from JNI.
            releaseStt()
            _state.value = VoiceState.Unavailable("Sprachmodell nicht lesbar (${e.message})")
            return
        }

        prepareWakeWord()
        prepareVoice()

        // Either voice will do. The Piper one is the better of the two and the platform one is
        // always there, so the only failure left is a phone with no German voice *and* a first
        // run that could not fetch Thorsten — which is mute, and worth saying out loud.
        if (!speaker.awaitReady() && !platformSpeaker.awaitReady()) {
            // Hearing without speaking is still useful: the chat view shows every answer.
            _state.value = VoiceState.Unavailable("Keine deutsche Stimme installiert")
            return
        }
        _state.value = VoiceState.Ready
    }

    /**
     * Fetches and loads the chosen voice, after the wake word and before the panel says Ready.
     *
     * Last of the three downloads on purpose: first-run order is what the panel can do soonest.
     * Hearing comes before speaking, hands-free before either, and until Thorsten's 114 MB
     * lands the phone's own voice answers — so the panel is never mute on first run, which it
     * is today on a device with no German platform voice.
     *
     * Never throws and never leaves the panel without a voice: every way out of here leaves
     * [platformSpeaker] speaking, with the reason in [voiceState].
     */
    private suspend fun prepareVoice() {
        val option = VoiceCatalogue.of(selectedVoice)
        if (option.isSystem) return

        _state.value = VoiceState.Preparing("Lade Stimme…")
        val loaded = load(option) ?: return
        swap(loaded)
    }

    /**
     * Downloads, unpacks and opens one voice, showing the download where a person can see it.
     *
     * Returns null for every failure — a download that could not finish, phoneme data that
     * could not be copied, a graph that will not open — because the caller's answer to all
     * three is the same: keep the voice that is speaking.
     */
    private suspend fun load(option: VoiceOption): PiperSpeaker? {
        val progress = scope.launch {
            voices.state.collect {
                if (it is VoiceModelState.Downloading) {
                    _state.value = VoiceState.Preparing("Lade ${it.name}… ${it.percent} %")
                }
            }
        }
        val files = try {
            voices.ensureAvailable(option)
        } finally {
            progress.cancel()
        }
        if (files == null) return null

        _state.value = VoiceState.Preparing("Lade Stimme…")
        // espeak-ng wants its 355 files by path, so they are copied out of the assets once.
        // Without them the graph loads and says nothing, which is why this is not optional.
        val phonemes = EspeakData.ensure(appContext, modelRoot) ?: run {
            voices.failed("Aussprachedaten fehlen")
            return null
        }

        val next = PiperSpeaker(files, phonemes, option.gain)
        if (!next.awaitReady()) {
            next.shutdown()
            voices.failed("Stimme nicht lesbar")
            return null
        }
        return next
    }

    /**
     * Puts [next] in front, under the sentence lock, and disposes of whatever it replaced.
     *
     * Only one voice is ever resident: a Piper graph is ~150 MB, and two of them on a phone
     * that is also holding Parakeet and a gigabyte of Tier 2 is the memory this milestone does
     * not have. [platformSpeaker] is the exception and is never shut down — it is the fallback.
     */
    private suspend fun swap(next: Speaker) {
        val previous = speaking.withLock {
            val current = speaker
            speaker = next
            piper = next as? PiperSpeaker
            current
        }
        if (previous !== platformSpeaker) previous.shutdown()
    }

    /**
     * Gets whatever the chosen [WakeMode] needs onto the device and into memory.
     *
     * [WakeMode.TRANSCRIPT] downloads nothing: its recogniser is the one [prepare] has already
     * loaded and its phrase was typed, so all that is left is a VAD of its own. That is the
     * mode's other quiet advantage — it works on first run, offline, before anything has been
     * fetched from a repository that may or may not still be there.
     *
     * Never throws. A wake word that will not load costs hands-free, not the panel.
     */
    private suspend fun prepareWakeWord() {
        if (mode == WakeMode.TRANSCRIPT) {
            ensureWakeVad()
            return
        }
        // Already loaded, which is the common case coming back from [WakeMode.TRANSCRIPT]:
        // neither mode's models are freed when the other is chosen, so switching between them
        // to compare them — which is exactly what somebody will do — costs nothing the second
        // time. Both together are a few megabytes beside Parakeet's seven hundred.
        // [selectWakeWord] clears it first, which is what makes a *different* head load.
        if (wakeWord != null) return
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
    }

    /**
     * Opens the second Silero session [WakeMode.TRANSCRIPT] listens through, once.
     *
     * Returns null when there are no speech models — which is the same panel that cannot hear a
     * command either, and is already saying so.
     */
    private suspend fun ensureWakeVad(): SpeechVad? {
        wakeVad?.let { return it }
        val files = speechFiles ?: return null
        return try {
            withContext(Dispatchers.IO) {
                SpeechVad.load(files.vad, audio.sampleRate, maxUtterance = TranscriptWakeWord.HARD_CAP)
            }.also { wakeVad = it }
        } catch (e: RuntimeException) {
            // sherpa-onnx reports an unloadable graph as a plain runtime exception from JNI.
            null
        }
    }

    /** Everything settings can offer: the catalogue plus anything pushed to the device. */
    override fun wakeWordOptions(): List<WakeWordOption> = wakeWordModels.options()

    override val selectedWakeWordId: String? get() = selectedWakeWord

    override val wakeMode: WakeMode get() = mode

    override val spokenWakePhrase: String get() = spokenPhrase.text

    /**
     * Switches between the two ways of hearing the panel's name.
     *
     * Suspends, because switching *to* [WakeMode.CLASSIFIER] may be the first time a classifier
     * head has ever been wanted on this device. Rearms afterwards only if it was armed before,
     * for the same reason [selectWakeWord] does: choosing in settings is not switching the
     * microphone on for somebody who had deliberately switched it off.
     */
    override suspend fun selectWakeMode(next: WakeMode) {
        if (next == mode) return
        val wasArmed = detector != null
        stopHandsFree()
        mode = next

        prepareWakeWord()
        if (wasArmed) startHandsFree() else _state.value = idleState()
    }

    /**
     * Changes the phrase [WakeMode.TRANSCRIPT] listens for.
     *
     * Nothing to fetch and nothing to load — the phrase is a string and the recogniser that
     * reads it is already resident — so this only has to put a listener holding the new phrase
     * back where the old one was. Takes effect immediately, which is what makes the setting
     * worth having: getting a phrase the recogniser reliably hears is a matter of trying one,
     * saying it, and trying the next.
     */
    override fun setSpokenWakePhrase(text: String) {
        val next = WakePhrase(text)
        if (next.text == spokenPhrase.text) return
        val wasArmed = detector != null
        if (mode == WakeMode.TRANSCRIPT) stopHandsFree()
        spokenPhrase = next
        if (mode != WakeMode.TRANSCRIPT) return
        if (wasArmed) startHandsFree() else _state.value = idleState()
    }

    override var listenCue: ListenCue = initialCue

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
        selectedWakeWord = id

        prepareWakeWord()
        if (wasArmed) startHandsFree() else _state.value = idleState()
    }

    /** Everything settings can offer: the catalogue plus any voice pushed to the device. */
    override fun voiceOptions(): List<VoiceOption> = voices.options()

    override val selectedVoiceId: String get() = resolveVoice(selectedVoice).id

    override val voiceState: StateFlow<VoiceModelState> = voices.state

    /**
     * Switches the voice, downloading it the first time it is chosen.
     *
     * Then says one sentence in it, because a voice is chosen by ear: a radio button that
     * changes nothing audible until the next timer fires is a setting nobody trusts.
     *
     * **A failure changes nothing.** The voice that was speaking keeps speaking and the
     * selection stays where it was, so a Cori download that died behind a captive portal does
     * not leave the panel selected onto a voice it does not have. The reason is in [voiceState],
     * which is the line under the row that was tapped.
     */
    override suspend fun selectVoice(id: String) {
        val option = resolveVoice(id)
        // Already chosen *and* already speaking. The second half matters on first run: the
        // selection can be Thorsten while the phone's voice is still covering the download.
        val active = if (option.isSystem) speaker === platformSpeaker else piper != null
        if (option.id == selectedVoiceId && active) return

        if (option.isSystem) {
            selectedVoice = option.id
            swap(platformSpeaker)
        } else {
            val loaded = load(option) ?: run {
                _state.value = idleState()
                return
            }
            selectedVoice = option.id
            swap(loaded)
        }
        _state.value = idleState()
        say(option.spokenGreeting)
    }

    /**
     * Gives the loaded graph's ~150 MB back, keeping the voice selected.
     *
     * Under the sentence lock, so it never frees a pointer a synthesis is still using. Fire and
     * forget: the caller is `onTrimMemory`, which is not a place to suspend.
     */
    override fun releaseVoice() {
        val loaded = piper ?: return
        scope.launch { speaking.withLock { loaded.release() } }
    }

    /** The catalogue, a sideloaded directory, or — for anything unknown — the default. */
    private fun resolveVoice(id: String?): VoiceOption =
        voices.options().firstOrNull { it.id == id } ?: VoiceCatalogue.DEFAULT

    /**
     * Arms the wake word: the microphone stays open and everything on it is listened to.
     *
     * Returns false when the chosen [WakeMode] has nothing to listen with — no classifier head,
     * or no recogniser — which leaves the panel on push-to-talk.
     */
    override fun startHandsFree(): Boolean {
        if (detector != null) return true
        val listener = newListener() ?: return false
        detector = listener
        return try {
            audio.addSink(listener)
            _handsFree.value = true
            if (_state.value is VoiceState.Ready) _state.value = VoiceState.Waiting(listener.phrase)
            true
        } catch (e: MicrophoneUnavailableException) {
            detector = null
            // Closed rather than dropped: [TranscriptWakeWord] has a coroutine of its own, and
            // a listener that never reached the microphone would otherwise sit there cycling
            // captures over silence for the life of the process.
            listener.close()
            _state.value = VoiceState.Unavailable(e.message ?: "Mikrofon nicht verfügbar")
            false
        }
    }

    /**
     * Builds the listener the chosen mode listens through, or null when it cannot.
     *
     * The one place the two ways of hearing the name are told apart. Everything downstream —
     * arming, parking for a turn, re-arming, resetting after Dobby speaks — sees a
     * [WakeListener] and nothing more.
     */
    private fun newListener(): WakeListener? = when (mode) {
        // Through forProfile, never the constructor: the threshold belongs to the microphone
        // that is open, and the two profiles' numbers are not interchangeable (`m2b-plan.md` B2).
        WakeMode.CLASSIFIER -> wakeWord?.let { models ->
            WakeWordDetector.forProfile(models, micProfile) { score -> onWakeWord(WakeWord(score)) }
        }

        WakeMode.TRANSCRIPT -> {
            val silero = wakeVad
            when {
                silero == null || recognizer == null || spokenPhrase.isEmpty -> null
                else -> TranscriptWakeWord(
                    scope = scope,
                    vad = silero,
                    wakePhrase = spokenPhrase,
                    transcribe = { samples -> transcribe(samples) },
                    onDetected = { detection -> onWakeWord(detection) },
                )
            }
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

    private fun onWakeWord(detection: WakeWord) {
        // Possibly on the audio thread — the classifier fires from there: acknowledge now, and
        // hand the turn to whoever is collecting. Doing the work here would block the
        // microphone. Whichever cue is chosen returns immediately; NONE is a real choice and
        // not a missing branch.
        when (listenCue) {
            ListenCue.VIBRATE -> haptics.listening()
            ListenCue.TONE -> earcon.play()
            ListenCue.NONE -> Unit
        }
        _wakeWords.tryEmit(detection)
    }

    /**
     * The one door into the recogniser, held open for one caller at a time.
     *
     * Both the command path and [WakeMode.TRANSCRIPT]'s listener come through here, which is
     * what makes the shared `OfflineRecognizer` safe to have two callers at all.
     */
    private suspend fun transcribe(samples: FloatArray): String {
        val engine = recognizer ?: return ""
        return recognition.withLock { withContext(Dispatchers.Default) { engine.transcribe(samples) } }
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
     * [openFor] is the fourth way to end early: a deadline on the *start* of speech rather than
     * on its end (see [VoiceIo.listen]). Once a voice is heard it stops applying and the
     * utterance ends the way every other one does.
     *
     * Returns null when nothing was said, the mic is unavailable, or the recogniser heard only
     * noise — all three are the same thing to the caller: no turn to take.
     */
    override suspend fun listen(openFor: Duration): String? = turn.withLock {
        if (recognizer == null) return@withLock null
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
            // A window that expires leaves `heardSpeech` false, which the check below already
            // treats as "no turn to take" — so there is one way out of a silent room, not two.
            if (withTimeoutOrNull(openFor) { capture.awaitSpeech() } != true) {
                capture.flush()
            } else {
                withTimeoutOrNull(utteranceTimeout) { capture.await() } ?: capture.flush()
            }
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
        val transcript = transcribe(samples)
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
            // Nothing was parked, which does not mean nothing needs putting back:
            // [TranscriptWakeWord] goes deaf the moment it fires, and a turn that never opened
            // the microphone — because the command arrived in the same breath as the phrase —
            // is a turn that left it that way. Harmless for the classifier, which is only
            // asked to forget a window it should forget anyway.
            detector?.reset()
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

    override fun buzz() = haptics.notUnderstood()

    /** Speaks [text], returning once it has finished playing. */
    override suspend fun say(text: String, onAudible: suspend () -> Unit) {
        if (text.isBlank()) return
        val previous = _state.value
        _state.value = VoiceState.Speaking(text)
        // The voices signal from whatever thread they are playing on — the TTS thread for
        // Piper, the engine's callback thread for the platform voice — and neither is a
        // coroutine. So the signal is bounced into [scope], which is also what keeps a caller
        // that wants to take a mutex from blocking the thread that is feeding the audio track.
        val audible = { scope.launch { onAudible() }; Unit }
        try {
            // One sentence is spoken by one voice: the lock is what keeps a swap from landing
            // between two writes of the same answer.
            speaking.withLock {
                val active = speaker
                // False means this voice said nothing — a graph freed under memory pressure,
                // or a platform engine with no German voice. The phone's own voice is what
                // stands behind both, and a sentence in the wrong voice beats a silent panel.
                // The fallback carries the same signal: it is the one that will be heard.
                if (!active.say(text, audible) && active !== platformSpeaker) {
                    platformSpeaker.say(text, audible)
                }
            }
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
        detector?.let { VoiceState.Waiting(it.phrase) } ?: VoiceState.Ready

    override fun shutdown() {
        stopHandsFree()
        pausedWakeWord?.close()
        pausedWakeWord = null
        audio.stop()
        earcon.close()
        piper?.shutdown()
        piper = null
        platformSpeaker.shutdown()
        releaseStt()
        wakeWord?.close()
        wakeWord = null
    }

    private fun releaseStt() {
        recognizer?.close()
        recognizer = null
        vad?.close()
        vad = null
        wakeVad?.close()
        wakeVad = null
        speechFiles = null
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
