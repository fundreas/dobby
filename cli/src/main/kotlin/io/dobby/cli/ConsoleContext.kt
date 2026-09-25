package io.dobby.cli

import io.dobby.core.sock.FocusLoss
import io.dobby.core.sock.Lang
import io.dobby.core.sock.Phrase
import io.dobby.core.sock.PlaybackCoordinator
import io.dobby.core.sock.ScreenController
import io.dobby.core.sock.SockConfigStore
import io.dobby.core.sock.SockContext
import io.dobby.core.sock.SockLog
import kotlinx.coroutines.CoroutineScope

/**
 * A [SockContext] for the terminal.
 *
 * The Android implementations land in Phase B; these exist so Socks can be exercised now,
 * and so the interfaces are pinned down by a real caller rather than by guesswork.
 */
class ConsoleContext(
    override val scope: CoroutineScope,
    private val verbose: () -> Boolean,
    /**
     * The answer language, read at the moment something is said rather than held.
     *
     * A provider and not a value because `/lang` switches it mid-session, and the whole point
     * of [io.dobby.core.sock.Phrase] is that a sentence built an hour ago is spoken in the
     * language that is set now.
     */
    private val lang: () -> Lang = { Lang.DEFAULT },
) : SockContext {

    override val playback: PlaybackCoordinator = ConsolePlayback()

    override val screen: ScreenController = ConsoleScreen()

    override val config: SockConfigStore = InMemoryConfig()

    override val log: SockLog = object : SockLog {
        override fun debug(message: String) {
            if (verbose()) println("      · $message")
        }

        override fun warn(message: String, cause: Throwable?) {
            println("      ! $message${cause?.let { " ($it)" } ?: ""}")
        }
    }

    override suspend fun announce(phrase: Phrase) {
        println("  🔊 ${phrase(lang())}")
    }
}

private class ConsolePlayback : PlaybackCoordinator {
    override var holder: String? = null
        private set

    /** The holder's loss callback. Kept so the terminal really does evict, as the device does. */
    private var onLost: (suspend (FocusLoss) -> Unit)? = null

    override suspend fun requestFocus(sockId: String, onLost: (suspend (FocusLoss) -> Unit)?): Boolean {
        evict(sockId)
        holder = sockId
        this.onLost = onLost
        return true
    }

    override suspend fun requestTransientFocus(sockId: String): Boolean = true

    override suspend fun claimExternal(sockId: String, onLost: (suspend (FocusLoss) -> Unit)?): Boolean {
        evict(sockId)
        holder = sockId
        this.onLost = onLost
        return true
    }

    override suspend fun releaseFocus(sockId: String) {
        if (holder != sockId) return
        holder = null
        onLost = null
    }

    private suspend fun evict(sockId: String) {
        if (holder == null || holder == sockId) return
        val previous = onLost
        onLost = null
        previous?.invoke(FocusLoss.EVICTED)
    }
}

private class ConsoleScreen : ScreenController {
    /** A terminal is being looked at by definition — somebody is typing into it. */
    override val isOn: Boolean = true

    override fun wakeFor(seconds: Int) = Unit

    override fun release() = Unit
}

private class InMemoryConfig : SockConfigStore {
    private val values = mutableMapOf<String, String>()

    override fun getString(key: String): String? = values[key]

    override fun getInt(key: String): Int? = values[key]?.toIntOrNull()

    override fun getBoolean(key: String): Boolean? = values[key]?.toBooleanStrictOrNull()

    override fun put(key: String, value: String) {
        values[key] = value
    }
}
