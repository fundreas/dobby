package io.dobby.core.nlu.llm

import io.dobby.core.registry.Introspection
import io.dobby.core.registry.SockRegistry
import io.dobby.core.registry.SpecFixtures
import io.dobby.core.sock.CommandInvocation
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The generated prompts and grammars, pinned byte for byte.
 *
 * A golden file is the right shape for these because the failure mode is *drift*: nobody
 * intends to change the system prompt, and a diff in CI is the only thing that makes an
 * accidental change visible before the model's behaviour does. When a change is deliberate, the
 * new file is the review.
 *
 * Four files now, because there are four generated artefacts: the route prompt and grammar,
 * which are one each, and the fill turns and grammars, which are one per command with params
 * and are written out in catalog order.
 */
class GeneratorGoldenTest {

    private val registry = SockRegistry.buildOrThrow(SpecFixtures.all())
    private val catalog = Tier2Catalog.of(registry)
    private val introspection = Introspection(registry)

    @Test
    fun `the route prompt is what it was`() {
        assertEquals(
            golden("route-prompt.txt"),
            PromptGenerator.routePrefix(catalog, introspection) + "<utterance>" +
                PromptGenerator.ASSISTANT_SUFFIX,
        )
    }

    @Test
    fun `the route grammar is what it was`() {
        assertEquals(golden("route-grammar.gbnf"), GrammarGenerator.route(catalog))
    }

    @Test
    fun `every fill turn is what it was`() {
        assertEquals(golden("fill-turns.txt"), renderFills(PromptGenerator.fillTurns(catalog, introspection)))
    }

    @Test
    fun `every fill grammar is what it was`() {
        assertEquals(golden("fill-grammars.gbnf"), renderFills(GrammarGenerator.fills(catalog)))
    }

    @Test
    fun `only commands with params get a fill program`() {
        val program = Tier2Program.of(registry, introspection)
        for (command in catalog.commands) {
            assertEquals(
                command.params.isNotEmpty(),
                command.id in program.fill,
                "${command.id} has ${command.params.size} params but " +
                    "${if (command.id in program.fill) "does" else "does not"} have a fill program",
            )
        }
        // The claim the whole split rests on: most commands never need the second request.
        assertTrue(
            program.fill.size < catalog.commands.size / 2,
            "${program.fill.size} of ${catalog.commands.size} commands need a fill step",
        )
    }

    @Test
    fun `everything is deterministic under a shuffled sock list`() {
        // Nothing may iterate a map. A golden file over a generator with an unstable order
        // tests the sort, not the generator — and the KV cache fingerprint would change on
        // every process start.
        val routes = mutableSetOf<String>()
        val grammars = mutableSetOf<String>()
        val fills = mutableSetOf<String>()
        val fingerprints = mutableSetOf<String>()
        repeat(20) { seed ->
            val shuffled = SockRegistry.buildOrThrow(SpecFixtures.all().shuffled(java.util.Random(seed.toLong())))
            val program = Tier2Program.of(shuffled)
            routes += program.route.systemPrefix
            grammars += program.route.grammar
            fills += program.fill.entries.joinToString("\n") { "${it.key}\n${it.value.userTurn}${it.value.grammar}" }
            fingerprints += program.fingerprint
        }
        assertEquals(1, routes.size, "the route prompt depends on registration order")
        assertEquals(1, grammars.size, "the route grammar depends on registration order")
        assertEquals(1, fills.size, "the fill programs depend on registration order")
        assertEquals(1, fingerprints.size, "the fingerprint depends on registration order")
    }

