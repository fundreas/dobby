package io.dobby.core.nlu.llm

import io.dobby.core.nlu.Normalizer
import io.dobby.core.registry.Introspection
import io.dobby.core.sock.CommandInvocation
import io.dobby.core.sock.CommandSpec
import io.dobby.core.sock.Example
import io.dobby.core.sock.ParamSpec
import io.dobby.core.sock.ParamType

/**
 * Turns the registry into the German system prompt, and wraps it in the chat scaffold.
 *
 * ### The scaffold is ours, and thinking is off
 *
 * Qwen3 is a hybrid-reasoning model: by default it writes a `<think>…</think>` scratchpad before
 * every answer, hundreds of tokens long, which is tens of seconds on an A55. Left on, the
 * grammar forbids `<think>` and forces `{`, and the model is pushed into a state it was never
 * trained on — valid JSON, degraded judgement, and nothing in the output to tell you why.
 *
 * Qwen's own off-switch is what its chat template emits for `enable_thinking=false`: an empty
 * think block after the assistant marker. So this class emits the **whole** prompt text itself,
 * in ChatML, and `llama_chat_apply_template` is never called — whatever template the GGUF
 * carries is irrelevant, which also means swapping the quantisation cannot silently change the
 * prompt.
 *
 * [Tier2Program.fingerprint] hashes the full scaffold rather than the system text alone, so a
 * change to the think block re-prefills the KV cache instead of being answered from a cache
 * built for a different prompt.
 *
 * ### Generated from structure, not from display strings
 *
 * The parameter lines are built from [ParamType] directly. [Introspection.describe] stringifies
 * an enumeration to `"enum[a|b|c]"` for human display, and re-parsing that to build a prompt is
 * exactly the coupling that breaks the day somebody puts a `|` in an enum value.
 *
 * The examples come from [Introspection.promptExamples], because *which* examples an audience
 * may see is Introspection's policy question rather than this generator's.
 */
object PromptGenerator {

    /**
     * The budget, from `dobby-plan.md`:420. `PromptBudgetTest` fails the build above it.
     *
     * Measured at today's registry: see the test, which prints the current number and the
     * headroom in commands. The tripwire is expected to fire partway through M4, *before* the
     * full ~18-command registry lands — which is a scheduling fact worth knowing early, and the
     * reason the failure message carries the three levers in order.
     */
    const val MAX_TOKENS: Int = 1500

    /** At most this many worked examples per command, paraphrases preferred. */
    const val EXAMPLES_PER_COMMAND: Int = 2

    /**
     * The system half of the scaffold, ending at the point the utterance is appended.
     *
     * Everything up to and including `<|im_start|>user\n` is the same for every request, which
     * is what makes `n_system` a stable cut for the KV cache: the BPE pre-tokenizer splits at
     * the newline, so the utterance's first token cannot merge backwards across it.
     */
    fun systemPrefix(catalog: Tier2Catalog, introspection: Introspection): String =
        "<|im_start|>system\n" + systemPrompt(catalog, introspection) + "<|im_end|>\n" +
            "<|im_start|>user\n"

    /**
     * What follows the utterance: the assistant marker and Qwen's empty think block.
     *
     * The trailing blank line is part of the off-switch, not an accident of formatting — it is
     * what Qwen's own template emits, and the model has seen that exact byte sequence in
     * training.
     */
    const val ASSISTANT_SUFFIX: String = "<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n"

    /** The complete prompt for one utterance. */
    fun prompt(catalog: Tier2Catalog, introspection: Introspection, utterance: String): String =
        systemPrefix(catalog, introspection) + utterance + ASSISTANT_SUFFIX

