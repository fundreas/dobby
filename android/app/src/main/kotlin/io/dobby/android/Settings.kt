package io.dobby.android

import android.content.Context
import androidx.core.content.edit
import io.dobby.pipeline.ListenCue

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

    private companion object {
        const val KEY_WAKE_WORD = "wakeword.id"
        const val KEY_HANDS_FREE = "wakeword.armed"
        const val KEY_LISTEN_CUE = "listen.cue"
    }
}