    @Test
    fun `the scaffold cuts where the KV cache needs it to`() {
        val program = Tier2Program.of(registry)
        // n_system is the token count of exactly this string, and the utterance follows it.
        // Ending on a newline is what stops the first utterance token merging backwards into
        // the prefix — the BPE pre-tokenizer splits there.
        assertTrue(program.route.systemPrefix.endsWith("<|im_start|>user\n"))
        // Thinking off, Qwen's own way: the empty block after the assistant marker, on both
        // steps, because a fill turn ends with the same scaffold.
        assertTrue(PromptGenerator.ASSISTANT_SUFFIX.endsWith("<think>\n\n</think>\n\n"))
        val fill = program.fillRequest("clock.set_timer", "weck mich")!!
        assertTrue(fill.tail.endsWith(PromptGenerator.ASSISTANT_SUFFIX))
        assertEquals(program.route.systemPrefix, fill.systemPrefix)
    }

    @Test
    fun `the fingerprint moves when anything the model sees moves`() {
        val base = Tier2Program.of(registry)
        assertEquals(base.fingerprint, Tier2Program.of(registry).fingerprint)

        // A different palette is a different program, and answering it from the prefilled cache
        // would answer with the wrong command list.
        val smaller = SockRegistry.buildOrThrow(listOf(SpecFixtures.clock()))
        assertTrue(base.fingerprint != Tier2Program.of(smaller).fingerprint)

        // …and so is the same palette with a different route prompt, grammar or fill turn. One
        // fingerprint over all of it: a changed fill turn re-prefills the route prefix once,
        // which is cheap and leaves one key to reason about instead of two.
        assertTrue(base.fingerprint != base.copy(route = base.route.copy(grammar = "root ::= \"x\"")).fingerprint)
        assertTrue(
            base.fingerprint != base.copy(
                fill = base.fill + ("clock.set_timer" to base.fill.getValue("clock.set_timer").copy(userTurn = "x")),
            ).fingerprint,
        )
    }

    @Test
    fun `every command is a route label, and so is none`() {
        val grammar = GrammarGenerator.route(catalog)
        for (command in catalog.commands) {
            assertContains(grammar, "\"${command.id}\"", message = "${command.id} is not a route label")
        }
        assertContains(grammar, "\"${CommandInvocation.NONE}\"")
        // A chain is one command as far as the model is concerned: one label, not one per
        // subscriber. `registry.commands` holds each shared spec exactly once, which is what
        // makes that free rather than a deduplication step.
        assertEquals(1, Regex(Regex.escape("\"shared.stop\"")).findAll(grammar).count())
    }

    @Test
    fun `every fill grammar can decline`() {
        // The escape. Without it the model is forced to invent a value for a command it has
        // already been told is the right one.
        for ((id, grammar) in GrammarGenerator.fills(catalog)) {
            assertContains(grammar, "\"${CommandInvocation.NONE}\"", message = "$id cannot decline")
            // …and the `"c"` head is gone, because the command is no longer in question.
            assertTrue("\\\"c\\\":" !in grammar, "$id still restates the command id")
        }
    }

    @Test
    fun `rule names cannot contain an underscore`() {
        // llama.cpp's is_word_char accepts [a-zA-Z0-9-] and not '_' — verified against the
        // pinned v0.4.1 by android/llama/tools/gbnf_check.cpp, both ways round.
        val grammars = listOf(GrammarGenerator.route(catalog)) + GrammarGenerator.fills(catalog).values
        for (grammar in grammars) {
            for (line in grammar.lines()) {
                val name = line.substringBefore(" ::=", "")
                if (name.isEmpty() || line.startsWith("#")) continue
                assertTrue(
                    name.all { it.isLetterOrDigit() || it == '-' },
                    "rule name \"$name\" is not a legal GBNF name",
                )
            }
        }
    }

    /** One file per artefact, with a header per command, so a diff names what moved. */
    private fun renderFills(byCommand: Map<String, String>): String = buildString {
        for ((id, text) in byCommand) {
            appendLine("═══ $id")
            appendLine(text.trimEnd())
            appendLine()
        }
    }.trimEnd() + "\n"

    private fun golden(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/tier2/$name")) { "missing golden /tier2/$name" }
            .bufferedReader().readText()
}
