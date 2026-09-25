package io.dobby.android

import android.content.Context
import androidx.core.content.edit
import io.dobby.core.audio.TurnDuck
import io.dobby.pipeline.ListenCue
import io.dobby.pipeline.audio.MicProfile
import io.dobby.pipeline.wakeword.WakeMode
import io.dobby.pipeline.wakeword.WakePhrase

/**
 * The panel's own settings, as opposed to a Sock's.
 *
 * Deliberately separate from `SockConfigStore`: that store is namespaced by Sock id and handed
 * to Socks, and the wake phrase belongs to nobody's Sock — it is core's, one layer below every
 * command. Sharing the file would have been convenient and would have put app settings in a
 * namespace Socks are invited to write to.
 */
class Settings(context: Context) {

    private val preferences =
        context.applicationContext.getSharedPreferences("dobby", Context.MODE_PRIVATE)

    /** The chosen wake phrase id, or null to let the catalogue default stand. */
    var wakeWordId: String?
        get() = preferences.getString(KEY_WAKE_WORD, null)
        set(value) = preferences.edit { putString(KEY_WAKE_WORD, value) }

    /**
     * Which of the two ways of hearing its own name the panel uses.
     *
     * Stored by name for the same reason [listenCue] is, and defaulting to the classifier: a
     * setting that silently upgraded an existing panel to a mode that runs ASR all day is not a
     * setting, it is a surprise.
     */
    var wakeMode: WakeMode
        get() = WakeMode.of(preferences.getString(KEY_WAKE_MODE, null))
        set(value) = preferences.edit { putString(KEY_WAKE_MODE, value.name) }

    /**
     * The phrase [WakeMode.TRANSCRIPT] answers to, as typed.
     *
     * Free text, and the reason that mode exists: the classifier can only answer to phrases
     * somebody has trained a head for, and nobody has trained one for "Hey Dobby". Blank reads
     * back as the default rather than as a panel that answers to everything.
     */
    var spokenWakePhrase: String
        get() = preferences.getString(KEY_SPOKEN_PHRASE, null)?.takeIf { it.isNotBlank() }
            ?: WakePhrase.DEFAULT
        set(value) = preferences.edit { putString(KEY_SPOKEN_PHRASE, value.trim()) }

    /**
     * The chosen voice's id, or null to let the catalogue default stand.
     *
     * Read back through `VoiceCatalogue.of()` rather than trusted, for the same reason
     * [listenCue] is read through `ListenCue.of()`: a sideloaded voice that was deleted and a
     * value written by an older build are both ids nothing answers to, and a settings file is
     * the one input that can arrive from a version of the app that no longer exists.
     */
    var voiceId: String?
        get() = preferences.getString(KEY_VOICE, null)
        set(value) = preferences.edit { putString(KEY_VOICE, value) }

    /**
     * Whether the wake word should arm itself at startup.
     *
     * Remembered because switching it off is a deliberate act — someone who turned the
     * microphone off does not expect the next reboot to turn it back on.
     */
    var handsFree: Boolean
        get() = preferences.getBoolean(KEY_HANDS_FREE, true)
        set(value) = preferences.edit { putBoolean(KEY_HANDS_FREE, value) }

    /**
     * How the panel acknowledges the wake word: a buzz, a pip, or nothing.
     *
     * Stored by name rather than by ordinal, so reordering the enum cannot silently change
     * what somebody chose. An unreadable value falls back to the default instead of throwing —
     * a settings file is the one input that can come back from an older version of the app.
     */
    var listenCue: ListenCue
        get() = ListenCue.of(preferences.getString(KEY_LISTEN_CUE, null))
        set(value) = preferences.edit { putString(KEY_LISTEN_CUE, value.name) }

    /**
     * What happens to the music while somebody is talking to the panel: quieter, stopped, or —
     * while it is playing on a Bluetooth speaker in some other room — nothing.
     *
     * Stored by name for the same reason [listenCue] is. The default is `DUCK` and stays there
     * until the measurement in `m2b-plan.md` B3 says otherwise — if the platform echo canceller
     * turns out not to reference the media mix on this device, `PAUSE` is the honest answer and
     * this is the switch that delivers it.
     */
    var turnDuck: TurnDuck
        get() = TurnDuck.of(preferences.getString(KEY_TURN_DUCK, null))
        set(value) = preferences.edit { putString(KEY_TURN_DUCK, value.name) }

    /**
     * Which microphone the panel opens: the unprocessed one, or the telephony chain with its
     * echo canceller.
     *
     * A setting rather than a constant because choosing between them is the 2×2 in
     * `m2b-plan.md` B3, and that measurement is somebody standing in a room saying the wake
     * phrase fifty times — which they cannot do if switching profiles means a rebuild. Takes
     * effect on the next start: the microphone source is fixed for the life of the pipeline.
     */
    var micProfile: MicProfile
        get() = MicProfile.of(preferences.getString(KEY_MIC_PROFILE, null))
        set(value) = preferences.edit { putString(KEY_MIC_PROFILE, value.name) }

    /**
     * The crash tripwire for Tier 2.
     *
     * Set immediately before the first-ever `nativeLoadModel`, cleared after a successful
     * prefill. Still set at the next start means the last attempt did not survive loading the
     * model — and a native crash on a `START_STICKY` service is a boot loop, not a crash. So
     * Tier 2 is skipped permanently until somebody re-arms it, and the panel says why.
     *
     * The one failure mode [io.dobby.llama.LlamaTier2] cannot catch, made survivable by the
     * only mechanism that works across a process death: a flag on disk.
     */
    var llmLoadAttempted: Boolean
        get() = preferences.getBoolean(KEY_LLM_ATTEMPTED, false)
        set(value) = preferences.edit { putBoolean(KEY_LLM_ATTEMPTED, value) }

    /** Cleared by the settings screen's re-arm toggle, after a crash disabled Tier 2. */
    var llmDisabledByCrash: Boolean
        get() = preferences.getBoolean(KEY_LLM_CRASHED, false)
        set(value) = preferences.edit { putBoolean(KEY_LLM_CRASHED, value) }

    /** Whether Tier 2 may run at all. Off by default: the gigabyte is a deliberate download. */
    var llmEnabled: Boolean
        get() = preferences.getBoolean(KEY_LLM_ENABLED, false)
        set(value) = preferences.edit { putBoolean(KEY_LLM_ENABLED, value) }

    private companion object {
        const val KEY_WAKE_WORD = "wakeword.id"
        const val KEY_WAKE_MODE = "wakeword.mode"
        const val KEY_SPOKEN_PHRASE = "wakeword.phrase"
        const val KEY_VOICE = "voice.id"
        const val KEY_HANDS_FREE = "wakeword.armed"
        const val KEY_LISTEN_CUE = "listen.cue"
        const val KEY_TURN_DUCK = "turn.duck"
        const val KEY_MIC_PROFILE = "mic.profile"
        const val KEY_LLM_ATTEMPTED = "llm.loadAttempted"
        const val KEY_LLM_CRASHED = "llm.disabledByCrash"
        const val KEY_LLM_ENABLED = "llm.enabled"
    }
}
