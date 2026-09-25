package io.dobby.core.audio

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Attenuates whatever is playing for the length of one turn.
 *
 * Deliberately not on `PlaybackCoordinator`: that one arbitrates *between Socks*, is keyed by
 * sock id and holds exactly one request. This is the panel interrupting all of them at once,
 * for as long as somebody is talking to it, and it has to leave the Sock-level arbitration
 * exactly as it found it. A turn duck routed through the coordinator would abandon the Radio
 * Sock's focus and never give it back.
 *
 * Why it exists at all: the microphone hears the panel's own speaker. Say the wake phrase over
 * music and Silero VAD, watching that stream, never finds its ~800 ms of trailing silence, so
 * every turn runs to the ten-second hard cap and Parakeet is handed ten seconds of music with a
 * sentence somewhere inside. Ducking is what makes the endpoint reachable again — it does not
 * help the wake word, which has to be heard *before* there is a turn to duck (`m2b-plan.md`).
 *
 * [duck] and [release] are idempotent: a turn takes one duck however many utterances it holds,
 * and releases it once however it ended.
 *
 * **[speaking] is the second, smaller duck, and it is about the ear rather than the
 * microphone.** The turn duck exists so the recogniser can find the end of a sentence, and
 * [TurnDuck] can switch it off — but a spoken answer nobody can hear over the music is not an
 * answer, whatever the setting says. So every sentence Dobby speaks is wrapped in one, and it
 * applies even where the turn duck did not: under [TurnDuck.DUCK_UNLESS_BLUETOOTH], and
 * outside a turn altogether, which is where a timer announces itself. Unlike the turn duck it
 * is taken late — when the first sample goes out, not when the sentence is handed to the voice
 * — because synthesising it takes a second the music would otherwise spend down for nothing.
 */
interface TurnAudio {
    suspend fun duck()

    suspend fun release()

    /**
     * Turns the music down for one spoken sentence, whatever the turn did.
     *
     * Nests inside a turn duck and costs nothing there — the music is already down, and a
     * second attenuation on top of the first would only make the answer quieter than the
     * panel intended. Outside one it is the whole duck.
     *
     * **The duck is taken by the sentence, not by the decision to say it.** [block] is handed
     * an `audible` signal and is expected to call it at the moment the first sample actually
     * goes out — which on this device is a second or more after the answer appeared in the
     * chat, because Piper has to synthesise it first. Ducking at the top of the block instead
     * would spend that whole second with the music down and nothing over it, which reads as
     * the panel having dropped the music rather than as it making room to speak.
     *
     * A block that never signals never ducks, which is the right answer for a sentence that
     * turned out not to be spoken at all — a voice with no graph, a `say` of blank text.
     */
    suspend fun <T> speaking(block: suspend (audible: suspend () -> Unit) -> T): T {
        beginSpeech()
        try {
            return block { speechAudible() }
        } finally {
            // NonCancellable, for the reason the turn's own release is: a turn aborted while
            // Dobby is mid-sentence would otherwise skip this and leave the music quiet for
            // good — and unlike the turn duck, nothing further up is holding a second
            // reference that would put it back.
            withContext(NonCancellable) { endSpeech() }
        }
    }

    /**
     * A sentence is in flight but not yet audible. Takes no duck — it only says that one is
     * coming, so that a turn ending mid-synthesis knows not to treat the music as settled.
     *
     * Counted, not flagged, by implementations: [speaking] can nest, and the inner sentence
     * finishing must not restore the music under the outer one.
     *
     * Defaulted to nothing so that [NONE] and every test double stays what it is — an object
     * with no audio to duck has no speech to duck either.
     */
    suspend fun beginSpeech() = Unit

    /**
     * The first sample of a spoken sentence is on its way to the speaker: duck now.
     *
     * Idempotent, and called once per sentence at most. Sentences after the first in one
     * answer find the music already down and change nothing.
     */
    suspend fun speechAudible() = Unit

    suspend fun endSpeech() = Unit

    companion object {
        /**
         * Does nothing, for every caller with no audio to duck: the terminal harness, the unit
         * tests, and a panel whose platform half has not been built yet.
         */
        val NONE: TurnAudio = object : TurnAudio {
            override suspend fun duck() = Unit

            override suspend fun release() = Unit
        }
    }
}

/**
 * Duck the music, or stop it, for the length of a turn.
 *
 * `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` leaves the music quietly playing;
 * `AUDIOFOCUS_GAIN_TRANSIENT` pauses it. Pausing is better for recognition and worse to live
 * with — it is heavier, and for a stream it means a re-buffer on every turn. Ducking is gentler
 * and may not be enough.
 *
 * Which of the two is right is a measurement, not an argument: the default stands at [DUCK]
 * until the 2×2 on the device (`m2b-plan.md` B3) says otherwise.
 *
 * [DUCK_UNLESS_BLUETOOTH] is the third answer, and the only one that is about the room rather
 * than about the recogniser: a duck is only worth its cost while the microphone can hear the
 * music, and a speaker in another room it cannot.
 */
