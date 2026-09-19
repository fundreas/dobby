package io.dobby.core.dispatch

import io.dobby.core.registry.SockRegistry
import io.dobby.core.registry.Subscriber
import io.dobby.core.sock.ChainMode
import io.dobby.core.sock.CommandInvocation
import io.dobby.core.sock.Sock
import io.dobby.core.sock.SockActivity
import io.dobby.core.sock.SockLog
import io.dobby.core.sock.SockResult
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Degradation observed by the dispatcher, separate from a Sock's self-reported status. */
class SockHealth {
    private val degraded = ConcurrentHashMap<String, String>()

    fun degrade(sockId: String, reason: String) {
        degraded[sockId] = reason
    }

    fun clear(sockId: String) {
        degraded.remove(sockId)
    }

    fun reasonFor(sockId: String): String? = degraded[sockId]

    fun snapshot(): Map<String, String> = degraded.toMap()
}

enum class StepOutcome {
    /** Claimed the command. */
    CONSUMED,

    /** "Nothing to do for me here." */
    PASSED,

    /** Threw. Marked degraded; the chain continues. */
    FAILED,

    /** Exceeded the handler timeout. Marked degraded; the chain continues. */
    TIMED_OUT,
}

/** One Sock's turn in a chain. The trace is how you debug "stopp stopped the wrong thing". */
data class ChainStep(
    val sockId: String,
    /** Null for an exclusive command: there was no chain, so nothing reported an activity. */
    val activity: SockActivity?,
    val outcome: StepOutcome,
    val detail: String? = null,
)

data class DispatchOutcome(
    val result: SockResult,
    val trace: List<ChainStep> = emptyList(),
) {
    val consumedBy: String? get() = trace.firstOrNull { it.outcome == StepOutcome.CONSUMED }?.sockId
}

/**
 * Routes an invocation to the Sock that should run it.
 *
 * Exclusive commands go straight to their owner. Shared commands walk a chain ranked by
 * [Sock.activityFor], and the first Sock that does not answer [SockResult.NotForMe] wins.
 *
 * See `socks.specs/shared-commands.specs.md`.
 */
