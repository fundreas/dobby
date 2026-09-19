package io.dobby.core

import io.dobby.core.dispatch.ChainStep
import io.dobby.core.dispatch.DispatchOutcome
import io.dobby.core.dispatch.Dispatcher
import io.dobby.core.nlu.Normalizer
import io.dobby.core.nlu.llm.Tier2
import io.dobby.core.nlu.llm.Tier2Outcome
import io.dobby.core.nlu.llm.Tier2Trace
import io.dobby.core.registry.Palette
import io.dobby.core.registry.PaletteEntry
import io.dobby.core.registry.SockRegistry
import io.dobby.core.registry.compileScopedPalette
import io.dobby.core.sock.CommandInvocation
import io.dobby.core.sock.ExclusiveCommandSpec
import io.dobby.core.sock.FollowUp
import io.dobby.core.sock.Sock
import io.dobby.core.sock.SockContext
import io.dobby.core.sock.SockLog
import io.dobby.core.sock.SockResult

/** Which tier resolved an utterance, if any. */
enum class Tier {
    /** A template matched. The overwhelmingly common case, and the only free one. */
    TEMPLATE,

    /** No template matched and the local model named a command. */
    MODEL,

    /** Nobody claimed it. */
    NONE,
}

/** Everything that happened to one utterance. The CLI and the debug dashboard both render this. */
data class EngineOutcome(
    val raw: String,
    val normalized: String,
    val invocation: CommandInvocation?,
    val matched: PaletteEntry?,
    val result: SockResult,
    val trace: List<ChainStep> = emptyList(),
    /** Defaulted so every existing construction and test compiles untouched. */
    val tier: Tier = if (invocation != null) Tier.TEMPLATE else Tier.NONE,
    /** Present whenever Tier 2 was consulted, whatever it said. */
    val tier2: Tier2Trace? = null,
) {
    val wasUnderstood: Boolean get() = invocation != null
}

/**
 * An utterance Tier 1 could not match, and what happened to it next.
 *
 * This is the flywheel's raw material: `toString()` is
 * `weck mich in 20 minuten → clock.set_timer (amount=20, unit=minuten) 1842ms`, which is enough
 * to promote a phrasing into a template by hand, in the owning Sock's spec, without re-deriving
 * what the person meant.
 */
data class Fallthrough(
    /** The normalized utterance — the same string both tiers were given. */
    val utterance: String,
    /** Null when there was no Tier 2 to consult. */
    val tier2: Tier2Trace?,
) {
    override fun toString(): String {
        val trace = tier2 ?: return utterance
        val latency = trace.latency.inWholeMilliseconds
        return when (val outcome = trace.outcome) {
            is Tier2Outcome.Resolved -> {
                val params = outcome.invocation.params
                val rendered = if (params.isEmpty()) {
                    ""
                } else {
                    params.entries.joinToString(", ", prefix = " (", postfix = ")") { "${it.key}=${it.value}" }
                }
                "$utterance → ${outcome.invocation.commandId}$rendered ${latency}ms"
            }

            Tier2Outcome.NoCommand -> "$utterance → none ${latency}ms"
            is Tier2Outcome.Rejected -> "$utterance → rejected (${outcome.reason}) ${latency}ms"
            is Tier2Outcome.Unavailable -> "$utterance → tier2 unavailable (${outcome.reason})"
            Tier2Outcome.Timeout -> "$utterance → timeout ${latency}ms"
        }
    }
}

/**
 * Wires normalize → match → dispatch.
 *
 * This is the whole of "raw Dobby": everything above the microphone and below the Socks.
 * It has no Android dependency and no knowledge of any command.
 */
