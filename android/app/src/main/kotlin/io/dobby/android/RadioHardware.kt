package io.dobby.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.extractor.metadata.icy.IcyInfo
import io.dobby.core.audio.DuckablePlayer
import io.dobby.core.audio.InProcessPlayers
import io.dobby.socks.radio.PlaybackState
import io.dobby.socks.radio.RadioPlayer
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The device half of the Radio Sock, and the only place Media3 is constructed.
 *
 * Exactly parallel to [ClockHardware] and [SpotifyHardware]: created by [DobbyService],
 * released in `onDestroy`, and invisible to the Sock — which sees [RadioPlayer] and nothing
 * else.
 *
 * @param players the turn-duck registry. **Must be [AndroidTurnAudio]'s own instance.** A
 *   second `InProcessPlayers` compiles, runs, and ducks nothing, which is the single easiest
 *   thing in M4 to get subtly wrong (`radio-plan.md` §D5).
 */
class RadioHardware(context: Context, players: InProcessPlayers) {

    private val exo = ExoRadioPlayer(context.applicationContext, players)

    val player: RadioPlayer = exo

    /** Called by the service that created this, never by the Sock. */
    fun release() = exo.releaseNow()
}

/**
 * An ExoPlayer behind [RadioPlayer], plus the volume knob a turn duck turns.
 *
 * Two things here are load-bearing and neither is obvious.
 *
 * **`handleAudioFocus = false`.** Passing `true` makes ExoPlayer request and manage audio focus
 * on its own. It would then hold a *third* focus request from this UID, alongside
 * `AndroidPlayback`'s and [AndroidTurnAudio]'s, and it would react to focus changes by ducking
 * or pausing itself — fighting [InProcessPlayers] for the volume during every single turn and
 * second-guessing `PlaybackCoordinator` for the channel. Focus is core's business and the
 * volume is the turn's. The player's job is to decode.
 *
 * **The player is created lazily and destroyed on release**, not merely stopped. The spec is
 * emphatic (`radio.specs.md` §3): a live HTTP stream left open is both battery and bandwidth,
 * and an idle ExoPlayer still holds buffers and a renderer. Registration with [InProcessPlayers]
 * follows that same lifetime exactly — a Sock that forgets to unregister leaves a dead player
 * holding a duck.
 */
