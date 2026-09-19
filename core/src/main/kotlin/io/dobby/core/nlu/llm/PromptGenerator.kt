package io.dobby.core.nlu.llm

import io.dobby.core.nlu.Normalizer
import io.dobby.core.registry.Introspection
import io.dobby.core.sock.CommandInvocation
import io.dobby.core.sock.CommandSpec
import io.dobby.core.sock.Example
import io.dobby.core.sock.ParamSpec
import io.dobby.core.sock.ParamType

/**
 * Turns the registry into the two prompts Tier 2 asks, and wraps them in the chat scaffold.
 *
 * ### Two prompts, because there are two decisions
 *
 * The single-shot prompt asked a 1.7B model "which of eighteen commands is this, and what are
 * its parameters, as one JSON line, starting now" — and carried a ~40-token worked example per
 * command to explain it. It reached 1426 of 1500 estimated tokens at eleven commands, with
 * roughly no room for the seven still to come.
 *
 * [routePrefix] asks only for a name. One line per command, no parameters, examples that are
 * `"utterance" -> id` and therefore ~18 tokens instead of ~40. [fillTurn] is then sent *only*
 * for a command that has parameters, and carries that one command's spec and its examples
 * rendered params-only. Each prompt is small and each decision is narrow.
 *
 * ### The scaffold is ours, and thinking is off
 *
 * Qwen3 is a hybrid-reasoning model: by default it writes a `<think>…</think>` scratchpad before
 * every answer, hundreds of tokens long, which is tens of seconds on an A55. Left on, the
 * grammar forbids `<think>` and forces the first legal token, and the model is pushed into a
 * state it was never trained on — valid output, degraded judgement, and nothing to say why.
 *
 * Qwen's own off-switch is what its chat template emits for `enable_thinking=false`: an empty
 * think block after the assistant marker. So this class emits the **whole** prompt text itself,
 * in ChatML, and `llama_chat_apply_template` is never called — whatever template the GGUF
 * carries is irrelevant, which also means swapping the quantisation cannot silently change the
 * prompt. Both steps are non-thinking.
 *
 * ### Generated from structure, not from display strings
 *
 * Parameter lines are built from [ParamType] directly. [Introspection] stringifies an
 * enumeration to `"enum[a|b|c]"` for human display, and re-parsing that to build a prompt is
 * exactly the coupling that breaks the day somebody puts a `|` in an enum value.
 *
 * Examples come from [Introspection.promptExamples], because *which* examples an audience may
 * see is Introspection's policy question rather than this generator's — and it is what keeps
 * [Example.heldOut] cases out of both prompts.
 */
object PromptGenerator {

    /**
     * The budget for the route prompt, from `dobby-plan.md`:420.
     *
     * Unchanged from the single-shot design on purpose: the context window did not get bigger,
     * the prompt got smaller. `PromptBudgetTest` fails the build above it and prints the
     * headroom in commands.
     */
    const val MAX_TOKENS: Int = 1500

    /**
     * The budget for one fill turn: `fill prefill allowance × pessimistic prefill rate`.
     *
     * A fill turn is **uncached prefill on the hot path** — unlike the route prefix, which is
     * prefilled once per process and truncated back to, this is re-decoded on every request
     * that routes to its command. So it gets a budget of its own, and it is derived from the
     * same 1.5 s that [Tier2.MAX_FILL_TOKENS] spends: `1.5 s × 120 tok/s` = 180.
     *
     * **The prefill rate is the one number here nobody has measured**, and it is the
     * milestone's largest open risk. 120 tok/s is twelve times the pessimistic *decode* rate,
     * which is a reasonable-but-not-generous ratio for a batched prefill on two A77 cores. If
     * the device measures slower, this budget is too big and the lever is the one `m6b-plan.md`
     * names: move the parameter specs into the *cached* route prefix, where they cost ~20
     * tokens per command once instead of ~170 per request. That spends route budget to buy
     * latency, and the measurement decides — `Tier2DeviceTest` reports fill prefill separately
     * for exactly this reason.
     */
    const val FILL_TURN_MAX_TOKENS: Int = 180

    /** At most this many worked examples per command in the route prompt. Paraphrases first. */
    const val EXAMPLES_PER_COMMAND: Int = 2

    /**
     * And at most this many in a fill turn, which is fewer, because a fill turn is expensive.
     *
     * The route prefix is prefilled once per process; a fill turn is re-decoded on every
     * request that routes to its command, so a line there costs far more than the same line in
     * the route prompt. It is also doing a smaller job: the command is already known, and what
     * is left is "here is the shape, here is one worked instance of it".
     *
     * Measured, not guessed: at two examples the real registry's fill turns were 190–231
     * estimated tokens against [FILL_TURN_MAX_TOKENS]. At one they fit.
     */
    const val FILL_EXAMPLES_PER_COMMAND: Int = 1

    /**
     * The route system prefix, ending where the utterance is appended.
     *
     * Everything up to and including `<|im_start|>user\n` is identical for every request, which
     * is what makes `n_system` a stable cut for the KV cache: the BPE pre-tokenizer splits at
     * the newline, so the utterance's first token cannot merge backwards across it.
     */
    fun routePrefix(catalog: Tier2Catalog, introspection: Introspection): String =
        "<|im_start|>system\n" + routePrompt(catalog, introspection) + "<|im_end|>\n" +
            "<|im_start|>user\n"

