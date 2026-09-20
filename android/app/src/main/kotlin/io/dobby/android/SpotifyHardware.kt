package io.dobby.android

import android.content.Context
import android.graphics.Bitmap
import io.dobby.socks.spotify.IntentFallback
import io.dobby.socks.spotify.MusicSearch
import io.dobby.socks.spotify.SpotifyCredentials
import io.dobby.socks.spotify.SpotifyPlayer
import io.dobby.socks.spotify.WebMusicSearch
import io.dobby.spotify.AppRemotePlayer
import io.dobby.spotify.SpotifyIntents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The device half of the Spotify Sock, and the only place the App Remote is constructed.
 *
 * Exactly parallel to [ClockHardware]: created by [DobbyService], released in `onDestroy`, and
 * invisible to the Sock, which sees [SpotifyPlayer] and [MusicSearch] and nothing else.
 *
 * The credentials come from `BuildConfig`, which is read **here and nowhere else** — the JVM
 * module takes them as a constructor parameter, which is what makes the search testable with a
 * fake and what keeps `local.properties` out of every other file.
 */
class SpotifyHardware(context: Context, scope: CoroutineScope) {

    val credentials: SpotifyCredentials = SpotifyCredentials(
        clientId = BuildConfig.SPOTIFY_CLIENT_ID,
        clientSecret = BuildConfig.SPOTIFY_CLIENT_SECRET,
        redirectUri = BuildConfig.SPOTIFY_REDIRECT_URI,
    )

    private val appRemote = AppRemotePlayer(context, credentials, scope)

    val player: SpotifyPlayer = appRemote

    val search: MusicSearch = WebMusicSearch(credentials)

    val fallback: IntentFallback = SpotifyIntents(context)

    /**
     * The current cover, straight off the App Remote's `ImagesApi`.
     *
     * Not routed through the Sock: it is a `Bitmap`, and `:socks:spotify` is a plain JVM module
     * that must not see an Android type (`spotify.specs.md` §7). The dashboard combines it with
     * the Sock's own snapshot, which is the layer where both are already in scope.
     */
    val artwork: StateFlow<Bitmap?> get() = appRemote.artwork

    /** Called by the service that created this, never by the Sock. */
    fun release() {
        appRemote.disconnect()
    }

    companion object {
        /** Nothing to draw and nothing to connect with. The shape a null hardware has. */
        val NO_ARTWORK: StateFlow<Bitmap?> = MutableStateFlow<Bitmap?>(null).asStateFlow()
    }
}