enum class TurnDuck {
    /** The music keeps playing, quietly, under the person talking. */
    DUCK,

    /** The music stops for the turn and resumes after it. */
    PAUSE,

    /**
     * [DUCK], except while the music is going out over Bluetooth, when nothing happens at all.
     *
     * The reason to duck is that the microphone hears the panel's own speaker. Send the music
     * to a Bluetooth speaker in another room and that stops being true: the panel is quiet, the
     * turn endpoints fine, and ducking only costs the people in the *other* room their music
     * every time somebody in this one says the wake phrase.
     *
     * Deliberately conditional rather than a plain "never": the speaker is a thing that comes
     * and goes, and the turn that happens after it disconnects is one where the phone is
     * playing out of its own speaker at the microphone again. So this falls back to [DUCK], and
     * what it really means is "duck when it would help".
     *
     * Dobby's own answers duck regardless — see [TurnAudio.speaking]. This setting is about
     * the microphone's problem, and being unable to hear the answer is the ear's.
     *
     * Whether the music is on Bluetooth is a platform question, so the platform half answers
     * it; core only carries the choice.
     */
    DUCK_UNLESS_BLUETOOTH,

    ;

    companion object {
        val DEFAULT: TurnDuck = DUCK

        /** Parses a stored name, falling back to [DEFAULT] for anything unrecognised. */
        fun of(name: String?): TurnDuck = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/**
 * A player inside this process, which a turn duck attenuates directly.
 *
 * Audio focus is the wrong tool for ducking yourself — requesting focus while our own app
 * already holds it has in-app listener semantics that are not worth relying on — so the
 * turn-level duck has two channels: focus for everything out of process (Spotify, and anything
 * else on the device) and this one for what Dobby plays itself.
 *
 * Radio (M4) is the first and so far only implementation: it owns an in-process Media3
 * ExoPlayer, so Dobby owns both the samples and the volume. Spotify's audio never passes
 * through this process at all — `spotify-app-remote` means the Spotify app decodes and plays,
 * which is why the out-of-process channel can never be dropped.
 */
interface DuckablePlayer {
    /** Attenuate to [volume], a linear 0..1 factor of whatever the player was set to. */
    fun duck(volume: Float)

    /** Back to full. Called once per [duck], and safe to call without one. */
    fun restore()
}

/**
 * The in-process half of a turn duck: every player Dobby itself is playing through.
 *
 * A registry rather than a single slot because the panel may one day play two things — a stream
 * and a chime — and because a Sock that forgets to unregister must not be able to leave a dead
 * player holding the duck. Registration is the Sock's own lifecycle: register when the player
 * is created, unregister when it is released.
 */
class InProcessPlayers {
    private val players = CopyOnWriteArrayList<DuckablePlayer>()

    /** What is registered right now. For assertions and for a debug readout. */
    val size: Int get() = players.size

    @Volatile
    private var ducked = false

    fun register(player: DuckablePlayer) {
        players += player
        // A player that starts mid-turn starts quiet: the alternative is music swelling up
        // under somebody who is still talking, which is the failure this whole thing is about.
        if (ducked) player.duck(DUCK_VOLUME)
    }

    fun unregister(player: DuckablePlayer) {
        players -= player
    }

    /**
     * Attenuates every registered player to [volume].
     *
     * One broken player must not cost the rest their duck — the same rule the frame router
     * applies to a broken sink — so failures are collected and handed to [onError].
     */
    fun duck(volume: Float = DUCK_VOLUME, onError: (Throwable) -> Unit = {}) {
        ducked = true
        for (player in players) {
            try {
                player.duck(volume)
            } catch (e: RuntimeException) {
                onError(e)
            }
        }
    }

    fun restore(onError: (Throwable) -> Unit = {}) {
        ducked = false
        for (player in players) {
            try {
                player.restore()
            } catch (e: RuntimeException) {
                onError(e)
            }
        }
    }

    companion object {
        /**
         * Roughly what `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` does to everybody else.
         *
         * Android's own ducking is about 20 % of the original volume; matching it is what keeps
         * "Dobby ducked the radio" and "Dobby ducked Spotify" from sounding like two different
         * products.
         */
        const val DUCK_VOLUME: Float = 0.2f

        /**
         * [TurnDuck.PAUSE]'s in-process meaning: silence, not a stopped player.
         *
         * Pausing a stream costs a re-buffer on every turn (`radio.specs.md` §9 already budgets
         * reconnects), and a turn is seconds. Muting gives the recogniser the same silence at
         * the cost of a few seconds of discarded stream, and the music is back the instant the
         * turn ends instead of after a reconnect.
         */
        const val MUTE_VOLUME: Float = 0f
    }
}
