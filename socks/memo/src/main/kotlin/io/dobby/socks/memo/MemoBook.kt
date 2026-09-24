package io.dobby.socks.memo

import io.dobby.core.sock.SockContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/** Which way through the pile the user is walking. */
enum class Direction {
    /** Started at the newest memo; "next" goes back in time. */
    NEWEST_FIRST,

    /** Started at the oldest memo; "next" comes forward in time. */
    OLDEST_FIRST,
    ;

    /** The memos in walking order. [newestFirst] is how [MemoBook] keeps them. */
    fun order(newestFirst: List<Memo>): List<Memo> =
        if (this == NEWEST_FIRST) newestFirst else newestFirst.asReversed()
}

/** Where a walk arrived. */
sealed interface Step {
    /**
     * A memo to read out.
     *
     * @param opening the direction this step *started* a walk in, or null if it continued one.
     *   It is what decides whether the memo is introduced as "dein neuestes" or simply read —
     *   and it is computed here rather than by the caller because "nächstes Memo" with no
     *   session open silently becomes a walk from the top (§4), and only [MemoBook] knows that
     *   happened.
     */
    data class At(val memo: Memo, val others: Int, val opening: Direction?) : Step

    /** Nothing is open at all. */
    data object Empty : Step

    /** The walk has run off the end ([forward]) or off the start of the pile. */
    data class Edge(val forward: Boolean) : Step
}

sealed interface AddOutcome {
    data class Added(val memo: Memo) : AddOutcome

    /** The pile is at [MemoConfig.maxMemos]. Refused rather than rotated — see [MemoBook.add]. */
    data object TooMany : AddOutcome
}

sealed interface CloseOutcome {
    data class Closed(val memo: Memo, val open: Int) : CloseOutcome

    /** No memo session, or it has timed out. The one case "Memo erledigt" must not guess at. */
    data object NoSession : CloseOutcome
}

/**
 * The open memos, the cursor that walks them, and the file they survive a reboot in.
 *
 * Two pieces of state with deliberately different lifetimes, which is the whole design of this
 * Sock:
 *
 * - **The memos are durable.** They are written to the config store on every structural change
 *   and read back in `onStart`, exactly as `TimerEngine` persists its deadlines. A note somebody
 *   dictated and a reboot ate is worse than no note-taking at all, because they stopped carrying
 *   it in their head the moment they said it out loud.
 * - **The session is not.** It is a cursor into a conversation — "das Memo" means the one just
 *   read out — and a conversation does not survive five minutes of silence, let alone a restart.
 *   After [MemoConfig.sessionTtl] it is gone, and "Memo erledigt" asks to be told which memo
 *   rather than closing whatever was last mentioned (`memo.specs.md` §4). That expiry is the
 *   safety property of the whole Sock: closing is the one destructive thing it can do, and the
 *   window in which one word can do it is short and always opened by the user.
 *
 * Neither is a follow-up question (`socks.specs/README.md` §5): a follow-up dies with the turn
 * and this must outlive it — the user says "welche Memos sind offen", listens, walks off, comes
 * back and says "nächstes Memo". That is three turns and one session.
 *
 * @param clock injected so the session's expiry is testable without waiting five minutes.
 */
internal class MemoBook(private val clock: Clock) {

    /**
     * One writer at a time.
     *
     * Core dispatches one utterance at a time, so this is concurrency by type rather than by
     * need — the same bargain `TimerEngine` makes. A memo list that is wrong under a race would
     * be a very quiet bug: the panel and the spoken answer would simply disagree about what the
     * user has to do today.
     */
    private val mutex = Mutex()

    private val _state = MutableStateFlow(MemoState())

    /** What the panel draws (§8), and the only view anybody outside this class gets. */
    val state: StateFlow<MemoState> = _state.asStateFlow()

    private val ids = AtomicLong()

    /** The cursor. Null when no memo has been read out recently enough to be "das Memo". */
    @Volatile
    private var session: Session? = null

    private data class Session(val cursor: Long, val direction: Direction, val touchedAt: Instant)

    private val memos: List<Memo> get() = _state.value.memos

