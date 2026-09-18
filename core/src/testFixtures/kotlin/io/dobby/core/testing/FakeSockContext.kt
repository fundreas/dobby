package io.dobby.core.testing

import io.dobby.core.sock.PlaybackCoordinator
import io.dobby.core.sock.ScreenController
import io.dobby.core.sock.SockConfigStore
import io.dobby.core.sock.SockContext
import io.dobby.core.sock.SockLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

/**
 * A recording [SockContext] for Sock unit tests.
 *
 * Published as test fixtures so every Sock module gets it without copying:
 *
 * ```kotlin
 * testImplementation(testFixtures(project(":core")))
 * ```
 */
class FakeSockContext(
    override val scope: CoroutineScope = CoroutineScope(SupervisorJob()),
    override val playback: FakePlaybackCoordinator = FakePlaybackCoordinator(),
    override val screen: FakeScreenController = FakeScreenController(),
    override val config: FakeConfigStore = FakeConfigStore(),
) : SockContext {

    val announcements: MutableList<String> = mutableListOf()
    val warnings: MutableList<String> = mutableListOf()

    override val log: SockLog = object : SockLog {
        override fun debug(message: String) = Unit

        override fun warn(message: String, cause: Throwable?) {
            warnings += message
        }
    }

    override suspend fun announce(text: String) {
        announcements += text
    }
}

class FakeScreenController : ScreenController {
    val wakeRequests: MutableList<Int> = mutableListOf()
    var releases: Int = 0
        private set

    override fun wakeFor(seconds: Int) {
        wakeRequests += seconds
    }

    override fun release() {
        releases++
    }
}

class FakePlaybackCoordinator : PlaybackCoordinator {
    override var holder: String? = null
        private set

    /** Set false to exercise the "focus denied" path. */
    var grantFocus: Boolean = true

    val transientRequests: MutableList<String> = mutableListOf()

    override suspend fun requestFocus(sockId: String): Boolean {
        if (!grantFocus) return false
        holder = sockId
        return true
    }

    override suspend fun requestTransientFocus(sockId: String): Boolean {
        transientRequests += sockId
        return grantFocus
    }

    override suspend fun releaseFocus(sockId: String) {
        if (holder == sockId) holder = null
    }
}

class FakeConfigStore(initial: Map<String, String> = emptyMap()) : SockConfigStore {
    private val values = initial.toMutableMap()

    override fun getString(key: String): String? = values[key]

    override fun getInt(key: String): Int? = values[key]?.toIntOrNull()

    override fun getBoolean(key: String): Boolean? = values[key]?.toBooleanStrictOrNull()

    override fun put(key: String, value: String) {
        values[key] = value
    }
}
