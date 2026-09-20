package io.dobby.socks.spotify

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Everything the Sock does to the Spotify app.
 *
 * The Clock Sock's `TimerAlarm` is the precedent and the argument: the Sock sees an interface,
 * the device sees `AlarmManager`, and the whole timer lifecycle runs on a plain JVM because of
 * it. Here the device half is the App Remote SDK, which lives in `:android:spotify` and is the
 * only module in the project that names a Spotify SDK type.
 *
 * Almost every call is a suspend function returning a boolean, because App Remote is a callback
 * API over a Binder connection and neither of those belongs in a Sock. A `false` means "it did
 * not happen" and says nothing about why — the Sock's German copy is the same either way
 * (`spotify.specs.md` §3). [connect] is the exception, and says why it is one.
 *
 * Album art is **not** here. It arrives as an Android `Bitmap` over the same connection, so it
 * stays on the device side and reaches the dashboard from `SpotifyHardware` directly; routing
 * it through this interface would mean either an Android type in a JVM module or an `Any`, and
 * neither buys the Sock anything it uses.
 */
interface SpotifyPlayer {

    /**
     * The last player state the App Remote pushed, or null when nothing is loaded.
     *
     * **A cached read, never a Binder round-trip.** `activityFor` is a pure state read under
     * the contract (`socks.specs/README.md` §4) and it reads exactly this.
     */
    val state: StateFlow<PlayerSnapshot?>

    /** Whether the Spotify app is on the device at all. Answerable without a connection. */
    val isInstalled: Boolean

    /**
     * Connects, or reuses a live connection. The one call that may show a consent dialog.
     *
     * Returns a small result rather than a boolean, and that is not decoration: "no Premium"
     * has its own German sentence and its own `Unavailable` status, while an overnight
     * disconnect has neither and must not be confused with it (`spotify.specs.md` §3, §10).
     * Everything else here really is a boolean, because everything else has one sentence.
     */
    suspend fun connect(): Connection

    suspend fun play(uri: String): Boolean

    /**
     * Plays the first playable item of the user's own home feed, and names it.
     *
     * This is the empty-query path (`spotify.specs.md` §3): recently played, their playlists,
     * made-for-you — content that runs inside their session and that the app token behind
     * [MusicSearch] could never see. Null when nothing came back or nothing played.
     */
    suspend fun resumeHome(): String?

    suspend fun pause(): Boolean

    suspend fun resume(): Boolean

    suspend fun skipNext(): Boolean

    suspend fun skipPrevious(): Boolean

    suspend fun seekToStart(): Boolean

    fun disconnect()

    companion object {
        /**
         * Off-device: no Spotify app, no connection, nothing playing.
         *
         * The terminal harness and any unit test get this, which is what keeps `cli/Main.kt`
         * compiling and the registry complete — the palette must contain every command whether
         * or not a device is present.
         */
        val NONE: SpotifyPlayer = object : SpotifyPlayer {
            override val state: StateFlow<PlayerSnapshot?> =
                MutableStateFlow<PlayerSnapshot?>(null).asStateFlow()

            override val isInstalled: Boolean = false

            override suspend fun connect(): Connection = Connection.NotInstalled

            override suspend fun play(uri: String): Boolean = false

            override suspend fun resumeHome(): String? = null

            override suspend fun pause(): Boolean = false

            override suspend fun resume(): Boolean = false

            override suspend fun skipNext(): Boolean = false

            override suspend fun skipPrevious(): Boolean = false

            override suspend fun seekToStart(): Boolean = false

            override fun disconnect() = Unit
        }
    }
}

/**
 * How a connect attempt ended.
 *
 * Three of the four are terminal in different ways and the Sock says something different about
 * each: [NotInstalled] and [NotPremium] make it `Unavailable`, while [Refused] is the ordinary
 * overnight case that must **not** count as degradation — reconnect on demand, at most one
 * retry per invocation (`spotify.specs.md` §10).
 */
sealed interface Connection {
    data object Connected : Connection

    data object NotInstalled : Connection

    /** App Remote playback control is Premium-only, and it says so by refusing authorization. */
    data object NotPremium : Connection

    /** Everything else: not logged in, offline mode, the service was not there. */
    data class Refused(val reason: String?) : Connection
}

/**
 * What is playing, flattened.
 *
 * Deliberately not Spotify's own `PlayerState`: the Sock module must not see an SDK type, and
 * the dashboard wants something it can draw without unpacking three nested objects.
 *
 * [positionMs] is a **snapshot**, not a ticker — the App Remote pushes a new state on a change,
 * not once a second. The card interpolates from here plus elapsed wall time while `!isPaused`
 * (`spotify.specs.md` §7); nothing polls for it.
 */
data class PlayerSnapshot(
    val uri: String,
    val title: String,
    val artist: String,
    val isPaused: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    /** Spotify's opaque image id for the current track, or null. Not a URL. */
    val artUri: String?,
)