    /** Picks up the memos that outlived the process. */
    suspend fun restore(ctx: SockContext) = mutex.withLock {
        if (memos.isNotEmpty()) return@withLock
        val saved = decode(ctx.config.getString(ENTRIES))
        if (saved.isEmpty()) return@withLock
        ids.set(saved.maxOf { it.id })
        ctx.log.debug("memo: ${saved.size} memo(s) restored")
        publish(ctx, saved)
    }

    /**
     * Notes a new memo, timestamped now.
     *
     * A full pile is refused rather than rotated. Dropping the oldest to make room would be the
     * one failure this Sock may not have — it would forget something nobody told it to forget,
     * silently, at the moment they were adding something else.
     */
    suspend fun add(ctx: SockContext, text: String): AddOutcome = mutex.withLock {
        if (memos.size >= MemoConfig(ctx.config).maxMemos) return@withLock AddOutcome.TooMany
        val memo = Memo(id = ids.incrementAndGet(), text = text, createdAt = clock.instant())
        publish(ctx, memos + memo)
        AddOutcome.Added(memo)
    }

    /** Starts a walk at the newest or the oldest memo, and opens a session on it. */
    suspend fun open(ctx: SockContext, direction: Direction): Step = mutex.withLock {
        val first = direction.order(memos).firstOrNull() ?: return@withLock clearSession(ctx)
        enter(ctx, first, direction, opening = direction)
    }

    /**
     * One memo further along the walk, or one back.
     *
     * With no live session this *starts* one at the newest memo instead of refusing. "Nächstes
     * Memo" said into a room where nothing has been read out yet is not a mistake — it is
     * somebody asking for a memo, in the words they would use for the second one — and the only
     * honest thing Dobby can do with it is hand them the first.
     */
    suspend fun step(ctx: SockContext, forward: Boolean): Step = mutex.withLock {
        val live = liveSession(ctx) ?: return@withLock openLocked(ctx, Direction.NEWEST_FIRST)
        val order = live.direction.order(memos)
        val index = order.indexOfFirst { it.id == live.cursor }
        // The memo the cursor sat on is gone — closed from the panel, or lost with a config
        // wipe. Restart the walk rather than guess at a neighbour by position.
        if (index < 0) return@withLock openLocked(ctx, live.direction)
        val target = order.getOrNull(index + if (forward) 1 else -1)
            ?: return@withLock edge(ctx, live, forward)
        enter(ctx, target, live.direction, opening = null)
    }

    /**
     * Closes the memo the session is sitting on.
     *
     * The session ends with it, deliberately. Advancing the cursor to the next memo would make
     * a second "erledigt" close a memo that was never read out — one word, no confirmation, and
     * a note gone that nobody heard. Ending the session costs the user four words ("welche Memos
     * sind offen"), and the memo that closing just promoted to newest is the one they hear.
     */
    suspend fun close(ctx: SockContext): CloseOutcome = mutex.withLock {
        val live = liveSession(ctx) ?: return@withLock CloseOutcome.NoSession
        val memo = memos.firstOrNull { it.id == live.cursor } ?: return@withLock CloseOutcome.NoSession
        session = null
        publish(ctx, memos.filterNot { it.id == memo.id })
        // Re-read through the getter: `publish` has already swapped the list, so this is what
        // is *still* open, which is the number the answer says out loud.
        CloseOutcome.Closed(memo, memos.size)
    }

    /**
     * Closes one memo by id, for a finger on the panel.
     *
     * No session and no cursor: pointing at a memo *is* saying which one, which is the whole
     * difference between this and [close]. The session is dropped if it was sitting on the memo
     * that just went, so a later "erledigt" cannot act on something that is no longer there.
     *
     * @return the memo that was closed, or null if that id is not open.
     */
    suspend fun closeById(ctx: SockContext, id: Long): Memo? = mutex.withLock {
        val memo = memos.firstOrNull { it.id == id } ?: return@withLock null
        if (session?.cursor == id) session = null
        publish(ctx, memos.filterNot { it.id == id })
        memo
    }

    /** The turn is over and so is the walk — the panel's X, or a Sock being stopped. */
    fun forgetSession() {
        session = null
        _state.update { it.copy(spotlight = null) }
    }