class DobbyEngine(
    val registry: SockRegistry,
    private val dispatcher: Dispatcher = Dispatcher(registry),
    /** Feeds the flywheel: utterances Tier 1 could not match, and what Tier 2 made of them. */
    private val onFallthrough: (Fallthrough) -> Unit = {},
    private val log: SockLog = SockLog.NONE,
    /**
     * The local model, or null.
     *
     * Null is today's behaviour byte for byte: [handle] falls straight through to the
     * not-understood path, and nothing on this class allocates, waits or logs differently.
     */
    private val tier2: Tier2? = null,
) {
    /**
     * The question currently holding the floor, if any.
     *
     * It lives here rather than in the Android controller so that the terminal harness and the
     * typed path get a dialogue turn for free — a whole conversation is then testable with no
     * device, which is the only way the awkward cases ever get exercised. Not synchronised:
     * dispatch is single-file by construction (one microphone, one turn at a time), and a
     * second concurrent utterance would interleave speech long before it raced on this field.
     */
    private var pending: PendingAsk? = null

    /** Whether core is holding the floor for an answer. The CLI prompts differently for it. */
    val awaitingAnswer: Boolean get() = pending != null

    suspend fun start(ctx: SockContext) {
        registry.socks.forEach { it.onStart(ctx) }
    }

    suspend fun stop() {
        endTurn()
        registry.socks.forEach { it.onStop() }
    }

    /**
     * The turn is over, whatever ended it: an answer, a silence, a Sock that gave up.
     *
     * A pending question dies here and nowhere else. It must never outlive the turn it was
     * asked in — otherwise the wake word plus "ja" three minutes later fires whatever was
     * half-built when the conversation was abandoned, which is exactly the class of surprise a
     * wall panel in a kitchen must not have.
     */
    suspend fun endTurn() {
        val ask = pending ?: return
        pending = null
        cancel(ask.sock, ask.token)
    }

    /**
     * @param useTier2 whether the local model may be consulted for this utterance.
     *   False for the second and third round of a turn: after a buzz the person is deliberately
     *   rephrasing toward a command they believe exists, which is Tier 1's best case and not
     *   Tier 2's — and a second and third five-second wait is what turns "it is thinking" into
     *   "it is stuck".
     */
    suspend fun handle(raw: String, useTier2: Boolean = true): EngineOutcome {
        val tokens = Normalizer.tokenize(raw)
        val normalized = tokens.joinToString(" ")

        // A question is open: its own templates get first refusal on this utterance, ahead of
        // every command Dobby knows. That is the whole point of a scoped palette — "minuten"
        // means the answer here, not whatever "minuten" might mean in the global one.
        val ask = pending
        if (ask != null) {
            val answer = ask.palette.match(tokens)
            if (answer != null) {
                pending = null
                val invocation = answer.invocation.copy(answering = ask.token)
                return finish(
                    raw = raw,
                    normalized = normalized,
                    invocation = invocation,
                    matched = answer.entry,
                    outcome = dispatcher.dispatchAnswer(ask.sock, invocation),
                    depth = ask.depth,
                )
            }
        }

        val match = registry.palette.match(tokens)
            ?: return fallThrough(raw, normalized, tokens, ask, useTier2)

        // Something else entirely was said while a question was open — "stopp", "lauter", "wie
        // spät ist es". It wins, and the question is abandoned rather than answered. Without
        // this the panel would be a trap: a person stuck inside "Meinst du …?" could say
        // nothing that got them out of it.
        if (ask != null) {
            pending = null
            cancel(ask.sock, ask.token)
        }

        return finish(
            raw = raw,
            normalized = normalized,
            invocation = match.invocation,
            matched = match.entry,
            outcome = dispatcher.dispatch(match.invocation),
            depth = 0,
        )
    }

    /**
     * No template matched: ask the local model, then give up.
     *
     * Tier 2 lives *inside* `handle` rather than beside it, and that placement is the whole
     * reason the Android side needs no change to buzz at the right moment: `respondTo` already
     * buzzes on `!outcome.wasUnderstood`, so with Tier 2 in here `wasUnderstood` is already the
     * post-Tier-2 answer. Buzzing on the Tier 1 miss would tell somebody to repeat themselves
     * while the command they actually gave is still being resolved — they repeat it, Tier 2
     * finishes the first one and sets a timer, Tier 1 matches the repeat and sets a second. A
     * false "I didn't get it" is strictly worse than a slow "I got it", because the false one
     * causes an action nobody asked for.
     */
    private suspend fun fallThrough(
        raw: String,
        normalized: String,
        tokens: List<String>,
        ask: PendingAsk?,
        useTier2: Boolean,
    ): EngineOutcome {
        val trace = if (useTier2 && tokens.isNotEmpty()) tier2?.resolve(normalized) else null
        onFallthrough(Fallthrough(normalized, trace))

        val resolved = (trace?.outcome as? Tier2Outcome.Resolved)?.invocation
        if (resolved != null) {
            // A question was open and the model named something else entirely. Same rule as a
            // Tier 1 match: it wins, and the question is abandoned rather than answered.
            if (ask != null) {
                pending = null
                cancel(ask.sock, ask.token)
            }
            return finish(
                raw = raw,
                normalized = normalized,
                invocation = resolved,
                matched = null,
                outcome = dispatcher.dispatch(resolved),
                depth = 0,
                tier = Tier.MODEL,
                tier2 = trace,
            )
        }

        // None, timeout, rejected, unavailable: all identical to today's not-understood path.
        // The question survives an utterance nobody could place. The person is most likely
        // still answering it, just not in words the scoped palette knows, and the caller's
        // retry loop gives them another go at the same question.
        return EngineOutcome(
            raw = raw,
            normalized = normalized,
            invocation = null,
            matched = null,
            result = SockResult.Spoken(NOT_UNDERSTOOD),
            tier = Tier.NONE,
            tier2 = trace,
        )
    }

    /**
     * Turns a dispatch into an outcome, arming the floor if the Sock asked something.
     *
     * [depth] is how many questions this turn has already asked — 0 for a fresh command, and
     * the previous question's depth when this dispatch was itself an answer.
     */
    @Suppress("LongParameterList")
    private suspend fun finish(
        raw: String,
        normalized: String,
        invocation: CommandInvocation,
        matched: PaletteEntry?,
        outcome: DispatchOutcome,
        depth: Int,
        tier: Tier = Tier.TEMPLATE,
        tier2: Tier2Trace? = null,
    ): EngineOutcome {
        val result = outcome.result
        val spoken = if (result is SockResult.Asked) {
            arm(result, outcome.consumedBy?.let { id -> registry.socks.firstOrNull { it.id == id } }, depth)
        } else {
            result
        }
        return EngineOutcome(
            raw = raw,
            normalized = normalized,
            invocation = invocation,
            matched = matched,
            result = spoken,
            trace = outcome.trace,
            tier = tier,
            tier2 = tier2,
        )
    }

    /**
     * Compiles the follow-up palette and takes the floor, or degrades to a plain statement.
     *
     * A question that cannot hold the floor is still a question worth asking out loud — the
     * person hears it and can say the whole thing again — so every failure here answers with
     * the same sentence, minus the microphone. What it must not do is leave the asking Sock
     * holding state for an answer that will never arrive, hence the cancellation on each path.
     */
    private suspend fun arm(asked: SockResult.Asked, asker: Sock?, depth: Int): SockResult {
        val follow = asked.follow
        val refusal = refuse(follow, asker, depth)
        if (refusal != null) {
            log.warn("follow-up on '${follow.commandId}' will not hold the floor: $refusal")
            asker?.let { cancel(it, follow.token) }
            return SockResult.Spoken(asked.text)
        }

        val spec = ExclusiveCommandSpec(
            id = follow.commandId,
            templates = follow.templates,
            description = "Antwort auf eine Rückfrage.",
            params = follow.params,
        )
        val build = compileScopedPalette(spec)
        val palette = build.palette
        if (palette == null) {
            build.errors.forEach { log.warn("follow-up on '${follow.commandId}': $it") }
            asker?.let { cancel(it, follow.token) }
            return SockResult.Spoken(asked.text)
        }

        pending = PendingAsk(
            sock = checkNotNull(asker) { "refuse() already rejected a question with no asker" },
            palette = palette,
            token = follow.token,
            depth = depth + 1,
        )
        // Handed back unchanged: the floor is taken, and the caller has to know that, because
        // it owns the microphone. A refusal above answers with [SockResult.Spoken] instead,
        // which is precisely the difference between a question and a sentence that ends a turn.
        return asked
    }

    /** Why this question may not hold the floor, or null if it may. */
    private fun refuse(follow: FollowUp, asker: Sock?, depth: Int): String? = when {
        asker == null -> "no Sock consumed the invocation that produced it"

        // The answer is routed straight back to the asker, so a command id belonging to
        // somebody else would deliver it to a Sock that never asked anything.
        !follow.commandId.startsWith("${asker.id}.") ->
            "'${follow.commandId}' is not a command of '${asker.id}'"

        depth + 1 > MAX_ASK_DEPTH ->
            "$MAX_ASK_DEPTH questions is already more than a wall panel should ask in one turn"

        else -> null
    }

    /**
     * Tells a Sock its question came to nothing.
     *
     * Guarded, because this runs on the way out of a turn: a Sock that throws while tidying up
     * must not take the turn — or the engine's own state — down with it.
     */
    private suspend fun cancel(sock: Sock, token: String) {
        try {
            sock.onAskCancelled(token)
        } catch (e: Exception) {
            log.warn("${sock.id} threw cancelling ask '$token'", e)
        }
    }

    /** One open question: who asked it, what it can hear, and what it is holding. */
    private class PendingAsk(
        val sock: Sock,
        val palette: Palette,
        val token: String,
        /** How many questions this turn has asked, this one included. */
        val depth: Int,
    )

    companion object {
        const val NOT_UNDERSTOOD: String = "Das habe ich nicht verstanden."

        /**
         * How many questions one turn may ask before core stops playing along.
         *
         * A question that follows an answer is legitimate — "zehn was?" then "welcher Timer?" —
         * but a Sock that keeps asking is a Sock with a bug, and the failure mode is a panel
         * interrogating a kitchen. Two is enough for every real case and short enough that the
         * buggy one is over in seconds.
         */
        const val MAX_ASK_DEPTH: Int = 2
    }
}
