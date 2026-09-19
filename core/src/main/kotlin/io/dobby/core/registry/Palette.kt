package io.dobby.core.registry

import io.dobby.core.nlu.GermanNumbers
import io.dobby.core.nlu.template.CompiledTemplate
import io.dobby.core.nlu.template.Specificity
import io.dobby.core.nlu.template.TemplateSyntaxException
import io.dobby.core.nlu.template.compileTemplate
import io.dobby.core.sock.CommandInvocation
import io.dobby.core.sock.CommandSpec
import io.dobby.core.sock.ParamSpec
import io.dobby.core.sock.ParamType

/** One template, and the command it resolves to. */
data class PaletteEntry(
    val command: CommandSpec,
    val template: CompiledTemplate,
    /** Params this template fixes by its wording — see [io.dobby.core.sock.TemplatePattern]. */
    val staticParams: Map<String, Any>,
    /** Sock that contributed this template to a shared command, or null for a catalog template. */
    val contributedBy: String?,
    /** Registration order, the final tiebreak. */
    val order: Int,
)

data class PaletteMatch(val invocation: CommandInvocation, val entry: PaletteEntry)

/**
 * Every template from every Sock, ordered specific → generic.
 *
 * First match wins, so the ordering is the whole ballgame: `spiele radio {station}` must be
 * tried before `spiele {query}`.
 */
class Palette(entries: List<PaletteEntry>) {

    val entries: List<PaletteEntry> = entries.sortedWith(
        Comparator { a, b ->
            val bySpecificity = Specificity.ORDER.compare(a.template.specificity, b.template.specificity)
            if (bySpecificity != 0) bySpecificity else a.order.compareTo(b.order)
        },
    )

    /**
     * Resolves normalized tokens to an invocation.
     *
     * An entry whose template matches but whose params fail to coerce is skipped, not fatal —
     * the next entry gets its chance.
     */
    fun match(tokens: List<String>): PaletteMatch? {
        if (tokens.isEmpty()) return null
        for (entry in entries) {
            val bindings = entry.template.match(tokens) { slot ->
                enumValuesFor(entry.command, slot)
            } ?: continue
            val params = ParamCoercion.coerce(entry.command, bindings, entry.staticParams) ?: continue
            return PaletteMatch(CommandInvocation(entry.command.id, params), entry)
        }
        return null
    }

    private fun enumValuesFor(command: CommandSpec, slot: String): List<String>? =
        (command.params.firstOrNull { it.name == slot }?.type as? ParamType.Enumeration)?.values
}

/**
 * A palette compiled outside the registry, for the lifetime of a single dialogue turn.
 *
 * [palette] is null when any template failed to compile — all or nothing, because a question
 * that can only hear half of its own answers is worse than one that holds no floor at all.
 */
data class ScopedPaletteBuild(val palette: Palette?, val errors: List<String>)

/**
 * Compiles the templates of an unregistered command into a palette of its own.
 *
 * This is the follow-up palette behind [io.dobby.core.sock.SockResult.Asked]. It deliberately
 * skips the registry's bare-`{text}` rejection: that rule exists because a global template
 * matching every utterance would swallow the whole palette, and these templates are tried only
 * while a question is open, against the one utterance that answers it. "Meinst du den Song oder
 * das Album?" wants to hear whatever comes back, not a closed candidate set.
 */
fun compileScopedPalette(command: CommandSpec): ScopedPaletteBuild {
    val errors = mutableListOf<String>()
    val entries = mutableListOf<PaletteEntry>()
    if (command.templates.isEmpty()) {
        errors += "'${command.id}' offers no templates and could never hear an answer"
    }
    for ((order, template) in command.templates.withIndex()) {
        val compiled = try {
            compileTemplate(template.pattern)
        } catch (e: TemplateSyntaxException) {
            errors += "'${command.id}': ${e.message}"
            continue
        }
        entries += PaletteEntry(command, compiled, template.params, null, order)
    }
    return ScopedPaletteBuild(if (errors.isEmpty()) Palette(entries) else null, errors)
}

/** Turns raw slot strings into typed params, applying defaults for absent optionals. */
object ParamCoercion {

    /**
     * @param bindings raw slot captures from the template
     * @param staticParams params the matched template fixes by its wording; a slot capture wins
     * @return the typed params, or null if this cannot satisfy the command's spec
     */
    fun coerce(
        command: CommandSpec,
        bindings: Map<String, String>,
        staticParams: Map<String, Any> = emptyMap(),
    ): Map<String, Any>? {
        val result = mutableMapOf<String, Any>()
        for (spec in command.params) {
            val raw = bindings[spec.name]
            if (raw != null) {
                result[spec.name] = coerceValue(spec, raw) ?: return null
                continue
            }
            val fixed = staticParams[spec.name]
            val fallback = fixed ?: spec.default
            when {
                fallback != null -> result[spec.name] = fallback
                spec.required -> return null
                else -> Unit // optional with no default: leave it out
            }
        }
        return result
    }

    private fun coerceValue(spec: ParamSpec, raw: String): Any? = when (val type = spec.type) {
        is ParamType.Text -> raw
        is ParamType.Integer -> GermanNumbers.parseSlotValue(raw)
        is ParamType.Enumeration -> type.values.firstOrNull { it.equals(raw, ignoreCase = true) }
    }
}