class Dispatcher(
    private val registry: SockRegistry,
    val health: SockHealth = SockHealth(),
    private val timeout: Duration = DEFAULT_TIMEOUT,
    private val log: SockLog = SockLog.NONE,
) {
    suspend fun dispatch(invocation: CommandInvocation): DispatchOutcome =
        if (invocation.isShared) dispatchChain(invocation) else dispatchExclusive(invocation)

    private suspend fun dispatchExclusive(invocation: CommandInvocation): DispatchOutcome {
        val owner = registry.ownerOf(invocation.commandId)
            ?: return DispatchOutcome(
                SockResult.Failed("Das kenne ich nicht."),
                listOf(ChainStep("?", null, StepOutcome.FAILED, "no owner")),
            )
        return deliver(owner, invocation, note = null)
    }

    /**
     * Hands an answer straight to the Sock that asked the question.
     *
     * Deliberately past [SockRegistry.ownerOf] and past the chain: the answer belongs to
     * whoever asked, not to whoever the command id would ordinarily resolve to. A Sock may
     * legitimately route an answer onto a command it shares an id with nobody, or onto one it
     * only reaches this way, and re-resolving the owner here would send "minuten" to a Sock
     * that has no idea what question it is answering.
     */
    suspend fun dispatchAnswer(asker: Sock, invocation: CommandInvocation): DispatchOutcome =
        deliver(asker, invocation, note = invocation.answering?.let { "answering $it" })

    /**
     * One Sock, one command, one timeout — the shape both the exclusive path and an answer take.
     *
     * [note] rides along into the trace so that "who ran this, and why them" stays readable
     * when the reason was a question rather than the palette.
     */
    private suspend fun deliver(owner: Sock, invocation: CommandInvocation, note: String?): DispatchOutcome =
        when (val attempt = run(owner, invocation)) {
            is Attempt.Threw -> {
                health.degrade(owner.id, attempt.cause.toString())
                log.warn("${owner.id} threw handling ${invocation.commandId}", attempt.cause)
                DispatchOutcome(
                    SockResult.Failed("Das hat gerade nicht geklappt.", attempt.cause),
                    listOf(step(owner, null, StepOutcome.FAILED, detail(note, attempt.cause.toString()))),
                )
            }

            is Attempt.TimedOut -> {
                health.degrade(owner.id, "Zeitüberschreitung")
                log.warn("${owner.id} timed out handling ${invocation.commandId}")
                DispatchOutcome(
                    SockResult.Failed("Das hat zu lange gedauert."),
                    listOf(step(owner, null, StepOutcome.TIMED_OUT, note)),
                )
            }

            is Attempt.Ok ->
                if (attempt.result is SockResult.NotForMe) {
                    // NotForMe is only meaningful in a chain. From an exclusive command it means
                    // the Sock's own `when` fell through — a bug in the Sock, not a routing hint.
                    log.warn("${owner.id} returned NotForMe for exclusive ${invocation.commandId}")
                    DispatchOutcome(
                        SockResult.Failed("Das kann ich gerade nicht."),
                        listOf(step(owner, null, StepOutcome.FAILED, detail(note, "NotForMe"))),
                    )
                } else {
                    DispatchOutcome(
                        attempt.result,
                        listOf(step(owner, null, StepOutcome.CONSUMED, note)),
                    )
                }
        }

    private fun detail(note: String?, reason: String): String =
        if (note == null) reason else "$note: $reason"

    private suspend fun dispatchChain(invocation: CommandInvocation): DispatchOutcome {
        val chain = registry.chainFor(invocation.commandId)
            ?: return DispatchOutcome(SockResult.Failed("Das kenne ich nicht."))

        val trace = mutableListOf<ChainStep>()
        var consumed: SockResult? = null

        for ((subscriber, activity) in rank(chain.subscribers, invocation)) {
            if (consumed != null && chain.command.chainMode == ChainMode.FIRST_CONSUMER) break
            val sock = subscriber.sock

            when (val attempt = run(sock, invocation)) {
                is Attempt.Threw -> {
                    // A broken Sock must never block the chain.
                    health.degrade(sock.id, attempt.cause.toString())
                    log.warn("${sock.id} threw on ${invocation.commandId}", attempt.cause)
                    trace += step(sock, activity, StepOutcome.FAILED, attempt.cause.toString())
                }

                is Attempt.TimedOut -> {
                    health.degrade(sock.id, "Zeitüberschreitung")
                    log.warn("${sock.id} timed out on ${invocation.commandId}")
                    trace += step(sock, activity, StepOutcome.TIMED_OUT)
                }

                is Attempt.Ok ->
                    if (attempt.result is SockResult.NotForMe) {
                        trace += step(sock, activity, StepOutcome.PASSED)
                    } else {
                        trace += step(sock, activity, StepOutcome.CONSUMED)
                        if (consumed == null) consumed = attempt.result
                    }
            }
        }

        log.debug("${invocation.commandId} -> ${trace.joinToString { "${it.sockId}:${it.activity}:${it.outcome}" }}")
        return DispatchOutcome(consumed ?: chain.command.unconsumedResponse, trace)
    }

    /** ACTIVE before IDLE before INACTIVE; then declared priority; then registration order. */
    private fun rank(
        subscribers: List<Subscriber>,
        invocation: CommandInvocation,
    ): List<Pair<Subscriber, SockActivity>> =
        subscribers
            .map { it to it.sock.activityFor(invocation) }
            .sortedWith(
                compareByDescending<Pair<Subscriber, SockActivity>> { it.second.rank }
                    .thenByDescending { it.first.subscription.priority }
                    .thenBy { it.first.order },
            )

    private suspend fun run(sock: Sock, invocation: CommandInvocation): Attempt = try {
        Attempt.Ok(withTimeout(timeout) { sock.handle(invocation) })
    } catch (e: TimeoutCancellationException) {
        Attempt.TimedOut
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Attempt.Threw(e)
    }

    private fun step(sock: Sock, activity: SockActivity?, outcome: StepOutcome, detail: String? = null) =
        ChainStep(sock.id, activity, outcome, detail)

    private sealed interface Attempt {
        data class Ok(val result: SockResult) : Attempt

        data class Threw(val cause: Exception) : Attempt

        data object TimedOut : Attempt
    }

    companion object {
        val DEFAULT_TIMEOUT: Duration = 5.seconds
    }
}
