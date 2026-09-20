package io.dobby.socks.clock

import io.dobby.core.nlu.Fillers
import io.dobby.core.nlu.template.Levenshtein
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
 * One timer, as the dashboard needs to draw it and as the Sock needs to speak about it.
 *
 * [isRinging] is the single fact the `shared.stop` chain reads: a merely counting-down timer
 * must never consume a bare "stopp" (`clock.specs.md` §7).
 *
 * ### How a timer gets its name (§3)
 *
 * Either the user gave it one ("Timer Nudeln auf 10 Minuten") or it has none and is identified
 * by its [ordinal] instead. The sole default timer is simply "Timer", the way it always was;
 * [numbered] flips the moment a second timer exists and it becomes "Timer 1", with the new one
 * "Timer 2". That rename is the point — "Timer" only reads as a name while there is nothing to
 * tell it apart from.
 *
 * @param id unique and never reused. Identity, so a countdown coroutine can ask whether the
 *   timer it was started for is still the timer that is running.
 * @param name what the user called it, capitalized for speech; null for a default timer.
 * @param ordinal 1-based, the lowest number free among the default timers when this one was
 *   created. 0 for a named timer, which does not carry a number.
 * @param numbered whether [ordinal] is spoken. Owned by [TimerEngine], which recomputes it for
 *   every timer whenever the list changes.
 */
data class TimerState(
    val id: Long,
    val endsAt: Instant,
    val totalMs: Long,
    val remainingMs: Long,
    val isRinging: Boolean = false,
    val name: String? = null,
    val ordinal: Int = 1,
    val numbered: Boolean = false,
) {
    /** 0f at the start, 1f when it fires — the dashboard's progress ring. */
    val progress: Float
        get() = if (totalMs <= 0) 1f else ((totalMs - remainingMs).toFloat() / totalMs).coerceIn(0f, 1f)

    /** What the panel prints next to the countdown: "Nudeln", "Timer", "Timer 2". */
    val label: String
        get() = name ?: if (numbered) "Timer $ordinal" else DEFAULT_LABEL

    /**
     * What Dobby calls it out loud: "Timer Nudeln", "Timer", "Timer 2".
     *
     * A user name is spoken with "Timer" in front of it, because "Nudeln abgebrochen" is a
     * sentence about noodles and "Timer Nudeln abgebrochen" is a sentence about a timer.
     */
    val spoken: String
        get() = if (name != null) "$DEFAULT_LABEL $name" else label

    /** [spoken] as the subject of a sentence: "Der Timer ist abgelaufen", "Timer 2 ist abgelaufen". */
    val subject: String
        get() = if (spoken == DEFAULT_LABEL) "Der $DEFAULT_LABEL" else spoken

    /**
     * The words that address this timer: its name, or its number.
     *
     * Lowercase, because they are compared against normalized speech. A default timer answers to
     * its ordinal alone — "brich Timer 2 ab" arrives here as the name "2", the leading "timer"
     * having been stripped by [TimerNames.clean].
     */
    internal val keys: List<String>
        get() = if (name != null) listOf(name.lowercase()) else listOf(ordinal.toString())

    private companion object {
        const val DEFAULT_LABEL = "Timer"
    }
}

/**
 * Turning what was said into the timer that was meant.
 *
 * A `{name}` slot is free text, which is the one slot kind the matcher cannot constrain: it
 * takes whatever tokens sit between the keywords around it. That is fine for "Timer Nudeln auf
 * 10 Minuten" and it is also what makes "stopp den Timer bitte" arrive here as the name
 * "bitte". So the name is cleaned before anything looks it up, and **a name made of nothing but
 * filler is not a name** — it is somebody being polite about the one timer that is running.
 *
 * This is deliberately not solved in the templates. Enumerating the particles a name must not be
 * is the cross-product the filler list exists to avoid (`socks.specs/README.md` §6), and the
 * matcher cannot express "any token except these" in a slot at all.
 */
internal object TimerNames {

    /** A captured `{name}`, reduced to the words that carry it, or null if none are left. */
    fun clean(raw: String?): String? {
        if (raw == null) return null
        val words = raw.split(' ')
            .filter { it.isNotBlank() && it !in Fillers.DE }
            .dropWhile { it in SCAFFOLD }
        return words.takeIf { it.isNotEmpty() }?.joinToString(" ")
    }

    /**
     * Words that can only be holding the name up, never be it.
     *
     * "Stell einen Timer für die Nudeln auf 10 Minuten" hands the slot "für die nudeln", because
     * the template's anchor is the *second* preposition. Dropping these in front makes that
     * sentence name its timer "Nudeln", which is what the person said; leaving them in would
     * name it "Für Die Nudeln", which nobody could then cancel by name.
     *
     * Leading only. A name is allowed to contain any of them further in — the capture ends at
     * the anchor keyword, so there is nothing to over-trim.
     */
    private val SCAFFOLD = setOf("timer", "wecker", "für", "auf", "über", "von", "vom", "zum", "zur", "bis")

    /** "grüner tee" → "Grüner Tee". Speech and the panel both want it capitalized. */
    fun display(name: String): String =
        name.split(' ').joinToString(" ") { word -> word.replaceFirstChar { it.titlecase() } }

    /**
     * The one timer [name] addresses, or null if it addresses none or several.
     *
     * Exact first, then fuzzy — the recogniser turns "Nudeln" into "Nudel" often enough that a
     * name it garbled by one letter should still reach its timer. `singleOrNull` is the guard:
     * two timers within tolerance of the same word is an ambiguity, and cancelling the wrong
     * timer is not recoverable by saying it again.
     */
    fun find(timers: List<TimerState>, name: String): TimerState? {
        val exact = timers.filter { timer -> timer.keys.any { it == name } }
        if (exact.isNotEmpty()) return exact.singleOrNull()
        return timers.singleOrNull { timer ->
            timer.keys.any { Levenshtein.atMost(name, it, Levenshtein.tolerance(it.length)) }
        }
    }
}
