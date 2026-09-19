package io.dobby.socks.clock

import java.time.Instant

/**
 * The unit a spoken duration comes in.
 *
 * The enum values are the words the user says, because they are also the `{unit:enum}`
 * candidate set: a slot the user speaks must be directly capturable by a template, which is why
 * the command takes `amount` + `unit` rather than a single `duration_s` nobody says out loud
 * (`clock.specs.md` §2).
 */
enum class TimerUnit(val spoken: String, val singular: String, val plural: String, val seconds: Long) {
    SEKUNDEN("sekunden", "Sekunde", "Sekunden", 1),
    MINUTEN("minuten", "Minute", "Minuten", 60),
    STUNDEN("stunden", "Stunde", "Stunden", 3600),
    ;

    companion object {
        /** The `ParamType.Enumeration` values. Singular forms are folded in by the enum matcher. */
        val SPOKEN: List<String> = entries.map { it.spoken }

        fun of(value: String): TimerUnit? = entries.firstOrNull { it.spoken.equals(value, ignoreCase = true) }
    }
}

/**
 * One timer, as the dashboard needs to draw it.
 *
 * [isRinging] is the single fact the `shared.stop` chain reads: a merely counting-down timer
 * must never consume a bare "stopp" (`clock.specs.md` §5).
 */
data class TimerState(
    val endsAt: Instant,
    val totalMs: Long,
    val remainingMs: Long,
    val isRinging: Boolean = false,
) {
    /** 0f at the start, 1f when it fires — the dashboard's progress ring. */
    val progress: Float
        get() = if (totalMs <= 0) 1f else ((totalMs - remainingMs).toFloat() / totalMs).coerceIn(0f, 1f)
}
