package io.dobby.core.testing

import io.dobby.core.sock.FocusLoss
import io.dobby.core.sock.Lang
import io.dobby.core.sock.Phrase
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

    /**
     * What was announced, and in which language it was announced.
     *
     * A [Phrase] is rendered here rather than kept, because [lang] is the whole question a
     * fixture can answer about one: a test that wants the other wording flips the field and
     * announces again.
     */
    val announcements: MutableList<String> = mutableListOf()
    val warnings: MutableList<String> = mutableListOf()

    /** The language announcements are rendered in. Settable, so both wordings are reachable. */
    var lang: Lang = Lang.DEFAULT

    override val log: SockLog = object : SockLog {
        override fun debug(message: String) = Unit

        override fun warn(message: String, cause: Throwable?) {
            warnings += message
        }
    }

    override suspend fun announce(phrase: Phrase) {
        announcements += phrase(lang)
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

    /**
     * Who is ducking the channel right now, or null.
     *
     * Transient focus does not displace [holder] — that is the entire difference between
     * ducking and stopping, and "the music came back to full volume" is an assertion a test
     * has to be able to make.
     */
    var duckedBy: String? = null
        private set

    /** Set false to exercise the "focus denied" path. */
    var grantFocus: Boolean = true

    val transientRequests: MutableList<String> = mutableListOf()

    /** The holder's loss callback, if it asked for one. One slot, because there is one claim. */
    private var onLost: (suspend (FocusLoss) -> Unit)? = null

    /**
     * Drives the loss path by hand, which is the only way to reach it without a device.
     *
     * `coordinator.loseFocus(FocusLoss.SYSTEM)` is an incoming phone call;
     * [FocusLoss.EVICTED] arrives by itself when another sock id claims.
     */
    suspend fun loseFocus(reason: FocusLoss) {
        val previous = onLost
        onLost = null
        holder = null
        previous?.invoke(reason)
    }

    override suspend fun requestFocus(sockId: String, onLost: (suspend (FocusLoss) -> Unit)?): Boolean {
        evict(sockId)
        if (!grantFocus) return false
        holder = sockId
        this.onLost = onLost
        return true
    }

    override suspend fun requestTransientFocus(sockId: String): Boolean {
        transientRequests += sockId
        if (!grantFocus) return false
        duckedBy = sockId
        return true
    }

    /** Who claimed the channel for another app's audio, if anyone. Never a real focus request. */
    var externalHolder: String? = null
        private set

    override suspend fun claimExternal(sockId: String, onLost: (suspend (FocusLoss) -> Unit)?): Boolean {
        evict(sockId)
        if (!grantFocus) return false
        holder = sockId
        externalHolder = sockId
        this.onLost = onLost
        return true
    }

    override suspend fun releaseFocus(sockId: String) {
        if (holder == sockId) {
            holder = null
            onLost = null
        }
        if (duckedBy == sockId) duckedBy = null
        if (externalHolder == sockId) externalHolder = null
    }

    /** The same rule the Android coordinator applies: a re-claim by the holder is not a loss. */
    private suspend fun evict(sockId: String) {
        if (holder == null || holder == sockId) return
        val previous = onLost
        onLost = null
        previous?.invoke(FocusLoss.EVICTED)
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
