package io.dobby.core.nlu.llm

import io.dobby.core.registry.SockRegistry
import io.dobby.core.sock.CommandSpec
import io.dobby.core.sock.ExclusiveCommandSpec
import io.dobby.core.sock.ParamSpec
import io.dobby.core.sock.ParamType
import io.dobby.core.sock.SharedCommandSpec

/**
 * The registry, in the shape the grammar and the prompt generators need it.
 *
 * `registry.commands` already holds exclusive *and* shared specs, each shared one exactly
 * once — which is the plan's "one branch per shared command", for free. A chain with four
 * subscribers is still one command as far as the model is concerned; who runs it is the
 * dispatcher's question, asked after Tier 2 has finished.
 *
 * **Ordering is fixed and total.** Exclusive commands first, then shared, each group sorted by
 * id; params and enum values in declaration order; nothing here iterates a map. A golden file
 * over a generator with an unstable order tests the sort, not the generator.
 */
class Tier2Catalog(val commands: List<CommandSpec>) {

    val commandIds: List<String> get() = commands.map { it.id }

    /**
     * The commands that need a fill step, in catalog order.
     *
     * The number that makes the two-step split cheap: everything *not* in here is answered
     * completely by the route, with no second request, no second prefill and no second decode.
     * At the registry as it stands that is the majority of commands.
     */
    val withParams: List<CommandSpec> get() = commands.filter { it.params.isNotEmpty() }

    /** Params in declaration order — the order the Sock author wrote, not alphabetical. */
    fun paramsOf(command: CommandSpec): List<ParamSpec> = command.params

    /**
     * A grammar rule name for a command or one of its params.
     *
     * Hyphens, not underscores: llama.cpp identifies GBNF rule names with `is_word_char`, which
     * accepts `[a-zA-Z0-9-]` and not `_`. `dobby-plan.md` §5.4's `cmd_play_music` sketch is
     * pseudocode, and a rule name with an underscore in it is a parse error at load time rather
     * than a bad answer at run time — which is at least a failure that announces itself.
     */
    fun ruleName(prefix: String, vararg parts: String): String =
        (listOf(prefix) + parts).joinToString("-") { part ->
            part.map { if (it.isLetterOrDigit()) it else '-' }.joinToString("")
        }

    companion object {
        fun of(registry: SockRegistry): Tier2Catalog {
            val (exclusive, shared) = registry.commands.values.partition { it is ExclusiveCommandSpec }
            return Tier2Catalog(exclusive.sortedBy { it.id } + shared.sortedBy { it.id })
        }

        /** The enum values of a param, or null if it is not an enumeration. */
        fun enumValues(param: ParamSpec): List<String>? =
            (param.type as? ParamType.Enumeration)?.values

        /** True for the commands a chain dispatches. Only used for the prompt's wording. */
        fun isShared(command: CommandSpec): Boolean = command is SharedCommandSpec
    }
}
