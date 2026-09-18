package io.dobby.cli

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

    override suspend fun announce(text: String) {
        println("  🔊 $text")
    }
}

private class ConsolePlayback : PlaybackCoordinator {
    override var holder: String? = null
        private set

    override suspend fun requestFocus(sockId: String): Boolean {
        holder = sockId
        return true
    }

    override suspend fun requestTransientFocus(sockId: String): Boolean = true

    override suspend fun releaseFocus(sockId: String) {
        if (holder == sockId) holder = null
    }
}

private class ConsoleScreen : ScreenController {
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
