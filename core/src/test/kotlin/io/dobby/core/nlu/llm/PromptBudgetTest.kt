package io.dobby.core.nlu.llm

import io.dobby.core.registry.Introspection
import io.dobby.core.registry.SockRegistry
import io.dobby.core.registry.SpecFixtures
import io.dobby.core.sock.CommandSpec
import io.dobby.core.sock.ParamSpec
import io.dobby.core.sock.ParamType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Four tripwires that fail the JVM build rather than the device.
 *
 * Each prompt has to fit somewhere and each reply has to fit in the time it is given, and all
 * four are functions of how many Socks are registered. None of them is something to discover on
 * a phone: by then the Sock is written, the spec is merged, and the cheap fix — one fewer
 * example line — is three reviews ago.
 *
 * The two-step split exists because the first of these was about to fire. It is worth keeping
 * the number it prints in view.
 */
class PromptBudgetTest {

    private val registry = SockRegistry.buildOrThrow(SpecFixtures.all())
    private val catalog = Tier2Catalog.of(registry)
    private val introspection = Introspection(registry)

    @Test
    fun `the route prompt fits the budget`() {
        val prompt = PromptGenerator.routePrefix(catalog, introspection)
        val estimate = TokenEstimate.of(prompt)
        val perCommand = estimate / catalog.commands.size
        assertTrue(
            estimate <= PromptGenerator.MAX_TOKENS,
            """
            The Tier 2 route prompt is $estimate estimated tokens, over the
            ${PromptGenerator.MAX_TOKENS} budget (dobby-plan.md:420), at ${catalog.commands.size}
            commands (~$perCommand tokens each).

            The levers, in order:
              1. One example line per command instead of ${PromptGenerator.EXAMPLES_PER_COMMAND}
                 (PromptGenerator.EXAMPLES_PER_COMMAND).
              2. Shorten the CommandSpec descriptions — they are one German line each and some
                 of them are two.
              3. Shard the route by Sock: pick an area, then a command. That is a third step and
                 a third prompt, and it should not be built before the accuracy test says the
                 two-step split is working.

            Do not raise MAX_TOKENS. It is the context window minus the room the utterance,
            the reply and the KV cache need, not a preference.
            """.trimIndent(),
        )
    }

    @Test
    fun `there is headroom, and the number is written down`() {
        val estimate = TokenEstimate.of(PromptGenerator.routePrefix(catalog, introspection))
        val perCommand = estimate.toDouble() / catalog.commands.size
        val headroom = ((PromptGenerator.MAX_TOKENS - estimate) / perCommand).toInt()
        assertTrue(headroom > 0, "no headroom left at ${catalog.commands.size} commands")
        println(
            "Tier 2 route prompt: $estimate estimated tokens at ${catalog.commands.size} commands " +
                "(~${"%.0f".format(perCommand)}/command), ~$headroom commands of headroom",
        )
    }

    /**
     * A fill turn is uncached prefill on the hot path, so it gets its own, much smaller budget.
     *
     * The route prefix is prefilled once per process and truncated back to; this is re-decoded
     * on every request that routes to the command. That difference is the whole reason there
     * are two budgets rather than one.
     */
    @Test
    fun `every fill turn fits its own budget`() {
        val over = PromptGenerator.fillTurns(catalog, introspection)
            .mapValues { TokenEstimate.of(it.value) }
            .filterValues { it > PromptGenerator.FILL_TURN_MAX_TOKENS }
        assertEquals(
            emptyMap(),
            over,
            "these fill turns are over FILL_TURN_MAX_TOKENS = ${PromptGenerator.FILL_TURN_MAX_TOKENS}. " +
                "The levers: one example instead of ${PromptGenerator.EXAMPLES_PER_COMMAND}, a shorter " +
                "description, shorter enum values",
        )
        val sizes = PromptGenerator.fillTurns(catalog, introspection).mapValues { TokenEstimate.of(it.value) }
        println("Tier 2 fill turns (of ${PromptGenerator.FILL_TURN_MAX_TOKENS}): $sizes")
    }

