package io.dobby.pipeline.tts

import android.media.AudioManager
import android.media.ToneGenerator
import java.io.Closeable

/**
 * The sound the panel makes when the wake word lands.
 *
 * Small, but not decoration: between the wake word firing and the panel having anything to show,
 * there is a second of silence in which the only honest question is "did it hear me?". The
 * beep answers it before the screen can. `dobby-plan.md` §5.1 asks for it by name.
 *
 * A generated tone rather than an audio asset — one short beep does not justify a file, and a
 * `ToneGenerator` cannot fail to decode.
 */
class Earcon(private val volumePercent: Int = DEFAULT_VOLUME) : Closeable {

    private var generator: ToneGenerator? = null

    /** Plays the pip. Returns immediately; the tone finishes on its own. */
    fun play() {
        val tone = generator ?: create() ?: return
        tone.startTone(ToneGenerator.TONE_CDMA_PIP, DURATION_MS)
    }

    private fun create(): ToneGenerator? = try {
        // STREAM_NOTIFICATION, not MUSIC: on a panel that will one day be playing the radio,
        // the acknowledgement must not duck or be ducked by what it is interrupting.
        ToneGenerator(AudioManager.STREAM_NOTIFICATION, volumePercent).also { generator = it }
    } catch (e: RuntimeException) {
        // Some devices refuse when the audio hardware is busy. A missing beep is not a reason
        // to not listen.
        null
    }

    override fun close() {
        generator?.release()
        generator = null
    }

    private companion object {
        const val DEFAULT_VOLUME = 70

        /**
         * A blip, not a beep.
         *
         * `TONE_PROP_BEEP` at 120 ms is the sound a microwave makes and it reads as an *alert* —
         * something has happened, attend to it. What this has to say is much smaller: the
         * microphone is open, carry on talking. A short pip says that and then gets out of the
         * way of the sentence following it.
         */
        const val DURATION_MS = 80
    }
}
