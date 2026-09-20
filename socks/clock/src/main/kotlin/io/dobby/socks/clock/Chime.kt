package io.dobby.socks.clock

/** The sounds `clock.chime_sound` can select (`clock.specs.md` §11). */
enum class ChimeSound(val configValue: String) {
    GLOCKE("glocke"),
    PIEP("piep"),
    GONG("gong"),
    ;

    companion object {
        val DEFAULT: ChimeSound = GLOCKE

        fun of(value: String?): ChimeSound =
            entries.firstOrNull { it.configValue.equals(value, ignoreCase = true) } ?: DEFAULT
    }
}

/**
 * Plays the expiry chime.
 *
 * An interface, and not `SoundPool`, for the reason the whole `SockContext` exists: the timer
 * lifecycle is tested on a plain JVM with a fake, and the Android implementation is a Phase B
 * detail the Sock never sees.
 */
fun interface ChimePlayer {
    /** One stroke of the chime. Called repeatedly while the timer rings. */
    fun play(sound: ChimeSound)

    /** Cuts a sound that is still sounding. Called when the chime is silenced. */
    fun stop() = Unit

    /** Frees the underlying player. Called by whoever created it, not by the Sock. */
    fun release() = Unit

    companion object {
        /** No sound at all — the terminal harness and any test that does not assert on audio. */
        val SILENT: ChimePlayer = ChimePlayer { }
    }
}
