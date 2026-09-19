package io.dobby.socks.calculator

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/**
 * The last result, and how long it stays the last result.
 *
 * "Und jetzt mal zwei" needs a number to continue from, and the number has to come from
 * somewhere that outlives the turn — the whole point is that it survives the pause where
 * somebody reads the next figure off a recipe. So this is deliberately *not* the follow-up
 * mechanism, which dies with the turn by design.
 *
 * It does die eventually, and that is the part worth arguing for. A result kept forever turns
 * a bare "mal zwei" into a sentence whose meaning depends on something said an hour ago and
 * since forgotten by everyone in the room — the panel would answer confidently and be the only
 * one who knows what it multiplied. After [ttl] the Sock asks instead ("Mal 2 — von welcher
 * Zahl?"), which costs one short sentence and can never be wrong.
 *
 * @param clock injected so the expiry is testable without waiting for it.
 */
class ResultMemory(private val clock: Clock, private val ttl: Duration) {

    private data class Remembered(val value: Double, val at: Instant)

    /**
     * Atomic because [remember] and [recall] are reached from `handle()` and the dashboard
     * state read, and a memory that is wrong under a race is a very quiet bug — the same
     * reasoning `ClockSock` applies to its half-built timers.
     */
    private val slot = AtomicReference<Remembered?>(null)

    fun remember(value: Double) {
        slot.set(Remembered(value, clock.instant()))
    }

    /** The last result, or null if there is none or it has gone stale. */
    fun recall(): Double? {
        val held = slot.get() ?: return null
        if (Duration.between(held.at, clock.instant()) > ttl) {
            // Dropped on read rather than on a timer: there is nothing to schedule for, and a
            // coroutine that exists only to null a field is a lifecycle to get wrong.
            slot.compareAndSet(held, null)
            return null
        }
        return held.value
    }

    /** @return true if there was something to forget. */
    fun forget(): Boolean = slot.getAndSet(null) != null

    companion object {
        /**
         * Long enough to cover reading the next line of a recipe, short enough that nobody
         * comes back to a number they have stopped thinking about.
         */
        val DEFAULT_TTL: Duration = Duration.ofMinutes(10)

        /** `calculator.memory_minutes` — see §9 of the spec. */
        const val CONFIG_KEY: String = "calculator.memory_minutes"

        fun ttlFrom(minutes: Int?): Duration =
            minutes?.takeIf { it > 0 }?.let { Duration.ofMinutes(it.toLong()) } ?: DEFAULT_TTL
    }
}
