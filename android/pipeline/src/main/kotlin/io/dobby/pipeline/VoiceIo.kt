package io.dobby.pipeline

import io.dobby.pipeline.tts.VoiceModelState
import io.dobby.pipeline.tts.VoiceOption
import io.dobby.pipeline.wakeword.WakeWordOption
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration

/**
 * Speech in, speech out — the whole of what the layer above the microphone is allowed to ask for.
 *
 * [VoicePipeline] is the implementation and needs a `Context`, an `AudioRecord`, 670 MB of models
 * and a device with a microphone. This interface needs none of those, which is the point: the
 * code that joins speech to the Phase A engine is the part most worth testing and the part
 * hardware makes hardest to test, so the hardware sits behind one seam with five members.
 */
interface VoiceIo {
    val state: StateFlow<VoiceState>

    /** False while speech input is impossible — no model, no microphone, no German voice. */
    val canListen: Boolean

    /**
     * Fires when the wake phrase is heard, carrying the detection score.
     *
     * Only a signal: what happens next is the same turn the button triggers, decided one layer
     * up. Hands-free that ran its own turn would be a second path to keep in step with the
     * first, and they would not stay in step.
     */
    val wakeWords: SharedFlow<Float>

    /** Whether the wake word is currently armed. */
    val handsFree: StateFlow<Boolean>

    /** The phrase the panel answers to, or null when no wake word model could be loaded. */
    val wakePhrase: String?

    /** Every phrase settings can offer: the catalogue, plus anything pushed to the device. */
    fun wakeWordOptions(): List<WakeWordOption>

    /** The chosen phrase's id, or null while the catalogue default is in use. */
    val selectedWakeWordId: String?

    /**
     * How the panel acknowledges the wake word. Settable, and takes effect on the next one.
     *
     * A plain property rather than a flow: nothing in the pipeline reacts to it, it is read
     * once per wake word on the audio thread, and the screen that changes it is the screen
     * that shows it.
     */
    var listenCue: ListenCue

    /** Switches the active phrase, fetching its classifier the first time it is chosen. */
    suspend fun selectWakeWord(id: String)

    /** Every voice settings can offer: the catalogue, plus anything pushed to the device. */
    fun voiceOptions(): List<VoiceOption>

    /**
     * The voice that is speaking. Never null — an id nothing answers to resolves to the default.
     *
     * It changes only when a voice has actually loaded: a download that failed leaves the
     * previous selection standing, and [voiceState] is where the reason is.
     */
    val selectedVoiceId: String

    /**
     * How far along getting the chosen voice we are, so the settings row can show it.
     *
     * A flow rather than a return value because the row that starts a 114 MB download is the
     * row that has to show it, and the person choosing is looking at it while it runs.
     */
    val voiceState: StateFlow<VoiceModelState>

    /** Switches the voice, fetching it the first time it is chosen. */
    suspend fun selectVoice(id: String)

    /**
     * Gives the loaded voice's ~150 MB back.
     *
     * `dobby-plan.md` §9's order of retreat: Tier 2's KV cache goes first, then this, and the
     * recogniser is never touched. The next sentence is spoken by the phone's own voice while
     * the graph reloads behind it. Does nothing when the system voice is the one selected.
     */
    fun releaseVoice()

    /** Arms the wake word. Returns false when there is no model, leaving push-to-talk. */
    fun startHandsFree(): Boolean

    /** Disarms the wake word, closing the microphone if nothing else is listening. */
    fun stopHandsFree()

    suspend fun prepare()

    /**
     * One utterance. Null when nothing was said or the microphone never opened.
     *
     * [openFor] caps how long the microphone waits for someone to *start* talking; once a voice
     * is heard the utterance runs to its own end as usual. It is what closes the microphone
     * again after a wake word that fired at the television, and what ends the follow-up window
     * when a repeated sentence never comes.
     *
     * The wake word comes off the microphone stream for the duration and stays off until
     * [endTurn] — command audio is not wake-word audio (`dobby-plan.md` §2, invariant 4).
     */
    suspend fun listen(openFor: Duration): String?

    /**
     * The turn that [listen] began is finished: dispatched, answered, spoken. Re-arms the wake
     * word.
     *
     * The caller owns the turn, so the caller says when it ended. Re-arming inside [listen]
     * would put the wake word back on the stream in time to hear Dobby's own answer; re-arming
     * after [say] alone would miss every turn that answers silently. Idempotent.
     */
    fun endTurn()

    /**
     * Speaks [text], returning once it has finished playing.
     *
     * [onAudible] runs once, when the first sample actually reaches the hardware — which on
     * this device is about a second after the call, because Piper has to synthesise the
     * sentence first. It is how the caller turns the music down at the moment Dobby starts
     * talking rather than at the moment it decided to; a sentence that ends up unspoken never
     * runs it.
     */
    suspend fun say(text: String, onAudible: suspend () -> Unit = {})

    /**
     * Two short buzzes: heard you, did not understand you.
     *
     * The wordless half of an answer. It exists so that the one sentence Dobby would otherwise
     * repeat most often — "Das habe ich nicht verstanden." — never has to be spoken: it is
     * slow, it says nothing a buzz does not, and it talks over the moment when the person is
     * about to try again. Returns immediately.
     */
    fun buzz()

    fun shutdown()
}
