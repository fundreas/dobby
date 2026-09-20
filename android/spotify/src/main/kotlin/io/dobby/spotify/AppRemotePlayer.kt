package io.dobby.spotify

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.android.appremote.api.error.CouldNotFindSpotifyApp
import com.spotify.android.appremote.api.error.NotLoggedInException
import com.spotify.android.appremote.api.error.UserNotAuthorizedException
import com.spotify.protocol.client.CallResult
import com.spotify.protocol.client.Subscription
import com.spotify.protocol.types.Image
import com.spotify.protocol.types.ListItem
import com.spotify.protocol.types.PlayerState
import io.dobby.socks.spotify.Connection
import io.dobby.socks.spotify.PlayerSnapshot
import io.dobby.socks.spotify.SpotifyCredentials
import io.dobby.socks.spotify.SpotifyPlayer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * The App Remote SDK, behind the one interface the Sock sees.
 *
 * This file is the whole of "every Spotify-specific line of code in the project" that needs a
 * device: a Binder connection into `com.spotify.music`, a callback API wrapped into suspend
 * functions, and a `PlayerState` subscription flattened into a [PlayerSnapshot].
 *
 * Three things it is careful about, each of which is a real failure mode:
 *
 * - **Connection is lazy and single-flight.** Nothing connects at `onStart` (a panel that has
 *   not been asked for music should not hold a Binder connection into another app), and two
 *   music commands arriving together must not open two connections.
 * - **A disconnect is normal.** Long-lived connections die overnight. The Sock reconnects on
 *   demand and does not call it degradation (`spotify.specs.md` §10).
 * - **The subscription's error callback clears the cache.** `activityFor` reads the cached
 *   state, so a silently dead App Remote would otherwise leave the Sock claiming commands it
 *   cannot execute. Clearing it to null turns most of that window into a correct `INACTIVE`.
 */
