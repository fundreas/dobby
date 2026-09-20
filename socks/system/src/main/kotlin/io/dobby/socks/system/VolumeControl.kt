package io.dobby.socks.system

/**
 * The media stream, in the units the device actually has.
 *
 * `TimerAlarm` and `SpotifyPlayer` are the precedent: the Sock sees an interface, the device
 * sees `AudioManager`, and everything above this line runs on a plain JVM.
 *
 * **Indices, not percent**, and that is the one decision in this file. `STREAM_MUSIC` has
 * somewhere between 7 and 25 steps depending on the device, and a percentage handed straight to
 * the platform rounds — on a 7-step stream "ein bisschen lauter" rounds to *nothing* and the
 * panel looks broken. Rounding is a judgement about what the user asked for, so it belongs in
 * [VolumeScale] where it is visible, not in the half of the code that only exists on a phone.
 */
interface VolumeControl {

    /** The loudest index the stream has. `0` means there is no stream to control. */
    val maxIndex: Int

    /** The current index, `0`..[maxIndex]. */
    val index: Int

    val isMuted: Boolean

    /** Sets the stream. Values outside `0`..[maxIndex] are the caller's mistake, not the device's. */
    fun setIndex(index: Int)

    /**
     * Mutes or unmutes without changing what the stream was set to.
     *
     * The platform restores the previous index by itself on unmute, and [SystemSock] still
     * remembers it separately — see `system.specs.md` §4. Two sources for one number, because
     * the one that survives a process death is the one the user asked about.
     */
    fun setMuted(muted: Boolean)

    /** False where there is no audio at all: the terminal, a unit test, a headless build. */
    val isAvailable: Boolean get() = maxIndex > 0

    /** No stream. The Sock is then `Unavailable` and says so rather than pretending. */
    object NONE : VolumeControl {
        override val maxIndex: Int = 0
        override val index: Int = 0
        override val isMuted: Boolean = false

        override fun setIndex(index: Int) = Unit

        override fun setMuted(muted: Boolean) = Unit
    }

    companion object {
        /**
         * A stream that exists only in memory, for the terminal harness.
         *
         * Fifteen steps because that is what most Android devices report for `STREAM_MUSIC`,
         * so the rounding somebody sees in the CLI is the rounding they get on the panel.
         */
        fun inMemory(maxIndex: Int = 15, index: Int = 7): VolumeControl = InMemoryVolume(maxIndex, index)
    }
}

/** [VolumeControl.inMemory]. Public because the CLI constructs one and reads it back. */
class InMemoryVolume(override val maxIndex: Int = 15, index: Int = 7) : VolumeControl {

    override var index: Int = index.coerceIn(0, maxIndex)
        private set

    override var isMuted: Boolean = false
        private set

    /** What the index was when mute was switched on, the way the platform remembers it. */
    private var beforeMute: Int = index

    override fun setIndex(index: Int) {
        this.index = index.coerceIn(0, maxIndex)
        if (this.index > 0) isMuted = false
    }

    override fun setMuted(muted: Boolean) {
        if (muted == isMuted) return
        isMuted = muted
        if (muted) {
            beforeMute = index
            index = 0
        } else {
            index = beforeMute
        }
    }
}

/**
 * Percent in, stream index out — and the rounding rules that make a spoken command land.
 *
 * Percent is what the user says ("auf mittel", "volle Lautstärke") and indices are what the
 * device has. Two rules turn one into the other, and both exist because of small streams:
 *
 * 1. **A relative change always moves.** `+10 %` of a 7-step stream is 0.7 of an index; naive
 *    rounding makes "mach lauter" do nothing, twice in a row, on a device that is working
 *    perfectly. [shift] moves at least one index whenever the target is not already the limit.
 * 2. **A ceiling is a limit, not a target.** `system.max_volume_percent` clamps every raise,
 *    including "volle Lautstärke" — a ceiling somebody can talk their way past is decoration.
 */
object VolumeScale {

    private const val PERCENT = 100

    fun toPercent(index: Int, maxIndex: Int): Int =
        if (maxIndex <= 0) 0 else Math.round(index * PERCENT.toFloat() / maxIndex)

    fun toIndex(percent: Int, maxIndex: Int): Int =
        if (maxIndex <= 0) 0 else Math.round(percent.coerceIn(0, PERCENT) * maxIndex / PERCENT.toFloat())
            .coerceIn(0, maxIndex)

    /**
     * The index [deltaPercent] away from [fromIndex], clamped to `0`..[ceilingPercent].
     *
     * Returns [fromIndex] unchanged only when the move is genuinely impossible — already at the
     * ceiling going up, already silent going down. That is what lets the Sock tell "it worked"
     * from "schon ganz laut" without asking the device twice.
     */
    fun shift(fromIndex: Int, deltaPercent: Int, maxIndex: Int, ceilingPercent: Int): Int {
        if (maxIndex <= 0) return fromIndex
        val ceiling = toIndex(ceilingPercent, maxIndex).coerceAtLeast(1)
        val limit = if (deltaPercent >= 0) ceiling else 0
        // Already past the ceiling because something else set it — coming back down is allowed,
        // going further up is not.
        if (deltaPercent >= 0 && fromIndex >= limit) return fromIndex
        if (deltaPercent < 0 && fromIndex <= limit) return fromIndex

        val rounded = toIndex(toPercent(fromIndex, maxIndex) + deltaPercent, maxIndex)
        val moved = when {
            deltaPercent > 0 && rounded <= fromIndex -> fromIndex + 1
            deltaPercent < 0 && rounded >= fromIndex -> fromIndex - 1
            else -> rounded
        }
        return moved.coerceIn(minOf(limit, fromIndex), maxOf(limit, fromIndex))
    }
}
