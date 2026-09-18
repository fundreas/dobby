package io.dobby.core

import io.dobby.core.dispatch.ChainStep
import io.dobby.core.dispatch.Dispatcher
import io.dobby.core.nlu.Normalizer
import io.dobby.core.registry.PaletteEntry
import io.dobby.core.registry.SockRegistry
import io.dobby.core.sock.CommandInvocation
import io.dobby.core.sock.SockContext
import io.dobby.core.sock.SockResult

/** Everything that happened to one utterance. The CLI and the debug dashboard both render this. */
data class EngineOutcome(
    val raw: String,
    val normalized: String,
    val invocation: CommandInvocation?,
    val matched: PaletteEntry?,
    val result: SockResult,
    val trace: List<ChainStep> = emptyList(),
) {
    val wasUnderstood: Boolean get() = invocation != null
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
    /** Feeds the Tier 2 flywheel: utterances Tier 1 could not match. */
    private val onFallthrough: (String) -> Unit = {},
) {
    suspend fun start(ctx: SockContext) {
        registry.socks.forEach { it.onStart(ctx) }
    }

    suspend fun stop() {
        registry.socks.forEach { it.onStop() }
    }

    suspend fun handle(raw: String): EngineOutcome {
        val tokens = Normalizer.tokenize(raw)
        val normalized = tokens.joinToString(" ")
        val match = registry.palette.match(tokens)
            ?: run {
                onFallthrough(normalized)
                return EngineOutcome(
                    raw = raw,
                    normalized = normalized,
                    invocation = null,
                    matched = null,
                    // Tier 2 (the local LLM) slots in here in M6. Until then, unmatched is final.
                    result = SockResult.Spoken(NOT_UNDERSTOOD),
                )
            }

        val outcome = dispatcher.dispatch(match.invocation)
        return EngineOutcome(
            raw = raw,
            normalized = normalized,
            invocation = match.invocation,
            matched = match.entry,
            result = outcome.result,
            trace = outcome.trace,
        )
    }

    companion object {
        const val NOT_UNDERSTOOD: String = "Das habe ich nicht verstanden."
    }
}