@OptIn(UnstableApi::class)
private class ExoRadioPlayer(
    private val context: Context,
    private val players: InProcessPlayers,
) : RadioPlayer, DuckablePlayer {

    /** The player is built on the main looper, so this is the one it wants. */
    private val handler = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow(PlaybackState.STOPPED)

    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val _streamTitle = MutableStateFlow<String?>(null)

    override val streamTitle: StateFlow<String?> = _streamTitle.asStateFlow()

    /** Null until the first [play]. Read and written on the main looper only. */
    private var exo: ExoPlayer? = null

    /** Whoever [play] has suspended waiting for sound. Main looper only. */
    private var waiter: CancellableContinuation<Boolean>? = null

    /**
     * What the turn duck last asked for.
     *
     * Kept rather than read back off the player, so a player built *during* a turn comes up at
     * the ducked volume instead of swelling under somebody who is still talking.
     */
    @Volatile
    private var volume = FULL_VOLUME

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) return
            _state.value = PlaybackState.PLAYING
            finish(true)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            // For the card, not for the waiter: only actual sound resolves [play].
            if (playbackState == Player.STATE_BUFFERING && _state.value != PlaybackState.FAILED) {
                _state.value = PlaybackState.BUFFERING
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            _state.value = PlaybackState.FAILED
            finish(false)
        }

        /**
         * ICY, which works out of the box on a progressive MP3 stream.
         *
         * Media3 sends `Icy-MetaData: 1`, parses the interleaved blocks and surfaces them here.
         * `IcyHeaders` arrives once at the start and carries `name`/`genre`, which nothing uses
         * — the station table's `displayName` is better copy than `icy-name: digital`.
         */
        override fun onMetadata(metadata: Metadata) {
            for (index in 0 until metadata.length()) {
                val entry = metadata.get(index)
                if (entry is IcyInfo) entry.title?.let { _streamTitle.value = it }
            }
        }
    }

    override suspend fun play(url: String, timeoutMs: Long): Boolean = withContext(Dispatchers.Main) {
        val player = ensurePlayer()
        // The old station's title must not linger under the new station's name.
        _streamTitle.value = null
        _state.value = PlaybackState.BUFFERING
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
        player.play()
        val started = withTimeoutOrNull(timeoutMs) { awaitPlaying() } ?: false
        if (!started) {
            player.stop()
            _state.value = PlaybackState.FAILED
        }
        started
    }

    override suspend fun release() = withContext(Dispatchers.Main) { releaseNow() }

    /**
     * The non-suspending release, for the service's `onDestroy`.
     *
     * Marshals to the main looper only when it is not already on it, because teardown runs on
     * the main thread and a posted release would be a race with `scope.cancel()`.
     */
    fun releaseNow() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { releaseNow() }
            return
        }
        finish(false)
        players.unregister(this)
        exo?.let {
            it.removeListener(listener)
            it.release()
        }
        exo = null
        _state.value = PlaybackState.STOPPED
        _streamTitle.value = null
    }

    /**
     * The turn duck, which **posts and returns**.
     *
     * [DuckablePlayer.duck] is not suspend and never will be: [InProcessPlayers.duck] is a
     * plain synchronous loop called at the start of every turn, and it can neither suspend nor
     * block. Fire-and-forget is correct here rather than a shortcut — a duck that lands a frame
     * late is inaudible, and ExoPlayer throws if touched off its own looper.
     */
    override fun duck(volume: Float) {
        this.volume = volume
        handler.post { exo?.volume = volume }
    }

    override fun restore() {
        volume = FULL_VOLUME
        handler.post { exo?.volume = FULL_VOLUME }
    }

    private fun ensurePlayer(): ExoPlayer = exo ?: build().also { exo = it }

    private fun build(): ExoPlayer {
        val player = ExoPlayer.Builder(context)
            .setLooper(Looper.getMainLooper())
            .setLoadControl(liveLoadControl())
            .build()
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            // See the class KDoc. This `false` is not optional.
            /* handleAudioFocus = */
            false,
        )
        // Keeps the CPU and the Wi-Fi radio alive with the screen off, which is most of the
        // time on a wall panel. WAKE_LOCK is already declared for the service's own lock.
        player.setWakeMode(C.WAKE_MODE_NETWORK)
        player.volume = volume
        player.addListener(listener)
        // Registration is the player's lifecycle and nothing else's (`radio.specs.md` §6).
        players.register(this)
        return player
    }

    private fun liveLoadControl() = DefaultLoadControl.Builder()
        // A live stream has no seek and no lookahead worth the name; what it has is a person
        // waiting for sound after they said "radio fm4". Start on 1.5 s rather than the 2.5 s
        // default, and keep the steady-state buffer generous so a Wi-Fi hiccup is inaudible.
        .setBufferDurationsMs(MIN_BUFFER_MS, MAX_BUFFER_MS, PLAYBACK_BUFFER_MS, REBUFFER_MS)
        .build()

    private suspend fun awaitPlaying(): Boolean = suspendCancellableCoroutine { continuation ->
        if (_state.value == PlaybackState.PLAYING) {
            continuation.resumeWith(Result.success(true))
            return@suspendCancellableCoroutine
        }
        waiter = continuation
        continuation.invokeOnCancellation { waiter = null }
    }

    /** Resumes [play]'s waiter at most once, whichever of sound, error or timeout got there. */
    private fun finish(started: Boolean) {
        val pending = waiter ?: return
        waiter = null
        if (pending.isActive) pending.resumeWith(Result.success(started))
    }

    private companion object {
        const val FULL_VOLUME = 1f

        const val MIN_BUFFER_MS = 15_000
        const val MAX_BUFFER_MS = 50_000
        const val PLAYBACK_BUFFER_MS = 1_500
        const val REBUFFER_MS = 3_000
    }
}
