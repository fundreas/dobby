package io.dobby.pipeline.haptics

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * The panel's one wordless answer: two short pulses meaning "heard you, did not understand you".
 *
 * It exists because the spoken version of that sentence is the worst thing Dobby says. It is
 * long, it is always the same, it arrives a second late, and it is the answer people get
 * exactly when they are already slightly annoyed — and unlike a real answer it carries no
 * information beyond "no". Two buzzes carry the same "no" in 200 ms and leave the room quiet
 * enough to simply say the command again, which is what the follow-up window is for.
 *
 * A wall panel is a phone in a bracket: the bracket is what makes the buzz audible across a
 * kitchen. On a device with no vibrator this degrades to nothing, which is the same degradation
 * a missing earcon gets and for the same reason — feedback is not worth a crash.
 */
class Haptics(context: Context) {

    private val vibrator: Vibrator? =
        context.applicationContext.getSystemService(VibratorManager::class.java)
            ?.defaultVibrator
            ?.takeIf { it.hasVibrator() }

    /** Buzz–pause–buzz. Returns immediately; the pattern plays on its own. */
    fun notUnderstood() {
        val device = vibrator ?: return
        try {
            device.vibrate(VibrationEffect.createWaveform(NOT_UNDERSTOOD, NO_REPEAT))
        } catch (e: RuntimeException) {
            // Some OEM vibrator services throw when the device is in a mode that forbids
            // haptics. Not a reason to lose the turn that was about to be retried.
        }
    }

    private companion object {
        /** Off, on, off, on — short enough to read as one gesture rather than two events. */
        val NOT_UNDERSTOOD = longArrayOf(0, 60, 90, 60)

        const val NO_REPEAT = -1
    }
}
