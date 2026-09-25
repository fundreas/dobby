package io.dobby.android

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.PowerManager
import android.util.Log
import androidx.core.content.edit
import io.dobby.core.sock.FocusLoss
import io.dobby.core.sock.Phrase
import io.dobby.core.sock.PlaybackCoordinator
import io.dobby.core.sock.ScreenController
import io.dobby.core.sock.SockConfigStore
import io.dobby.core.sock.SockContext
import io.dobby.core.sock.SockLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

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
    private val speak: suspend (Phrase) -> Unit,
) : SockContext {

    private val appContext = context.applicationContext

    override val playback: PlaybackCoordinator = AndroidPlayback(appContext, scope)

    override val screen: ScreenController = AndroidScreen(appContext)

    override val config: SockConfigStore = PreferencesConfig(appContext)

    override val log: SockLog = LogcatLog()

    override suspend fun announce(phrase: Phrase) = speak(phrase)
}

/**
 * Audio focus, wrapped so a Sock never sees an [AudioManager].
 *
 * Two kinds of claim, and the difference is which process makes the sound. [requestFocus] is
 * for a Sock that plays audio itself — Radio's ExoPlayer, in this process — and asks Android
 * for `AUDIOFOCUS_GAIN`. [claimExternal] is for a Sock whose audio comes out of another app,
 * which is Spotify: the App Remote only tells `com.spotify.music` to play, and that process
 * holds its own focus. Asking for GAIN on Dobby's behalf there would send `AUDIOFOCUS_LOSS` to
 * Spotify — stopping the music one instant before asking it to start (`spotify.specs.md` §3).
 *
 * **Losing the channel is a callback** (`radio-plan.md` §A2). One optional `onLost` slot,
 * because the coordinator holds exactly one claim, invoked from two places: [evict], when
 * another Sock takes the channel, and the focus listener on the request built in [grant], when
 * another app does. Without it the arbitration is one-way — starting Spotify over the radio
 * would leave Dobby's own ExoPlayer streaming, because the request it would have lost was
 * abandoned a line earlier and carried no listener anyway.
 */
private class AndroidPlayback(context: Context, private val scope: CoroutineScope) : PlaybackCoordinator {
    private val audio = context.getSystemService(AudioManager::class.java)
    private var request: AudioFocusRequest? = null

    /** The current holder's loss callback, if it wanted one. One slot, one claim. */
    private var onLost: (suspend (FocusLoss) -> Unit)? = null

    /**
     * The transient claim, which is a **second** slot and not the standing one.
     *
     * A duck is not a change of holder — that is the whole difference between ducking and
     * stopping, and `FakePlaybackCoordinator` has said so since Phase A. Folding the Clock's
     * chime into [request] was harmless while nothing listened for a loss; with [onLost] in the
     * picture it would hand Radio's callback to the chime and release it two seconds later.
     * The row this protects is a timer ringing over the radio: the chime stops, the stream does
     * not (`shared-commands.specs.md` §3.4, `radio-plan.md` §K3 row 7).
     */
    private var transient: AudioFocusRequest? = null
    private var transientHolder: String? = null

    /**
     * Whether the current claim was an external one, so [releaseFocus] does the right one of
     * two things. A field rather than a lookup on [request] because "no request" is also the
     * state before anything has ever claimed, and those two must not be confused.
     */
    private var external = false

    override var holder: String? = null
        private set

    override suspend fun requestFocus(sockId: String, onLost: (suspend (FocusLoss) -> Unit)?): Boolean =
        grant(sockId, onLost)

    override suspend fun requestTransientFocus(sockId: String): Boolean {
        transient?.let { audio.abandonAudioFocusRequest(it) }
        val next = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(mediaAttributes())
            .build()
        transient = next
        transientHolder = sockId
        return audio.requestAudioFocus(next) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    /**
     * Bookkeeping, and deliberately nothing else.
     *
     * Any focus request Dobby holds is abandoned first: the channel is changing hands, and
     * leaving Radio's GAIN standing while Spotify plays would make [holder] and the OS
     * disagree. Always granted — there is nothing that could refuse it.
     */
    override suspend fun claimExternal(sockId: String, onLost: (suspend (FocusLoss) -> Unit)?): Boolean {
        evict(sockId)
        request?.let { audio.abandonAudioFocusRequest(it) }
        request = null
        external = true
        holder = sockId
        this.onLost = onLost
        return true
    }

    override suspend fun releaseFocus(sockId: String) {
        if (transientHolder == sockId) {
            transient?.let { audio.abandonAudioFocusRequest(it) }
            transient = null
            transientHolder = null
        }
        if (holder != sockId) return
        // Nothing to abandon for an external claim — nothing was ever requested.
        if (!external) request?.let { audio.abandonAudioFocusRequest(it) }
        request = null
        external = false
        holder = null
        onLost = null
    }

    private suspend fun grant(sockId: String, onLost: (suspend (FocusLoss) -> Unit)?): Boolean {
        evict(sockId)
        request?.let { audio.abandonAudioFocusRequest(it) }
        external = false
        val next = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(mediaAttributes())
            .setOnAudioFocusChangeListener { change ->
                // Permanent loss only. A transient loss is either AndroidTurnAudio ducking us
                // for our own turn — which InProcessPlayers already handles, at a volume rather
                // than a stop — or somebody else's nav prompt, which is over before a reconnect
                // would finish. Stopping a stream for either is worse than riding it out, and
                // filtering here makes "does the OS deliver in-app focus changes" moot: neither
                // turn mode can produce a permanent loss, so a turn can never look like one.
                if (change == AudioManager.AUDIOFOCUS_LOSS) notifyLost(FocusLoss.SYSTEM)
            }
            .build()
        request = next
        val granted = audio.requestAudioFocus(next) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        holder = if (granted) sockId else null
        this.onLost = if (granted) onLost else null
        return granted
    }

    private fun mediaAttributes(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    /**
     * Tells the standing holder the channel is gone, unless it is the one asking.
     *
     * The same sock id claiming twice is a re-tune — "radio ö3" over FM4 — and a callback there
     * would release the player the Sock is one line away from handing a new URL.
     */
    private suspend fun evict(sockId: String) {
        if (holder == null || holder == sockId) return
        val previous = onLost
        onLost = null
        previous?.invoke(FocusLoss.EVICTED)
    }

    /**
     * The system's half, from the focus listener — which is not a coroutine and never will be.
     *
     * Launched on the context's scope rather than blocked on: the listener runs on the main
     * looper, and a Sock releasing an ExoPlayer from underneath it must not stall the thread
     * that delivers focus changes to the rest of the app.
     */
    private fun notifyLost(reason: FocusLoss) {
        val previous = onLost ?: return
        onLost = null
        holder = null
        scope.launch { previous.invoke(reason) }
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

    /**
     * Whether the panel's screen is awake — the gate on the Departures Sock's polling
     * (`departures.specs.md` §6.1).
     *
     * `isInteractive` and not `isScreenOn`: the latter is deprecated and answers "is the
     * display powered", which on a device showing an always-on clock is true all night. What
     * this has to mean is "could somebody be reading the card right now", and that is exactly
     * what interactive means.
     */
    override val isOn: Boolean get() = power.isInteractive

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
