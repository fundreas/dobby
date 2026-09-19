package io.dobby.core.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The in-process half of a turn duck, and the setting that chooses how hard it ducks.
 *
 * The out-of-process half is an `AudioFocusRequest` and lives on the device. This half is a
 * list and some arithmetic, which is exactly the kind of thing that is cheap to get right here
 * and expensive to debug in a room with music playing.
 */
class TurnAudioTest {

    private class FakePlayer : DuckablePlayer {
        val volumes: MutableList<Float> = mutableListOf()
        var restores: Int = 0
            private set

        override fun duck(volume: Float) {
            volumes += volume
        }

        override fun restore() {
            restores++
        }
    }

    @Test
    fun `every registered player ducks and comes back`() {
        val players = InProcessPlayers()
        val radio = FakePlayer()
        val chime = FakePlayer()
        players.register(radio)
        players.register(chime)

        players.duck()
        players.restore()

        assertEquals(listOf(InProcessPlayers.DUCK_VOLUME), radio.volumes)
        assertEquals(listOf(InProcessPlayers.DUCK_VOLUME), chime.volumes)
        assertEquals(1, radio.restores)
        assertEquals(1, chime.restores)
    }

    @Test
    fun `a player that starts mid-turn starts quiet`() {
        // Otherwise a stream that connects while somebody is mid-sentence comes up at full
        // volume under them — which is the failure this whole thing is about, arriving late.
        val players = InProcessPlayers()
        players.duck()

        val late = FakePlayer()
        players.register(late)

        assertEquals(listOf(InProcessPlayers.DUCK_VOLUME), late.volumes)
        players.restore()
        assertEquals(1, late.restores)
    }

    @Test
    fun `an unregistered player is left alone`() {
        // A Sock that released its player must not still be reachable: calling into a released
        // ExoPlayer is an exception on somebody else's thread, at the end of a turn.
        val players = InProcessPlayers()
        val gone = FakePlayer()
        players.register(gone)
        players.unregister(gone)

        players.duck()
        players.restore()

        assertEquals(emptyList(), gone.volumes)
        assertEquals(0, gone.restores)
        assertEquals(0, players.size)
    }

    @Test
    fun `one broken player does not cost the others their duck`() {
        val players = InProcessPlayers()
        val broken = object : DuckablePlayer {
            override fun duck(volume: Float) = throw IllegalStateException("released")

            override fun restore() = throw IllegalStateException("released")
        }
        val working = FakePlayer()
        players.register(broken)
        players.register(working)

        val failures = mutableListOf<Throwable>()
        players.duck(onError = { failures += it })
        players.restore(onError = { failures += it })

        assertEquals(listOf(InProcessPlayers.DUCK_VOLUME), working.volumes)
        assertEquals(1, working.restores)
        assertEquals(2, failures.size, "the failures were swallowed instead of reported")
    }

    @Test
    fun `pausing in process means silence, not a stopped stream`() {
        // Pausing a stream costs a re-buffer on every turn, and a turn is seconds. Muting gives
        // the recogniser the same silence and the music is back the instant the turn ends.
        assertEquals(0f, InProcessPlayers.MUTE_VOLUME)
        assertTrue(InProcessPlayers.DUCK_VOLUME > 0f, "ducking is quieter, not silent")
        assertTrue(InProcessPlayers.DUCK_VOLUME < 1f)
    }

    @Test
    fun `an unreadable setting falls back to ducking rather than throwing`() {
        // A settings file is the one input that can come back from an older version of the app.
        assertEquals(TurnDuck.DUCK, TurnDuck.DEFAULT)
        assertEquals(TurnDuck.DUCK, TurnDuck.of(null))
        assertEquals(TurnDuck.DUCK, TurnDuck.of("STOP_EVERYTHING"))
        assertEquals(TurnDuck.PAUSE, TurnDuck.of("PAUSE"))
    }

    @Test
    fun `the no-op turn audio is safe to call in either order`() = kotlinx.coroutines.test.runTest {
        // Every caller off the device gets this one, including the terminal harness.
        TurnAudio.NONE.release()
        TurnAudio.NONE.duck()
        TurnAudio.NONE.duck()
        TurnAudio.NONE.release()
    }
}
