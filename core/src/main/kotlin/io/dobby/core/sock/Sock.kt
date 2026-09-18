package io.dobby.core.sock

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private val ALWAYS_READY: StateFlow<SockStatus> =
    MutableStateFlow<SockStatus>(SockStatus.Ready).asStateFlow()

/**
 * Dobby's unit of capability.
 *
 * Core owns the microphone, wake word, STT, parsing, dispatch and TTS — and no commands.
 * Every command a user can speak is contributed by a Sock.
 *
 * See `socks.specs/README.md` for the full contract, including what a Sock may NOT do.
 */
interface Sock {
    /** Stable lowercase slug. Prefixes every exclusive command id. */
    val id: String

    val displayName: String

    /** Commands this Sock alone owns. Each id must be `"$id.<name>"`. */
    val commands: List<ExclusiveCommandSpec> get() = emptyList()

    /** Chains this Sock takes part in. */
    val shared: List<SharedSubscription> get() = emptyList()

    val status: StateFlow<SockStatus> get() = ALWAYS_READY

    suspend fun onStart(ctx: SockContext) {}

    suspend fun onStop() {}

    /**
     * Ranks this Sock in a chain.
     *
     * Contract: a **pure state read**. No I/O, no network, no Binder call, under 5 ms —
     * it runs for every subscriber on every shared invocation.
     */
    fun activityFor(invocation: CommandInvocation): SockActivity = SockActivity.INACTIVE

    /**
     * Executes the command.
     *
     * For a shared command, may return [SockResult.NotForMe] to pass it down the chain —
     * and must do so without performing I/O when [activityFor] reported
     * [SockActivity.INACTIVE].
     */
    suspend fun handle(invocation: CommandInvocation): SockResult
}