    /**
     * What follows a user turn: the assistant marker and Qwen's empty think block.
     *
     * The trailing blank line is part of the off-switch, not an accident of formatting — it is
     * what Qwen's own template emits, and the model has seen that exact byte sequence in
     * training.
     */
    const val ASSISTANT_SUFFIX: String = "<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n"

    /** Step 1's system prompt: what Dobby is, and the list of names it may answer with. */
    fun routePrompt(catalog: Tier2Catalog, introspection: Introspection): String = buildString {
        appendLine("Du bist der Befehls-Interpreter eines Sprachpanels in einer Wohnung.")
        appendLine("Der Nutzer sagt einen Satz. Nenne den einen Befehl aus der Liste, der gemeint ist.")
        appendLine()
        appendLine("Regeln:")
        appendLine("- Antworte nur mit dem Namen des Befehls, sonst nichts.")
        appendLine("- Nimm nur Namen aus der Liste. Erfinde nichts dazu.")
        appendLine("- Passt kein Befehl: ${CommandInvocation.NONE}.")
        appendLine()
        appendLine("Befehle:")
        for (command in catalog.commands) {
            appendLine("- ${command.id}: ${command.description}")
        }
        appendLine()
        appendLine("Beispiele:")
        for (command in catalog.commands) {
            for (example in examplesFor(introspection, command)) {
                appendLine("${quoted(command, example)} -> ${command.id}")
            }
        }
        // Three negatives, not one. Each costs ~12 tokens in this format, and the failure they
        // guard against — "wie wird das wetter" starting a timer — is the one a wall panel may
        // not have. A model shown only positives learns that some command is always the answer.
        for (negative in NO_COMMAND_EXAMPLES) {
            appendLine("\"$negative\" -> ${CommandInvocation.NONE}")
        }
    }.trimEnd() + "\n"

    /**
     * Step 2: one user turn carrying a single command's parameter spec and examples.
     *
     * The rules that only matter once the command is known live here rather than in the route
     * prompt, where they would be eighteen commands' worth of instruction nobody has asked for
     * yet.
     */
    fun fillTurn(catalog: Tier2Catalog, introspection: Introspection, command: CommandSpec): String {
        require(command.params.isNotEmpty()) {
            "'${command.id}' has no params and needs no fill turn — route answers it whole"
        }
        return buildString {
            appendLine("Befehl ${command.id}: ${command.description}")
            appendLine("Parameter: ${catalog.paramsOf(command).joinToString("; ") { describe(it) }}")
            // No "Zahlen stehen bereits als Ziffern im Satz" here, unlike the single-shot
            // prompt: the `int` rule cannot emit "drei", so the instruction is an unenforceable
            // restatement of something the grammar already guarantees — and a fill turn pays
            // for every line on every request.
            if (catalog.paramsOf(command).any { !it.required }) {
                appendLine("Ein Parameter, der im Satz nicht vorkommt: null.")
            }
            appendLine("Fehlt ein Pflichtparameter: ${CommandInvocation.NONE}.")
            val examples = introspection.promptExamples(command.id)
                .filter { it.params.isNotEmpty() }
                .take(FILL_EXAMPLES_PER_COMMAND)
            if (examples.isNotEmpty()) {
                appendLine(if (examples.size == 1) "Beispiel:" else "Beispiele:")
                for (example in examples) {
                    appendLine("${quoted(command, example)} -> ${Tier2Json.encodeParams(example.params)}")
                }
            }
            append("Antworte nur mit dem JSON.")
        }
    }

    /** Every fill turn, in catalog order. For the golden file and the budget test. */
    fun fillTurns(catalog: Tier2Catalog, introspection: Introspection): Map<String, String> =
        catalog.withParams.associate { it.id to fillTurn(catalog, introspection, it) }

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
     * At most [EXAMPLES_PER_COMMAND] per command, paraphrases first, held-out cases never.
     *
     * Fixed rather than budget-adaptive. A selector that spent whatever budget was left would
     * rewrite the golden file every time an unrelated Sock landed, which turns the golden test
     * from a statement about this generator into noise.
     */
    private fun examplesFor(introspection: Introspection, command: CommandSpec): List<Example> =
        introspection.promptExamples(command.id).take(EXAMPLES_PER_COMMAND)

    /**
     * An example utterance, checked.
     *
     * The `require` is the subtlest bug in this milestone made into a build failure: Tier 2 is
     * handed the *normalized* utterance, where "zwanzig" is already "20", so a few-shot written
     * in raw German teaches the model a surface form it will never be shown.
     */
    private fun quoted(command: CommandSpec, example: Example): String {
        require(Normalizer.normalize(example.utterance) == example.utterance) {
            "'${command.id}' example \"${example.utterance}\" is not normalized — Tier 2 only ever " +
                "sees Normalizer output, so a few-shot in raw German teaches a form the model " +
                "will never see. Expected: \"${Normalizer.normalize(example.utterance)}\""
        }
        return "\"${example.utterance}\""
    }

    /**
     * The negative few-shots. Normalized, like every other line.
     *
     * Three rather than one, and deliberately of three different kinds: a question the panel
     * cannot answer, a sentence addressed to a person in the room, and one that opens with a
     * word a real command also opens with.
     */
    val NO_COMMAND_EXAMPLES: List<String> = listOf(
        "wie wird das wetter morgen",
        "hast du den müll schon rausgebracht",
        "mach dir keinen kopf",
    )
}
