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
 * `ToneGenerator` cannot fail to decode. Nothing here is shipped in the APK and nothing is a
 * system sound the user could have changed: the platform synthesises the waveform on demand,
 * the same machinery that makes a dial tone.
 */
class Earcon(private val volumePercent: Int = DEFAULT_VOLUME) : Closeable {

    private var generator: ToneGenerator? = null

    /** Plays the pip. Returns immediately; the tone finishes on its own. */
    fun play() {
        val tone = generator ?: create() ?: return
        tone.startTone(ToneGenerator.TONE_CDMA_PIP, DURATION_MS)
    }

    private fun create(): ToneGenerator? = try {
        // STREAM_MUSIC, and it has to be.
        //
        // This was STREAM_NOTIFICATION, on the reasoning that an acknowledgement should not be
        // ducked by the radio it interrupts. True, and irrelevant next to what it cost: ringer
        // mode mutes STREAM_NOTIFICATION outright, so a phone set to vibrate — which a wall
        // panel very reasonably is — played nothing at all, silently, with the tone generator
        // reporting success. Somebody who goes into settings and chooses "Ton" has asked for a
        // sound in the plainest terms available to them; a panel that answers by consulting a
        // ringer it never rings is broken, whatever the reasoning behind it was.
        //
        // Media volume is also the one the rocker controls by default, so "make it quieter" now
        // does what it looks like it does.
        ToneGenerator(AudioManager.STREAM_MUSIC, volumePercent).also { generator = it }
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
