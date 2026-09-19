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
        assertTrue(program.route.grammar.isNotEmpty())
        assertTrue(program.route.systemPrefix.isNotEmpty())
        assertTrue(program.fill.isNotEmpty(), "no command has params? check the registry")
    }

    @Test
    fun `every shipped command is a route label, and only the ones with params can be filled`() {
        val program = Tier2Program.of(registry)
        for (id in registry.commands.keys) {
            assertTrue("\"$id\"" in program.route.grammar, "$id is not a route label")
            assertTrue(id in program.route.systemPrefix, "$id is not in the route prompt")
        }
        for (command in catalog.commands) {
            assertEquals(
                command.params.isNotEmpty(),
                command.id in program.fill,
                "${command.id} has ${command.params.size} params; fill program present: " +
                    "${command.id in program.fill}",
            )
        }
    }

    @Test
    fun `the shipped route prompt is inside the budget`() {
        val program = Tier2Program.of(registry)
        val estimate = TokenEstimate.of(program.route.systemPrefix)
        assertTrue(
            estimate <= PromptGenerator.MAX_TOKENS,
            "the shipped route prompt is $estimate tokens, over ${PromptGenerator.MAX_TOKENS}: " +
                "see PromptBudgetTest for the levers, in order",
        )
        val perCommand = estimate.toDouble() / registry.commands.size
        val headroom = ((PromptGenerator.MAX_TOKENS - estimate) / perCommand).toInt()
        // This is the number the two-step split was built to move. The single-shot prompt was
        // 1426 of 1500 at eleven commands — about zero commands of headroom, with seven Socks
        // still to come.
        println(
            "shipped Tier 2 route prompt: $estimate of ${PromptGenerator.MAX_TOKENS} tokens, " +
                "${registry.commands.size} commands (~${"%.0f".format(perCommand)} each), " +
                "~$headroom commands of headroom; ${program.fill.size} need a fill step",
        )
    }

    @Test
    fun `every shipped fill turn is inside its own budget`() {
        val program = Tier2Program.of(registry)
        val sizes = program.fill.mapValues { TokenEstimate.of(it.value.userTurn) }
        val over = sizes.filterValues { it > PromptGenerator.FILL_TURN_MAX_TOKENS }
        assertEquals(
            emptyMap(),
            over,
            "these fill turns are over FILL_TURN_MAX_TOKENS = ${PromptGenerator.FILL_TURN_MAX_TOKENS}; " +
                "a fill turn is uncached prefill on the hot path, so it is the expensive one",
        )
        println("shipped Tier 2 fill turns (of ${PromptGenerator.FILL_TURN_MAX_TOKENS}): $sizes")
    }

    /**
     * The decode budget, reported rather than enforced — and the reason is worth stating.
     *
     * [Tier2.MAX_FILL_TOKENS] is derived from a decode rate nobody has measured on a 750G yet.
     * Blocking a build on a guess is blocking on a guess, so the hard version of this assertion
     * is the device suite, with a measured tok/s behind it. What is enforced here is the one
     * limit that does not depend on speed: a reply the decoder would throw away.
     *
     * Dropping the `{"c":"…"}` head bought about ten tokens per reply, which is most of why
     * this is comfortable now and was not before.
     */
    @Test
    fun `the decode budget is reported for every shipped command with params`() {
        val over = catalog.withParams
            .map { it.id to TokenEstimate.of(worstFill(it)) }
            .filter { (_, tokens) -> tokens > Tier2.MAX_FILL_TOKENS }
            .sortedByDescending { it.second }

        println(
            if (over.isEmpty()) {
                "shipped Tier 2 fill replies: all inside Tier2.MAX_FILL_TOKENS (${Tier2.MAX_FILL_TOKENS})"
            } else {
                "shipped Tier 2 fill replies OVER the ${Tier2.MAX_FILL_TOKENS}-token decode budget " +
                    "at their worst case: " + over.joinToString(", ") { "${it.first} = ${it.second}" }
            },
        )

        for (command in catalog.withParams) {
            assertTrue(
                worstFill(command).length <= Tier2Json.MAX_INPUT,
                "${command.id} can emit a reply the decoder drops unread",
            )
        }
    }

    @Test
    fun `none stays reserved, which is what makes both steps' none unambiguous`() {
        assertEquals(null, registry.commands["none"])
    }

    @Test
    fun `every shipped command has held-out cases to be measured against`() {
        // Not an assertion that they exist yet — the Socks still to come will arrive without
        // them — but a printed reminder of which ones the accuracy test can say nothing about.
        val without = registry.commands.values
            .filter { command -> command.examples.none { it.heldOut } }
            .map { it.id }
            .sorted()
        println(
            if (without.isEmpty()) {
                "every shipped command has held-out accuracy cases"
            } else {
                "no held-out accuracy cases (Tier2AccuracyTest cannot measure these): " +
                    without.joinToString(", ")
            },
        )
    }

    /** Every param filled, every text param at the cap — the worst case the grammar allows. */
    private fun worstFill(command: CommandSpec): String = Tier2Json.encodeParams(
        command.params.associate { spec ->
            spec.name to when (val type = spec.type) {
                is ParamType.Integer -> 9999
                is ParamType.Text -> "a".repeat(GrammarGenerator.MAX_TEXT_CHARS)
                is ParamType.Enumeration -> type.values.maxByOrNull { it.length } ?: ""
            }
        },
    )
}