    /** The German system prompt: what Dobby is, what it may answer, and what it can do. */
    fun systemPrompt(catalog: Tier2Catalog, introspection: Introspection): String = buildString {
        appendLine("Du bist der Befehls-Interpreter eines Sprachpanels in einer Wohnung.")
        appendLine("Der Nutzer sagt einen Satz. Ordne ihn genau einem Befehl aus der Liste zu.")
        appendLine()
        appendLine("Regeln:")
        appendLine("- Antworte ausschließlich mit einer Zeile JSON, ohne Erklärung und ohne Text davor oder danach.")
        appendLine("- Format: ${Tier2Json.encode("befehl.name", mapOf("parameter" to "wert"))}")
        appendLine("- Nimm nur Befehle und Parameter aus der Liste. Erfinde nichts dazu.")
        appendLine("- Ein optionaler Parameter, der im Satz nicht vorkommt: null.")
        appendLine(
            "- Passt kein Befehl, oder fehlt ein Pflicht-Parameter: " +
                Tier2Json.encode(CommandInvocation.NONE) + ".",
        )
        appendLine("- Zahlen stehen bereits als Ziffern im Satz.")
        appendLine()
        appendLine("Befehle:")
        for (command in catalog.commands) {
            appendLine(describe(catalog, command))
        }
        appendLine()
        appendLine("Beispiele:")
        for (command in catalog.commands) {
            for (example in examplesFor(introspection, command)) {
                appendLine(exampleLine(command, example))
            }
        }
        // One negative, always last. A model shown only positives learns that some command is
        // always the answer, and `Tier2AccuracyTest` exists because that failure is invisible
        // until somebody asks about the weather and a timer starts.
        appendLine(
            "\"${NO_COMMAND_EXAMPLE}\" -> " + Tier2Json.encode(CommandInvocation.NONE),
        )
    }.trimEnd() + "\n"

    /** `- clock.set_timer: Stellt einen Timer … | amount: ganze Zahl; unit: …, optional` */
    private fun describe(catalog: Tier2Catalog, command: CommandSpec): String = buildString {
        append("- ").append(command.id).append(": ").append(command.description)
        val params = catalog.paramsOf(command)
        if (params.isEmpty()) return@buildString
        append(" | ").append(params.joinToString("; ") { describe(it) })
    }

    private fun describe(param: ParamSpec): String = buildString {
        append(param.name).append(": ")
        append(
            when (val type = param.type) {
                is ParamType.Text -> "Text"
                is ParamType.Integer -> "ganze Zahl"
                is ParamType.Enumeration -> type.values.joinToString("/")
            },
        )
        if (!param.required) append(", optional")
    }

    /**
     * At most [EXAMPLES_PER_COMMAND] lines per command, paraphrases first.
     *
     * Fixed rather than budget-adaptive. A selector that spent whatever budget was left would
     * re-write the golden file every time an unrelated Sock landed, which turns the golden test
     * from a statement about this generator into noise.
     */
    private fun examplesFor(introspection: Introspection, command: CommandSpec): List<Example> =
        introspection.promptExamples(command.id).take(EXAMPLES_PER_COMMAND)

    /**
     * One few-shot, rendered through [Tier2Json.encode] so it is grammar-legal by construction.
     *
     * The `require` is the subtlest bug in this milestone made into a build failure: Tier 2 is
     * handed the *normalized* utterance, where "zwanzig" is already "20", so a few-shot written
     * in raw German teaches the model a surface form it will never be shown.
     */
    private fun exampleLine(command: CommandSpec, example: Example): String {
        require(Normalizer.normalize(example.utterance) == example.utterance) {
            "'${command.id}' example \"${example.utterance}\" is not normalized — Tier 2 only ever " +
                "sees Normalizer output, so a few-shot in raw German teaches a form the model " +
                "will never see. Expected: \"${Normalizer.normalize(example.utterance)}\""
        }
        return "\"${example.utterance}\" -> ${Tier2Json.encode(command.id, example.params)}"
    }

    /** The negative few-shot. Normalized, like every other line. */
    const val NO_COMMAND_EXAMPLE: String = "wie wird das wetter morgen"
}