    @Test
    fun `the longest route label fits MAX_ROUTE_TOKENS`() {
        val program = Tier2Program.of(registry, introspection)
        val cap = Tier2.maxRouteTokens(program.route.labels)
        val longest = program.route.labels.maxBy { TokenEstimate.of(it) }
        assertTrue(
            TokenEstimate.of(longest) <= cap,
            "the longest label \"$longest\" does not fit its own derived cap",
        )
        // Derived from the catalog rather than fixed, so a Sock with a long id cannot be
        // silently truncated into a label that does not exist.
        println("Tier 2 route cap: $cap tokens, longest label \"$longest\"")
    }

    /**
     * `max_tokens` for a fill is derived from the deadline, and the grammar must fit inside it.
     *
     * This is the assertion that a Sock with three optional params fails the build instead of
     * being truncated mid-JSON at run time — a truncated reply is an unparsable one, and the
     * person waited the full five seconds to be told no.
     */
    @Test
    fun `the longest params-only reply fits MAX_FILL_TOKENS`() {
        if (catalog.withParams.isEmpty()) return
        val byCommand = catalog.withParams.associate { it.id to TokenEstimate.of(longestFill(it)) }
        val longest = byCommand.maxBy { it.value }
        assertTrue(
            longest.value <= Tier2.MAX_FILL_TOKENS,
            "the longest grammar-legal fill reply is ${longest.value} estimated tokens, over " +
                "Tier2.MAX_FILL_TOKENS = ${Tier2.MAX_FILL_TOKENS}: ${longest.key}. Narrow a param — " +
                "Qwen tokenizes digits one at a time, so a four-digit operand costs four tokens",
        )
        println("Tier 2 fill replies (of ${Tier2.MAX_FILL_TOKENS}): $byCommand")
    }

    @Test
    fun `FILL_TURN_MAX_TOKENS is the same allowance, spent on prefill`() {
        // Both constants spend from the 1.5 s the deadline sets aside for the fill step's
        // prefill. Writing the arithmetic down is what stops one of them drifting into a
        // number somebody liked — and the prefill rate is the one factor nobody has measured.
        val fillPrefillAllowanceSeconds = 1.5
        val pessimisticPrefillTokensPerSecond = 120
        assertEquals(
            (fillPrefillAllowanceSeconds * pessimisticPrefillTokensPerSecond).toInt(),
            PromptGenerator.FILL_TURN_MAX_TOKENS,
            "FILL_TURN_MAX_TOKENS must stay derived from the fill prefill allowance",
        )
    }

    @Test
    fun `MAX_FILL_TOKENS is the deadline, not a number somebody liked`() {
        val routeAllowanceSeconds = 1.0
        val fillPrefillAllowanceSeconds = 1.5
        val pessimisticTokensPerSecond = 10
        val decodeSeconds =
            Tier2.DEFAULT_TIMEOUT.inWholeSeconds - routeAllowanceSeconds - fillPrefillAllowanceSeconds
        assertEquals(
            (decodeSeconds * pessimisticTokensPerSecond).toInt(),
            Tier2.MAX_FILL_TOKENS,
            "MAX_FILL_TOKENS must stay derived from DEFAULT_TIMEOUT",
        )
    }

    /** Every param filled, every text param at the cap — the worst case the grammar allows. */
    private fun longestFill(command: CommandSpec): String =
        Tier2Json.encodeParams(command.params.associate { spec -> spec.name to widest(spec) })

    private fun widest(spec: ParamSpec): Any = when (val type = spec.type) {
        is ParamType.Integer -> 9999
        is ParamType.Text -> "a".repeat(GrammarGenerator.MAX_TEXT_CHARS)
        is ParamType.Enumeration -> type.values.maxByOrNull { it.length } ?: ""
    }
}
