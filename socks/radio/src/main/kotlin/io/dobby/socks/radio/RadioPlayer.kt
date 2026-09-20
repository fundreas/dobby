package io.dobby.socks.radio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Everything the Sock does to a stream.
 *
 * `TimerAlarm` and `SpotifyPlayer` are the precedent and the argument: the Sock sees this, the
 * device sees Media3, and the station table, the resolver, the German copy and the whole
 * command surface stay reachable from a terminal with no device and no network. That is
 * roughly two thirds of this Sock, which is why the seam is drawn before a line of ExoPlayer
 * is written.
 *
 * No Android type crosses this line, which is what keeps `:socks:radio` a plain JVM module.
 */
interface RadioPlayer {

    /**
     * What the player is doing, as it last reported it.
     *
     * A cached read, never a call into the player — `activityFor` reads the Sock's own state
     * and is a pure state read under the contract (`socks.specs/README.md` §4).
     */
    val state: StateFlow<PlaybackState>

    /**
     * The stream's own ICY `StreamTitle`, raw and uncleaned.
     *
     * Null until one arrives, and many arrive a few seconds late. [NowPlaying] is what turns it
     * into something worth putting on a wall.
     */
    val streamTitle: StateFlow<String?>

    /**
     * Points the player at [url] and starts it. Returns when playback has actually begun.
     *
     * False means it did not start within [timeoutMs] — a 404, a refused connection, or a
     * buffer that never filled. The Sock's German copy is the same for all three
     * (`radio.specs.md` §3), so the distinction is not carried across this line.
     *
     * Returning a boolean *after* playback begins is the important shape: it collapses the
     * spec's ten-second buffering timeout into the call, so the Sock never has to poll a state
     * flow to find out whether the thing it asked for happened.
     */
    suspend fun play(url: String, timeoutMs: Long): Boolean

    /** Stops and releases. Idempotent, and safe before any [play]. */
    suspend fun release()

    companion object {
        /**
         * Off-device: nothing plays, nothing fails, the palette is still complete.
         *
         * The terminal harness runs on this. Every template, the resolver and the whole chain
         * are exercisable against it; only the sound is missing.
         */
        val NONE: RadioPlayer = object : RadioPlayer {
            override val state: StateFlow<PlaybackState> =
                MutableStateFlow(PlaybackState.STOPPED).asStateFlow()

            override val streamTitle: StateFlow<String?> =
                MutableStateFlow<String?>(null).asStateFlow()

            override suspend fun play(url: String, timeoutMs: Long): Boolean = false

            override suspend fun release() = Unit
        }
    }
}

/** The four things a stream can be doing, as far as anything outside the player cares. */
enum class PlaybackState { STOPPED, BUFFERING, PLAYING, FAILED }
