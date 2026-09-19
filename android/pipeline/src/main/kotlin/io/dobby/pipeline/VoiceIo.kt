package io.dobby.pipeline

import io.dobby.pipeline.wakeword.WakeWordOption
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

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

    /** Switches the active phrase, fetching its classifier the first time it is chosen. */
    suspend fun selectWakeWord(id: String)

    /** Arms the wake word. Returns false when there is no model, leaving push-to-talk. */
    fun startHandsFree(): Boolean

    /** Disarms the wake word, closing the microphone if nothing else is listening. */
    fun stopHandsFree()

    suspend fun prepare()

    /**
     * One utterance. Null when nothing was said or the microphone never opened.
     *
     * The wake word comes off the microphone stream for the duration and stays off until
     * [endTurn] — command audio is not wake-word audio (`dobby-plan.md` §2, invariant 4).
     */
    suspend fun listen(): String?

    /**
     * The turn that [listen] began is finished: dispatched, answered, spoken. Re-arms the wake
     * word.
     *
     * The caller owns the turn, so the caller says when it ended. Re-arming inside [listen]
     * would put the wake word back on the stream in time to hear Dobby's own answer; re-arming
     * after [say] alone would miss every turn that answers silently. Idempotent.
     */
    fun endTurn()

    /** Speaks [text], returning once it has finished playing. */
    suspend fun say(text: String)

    fun shutdown()
}
