package io.dobby.pipeline

import kotlinx.coroutines.flow.StateFlow

/**
 * Speech in, speech out — the whole of what the layer above the microphone is allowed to ask for.
 *
 * [VoicePipeline] is the implementation and needs a `Context`, an `AudioRecord`, a 46 MB model
 * and a device with a microphone. This interface needs none of those, which is the point: the
 * code that joins speech to the Phase A engine is the part most worth testing and the part
 * hardware makes hardest to test, so the hardware sits behind one seam with five members.
 */
interface VoiceIo {
    val state: StateFlow<VoiceState>

    /** False while speech input is impossible — no model, no microphone, no German voice. */
    val canListen: Boolean

    suspend fun prepare()

    /** One utterance. Null when nothing was said or the microphone never opened. */
    suspend fun listen(): String?

    /** Speaks [text], returning once it has finished playing. */
    suspend fun say(text: String)

    fun shutdown()
}
