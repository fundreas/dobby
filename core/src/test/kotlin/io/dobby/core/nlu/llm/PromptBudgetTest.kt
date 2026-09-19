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
 * Two tripwires that fail the JVM build rather than the device.
 *
 * The prompt has to fit in the context window and the reply has to fit in the time budget, and
 * both are functions of how many Socks are registered. Neither is something to discover on a
 * phone: by then the Sock is written, the spec is merged, and the cheap fix — one fewer example
 * line — is three reviews ago.
 */
class PromptBudgetTest {

    private val registry = SockRegistry.buildOrThrow(SpecFixtures.all())
    private val catalog = Tier2Catalog.of(registry)
    private val introspection = Introspection(registry)

    @Test
    fun `the system prompt fits the budget`() {
        val prompt = PromptGenerator.systemPrefix(catalog, introspection)
        val estimate = TokenEstimate.of(prompt)
        val perCommand = estimate / catalog.commands.size
        assertTrue(
            estimate <= PromptGenerator.MAX_TOKENS,
            """
            The Tier 2 system prompt is $estimate estimated tokens, over the
            ${PromptGenerator.MAX_TOKENS} budget (dobby-plan.md:420), at ${catalog.commands.size}
            commands (~$perCommand tokens each).

            The levers, in order:
              1. One example line per command instead of ${PromptGenerator.EXAMPLES_PER_COMMAND}
                 (PromptGenerator.EXAMPLES_PER_COMMAND).
              2. Shorten the CommandSpec descriptions — they are one German line each and some
                 of them are two.
              3. Shard the prompt by Sock, or go two-step: pick an area, then a command.

            Do not raise MAX_TOKENS. It is the context window minus the room the utterance,
            the reply and the KV cache need, not a preference.
            """.trimIndent(),
        )
    }

    @Test
    fun `there is headroom, and the number is written down`() {
        // Not an assertion about the future so much as a record of the present, so the next
        // person knows how much room they have before doing the arithmetic themselves.
        val estimate = TokenEstimate.of(PromptGenerator.systemPrefix(catalog, introspection))
        val perCommand = estimate.toDouble() / catalog.commands.size
        val headroom = ((PromptGenerator.MAX_TOKENS - estimate) / perCommand).toInt()
        assertTrue(headroom > 0, "no headroom left at ${catalog.commands.size} commands")
        println(
            "Tier 2 prompt: $estimate estimated tokens at ${catalog.commands.size} commands " +
                "(~${"%.0f".format(perCommand)}/command), ~$headroom commands of headroom",
        )
    }

    /**
     * `max_tokens` is derived from the deadline, and the grammar must fit inside it.
     *
     * `(5 s − 1 s of prefill allowance) × 10 tok/s` = 40. This is the assertion that a Sock with
     * a long id and three optional text params fails the build instead of being truncated
     * mid-JSON at run time — a truncated reply is an unparsable one, and the person waited the
     * full five seconds to be told no.
     */
    @Test
    fun `the longest reply the grammar admits fits in MAX_REPLY_TOKENS`() {
        val longest = catalog.commands.maxOf { TokenEstimate.of(longestReply(it)) }
        assertTrue(
            longest <= Tier2.MAX_REPLY_TOKENS,
            "the longest grammar-legal reply is $longest estimated tokens, over " +
                "Tier2.MAX_REPLY_TOKENS = ${Tier2.MAX_REPLY_TOKENS}: " +
                catalog.commands.maxBy { TokenEstimate.of(longestReply(it)) }.id,
        )
        println("Tier 2 longest grammar-legal reply: $longest estimated tokens")
    }

    @Test
    fun `MAX_REPLY_TOKENS is the deadline, not a number somebody liked`() {
        val prefillAllowanceSeconds = 1
        val pessimisticTokensPerSecond = 10
        val decodeSeconds = Tier2.DEFAULT_TIMEOUT.inWholeSeconds - prefillAllowanceSeconds
        assertEquals(
            (decodeSeconds * pessimisticTokensPerSecond).toInt(),
            Tier2.MAX_REPLY_TOKENS,
            "MAX_REPLY_TOKENS must stay derived from DEFAULT_TIMEOUT",
        )
    }

    /** Every param filled, every text param at the cap — the worst case the grammar allows. */
    private fun longestReply(command: CommandSpec): String {
        val params = command.params.associate { spec -> spec.name to widest(spec) }
        return Tier2Json.encode(command.id, params)
    }

    private fun widest(spec: ParamSpec): Any = when (val type = spec.type) {
        is ParamType.Integer -> 9999
        is ParamType.Text -> "a".repeat(GrammarGenerator.MAX_TEXT_CHARS)
        is ParamType.Enumeration -> type.values.maxByOrNull { it.length } ?: ""
    }
}
