package io.dobby.android.chat

import io.dobby.core.EngineOutcome

/**
 * The one-line trace under a Dobby answer: what the utterance resolved to, and who ran it.
 *
 * Same information the terminal harness prints under `→`, on one line because a chat bubble is
 * not a terminal. Null when there is nothing to say — an utterance nobody matched resolved to
 * nothing, and "(nothing)" under every misheard sentence is noise.
 */
fun EngineOutcome.detailLine(): String? {
    val invocation = invocation ?: return null
    val params = if (invocation.params.isEmpty()) {
        ""
    } else {
        invocation.params.entries.joinToString(", ", prefix = " (", postfix = ")") { "${it.key}=${it.value}" }
    }
    val chain = trace.joinToString(" → ") { step ->
        val activity = step.activity?.let { " $it" } ?: ""
        "${step.sockId}$activity ${step.outcome}"
    }
    return if (chain.isEmpty()) "${invocation.commandId}$params" else "${invocation.commandId}$params · $chain"
}
