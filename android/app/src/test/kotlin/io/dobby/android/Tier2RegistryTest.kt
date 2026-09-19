package io.dobby.android

import io.dobby.core.nlu.llm.GrammarGenerator
import io.dobby.core.nlu.llm.PromptGenerator
import io.dobby.core.nlu.llm.Tier2
import io.dobby.core.nlu.llm.Tier2Catalog
import io.dobby.core.nlu.llm.Tier2Json
import io.dobby.core.nlu.llm.Tier2Program
import io.dobby.core.nlu.llm.TokenEstimate
import io.dobby.core.registry.SockRegistry
import io.dobby.core.sock.CommandSpec
import io.dobby.core.sock.ParamType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tier 2 against the Socks that actually ship.
 *
 * `:core`'s own tests run against `SpecFixtures`, which is the right scope for the generators
 * but cannot see a real Sock's examples — and the examples are where the mistakes are. This is
 * the only place the whole registry and the whole generator meet, which makes it the build
 * failure for everything `Tier2Program.of` can reject.
 *
 * It lives in the app module for the same reason `DobbySocks` does: this is the one place that
 * knows which Socks exist. Everything here is derived from the registry rather than pinned by
 * name, because the registry is the thing that changes every time a Sock lands.
 */
class Tier2RegistryTest {

    private val registry = SockRegistry.buildOrThrow(DobbySocks.create().socks)

    private val catalog = Tier2Catalog.of(registry)

    @Test
    fun `the shipped registry produces a program at all`() {
        // Throws, deliberately: PromptGenerator rejects a few-shot that is not normalized, and
        // the message names the Sock, the utterance and what it should have been. On a running
        // panel the same failure is caught by Tier2Program.ofOrNull and costs Tier 2 alone —
        // this is the test that stops it getting that far.
        val program = Tier2Program.of(registry)
        assertTrue(program.grammar.isNotEmpty())
        assertTrue(program.systemPrefix.isNotEmpty())
    }

    @Test
    fun `every shipped command is reachable by the model`() {
        val program = Tier2Program.of(registry)
        for (id in registry.commands.keys) {
            assertTrue("\\\"$id\\\"" in program.grammar, "$id has no grammar branch")
            assertTrue(id in program.systemPrefix, "$id is not in the system prompt")
        }
    }

    @Test
    fun `the shipped prompt is inside the budget`() {
        val estimate = TokenEstimate.of(Tier2Program.of(registry).systemPrefix)
        assertTrue(
            estimate <= PromptGenerator.MAX_TOKENS,
            "the shipped prompt is $estimate tokens, over ${PromptGenerator.MAX_TOKENS}: " +
                "see PromptBudgetTest for the levers, in order",
        )
        val perCommand = estimate.toDouble() / registry.commands.size
        val headroom = ((PromptGenerator.MAX_TOKENS - estimate) / perCommand).toInt()
        // The plan predicted this tripwire would fire partway through M4, before the full
        // registry lands. The number below is what says whether that is still true — and a real
        // Sock costs several times what a fixture command does, so watch it per Sock.
        println(
            "shipped Tier 2 prompt: $estimate of ${PromptGenerator.MAX_TOKENS} tokens, " +
                "${registry.commands.size} commands (~${"%.0f".format(perCommand)} each), " +
                "~$headroom commands of headroom",
        )
    }

    /**
     * The decode budget, reported rather than enforced — and the reason is worth stating.
     *
     * [Tier2.MAX_REPLY_TOKENS] is derived from a decode rate nobody has measured on a 750G yet.
     * Blocking a build on a guess is blocking on a guess, so the hard version of this assertion
     * is `Tier2ModelTest` on the device, with a measured tok/s behind it. What is enforced here
     * is the one limit that does not depend on speed: a reply the decoder would throw away.
     *
     * A command listed in the output will, at its worst case, have its reply cut off by
     * `max_tokens` mid-JSON; the decoder then rejects it and the turn buzzes after the full
     * deadline. Degraded, not dangerous. The fix when it matters is to narrow the param — Qwen
     * tokenizes digits one at a time, so a four-digit operand really does cost four tokens, and
     * two of them in one reply is most of the budget.
     */
    @Test
    fun `the decode budget is reported for every shipped command`() {
        val over = catalog.commands
            .map { it.id to TokenEstimate.of(worstReply(it)) }
            .filter { (_, tokens) -> tokens > Tier2.MAX_REPLY_TOKENS }
            .sortedByDescending { it.second }

        println(
            if (over.isEmpty()) {
                "shipped Tier 2 replies: all inside Tier2.MAX_REPLY_TOKENS (${Tier2.MAX_REPLY_TOKENS})"
            } else {
                "shipped Tier 2 replies OVER the ${Tier2.MAX_REPLY_TOKENS}-token decode budget " +
                    "at their worst case: " + over.joinToString(", ") { "${it.first} = ${it.second}" }
            },
        )

        for (command in catalog.commands) {
            assertTrue(
                worstReply(command).length <= Tier2Json.MAX_INPUT,
                "${command.id} can emit a reply the decoder drops unread",
            )
        }
    }

    @Test
    fun `none stays reserved, which is what makes the gate's none branch unambiguous`() {
        assertEquals(null, registry.commands["none"])
    }

    /** Every param filled, every text param at the cap — the worst case the grammar allows. */
    private fun worstReply(command: CommandSpec): String = Tier2Json.encode(
        command.id,
        command.params.associate { spec ->
            spec.name to when (val type = spec.type) {
                is ParamType.Integer -> 9999
                is ParamType.Text -> "a".repeat(GrammarGenerator.MAX_TEXT_CHARS)
                is ParamType.Enumeration -> type.values.maxByOrNull { it.length } ?: ""
            }
        },
    )
}
