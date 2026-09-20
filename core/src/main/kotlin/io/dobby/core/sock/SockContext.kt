package io.dobby.core.sock

import kotlinx.coroutines.CoroutineScope

/**
 * Everything a Sock is allowed to touch.
 *
 * Anything not reachable from here is off-limits — that is what keeps Socks unit-testable
 * without an emulator. Phase A ships the interfaces plus console/no-op implementations;
 * the Android implementations arrive in Phase B.
 */
interface SockContext {
    /** Service-lifetime scope for work that outlives a single `handle()` call. */
    val scope: CoroutineScope

    val playback: PlaybackCoordinator
    val screen: ScreenController
    val config: SockConfigStore
    val log: SockLog

    /**
     * Asynchronous speech — a timer firing, a stream dropping.
     *
     * Command acknowledgements do NOT go here; return a [SockResult] instead.
     *
     * A [Phrase] for the same reason every sayable [SockResult] is one, and here the late
     * binding earns its keep twice over: an announcement is built when the timer is *set* and
     * spoken when it *expires*, which can be an hour and a voice change later.
     */
    suspend fun announce(phrase: Phrase)
}

/**
 * Why a Sock lost the channel.
 *
 * Both mean "stop"; only the log line differs. The distinction is worth carrying because the
 * two read differently in a bug report — an eviction is Dobby arbitrating between its own
 * Socks, a system loss is somebody else's phone call.
 */
enum class FocusLoss {
    /** Another Sock claimed the channel. Radio evicted by Spotify, or the reverse. */
    EVICTED,

    /** Another app on the device took `AUDIOFOCUS_GAIN`. A phone call, a video, a nav prompt. */
    SYSTEM,
}

/**
 * Arbitrates the audio channel so two Socks can never play at once.
 *
 * Transient focus ducks the current holder instead of stopping it.
 *
 * **Losing the channel is a callback, not a poll** (`radio-plan.md` §A). Until Radio there was
 * no Sock holding sustained audio *inside* this process, so nothing had to be told when the
 * channel changed hands: Spotify's audio comes out of another process which handles its own
 * focus loss, and the Clock's chime is transient and over in two seconds. A Sock with an
 * in-process player has neither excuse, and a coordinator that only records who holds the
 * channel would leave an ExoPlayer streaming underneath the song that evicted it.
 */
interface PlaybackCoordinator {
    val holder: String?

    /**
     * Claims the channel for a Sock that plays audio **in this process**.
     *
     * [onLost] is invoked at most once per grant, when the channel is taken away — by another
     * Sock or by another app. Null for a Sock with nothing to stop, which is why the parameter
     * is optional: Clock and Spotify want no callback and say so by not passing one.
     *
     * A second claim from the **same** sock id is a re-tune, not an eviction, and does not fire
     * the callback — "radio ö3" while FM4 plays would otherwise stop the player the Sock is
     * about to hand a new URL.
     */
    suspend fun requestFocus(sockId: String, onLost: (suspend (FocusLoss) -> Unit)? = null): Boolean

    suspend fun requestTransientFocus(sockId: String): Boolean

    /**
     * Claims the audio channel for a Sock whose sound is produced by **another app**.
     *
     * Bookkeeping only: no Android audio focus is requested, because the playing process
     * requests its own and taking `AUDIOFOCUS_GAIN` here would stop it. Spotify is the case
     * (`spotify.specs.md` §3) — the audio comes out of `com.spotify.music`, and a Sock that
     * asked for focus in the ordinary way would pause the music one instant before asking the
     * App Remote to play it.
     *
     * The coordinator still learns who holds the channel, which is the half that matters to
     * everyone else: Radio-vs-Spotify arbitration and the dashboard both read [holder], and a
     * holder that lies is worse than no coordinator at all.
     *
     * The eviction is this coordinator's job and not the OS's: a standing [requestFocus]
     * callback from a *different* Sock is invoked with [FocusLoss.EVICTED] before the claim is
     * recorded. Leaving it to the system was the hole M4 had to close — the Spotify app's
     * `AUDIOFOCUS_GAIN` is granted against Dobby's abandoned request, so nothing of ours is
     * left for the OS to revoke and Radio's ExoPlayer would stream on underneath the song
     * (`radio-plan.md` §A1).
     *
     * Turn ducking is untouched: [requestTransientFocus] is cross-process, so the other app
     * ducks while Dobby speaks and comes back up by itself.
     */
    suspend fun claimExternal(sockId: String, onLost: (suspend (FocusLoss) -> Unit)? = null): Boolean

    /** Releases the channel, and the standing [requestFocus] callback with it. */
    suspend fun releaseFocus(sockId: String)
}

/** Owns the screen-on wake locks. Socks ask; they never hold locks themselves. */
interface ScreenController {
    fun wakeFor(seconds: Int)

    fun release()
}

/** Namespaced key-value config. Keys are `<sockId>.<key>` by convention. */
interface SockConfigStore {
    fun getString(key: String): String?

    fun getInt(key: String): Int?

    fun getBoolean(key: String): Boolean?

    fun put(key: String, value: String)
}

interface SockLog {
    fun debug(message: String)

    fun warn(message: String, cause: Throwable? = null)

    companion object {
        /** Discards everything. The default wherever core takes a log it can do without. */
        val NONE: SockLog = object : SockLog {
            override fun debug(message: String) = Unit

            override fun warn(message: String, cause: Throwable?) = Unit
        }
    }
}
