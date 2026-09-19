package io.dobby.pipeline

/**
 * How the panel says "I am listening" the moment the wake word lands.
 *
 * The acknowledgement is not decoration. Between the wake word firing and the panel having
 * anything to show there is a second of silence in which the only honest question is "did it
 * hear me?", and a panel that answers it late is a panel people talk over. What is in dispute
 * is only *how* it answers, and that is not a question code can settle: a kitchen at midday
 * wants a sound, a bedroom at half past five in the morning is exactly where a sound is the
 * reason a thing gets unplugged, and a panel beside a sleeping baby wants neither. So it is a
 * setting, with three honest answers and no clever default.
 */
enum class ListenCue {
    /** One firm pulse of the vibrator. Silent to the room, unmistakable to the wall. */
    VIBRATE,

    /** A short pip. The only one of the three that carries across a room. */
    TONE,

    /** Nothing at all. The screen coming on is the acknowledgement. */
    NONE,

    ;

    companion object {
        /** Silent, and felt through the bracket — the least imposing cue that still answers. */
        val DEFAULT: ListenCue = VIBRATE

        /** Parses a stored name, falling back to [DEFAULT] for anything unrecognised. */
        fun of(name: String?): ListenCue = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
