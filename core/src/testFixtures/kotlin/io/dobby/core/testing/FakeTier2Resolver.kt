package io.dobby.core.testing

import io.dobby.core.nlu.llm.Step
import io.dobby.core.nlu.llm.Tier2Request
import io.dobby.core.nlu.llm.Tier2Resolver
import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO

/**
 * A Tier 2 resolver scripted per step, for every test that is not about the model.
 *
 * In test fixtures rather than in a test source set because three modules need it: `:core` for
 * the engine wiring, `:android:app` for the controller's "a resolved utterance does not buzz",
 * and the CLI's `/tier2`, which is this idea with a terminal in front of it.
 *
 * It answers with **raw text**, not with a parsed reply, so the decoder and the gate are on the
 * path in every test that uses it. A fake that returned a `CommandInvocation` would quietly
 * skip the half of Tier 2 that is actually load-bearing.
 *
 * [requests] records every request, which is how a test asserts the thing the two-step split
 * is for: that a zero-param command *never asked the second question*.
 */
class FakeTier2Resolver(
    /** Route answers by normalized utterance: a bare command id, or `none`. */
    private val routes: Map<String, String> = emptyMap(),
    /** Fill answers by normalized utterance: a params-only JSON object, or `none`. */
    private val fills: Map<String, String> = emptyMap(),
    /** What an unscripted route gets. Null means "the resolver could not run". */
    private val defaultRoute: String? = "none",
    /** What an unscripted fill gets. Null means "the resolver could not run". */
    private val defaultFill: String? = "none",
    override val available: Boolean = true,
    override val unavailableReason: String? = null,
    /** How long each step pretends to think. Drives the timeout tests. */
    private val latency: Duration = ZERO,
) : Tier2Resolver {

    /** Every request, in order. Assert on `.step` to prove a step was or was not taken. */
    val requests: MutableList<Tier2Request> = mutableListOf()

    /** Utterances the route step was asked about. */
    val routed: List<String> get() = requests.filter { it.step == Step.ROUTE }.map { it.utterance }

    /** Command ids the fill step was asked about. Empty is the assertion the split exists for. */
    val filled: List<String> get() = requests.mapNotNull { if (it.step == Step.FILL) it.commandId else null }

    override suspend fun generate(request: Tier2Request): String? {
        requests += request
        if (latency > ZERO) delay(latency)
        return when (request.step) {
            Step.ROUTE -> routes[request.utterance] ?: defaultRoute
            Step.FILL -> fills[request.utterance] ?: defaultFill
        }
    }
}
