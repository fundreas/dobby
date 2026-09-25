package io.dobby.android

import io.dobby.pipeline.ListenCue
import io.dobby.pipeline.VoiceIo
import io.dobby.pipeline.VoiceState
import io.dobby.pipeline.tts.VoiceCatalogue
import io.dobby.pipeline.tts.VoiceModelState
import io.dobby.pipeline.tts.VoiceOption
import io.dobby.pipeline.wakeword.WakeMode
import io.dobby.pipeline.wakeword.WakePhrase
import io.dobby.pipeline.wakeword.WakeWord
import io.dobby.pipeline.wakeword.WakeWordOption
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Duration

/**
 * The hardware, scripted.
 *
 * In a file of its own rather than nested in one test, because the join between the microphone
 * and Phase A is now asserted by more than one suite — what a turn says and hears
 * ([DobbyControllerTest]) and what a turn does to the music ([TurnDuckTest]) are different
 * questions about the same object, and a second copy of this class is how the two would start
 * disagreeing about what a turn looks like.
 */
internal class FakeVoice(vararg utterances: String?) : VoiceIo {
    /**
     * What the microphone returns, one per call to [listen].
     *
     * A used-up script is silence, not the last line on repeat: a turn stays open after
     * anything Dobby handled, so a room that has stopped talking has to be expressible or
     * no test of that would ever finish. Repetition is written out where a test wants it.
     */
    private val script = utterances.toMutableList()

    var heard: String?
        get() = script.firstOrNull()
        set(value) {
            script.clear()
            script += value
        }

    /**
     * Everything that happened to the panel's audio, in order.
     *
     * Ordering is the whole of what a turn duck has to get right — taken before the first
     * microphone opens, held across every utterance in between, released once at the end — and
     * counters cannot express it. The duck writes into this same list.
     */
    val events: MutableList<String> = mutableListOf()

    /** Thrown from the next [listen]. The microphone failing mid-turn, on demand. */
    var failNextListen: Throwable? = null

    /**
     * Awaited inside [listen], so a test can act on a turn that is still in flight.
     *
     * The microphone really is where a turn spends its time, so it is the honest place to hold
     * one still — and the only way to cancel a turn *during* rather than after it.
     */
    var holdListen: CompletableDeferred<Unit>? = null

    // Starts where the real pipeline starts: nothing is loaded yet.
    private val _state = MutableStateFlow<VoiceState>(VoiceState.Preparing("Starte…"))
    override val state: StateFlow<VoiceState> = _state.asStateFlow()
    override var canListen: Boolean = true

    val spoken: MutableList<String> = mutableListOf()
    var prepared: Boolean = false
    var shutdownCalls: Int = 0
    var buzzes: Int = 0

    /** One entry per [listen], holding the deadline it was given for speech to start. */
    val windows: MutableList<Duration> = mutableListOf()

    /**
     * One entry per [listen]: everything already spoken when the microphone opened.
     *
     * The only way to assert the ordering that keeps Dobby from hearing itself — a
     * question has to be out of the speaker before the mic comes back.
     */
    val spokenBeforeListen: MutableList<List<String>> = mutableListOf()

    override var listenCue: ListenCue = ListenCue.DEFAULT

    private val _wakeWords = MutableSharedFlow<WakeWord>(extraBufferCapacity = 1)
    override val wakeWords: SharedFlow<WakeWord> = _wakeWords.asSharedFlow()

    private val _handsFree = MutableStateFlow(false)
    override val handsFree: StateFlow<Boolean> = _handsFree.asStateFlow()

    override var wakePhrase: String? = "Hey Dobby"

    override var selectedWakeWordId: String? = null
        private set

    override var wakeMode: WakeMode = WakeMode.DEFAULT
        private set

    override var spokenWakePhrase: String = WakePhrase.DEFAULT
        private set

    override suspend fun selectWakeMode(next: WakeMode) {
        wakeMode = next
        if (next == WakeMode.TRANSCRIPT) wakePhrase = spokenWakePhrase
    }

    override fun setSpokenWakePhrase(text: String) {
        spokenWakePhrase = WakePhrase(text).text
        if (wakeMode == WakeMode.TRANSCRIPT) wakePhrase = spokenWakePhrase
    }

    override fun wakeWordOptions(): List<WakeWordOption> = listOf(
        WakeWordOption("hey_dobby", "Hey Dobby", "hey_dobby.onnx", "", ""),
        WakeWordOption("hey_jarvis", "Hey Jarvis", "hey_jarvis_v0.1.onnx", "", ""),
    )

