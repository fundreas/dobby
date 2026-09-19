package io.dobby.android.chat

import io.dobby.core.EngineOutcome

/**
 * The one-line trace under a Dobby answer: what the utterance resolved to, and who ran it.
 *
 * Same information the terminal harness prints under `→`, on one line because a chat bubble is
 * not a terminal. Null when there is nothing to say — an utterance nobody matched resolved to
 * nothing, and "(nothing)" under every misheard sentence is noise.
 *
 * ```
 * tier2 1.8s · clock.set_timer (amount=15, unit=minuten) · clock CONSUMED
 * tier2 4.9s · none
 * tier2 5.0s · timeout
 * ```
 *
 * The Tier 2 prefix goes on the left because that is where the eye lands, and a *miss* now gets
 * a detail line whenever the model was consulted: "the model looked and said no" is the only
 * thing that tells you the tier is running at all. Keyed on `tier2 != null`, so with no resolver
 * the function still returns null for a miss and the existing test passes unchanged.
 */
fun EngineOutcome.detailLine(): String? {
    val consultation = tier2?.toString()
    val invocation = invocation ?: return consultation
    val params = if (invocation.params.isEmpty()) {
        ""
    } else {
        invocation.params.entries.joinToString(", ", prefix = " (", postfix = ")") { "${it.key}=${it.value}" }
    }
    val chain = trace.joinToString(" → ") { step ->
        val activity = step.activity?.let { " $it" } ?: ""
        "${step.sockId}$activity ${step.outcome}"
    }
    val resolution = if (chain.isEmpty()) "${invocation.commandId}$params" else "${invocation.commandId}$params · $chain"
    // A Tier 2 resolution already names the command, so the prefix replaces that half rather
    // than repeating it — the chain is what the prefix cannot know.
    return if (consultation == null) resolution else "$consultation${if (chain.isEmpty()) "" else " · $chain"}"
}
