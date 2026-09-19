package io.dobby.android

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log
import io.dobby.core.audio.InProcessPlayers
import io.dobby.core.audio.TurnAudio
import io.dobby.core.audio.TurnDuck

/**
 * The platform half of [TurnAudio]: quieten the room while somebody is talking to the panel.
 *
 * **Two channels, because the audio has two origins.**
 *
 * Out of process — Spotify, and anything else on the device — is reached through an
 * [AudioFocusRequest] of this object's own. It is emphatically *not* `AndroidPlayback`'s: that
 * one is keyed by sock id, holds exactly one request and abandons whatever it finds before
 * making its own, so borrowing it for a turn would abandon the Radio Sock's focus and never
 * give it back. Two requests from one app are two independent grants as far as the system is
 * concerned, which is what lets the turn interrupt every Sock at once and then leave the
 * Sock-level arbitration exactly as it found it.
 *
 * In process — Radio's ExoPlayer, from M4 — is attenuated directly through [players]. Audio
 * focus is the wrong tool for ducking yourself, and its in-app listener semantics are not worth
 * relying on when setting a volume is both simpler and certain.
 *
 * What this does **not** fix is the wake word, which has to be heard before there is a turn to
 * duck at all. That is Part B's problem and the microphone source's (`m2b-plan.md`).
 */
class AndroidTurnAudio(
    context: Context,
    /**
     * Read at duck time rather than captured, so changing the setting takes effect on the next
     * turn instead of on the next restart.
     */
    private val mode: () -> TurnDuck,
    /** Every player Dobby is playing through itself. Radio registers here in M4. */
    val players: InProcessPlayers = InProcessPlayers(),
) : TurnAudio {

    private val audio = context.applicationContext.getSystemService(AudioManager::class.java)

    /** Non-null exactly while a turn holds the duck. Guarded by the caller's turn mutex. */
    private var request: AudioFocusRequest? = null

    override suspend fun duck() {
        // A turn takes one duck however many utterances it holds. Un-ducking between them would
        // let the music swell back up in the gaps — which is worse than not ducking at all,
        // because it happens exactly while the person is waiting to speak again.
        if (request != null) return

        val duckMode = mode()
        val next = AudioFocusRequest.Builder(
            when (duckMode) {
                TurnDuck.DUCK -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                TurnDuck.PAUSE -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            },
        )
            .setAudioAttributes(
                // What this request actually is: an assistant taking a turn, not the panel
                // playing media. The usage is what other apps' focus listeners see, and
                // describing a turn as music would be a lie that costs somebody a pause.
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .build()

        request = next
        // A refusal is worth a line and nothing else: the panel still listens, it just listens
        // over the top of whatever would not give way.
        if (audio.requestAudioFocus(next) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Log.i(TAG, "turn duck: focus refused, listening over the top")
        }

        players.duck(
            when (duckMode) {
                TurnDuck.DUCK -> InProcessPlayers.DUCK_VOLUME
                TurnDuck.PAUSE -> InProcessPlayers.MUTE_VOLUME
            },
        ) { Log.w(TAG, "turn duck: in-process player refused to duck", it) }
    }

    override suspend fun release() {
        val held = request
        request = null
        // Both halves unconditionally, and the in-process one even if there was no focus: a
        // duck that outlived its turn is a panel that permanently quietened the music, which is
        // a more annoying failure than the one being fixed.
        held?.let { audio.abandonAudioFocusRequest(it) }
        players.restore { Log.w(TAG, "turn duck: in-process player refused to restore", it) }
    }

    private companion object {
        const val TAG = "Dobby"
    }
}
