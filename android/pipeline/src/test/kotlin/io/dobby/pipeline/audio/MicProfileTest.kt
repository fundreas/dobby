package io.dobby.pipeline.audio

import android.media.MediaRecorder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Which microphone the panel opens, and the promise that this has not quietly changed.
 *
 * `m2b-plan.md` B1 makes the source a choice and leaves the default where M2 put it: the point
 * of the step is that no behaviour moves until the 2×2 in a real room says which way. A default
 * that drifted in a later edit would be exactly the kind of change nobody notices until the
 * measurements no longer describe the thing that shipped.
 */
class MicProfileTest {

    @Test
    fun `the default is still the unprocessed source M2 shipped`() {
        assertEquals(MicProfile.RECOGNITION, MicProfile.DEFAULT)
        assertEquals(MediaRecorder.AudioSource.VOICE_RECOGNITION, MicProfile.DEFAULT.source)
    }

    @Test
    fun `the processed profile asks for the telephony chain`() {
        // Which is where the hardware echo canceller lives, and the only thing on this device
        // that could ever let the wake word be heard over the panel's own speaker.
        assertEquals(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MicProfile.COMMUNICATION.source,
        )
        assertTrue(MicProfile.COMMUNICATION.wantsPlatformEffects)
        assertTrue(
            !MicProfile.RECOGNITION.wantsPlatformEffects,
            "attaching AEC and noise suppression to the unprocessed source fights the one " +
                "property it is chosen for",
        )
    }

    @Test
    fun `each profile carries its own wake word tuning`() {
        // They start equal because neither has been measured. They are separate constants so
        // that the day one of them moves, it moves alone.
        for (profile in MicProfile.entries) {
            assertTrue(profile.threshold in 0f..1f, "$profile threshold ${profile.threshold}")
            assertTrue(profile.patience >= 1, "$profile patience ${profile.patience}")
        }
    }

    @Test
    fun `an unreadable setting falls back to the default rather than throwing`() {
        // A settings file is the one input that can come back from an older version of the app.
        assertEquals(MicProfile.DEFAULT, MicProfile.of(null))
        assertEquals(MicProfile.DEFAULT, MicProfile.of("VOICE_CALL"))
        assertEquals(MicProfile.COMMUNICATION, MicProfile.of("COMMUNICATION"))
    }
}
