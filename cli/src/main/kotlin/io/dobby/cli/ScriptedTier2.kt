package io.dobby.cli

import io.dobby.core.nlu.Normalizer
import io.dobby.core.nlu.llm.Tier2Json
import io.dobby.core.nlu.llm.Tier2Program
import io.dobby.core.nlu.llm.Tier2Resolver
import io.dobby.core.registry.Introspection
import io.dobby.core.registry.SockRegistry

/**
 * Tier 2 without a model: the registry's own few-shots, replayed.
 *
 * The terminal has no llama.cpp and never will — Tier 2 is phone-only, by decision. But
 * everything *around* the model is pure `:core`: the grammar, the prompt, the decoder and the
 * gate, which is where the interesting failures are. This resolver stands in for the model so
 * that whole path is exercised by typing German into a terminal.
 *
 * The script is every Tier 2 paraphrase any Sock declares, answered with the JSON the model
 * would have to emit to be right about it. So `weck mich in 20 minuten` genuinely resolves
 * here, through the real decoder and the real gate, and a few-shot whose params do not survive
 * `ParamCoercion` is visible on a laptop instead of on a device.
 *
 * Anything unscripted answers `none`, which is also what the real model should mostly say.
 */
class ScriptedTier2(registry: SockRegistry, introspection: Introspection = Introspection(registry)) :
    Tier2Resolver {

    private val script: Map<String, String> = buildMap {
        for (command in registry.commands.values) {
            for (example in introspection.promptExamples(command.id)) {
                put(Normalizer.normalize(example.utterance), Tier2Json.encode(command.id, example.params))
            }
        }
    }

    /** What the script would answer, for `/tier2` to print alongside the gate's verdict. */
    val utterances: Set<String> get() = script.keys

    override val available: Boolean = true

    override val unavailableReason: String? = null

    /** Overridden by `/tier2 <utterance> = <json>`, which is how a hostile reply is tried. */
    var override: String? = null

    override suspend fun generate(utterance: String, program: Tier2Program): String =
        override ?: script[utterance] ?: Tier2Json.encode("none")
}