class AppRemotePlayer(
    context: Context,
    private val credentials: SpotifyCredentials,
    private val scope: CoroutineScope,
) : SpotifyPlayer {

    private val appContext = context.applicationContext

    private val _state = MutableStateFlow<PlayerSnapshot?>(null)

    override val state: StateFlow<PlayerSnapshot?> = _state.asStateFlow()

    private val _artwork = MutableStateFlow<Bitmap?>(null)

    /**
     * The current track's cover, or null while it loads.
     *
     * Over the App Remote's own `ImagesApi` rather than the Web API: it arrives as a `Bitmap`
     * on the connection that is already open, with no URL, no second HTTP client and no token.
     * Exposed here rather than on `SpotifyPlayer` so the JVM module never sees an Android type.
     */
    val artwork: StateFlow<Bitmap?> = _artwork.asStateFlow()

    override val isInstalled: Boolean get() = SpotifyAppRemote.isSpotifyInstalled(appContext)

    /** Single-flight: two music commands in one turn must not open two connections. */
    private val gate = Mutex()

    @Volatile
    private var remote: SpotifyAppRemote? = null

    private var subscription: Subscription<PlayerState>? = null

    /** The track the cover in [artwork] belongs to, so the same image is not fetched twice. */
    private var artworkFor: String? = null

    /**
     * Completed by the first event the subscription delivers after a connect.
     *
     * Without it there is a window with a wrong answer in it: the subscription is asynchronous,
     * so "nächster song" arriving immediately after a cold connect would read an empty cache
     * and be told "Auf Spotify läuft gerade nichts" while the music was playing. A connect that
     * waits for its own first state closes it, and the timeout keeps a silent subscription from
     * turning into a hung command.
     */
    private var firstState = CompletableDeferred<Unit>()

    override suspend fun connect(): Connection = gate.withLock {
        remote?.takeIf { it.isConnected }?.let { return@withLock Connection.Connected }
        if (!credentials.canConnect) return@withLock Connection.Refused("no client id or redirect uri")
        // Binding a service is main-thread work in the SDK, and the consent dialog it may show
        // certainly is.
        val outcome = withContext(Dispatchers.Main) { bind() }
        if (outcome is Connection.Connected) {
            subscribe()
            withTimeoutOrNull(FIRST_STATE_MS) { firstState.await() }
        }
        outcome
    }

    /**
     * `SpotifyAppRemote.connect`, as a suspend function.
     *
     * `showAuthView(true)` is what makes the Spotify app put up a one-time consent dialog on
     * the first connect; after that the connection is established with no UI and there is no
     * token for us to store (`spotify.specs.md` §1).
     */
    private suspend fun bind(): Connection = suspendCancellableCoroutine { continuation ->
        val params = ConnectionParams.Builder(credentials.clientId)
            .setRedirectUri(credentials.redirectUri)
            .showAuthView(true)
            .build()
        SpotifyAppRemote.connect(
            appContext,
            params,
            object : Connector.ConnectionListener {
                override fun onConnected(connected: SpotifyAppRemote) {
                    remote = connected
                    if (continuation.isActive) continuation.resume(Connection.Connected)
                }

                override fun onFailure(error: Throwable) {
                    remote = null
                    _state.value = null
                    Log.w(TAG, "app remote connect failed", error)
                    if (continuation.isActive) continuation.resume(classify(error))
                }
            },
        )
    }

    /**
     * One subscription, two consumers: `activityFor` and the dashboard card.
     *
     * The error callback clearing the flow is the cheap half of the liveness problem the spec
     * leaves open (§12) — it costs nothing and turns a dead connection into an honest
     * `INACTIVE` for everything that arrives after the error.
     */
    private fun subscribe() {
        subscription?.cancel()
        firstState = CompletableDeferred()
        val player = remote?.playerApi ?: return
        val next = player.subscribeToPlayerState()
        next.setEventCallback { playerState -> onPlayerState(playerState) }
        next.setErrorCallback { error ->
            Log.w(TAG, "player state subscription failed", error)
            _state.value = null
            _artwork.value = null
            artworkFor = null
            firstState.complete(Unit)
        }
        subscription = next
    }

    private fun onPlayerState(playerState: PlayerState) {
        val snapshot = playerState.toSnapshot()
        _state.value = snapshot
        firstState.complete(Unit)
        val art = snapshot?.artUri
        if (art == null || snapshot.uri == artworkFor) return
        artworkFor = snapshot.uri
        _artwork.value = null
        scope.launch { _artwork.value = loadArtwork(playerState) }
    }

    private suspend fun loadArtwork(playerState: PlayerState): Bitmap? {
        val images = remote?.imagesApi ?: return null
        val imageUri = playerState.track?.imageUri ?: return null
        return images.getImage(imageUri, Image.Dimension.LARGE).awaitResult()
    }

    override suspend fun play(uri: String): Boolean = call { it.playerApi.play(uri) }

    /**
     * The user's own home feed (`spotify.specs.md` §3).
     *
     * `getRecommendedContentItems` runs inside their Spotify session — recently played, their
     * own playlists, made-for-you — which is content an app token could never reach. This is
     * the empty-query path, and it makes no Web API call at all.
     */
    override suspend fun resumeHome(): String? {
        val connected = remote?.takeIf { it.isConnected } ?: return null
        val feed = connected.contentApi.getRecommendedContentItems(RECOMMENDED_DEFAULT).awaitResult()
            ?: return null
        val playable: ListItem = feed.items?.filterNotNull()?.firstOrNull { it.playable }
            ?: return null
        val started = connected.contentApi.playContentItem(playable).awaitResult() != null
        return if (started) playable.title else null
    }

    override suspend fun pause(): Boolean = call { it.playerApi.pause() }

    override suspend fun resume(): Boolean = call { it.playerApi.resume() }

    override suspend fun skipNext(): Boolean = call { it.playerApi.skipNext() }

    override suspend fun skipPrevious(): Boolean = call { it.playerApi.skipPrevious() }

    /** "Nochmal von vorne" — a seek to zero, not a re-`play(uri)`, which would rebuild the queue. */
    override suspend fun seekToStart(): Boolean = call { it.playerApi.seekTo(0) }

    override fun disconnect() {
        subscription?.cancel()
        subscription = null
        remote?.let { SpotifyAppRemote.disconnect(it) }
        remote = null
        _state.value = null
        _artwork.value = null
        artworkFor = null
    }

    /** Every transport command has this shape: a live connection, one call, did it come back. */
    private suspend fun <T : Any> call(action: (SpotifyAppRemote) -> CallResult<T>): Boolean {
        val connected = remote?.takeIf { it.isConnected } ?: return false
        return action(connected).awaitResult() != null
    }

    /**
     * A `CallResult`, as a suspend function.
     *
     * An error resolves to null rather than throwing: the Sock's German copy is the same for
     * every way a call can fail (`spotify.specs.md` §3), and an exception type crossing this
     * boundary would be an SDK type in a module that must not see one.
     *
     * Not called `await`: `PendingResultBase` already has a **blocking** `await()`, and a
     * member always wins over an extension — so the name that reads best is the one that would
     * have silently parked a coroutine dispatcher thread on a Binder round trip.
     */
    private suspend fun <T : Any> CallResult<T>.awaitResult(): T? = suspendCancellableCoroutine { continuation ->
        setResultCallback { value -> if (continuation.isActive) continuation.resume(value) }
        setErrorCallback { error ->
            Log.w(TAG, "app remote call failed", error)
            if (continuation.isActive) continuation.resume(null)
        }
        continuation.invokeOnCancellation { this@awaitResult.cancel() }
    }

    private companion object {
        const val TAG = "Dobby"

        /**
         * How long a connect waits for the subscription's first event.
         *
         * Long enough for a Binder round trip on a busy phone, short enough that it disappears
         * inside the connect it is part of — and it only ever runs on a *cold* connect, which
         * is once per evening.
         */
        const val FIRST_STATE_MS = 1_000L

        /** `ContentApi.ContentType.DEFAULT` — a bare string in this version of the SDK. */
        const val RECOMMENDED_DEFAULT = "default"

        /**
         * Why the connection was refused, in the three shapes the Sock says something different
         * about.
         *
         * `UserNotAuthorizedException` is what a non-Premium account produces: App Remote
         * playback control is Premium-only, and it declines authorization rather than reporting
         * a subscription level.
         */
        fun classify(error: Throwable): Connection = when (error) {
            is CouldNotFindSpotifyApp -> Connection.NotInstalled
            is UserNotAuthorizedException -> Connection.NotPremium
            is NotLoggedInException -> Connection.Refused("not logged in")
            else -> Connection.Refused(error.javaClass.simpleName)
        }

        /**
         * Spotify's `PlayerState` flattened, or null when nothing is loaded.
         *
         * A state with no track is the App Remote's way of saying the queue is empty, and it
         * has to come back as null: `activityFor` reads this, and "connected but nothing
         * loaded" is `INACTIVE` (§6).
         */
        fun PlayerState.toSnapshot(): PlayerSnapshot? {
            val current = track ?: return null
            return PlayerSnapshot(
                uri = current.uri.orEmpty(),
                title = current.name.orEmpty(),
                artist = current.artist?.name.orEmpty(),
                isPaused = isPaused,
                positionMs = playbackPosition,
                durationMs = current.duration,
                artUri = current.imageUri?.raw,
            )
        }
    }
}
