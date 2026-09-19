package io.dobby.core.testing

import io.dobby.core.nlu.llm.Tier2Program
import io.dobby.core.nlu.llm.Tier2Resolver
import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO

/**
 * A Tier 2 resolver scripted by utterance, for every test that is not about the model.
 *
 * In test fixtures rather than in a test source set because three modules need it: `:core` for
 * the engine wiring, `:android:app` for the controller's "a resolved utterance does not buzz",
 * and the CLI's `/tier2` command, which is this class with a terminal in front of it.
 *
 * It answers with **raw text**, not with a parsed reply, so the gate and the decoder are on the
 * path in every test that uses it. A fake that returned a `CommandInvocation` would quietly skip
 * the half of Tier 2 that is actually load-bearing.
 */
class FakeTier2Resolver(
    /** Raw model output per normalized utterance. An absent key answers [default]. */
    private val replies: Map<String, String> = emptyMap(),
    /** What an unscripted utterance gets. Null means "the resolver could not run". */
    private val default: String? = """{"c":"none"}""",
    override val available: Boolean = true,
    override val unavailableReason: String? = null,
    /** How long [generate] pretends to think. Drives the timeout tests. */
    private val latency: Duration = ZERO,
) : Tier2Resolver {

    /** Every utterance this resolver was asked about, in order. */
    val asked: MutableList<String> = mutableListOf()

    /** The fingerprint of the program each call carried, for the cache-invalidation tests. */
    val fingerprints: MutableList<String> = mutableListOf()

    override suspend fun generate(utterance: String, program: Tier2Program): String? {
        asked += utterance
        fingerprints += program.fingerprint
        if (latency > ZERO) delay(latency)
        return replies[utterance] ?: default
    }
}
