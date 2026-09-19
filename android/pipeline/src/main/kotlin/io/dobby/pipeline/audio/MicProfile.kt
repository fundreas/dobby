package io.dobby.pipeline.audio

import android.media.MediaRecorder

/**
 * Which microphone the panel opens, and what the platform is asked to do to it on the way out.
 *
 * M2 hard-coded `VOICE_RECOGNITION`, which is *defined* as the source with no AGC, no noise
 * suppression and **no echo cancellation** — it hands the recogniser the least-processed signal
 * the hardware can give, because in a quiet room that is the best thing for ASR. It is also why
 * the panel hears itself: nothing was ever asked to stop it.
 *
 * `VOICE_COMMUNICATION` requests the telephony chain instead, which is where the hardware echo
 * canceller lives. It is not free, and the costs are not knowable from the API:
 *
 * - **The AEC's reference signal is HAL-dependent.** Whether the *media* mix is in the echo
 *   reference on this device, or only a voice-call downlink, is an empirical question about the
 *   vendor's audio HAL. If it turns out to be the latter, barge-in over music is not supported
 *   on this hardware and the answer is the turn duck's `PAUSE` mode instead.
 * - **AGC and noise suppression are tuned for telephony, not for ASR.** openWakeWord's graphs
 *   were trained on unprocessed audio, so the score distribution shifts — which is why
 *   [threshold] and [patience] hang off the profile. Parakeet may get worse too, in the quiet
 *   room that is the common case.
 * - **`AudioManager.setMode(MODE_IN_COMMUNICATION)` is out of bounds.** On some devices it is
 *   what fully engages the AEC path; it also reroutes audio to the earpiece and turns the
 *   volume rocker into a call-volume control, which on a wall panel is unshippable.
 *
 * **The default does not move until it is measured** (`m2b-plan.md` B3): the 2×2 of profile
 * against a room with and without music, counting false rejects and false accepts, plus
 * `SttTemplateTest`'s pass rate under each.
 */
enum class MicProfile(
    val source: Int,
    /**
     * The wake word's detection threshold under this profile.
     *
     * Per-profile because a number measured under one is meaningless under the other, and both
     * start at openWakeWord's own suggested value because **neither has been measured yet**.
     * They are separate constants so that the day one of them moves, it moves alone.
     */
    val threshold: Float,
    /** Consecutive frames over [threshold] before the panel wakes up. Per-profile, as above. */
    val patience: Int,
) {
    /** Unprocessed, what M2 shipped. Best in a quiet room, deaf to nothing but itself. */
    RECOGNITION(MediaRecorder.AudioSource.VOICE_RECOGNITION, threshold = 0.5f, patience = 2),

    /** The telephony chain: platform AEC, noise suppression and AGC. */
    COMMUNICATION(MediaRecorder.AudioSource.VOICE_COMMUNICATION, threshold = 0.5f, patience = 2),

    ;

    /**
     * Whether to attach `AcousticEchoCanceler` and `NoiseSuppressor` to the session.
     *
     * Only for [COMMUNICATION]: on [RECOGNITION] they would fight the one property that source
     * is chosen for. Usually a no-op even here — the telephony chain normally has them on
     * already — but "usually" is not "always", and the effects are one `isAvailable()` call.
     */
    val wantsPlatformEffects: Boolean get() = this == COMMUNICATION

    companion object {
        /** What M2 shipped. Moves when, and only when, the 2×2 says so. */
        val DEFAULT: MicProfile = RECOGNITION

        /** Parses a stored name, falling back to [DEFAULT] for anything unrecognised. */
        fun of(name: String?): MicProfile = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
