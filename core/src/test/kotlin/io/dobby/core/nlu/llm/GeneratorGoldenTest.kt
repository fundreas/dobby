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
 * The generated grammar and prompt, pinned byte for byte.
 *
 * A golden file is the right shape for these two because the failure mode is *drift*: nobody
 * intends to change the system prompt, and a diff in CI is the only thing that makes an
 * accidental change visible before the model's behaviour does. When a change is deliberate, the
 * new file is the review.
 *
 * Regenerate with the dump in the commit message, or by reading the assertion failure — the
 * whole file is in it.
 */
class GeneratorGoldenTest {

    private val registry = SockRegistry.buildOrThrow(SpecFixtures.all())
    private val catalog = Tier2Catalog.of(registry)
    private val introspection = Introspection(registry)

    @Test
    fun `the grammar is what it was`() {
        assertEquals(golden("grammar.gbnf"), GrammarGenerator.generate(catalog))
    }

    @Test
    fun `the prompt is what it was, scaffold and empty think block included`() {
        assertEquals(golden("prompt.txt"), PromptGenerator.prompt(catalog, introspection, "mach mal was"))
    }

    @Test
    fun `both are deterministic under a shuffled sock list`() {
        // Nothing may iterate a map. A golden file over a generator with an unstable order
        // tests the sort, not the generator — and the KV cache fingerprint would change on
        // every process start.
        val grammars = mutableSetOf<String>()
        val prompts = mutableSetOf<String>()
        val fingerprints = mutableSetOf<String>()
        repeat(20) { seed ->
            val shuffled = SockRegistry.buildOrThrow(SpecFixtures.all().shuffled(java.util.Random(seed.toLong())))
            val program = Tier2Program.of(shuffled)
            grammars += program.grammar
            prompts += program.systemPrefix
            fingerprints += program.fingerprint
        }
        assertEquals(1, grammars.size, "the grammar depends on registration order")
        assertEquals(1, prompts.size, "the prompt depends on registration order")
        assertEquals(1, fingerprints.size, "the fingerprint depends on registration order")
    }

    @Test
    fun `the scaffold cuts where the KV cache needs it to`() {
        val program = Tier2Program.of(registry)
        // n_system is the token count of exactly this string, and the utterance follows it.
        // Ending on a newline is what stops the first utterance token merging backwards into
        // the prefix — the BPE pre-tokenizer splits there.
        assertTrue(program.systemPrefix.endsWith("<|im_start|>user\n"))
        assertEquals(program.systemPrefix + "hallo" + program.assistantSuffix, program.promptFor("hallo"))
        // Thinking off, Qwen's own way: the empty block after the assistant marker.
        assertTrue(program.assistantSuffix.endsWith("<think>\n\n</think>\n\n"))
    }

    @Test
    fun `the fingerprint moves when anything the model sees moves`() {
        val base = Tier2Program.of(registry)
        assertEquals(base.fingerprint, Tier2Program.of(registry).fingerprint)

        // A different palette is a different program, and answering it from the prefilled cache
        // would answer with the wrong command list.
        val smaller = SockRegistry.buildOrThrow(listOf(SpecFixtures.clock()))
        assertTrue(base.fingerprint != Tier2Program.of(smaller).fingerprint)

        // …and so is the same palette with a different scaffold.
        assertTrue(base.fingerprint != base.copy(assistantSuffix = "<think>").fingerprint)
        assertTrue(base.fingerprint != base.copy(grammar = base.grammar + "\n").fingerprint)
    }

    @Test
    fun `every command reaches the grammar, and so does none`() {
        val grammar = GrammarGenerator.generate(catalog)
        for (command in catalog.commands) {
            assertContains(grammar, "\\\"${command.id}\\\"", message = "${command.id} has no branch")
        }
        assertContains(grammar, GrammarGenerator.NONE_RULE)
        assertContains(grammar, Tier2Json.encode(CommandInvocation.NONE).replace("\"", "\\\""))
        // A chain is one command as far as the model is concerned: one branch, not one per
        // subscriber. `registry.commands` holds each shared spec exactly once, which is what
        // makes that free rather than a deduplication step.
        assertEquals(1, Regex("cmd-shared-stop ::=").findAll(grammar).count())
    }

    @Test
    fun `rule names cannot contain an underscore`() {
        // llama.cpp's is_word_char accepts [a-zA-Z0-9-] and not '_' — verified against
        // src/llama-grammar.cpp at the pinned v0.4.1. An underscore is a load-time parse error.
        val grammar = GrammarGenerator.generate(catalog)
        for (line in grammar.lines()) {
            val name = line.substringBefore(" ::=", "")
            if (name.isEmpty() || line.startsWith("#")) continue
            assertTrue(
                name.all { it.isLetterOrDigit() || it == '-' },
                "rule name \"$name\" is not a legal GBNF name",
            )
        }
        assertTrue("clock_set_timer" !in grammar)
    }

    private fun golden(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/tier2/$name")) { "missing golden /tier2/$name" }
            .bufferedReader().readText()
}