    /** Must be called with [mutex] held. */
    private fun openLocked(ctx: SockContext, direction: Direction): Step {
        val first = direction.order(memos).firstOrNull() ?: return clearSession(ctx)
        return enter(ctx, first, direction, opening = direction)
    }

    /** Puts the cursor on [memo] and answers with it. Must be called with [mutex] held. */
    private fun enter(ctx: SockContext, memo: Memo, direction: Direction, opening: Direction?): Step {
        session = Session(memo.id, direction, clock.instant())
        _state.update { it.copy(spotlight = memo.id) }
        ctx.log.debug("memo: reading ${memo.id}, ${memos.size - 1} other(s) open")
        return Step.At(memo, others = memos.size - 1, opening = opening)
    }

    /**
     * The walk has nowhere left to go — and the cursor stays where it is.
     *
     * The session is touched rather than left to age out: being told "das war schon das letzte
     * Memo" is somebody still working on their memos, and letting that answer start the clock
     * running out on "erledigt" would be a trap of Dobby's own making.
     */
    private fun edge(ctx: SockContext, live: Session, forward: Boolean): Step {
        session = live.copy(touchedAt = clock.instant())
        ctx.log.debug("memo: no memo ${if (forward) "after" else "before"} ${live.cursor}")
        return Step.Edge(forward)
    }

    private fun clearSession(ctx: SockContext): Step {
        forgetSession()
        ctx.log.debug("memo: nothing open")
        return Step.Empty
    }

    /**
     * The session, or null if it has timed out.
     *
     * Dropped on read rather than on a timer, for `ResultMemory`'s reason: there is nothing to
     * schedule for, and a coroutine whose only job is to null a field is a lifecycle to get
     * wrong.
     */
    private fun liveSession(ctx: SockContext): Session? {
        val held = session ?: return null
        if (Duration.between(held.touchedAt, clock.instant()) > MemoConfig(ctx.config).sessionTtl) {
            forgetSession()
            return null
        }
        return held
    }

    /**
     * The one place the list changes. Must be called with [mutex] held.
     *
     * Newest first, which is the order everything else reads: the panel's card, `latest_memo`,
     * and `oldest_memo` through [Direction.order]. Persistence happens here for the same reason
     * it does in `TimerEngine.publish` — every structural change is one, and nothing else is.
     */
    private fun publish(ctx: SockContext, memos: List<Memo>) {
        val sorted = memos.sortedWith(compareByDescending<Memo> { it.createdAt }.thenByDescending { it.id })
        _state.value = MemoState(sorted, session?.cursor?.takeIf { id -> sorted.any { it.id == id } })
        ctx.config.put(ENTRIES, encode(sorted))
    }

    /**
     * One line per memo, `id|createdAt|text`.
     *
     * The text is Normalizer output cleaned by [MemoText], so it holds letters, digits,
     * apostrophes and spaces and nothing else — neither separator can appear inside one. That is
     * `TimerEngine`'s argument for the same format, and it is the reason this is three fields of
     * plain text rather than a JSON dependency in a module that otherwise has none.
     */
    private fun encode(memos: List<Memo>): String =
        memos.joinToString("\n") { "${it.id}|${it.createdAt.toEpochMilli()}|${it.text}" }

    /** A line that does not parse is dropped, not fatal: a memo is not worth a crash loop. */
    private fun decode(raw: String?): List<Memo> = raw.orEmpty().lineSequence()
        .mapNotNull { line ->
            val parts = line.split('|')
            if (parts.size != PERSISTED_FIELDS) return@mapNotNull null
            val id = parts[0].toLongOrNull() ?: return@mapNotNull null
            val createdAt = parts[1].toLongOrNull() ?: return@mapNotNull null
            val text = parts[2].takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Memo(id, text, Instant.ofEpochMilli(createdAt))
        }
        .toList()

    private companion object {
        /** Written by `publish`, read by `restore`. Not user-facing config — see [MemoConfig]. */
        const val ENTRIES = "memo.entries"

        const val PERSISTED_FIELDS = 3
    }
}
