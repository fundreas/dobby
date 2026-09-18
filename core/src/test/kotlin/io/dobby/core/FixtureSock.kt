package io.dobby.core

import io.dobby.core.sock.CommandInvocation
import io.dobby.core.sock.ExclusiveCommandSpec
import io.dobby.core.sock.SharedSubscription
import io.dobby.core.sock.Sock
import io.dobby.core.sock.SockActivity
import io.dobby.core.sock.SockResult

/**
 * A Sock with no behaviour of its own, configured per test.
 *
 * Records every [handle] call so a test can assert that a Sock further down a
 * FIRST_CONSUMER chain was never even asked.
 */
class FixtureSock(
    override val id: String,
    override val commands: List<ExclusiveCommandSpec> = emptyList(),
    override val shared: List<SharedSubscription> = emptyList(),
    private val activity: (CommandInvocation) -> SockActivity = { SockActivity.INACTIVE },
    private val handler: suspend (CommandInvocation) -> SockResult = { SockResult.NotForMe },
) : Sock {
    override val displayName: String get() = id

    val handled: MutableList<String> = mutableListOf()

    /** Set by tests that assert the no-I/O contract for INACTIVE Socks. */
    var performedIo: Boolean = false
        private set

    override fun activityFor(invocation: CommandInvocation): SockActivity = activity(invocation)

    override suspend fun handle(invocation: CommandInvocation): SockResult {
        handled += invocation.commandId
        return handler(invocation)
    }

    fun markIo() {
        performedIo = true
    }
}
