package io.dobby.android

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
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
 * **And a third answer: nothing.** Under [TurnDuck.DUCK_UNLESS_BLUETOOTH] a turn whose music is
 * routed to a Bluetooth speaker touches neither channel. The duck exists because the microphone
 * hears the panel's own speaker; a speaker in another room it does not, and the duck is then
 * pure cost to whoever is listening over there.
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

        // Nothing at all, on either channel: no focus request, so nobody out of process is asked
        // to give way, and no in-process attenuation either — the radio is coming out of the
        // same distant speaker Spotify is.
        val duckMode = effectiveMode() ?: return

        val next = AudioFocusRequest.Builder(
            when (duckMode) {
                TurnDuck.PAUSE -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                else -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
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
                TurnDuck.PAUSE -> InProcessPlayers.MUTE_VOLUME
                // DUCK, and DUCK_UNLESS_BLUETOOTH once it has resolved to one.
                else -> InProcessPlayers.DUCK_VOLUME
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

    /**
     * What this turn actually does, or null for "leave the room alone".
     *
     * [TurnDuck.DUCK_UNLESS_BLUETOOTH] is resolved here, per turn, rather than remembered:
     * a speaker is connected and disconnected between turns, and the answer that matters is
     * where the music is going *now*.
     */
    private fun effectiveMode(): TurnDuck? = when (val chosen = mode()) {
        TurnDuck.DUCK, TurnDuck.PAUSE -> chosen
        TurnDuck.DUCK_UNLESS_BLUETOOTH -> if (onBluetooth()) null else TurnDuck.DUCK
    }

    /**
     * Whether media is currently *routed* to a Bluetooth speaker — not merely whether one is
     * paired, or even connected.
     *
     * `getAudioDevicesForAttributes` asks the question the setting is actually about: if the
     * music started now, where would it come out? A phone can hold a connected speaker while
     * still playing out of its own, and in that case the microphone hears the music and the
     * duck is worth taking.
     */
    private fun onBluetooth(): Boolean {
        val music = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        // A device list is a platform answer and this runs on every turn: a refusal costs the
        // turn its duck, which is louder than it is broken, so it falls back to ducking.
        return try {
            audio.getAudioDevicesForAttributes(music).any { it.type in BLUETOOTH_OUTPUTS }
        } catch (e: RuntimeException) {
            Log.w(TAG, "turn duck: could not read the media route, ducking anyway", e)
            false
        }
    }

    private companion object {
        const val TAG = "Dobby"

        /**
         * The Bluetooth output types a speaker in another room can arrive as.
         *
         * SCO is deliberately absent: it is the telephony route, it is not where media goes,
         * and a headset on somebody's head is not the "speaker over there" this is about.
         */
        val BLUETOOTH_OUTPUTS = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLE_BROADCAST,
        )
    }
}