    override suspend fun selectWakeWord(id: String) {
        val option = wakeWordOptions().first { it.id == id }
        selectedWakeWordId = id
        wakePhrase = option.phrase
        if (_handsFree.value) _state.value = VoiceState.Waiting(option.phrase)
    }

    /**
     * The catalogue, as the pipeline would offer it with nothing sideloaded.
     *
     * The real store also reads a directory; here the list is fixed, because what the
     * controller has to get right is which id reaches settings and which one comes back — not
     * what is on disk.
     */
    override fun voiceOptions(): List<VoiceOption> = VoiceCatalogue.ALL

    override var selectedVoiceId: String = VoiceCatalogue.DEFAULT.id
        private set

    private val _voiceState = MutableStateFlow<VoiceModelState>(VoiceModelState.Absent)
    override val voiceState: StateFlow<VoiceModelState> = _voiceState.asStateFlow()

    /**
     * Set to make the next [selectVoice] fail, as a download behind a captive portal does.
     *
     * The interesting half of choosing a voice is the half that does not work: the selection
     * has to stay where it was, because persisting a voice that is not on the device is a next
     * start with nothing to load.
     */
    var failNextVoice: String? = null

    override suspend fun selectVoice(id: String) {
        val option = voiceOptions().firstOrNull { it.id == id } ?: VoiceCatalogue.DEFAULT
        failNextVoice?.let { reason ->
            failNextVoice = null
            _voiceState.value = VoiceModelState.Failed(reason)
            return
        }
        selectedVoiceId = option.id
        _voiceState.value = VoiceModelState.Ready(option.name)
        say(option.spokenGreeting)
    }

    /** Drives the download state directly, for the percentage the settings row renders. */
    fun emitVoiceState(state: VoiceModelState) {
        _voiceState.value = state
    }

    /** One entry per call, so §9's order of retreat is assertable without a phone. */
    var voiceReleases: Int = 0
        private set

    override fun releaseVoice() {
        voiceReleases++
    }

    override fun startHandsFree(): Boolean {
        if (wakePhrase == null) return false
        _handsFree.value = true
        _state.value = VoiceState.Waiting(wakePhrase!!)
        return true
    }

    override fun stopHandsFree() {
        _handsFree.value = false
        _state.value = VoiceState.Ready
    }

    /**
     * Pretends someone said the wake phrase, optionally with a command in the same breath.
     *
     * [rest] is what only `WakeMode.TRANSCRIPT` can supply: the detector read the whole
     * sentence, so the turn starts with the command already in hand.
     */
    fun sayWakeWord(score: Float = 0.9f, rest: String = "") {
        _wakeWords.tryEmit(WakeWord(score, rest))
    }

    /** Drives the pipeline state directly, for the states a whole turn passes through. */
    fun emit(state: VoiceState) {
        _state.value = state
    }

    override suspend fun prepare() {
        prepared = true
        _state.value = VoiceState.Ready
    }

    /** Set while [listen] is running: the wake word is off the stream until [endTurn]. */
    var turnOpen: Boolean = false
        private set

    val turnsEnded: MutableList<Boolean> = mutableListOf()

    override suspend fun listen(openFor: Duration): String? {
        events += LISTEN
        failNextListen?.let {
            failNextListen = null
            throw it
        }
        holdListen?.await()
        turnOpen = true
        windows += openFor
        spokenBeforeListen += spoken.toList()
        _state.value = VoiceState.Listening(speaking = false)
        _state.value = VoiceState.Listening(speaking = true)
        _state.value = VoiceState.Transcribing
        _state.value = VoiceState.Ready
        return if (script.isEmpty()) null else script.removeAt(0)
    }

    override fun endTurn() {
        events += END_TURN
        turnsEnded += turnOpen
        turnOpen = false
    }

    override suspend fun say(text: String, onAudible: suspend () -> Unit) {
        // A fake voice is audible the instant it is asked to speak: there is no synthesis to
        // wait through, so the duck's late signal and its early one are the same moment here.
        onAudible()
        spoken += text
    }

    override fun buzz() {
        buzzes++
    }

    override fun shutdown() {
        shutdownCalls++
    }

    companion object {
        const val LISTEN = "listen"
        const val END_TURN = "endTurn"
    }
}
