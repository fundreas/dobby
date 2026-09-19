package io.dobby.android

import android.content.Context
import androidx.core.content.edit

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

    private companion object {
        const val KEY_WAKE_WORD = "wakeword.id"
        const val KEY_HANDS_FREE = "wakeword.armed"
    }
}
