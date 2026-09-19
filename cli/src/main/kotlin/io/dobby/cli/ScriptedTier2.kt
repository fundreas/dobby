package io.dobby.cli

import io.dobby.core.nlu.Normalizer
import io.dobby.core.nlu.llm.Step
import io.dobby.core.nlu.llm.Tier2Json
import io.dobby.core.nlu.llm.Tier2Request
import io.dobby.core.nlu.llm.Tier2Resolver
import io.dobby.core.registry.Introspection
import io.dobby.core.registry.SockRegistry
import io.dobby.core.sock.CommandInvocation

/**
 * Tier 2 without a model: the registry's own few-shots, replayed, in two steps.
 *
 * The terminal has no llama.cpp and never will — Tier 2 is phone-only, by decision. But
 * everything *around* the model is pure `:core`: both prompts, both grammars, the decoder and
 * the gate, which is where the interesting failures are. This resolver stands in for the model
 * so that whole path is exercised by typing German into a terminal.
 *
 * The script is every Tier 2 paraphrase any Sock declares, answered the way the model would
 * have to answer to be right about it: a bare command id for the route, the params object for
 * the fill. So `weck mich in 20 minuten` genuinely resolves here, through the real decoder and
 * the real gate, and a few-shot whose params do not survive `ParamCoercion` is visible on a
 * laptop instead of on a device.
 *
 * Anything unscripted routes to `none`, which is also what the real model should mostly say.
 */
class ScriptedTier2(registry: SockRegistry, introspection: Introspection = Introspection(registry)) :
    Tier2Resolver {

    private val routes: Map<String, String> = buildMap {
        for (command in registry.commands.values) {
            for (example in introspection.promptExamples(command.id)) {
                put(Normalizer.normalize(example.utterance), command.id)
            }
        }
    }

    private val fills: Map<String, String> = buildMap {
        for (command in registry.commands.values) {
            if (command.params.isEmpty()) continue
            for (example in introspection.promptExamples(command.id)) {
                put(Normalizer.normalize(example.utterance), Tier2Json.encodeParams(example.params))
            }
        }
    }

    /** What the script knows, for `/tier2` to list when asked with no argument. */
    val utterances: Set<String> get() = routes.keys

    override val available: Boolean = true

    override val unavailableReason: String? = null

    /**
     * Forces the next answer of one step, which is how a hostile reply is tried.
     *
     * `/tier2 mach was = nope.nope` shows the gate rejecting an invented label;
     * `/tier2 weck mich in 20 minuten = {"amount":9}` shows a fill that fails coercion.
     */
    var overrideRoute: String? = null

    var overrideFill: String? = null

    override suspend fun generate(request: Tier2Request): String = when (request.step) {
        Step.ROUTE -> overrideRoute ?: routes[request.utterance] ?: CommandInvocation.NONE
        Step.FILL -> overrideFill ?: fills[request.utterance] ?: CommandInvocation.NONE
    }
}
