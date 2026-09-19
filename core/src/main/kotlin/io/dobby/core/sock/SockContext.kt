package io.dobby.core.sock

import kotlinx.coroutines.CoroutineScope

/**
 * Everything a Sock is allowed to touch.
 *
 * Anything not reachable from here is off-limits — that is what keeps Socks unit-testable
 * without an emulator. Phase A ships the interfaces plus console/no-op implementations;
 * the Android implementations arrive in Phase B.
 */
interface SockContext {
    /** Service-lifetime scope for work that outlives a single `handle()` call. */
    val scope: CoroutineScope

    val playback: PlaybackCoordinator
    val screen: ScreenController
    val config: SockConfigStore
    val log: SockLog

    /**
     * Asynchronous speech — a timer firing, a stream dropping.
     *
     * Command acknowledgements do NOT go here; return a [SockResult] instead.
     */
    suspend fun announce(text: String)
}

/**
 * Arbitrates the audio channel so two Socks can never play at once.
 *
 * Transient focus ducks the current holder instead of stopping it.
 */
interface PlaybackCoordinator {
    val holder: String?

    suspend fun requestFocus(sockId: String): Boolean

    suspend fun requestTransientFocus(sockId: String): Boolean

    suspend fun releaseFocus(sockId: String)
}

/** Owns the screen-on wake locks. Socks ask; they never hold locks themselves. */
interface ScreenController {
    fun wakeFor(seconds: Int)

    fun release()
}

/** Namespaced key-value config. Keys are `<sockId>.<key>` by convention. */
interface SockConfigStore {
    fun getString(key: String): String?

    fun getInt(key: String): Int?

    fun getBoolean(key: String): Boolean?

    fun put(key: String, value: String)
}

interface SockLog {
    fun debug(message: String)

    fun warn(message: String, cause: Throwable? = null)

    companion object {
        /** Discards everything. The default wherever core takes a log it can do without. */
        val NONE: SockLog = object : SockLog {
            override fun debug(message: String) = Unit

            override fun warn(message: String, cause: Throwable?) = Unit
        }
    }
}
