package io.dobby.android

import android.content.Context
import android.media.AudioManager
import android.util.Log
import io.dobby.socks.system.VolumeControl

/**
 * The device half of the System Sock, and the only place `AudioManager` is named.
 *
 * Exactly parallel to [ClockHardware] and [SpotifyHardware], and thinner than either: there is
 * nothing to connect to and nothing to release, so this is a constructor and four one-line
 * overrides. The Sock sees [VolumeControl] and never an Android type, which is what lets the
 * whole volume surface resolve in a terminal.
 *
 * `STREAM_MUSIC` throughout — the stream Spotify, the radio and Dobby's own TTS all use
 * (`system.specs.md` §3). Per-stream volume is explicitly out of scope for v1.
 */
class SystemHardware(context: Context) {

    val volume: VolumeControl = AndroidVolume(context.applicationContext)
}

private class AndroidVolume(context: Context) : VolumeControl {

    private val audio = context.getSystemService(AudioManager::class.java)

    override val maxIndex: Int get() = audio?.getStreamMaxVolume(STREAM) ?: 0

    override val index: Int get() = audio?.getStreamVolume(STREAM) ?: 0

    override val isMuted: Boolean get() = audio?.isStreamMute(STREAM) ?: false

    /**
     * **Flag `0`, never `FLAG_SHOW_UI`.** This is a wall panel: a system volume overlay across
     * the dashboard is the wrong answer to "mach lauter" (`system.specs.md` §3).
     *
     * `setStreamVolume` throws when Do Not Disturb is on and the app has no
     * `ACCESS_NOTIFICATION_POLICY`. Logged rather than crashed — a refused volume change is a
     * command that did not happen, not a reason to take the panel down with it.
     */
    override fun setIndex(index: Int) {
        val manager = audio ?: return
        try {
            manager.setStreamVolume(STREAM, index.coerceIn(0, maxIndex), 0)
        } catch (e: SecurityException) {
            Log.w(TAG, "volume change refused — Do Not Disturb without ACCESS_NOTIFICATION_POLICY", e)
        }
    }

    override fun setMuted(muted: Boolean) {
        val manager = audio ?: return
        val direction = if (muted) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE
        try {
            manager.adjustStreamVolume(STREAM, direction, 0)
        } catch (e: SecurityException) {
            Log.w(TAG, "mute refused — Do Not Disturb without ACCESS_NOTIFICATION_POLICY", e)
        }
    }

    private companion object {
        const val STREAM = AudioManager.STREAM_MUSIC
        const val TAG = "Dobby"
    }
}
