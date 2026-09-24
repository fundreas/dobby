package io.dobby.socks.memo

import io.dobby.core.sock.SockConfigStore
import java.time.Duration

/**
 * The Sock's settings (`memo.specs.md` §9), read through [SockConfigStore].
 *
 * Read at the moment they are used rather than captured at `onStart`, the way `ClockConfig` and
 * `RadioConfig` are: a session length somebody has just changed should apply to the next memo
 * they read out, not to the next boot.
 */
internal class MemoConfig(private val store: SockConfigStore) {

    /**
     * How long "das Memo" keeps meaning the one that was just read out.
     *
     * The idea file's five minutes, and the number that makes "Memo erledigt" safe: past it the
     * Sock asks instead of closing, so a word said into the room half an hour later cannot throw
     * away a note. Zero and negatives are a broken setting, not a preference, and fall back to
     * the default rather than making the close command unreachable.
     */
    val sessionTtl: Duration
        get() = store.getInt(SESSION_MINUTES)
            ?.takeIf { it > 0 }
            ?.let { Duration.ofMinutes(it.toLong()) }
            ?: DEFAULT_SESSION_TTL

    /** The cap on open memos. See [MemoBook.add] for why a full pile is refused, not rotated. */
    val maxMemos: Int
        get() = (store.getInt(MAX_MEMOS) ?: DEFAULT_MAX_MEMOS).coerceIn(1, HARD_MAX_MEMOS)

    companion object {
        const val SESSION_MINUTES: String = "memo.session_minutes"
        const val MAX_MEMOS: String = "memo.max_memos"

        val DEFAULT_SESSION_TTL: Duration = Duration.ofMinutes(5)

        /**
         * Enough for a week of a household's worth of notes, few enough to read out loud.
         *
         * The limit is about the *listener*, not the storage: a pile nobody can walk to the end
         * of in one sitting is a pile that stops being consulted, and then it is a graveyard
         * with a voice interface.
         */
        const val DEFAULT_MAX_MEMOS: Int = 50

        private const val HARD_MAX_MEMOS = 500
    }
}
