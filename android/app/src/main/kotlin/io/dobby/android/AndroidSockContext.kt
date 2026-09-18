package io.dobby.android

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.PowerManager
import android.util.Log
import androidx.core.content.edit
import io.dobby.core.sock.PlaybackCoordinator
import io.dobby.core.sock.ScreenController
import io.dobby.core.sock.SockConfigStore
import io.dobby.core.sock.SockContext
import io.dobby.core.sock.SockLog
import kotlinx.coroutines.CoroutineScope

/**
 * The Phase B half of `SockContext`.
 *
 * Phase A shipped these interfaces with console implementations so the Socks could be written
 * and tested before the device existed. Nothing in a Sock changes here — which was the point
 * of the interfaces, and is the first real test of whether that boundary was drawn correctly.
 */
class AndroidSockContext(
    context: Context,
    override val scope: CoroutineScope,
    private val speak: suspend (String) -> Unit,
) : SockContext {

    private val appContext = context.applicationContext

    override val playback: PlaybackCoordinator = AndroidPlayback(appContext)

    override val screen: ScreenController = AndroidScreen(appContext)

    override val config: SockConfigStore = PreferencesConfig(appContext)

    override val log: SockLog = LogcatLog()

    override suspend fun announce(text: String) = speak(text)
}

/**
 * Audio focus, wrapped so a Sock never sees an [AudioManager].
 *
 * There is nothing to arbitrate yet — Clock and Help make no sound of their own. It is
 * implemented now anyway because the interface is already public to every Sock, and a
 * coordinator that silently does nothing would be discovered by Radio and Spotify in M4 at
 * the worst possible moment.
 */
private class AndroidPlayback(context: Context) : PlaybackCoordinator {
    private val audio = context.getSystemService(AudioManager::class.java)
    private var request: AudioFocusRequest? = null

    override var holder: String? = null
        private set

    override suspend fun requestFocus(sockId: String): Boolean = grant(sockId, AudioManager.AUDIOFOCUS_GAIN)

    override suspend fun requestTransientFocus(sockId: String): Boolean =
        grant(sockId, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)

    override suspend fun releaseFocus(sockId: String) {
        if (holder != sockId) return
        request?.let { audio.abandonAudioFocusRequest(it) }
        request = null
        holder = null
    }

    private fun grant(sockId: String, durationHint: Int): Boolean {
        request?.let { audio.abandonAudioFocusRequest(it) }
        val next = AudioFocusRequest.Builder(durationHint)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .build()
        request = next
        val granted = audio.requestAudioFocus(next) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        holder = if (granted) sockId else null
        return granted
    }
}

/**
 * The screen wake lock (`dobby-plan.md` §7.2).
 *
 * The flags are deprecated and the plan says to use them anyway: the alternative routes the
 * wake through an Activity, and a wall panel whose screen is driven by whichever Sock happens
 * to be talking is exactly the case the Activity API does not cover. Socks ask; only this
 * class ever holds a lock.
 */
private class AndroidScreen(context: Context) : ScreenController {
    private val power = context.getSystemService(PowerManager::class.java)

    @Suppress("DEPRECATION")
    private val lock = power.newWakeLock(
        PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
        "dobby:screen",
    )

    override fun wakeFor(seconds: Int) {
        if (seconds <= 0) return
        // A referenced lock would need one release per acquire; this one is a timer that any
        // later request simply extends.
        lock.setReferenceCounted(false)
        lock.acquire(seconds * MILLIS_PER_SECOND)
    }

    override fun release() {
        if (lock.isHeld) lock.release()
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1000L
    }
}

/** Namespaced config on top of SharedPreferences. Keys are `<sockId>.<key>` by convention. */
private class PreferencesConfig(context: Context) : SockConfigStore {
    private val preferences = context.getSharedPreferences("dobby-socks", Context.MODE_PRIVATE)

    override fun getString(key: String): String? = preferences.getString(key, null)

    override fun getInt(key: String): Int? = getString(key)?.toIntOrNull()

    override fun getBoolean(key: String): Boolean? = getString(key)?.toBooleanStrictOrNull()

    override fun put(key: String, value: String) = preferences.edit { putString(key, value) }
}

private class LogcatLog : SockLog {
    override fun debug(message: String) {
        Log.d(TAG, message)
    }

    override fun warn(message: String, cause: Throwable?) {
        Log.w(TAG, message, cause)
    }

    private companion object {
        const val TAG = "Dobby"
    }
}
