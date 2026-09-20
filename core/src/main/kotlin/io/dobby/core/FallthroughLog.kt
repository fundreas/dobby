package io.dobby.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The last [CAPACITY] utterances Tier 1 could not match, newest last.
 *
 * This is §5.4's flywheel made readable: the CLI's `/fallthrough` and the settings screen are
 * two renderings of it, and a phrasing that keeps appearing here is a template somebody should
 * add to the owning Sock's spec.
 *
 * ### The constraint, written down where it cannot be missed
 *
 * **This is a verbatim record of things people said in their own home.** Not commands — the
 * things that were *not* commands, which is to say whatever else was being said in the kitchen
 * when the wake word fired at the television. That makes it the most sensitive data structure in
 * the codebase, and the rules follow from that rather than from convenience:
 *
 * - It stays on the device. Nothing here is uploaded, and there is no code path that could.
 * - It stays in memory, bounded at [CAPACITY]. A ring rather than a list because an unbounded
 *   one is a transcript.
 * - It is clearable from settings, and clearing it is immediate and total.
 * - It does **not** survive a reboot. Persisting it is an M7 decision that needs a retention
 *   policy first, and shipping the storage before the policy is how a debug aid becomes a
 *   recording.
 */
class FallthroughLog(private val capacity: Int = CAPACITY) {

    private val _entries = MutableStateFlow<List<Fallthrough>>(emptyList())

    val entries: StateFlow<List<Fallthrough>> = _entries.asStateFlow()

    /**
     * Utterances the filler-skipping pass rescued (M6c), newest last.
     *
     * The same ring, the same bound, the same rules: these are also things somebody said in
     * their own home, and the fact that one of them resolved to a command does not make the
     * sentence around it less theirs. Kept beside the fallthroughs rather than in a counter
     * because the question this answers is "*which* utterances", and a bare number cannot say
     * whether a word belongs on the list.
     */
    private val _rescues = MutableStateFlow<List<Rescue>>(emptyList())

    val rescues: StateFlow<List<Rescue>> = _rescues.asStateFlow()

    fun record(fallthrough: Fallthrough) {
        _entries.update { it.ring(fallthrough) }
    }

    fun record(rescue: Rescue) {
        _rescues.update { it.ring(rescue) }
    }

    /** Immediate and total, as the class doc promises — both halves, or it is not "clear". */
    fun clear() {
        _entries.value = emptyList()
        _rescues.value = emptyList()
    }

    val size: Int get() = _entries.value.size

    val rescueCount: Int get() = _rescues.value.size

    private fun <T> List<T>.ring(next: T): List<T> {
        val grown = this + next
        return if (grown.size > capacity) grown.subList(grown.size - capacity, grown.size) else grown
    }

    companion object {
        /**
         * Enough to see a pattern across a few days of use, small enough to be a debug aid
         * rather than a record of a household.
         */
        const val CAPACITY: Int = 200
    }
}
