package io.dobby.core.registry

import io.dobby.core.dispatch.SockHealth
import io.dobby.core.nlu.template.KeywordMatcher
import io.dobby.core.nlu.template.Syntax
import io.dobby.core.sock.CommandSpec
import io.dobby.core.sock.Example
import io.dobby.core.sock.ExclusiveCommandSpec
import io.dobby.core.sock.ParamSpec
import io.dobby.core.sock.ParamType
import io.dobby.core.sock.SharedCommandSpec
import io.dobby.core.sock.SockStatus

/**
 * A read-only view of what Dobby can currently do.
 *
 * Lives in core rather than in the CLI because every surface needs it: the terminal harness,
 * the Phase B settings screen, and the generated Tier 2 system prompt are three renderings of
 * the same question. Returns plain data — no formatting, no strings meant for a terminal.
 */
class Introspection(
    private val registry: SockRegistry,
    private val health: SockHealth? = null,
) {

    fun socks(): List<SockInfo> = registry.socks.map { describe(it.id)!! }.sortedBy { it.id }

    fun sock(sockId: String): SockInfo? = describe(sockId)

    fun sockIds(): List<String> = registry.socks.map { it.id }.sorted()

    /** Every command in the palette: exclusive commands, then shared ones. */
    fun commands(): List<CommandInfo> = registry.commands.values
        .map { describe(it) }
        .sortedWith(compareBy({ it.kind }, { it.id }))

    /**
     * Everything reachable through one Sock: the commands it owns, plus the chains it takes
     * part in — a chain command is genuinely part of what that Sock does, even though it does
     * not own it.
     */
    fun commands(sockId: String): List<CommandInfo>? {
        val sock = registry.socks.firstOrNull { it.id == sockId } ?: return null
        // Declaration order, not alphabetical: a Sock author lists its most important command
        // first, and that order is what a spoken "what can the clock do" should follow.
        return sock.commands.map { describe(it) } + sock.shared.map { describe(it.command) }
    }

    fun command(commandId: String): CommandInfo? = registry.commands[commandId]?.let { describe(it) }

    /** Substring match over command ids and descriptions. For a "what can you do about X" view. */
    fun search(query: String): List<CommandInfo> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return emptyList()
        return commands().filter {
            needle in it.id.lowercase() || needle in it.description.lowercase()
        }
    }

    /**
     * The examples a Tier 2 system prompt may show the model, best first.
     *
     * Deliberately not [CommandInfo.examples], which is the *spoken help* view: that one filters
     * to `matchedByTemplates` and drops [Example.params], and both decisions are exactly
     * inverted here.
     *
     * - A Tier 2 paraphrase comes first, because a few-shot's whole job is to demonstrate the
     *   case templates cannot reach. Showing the model an utterance Tier 1 already handles
     *   teaches it to duplicate work it will never be asked to do.
     * - Then [SharedSubscription.extraExamples] — the phrasings one Sock adds to a chain, which
     *   are read by nothing at all today.
     * - Then the Tier 1 examples, which are still worth showing when a command has no
     *   paraphrase of its own.
     * - Params are kept, because a few-shot without them is a worked example with the work
     *   left out.
     * - [Example.heldOut] cases are excluded outright. They exist to measure the model, and a
     *   measurement the model was shown the answers to measures nothing.
     *
     * Which examples an audience may see is Introspection's policy question, which is why this
     * lives here rather than in the generator that consumes it.
     */
    fun promptExamples(commandId: String): List<Example> {
        val command = registry.commands[commandId] ?: return emptyList()
        val extra = registry.socks
            .flatMap { it.shared }
            .filter { it.command.id == commandId }
            .flatMap { it.extraExamples }
        val shown = command.examples.filterNot { it.heldOut }
        val (paraphrases, tier1) = shown.partition { !it.matchedByTemplates }
        return (paraphrases + extra.filterNot { it.heldOut } + tier1).distinctBy { it.utterance }
    }

    /**
     * Every literal keyword in the palette, and what the phonetic tier makes of it.
     *
     * The tool you reach for when a new Sock stops something matching. A keyword that was
     * recovering garbles yesterday and is `CONTESTED` today has been retired by something
     * somebody else added — which is the tier working as designed, and completely invisible
     * without this listing.
     */
    fun keywords(): List<KeywordInfo> {
        val palette = registry.palette
        val entriesByLiteral = mutableMapOf<String, MutableList<PaletteEntry>>()
        for (entry in palette.entries) {
            for (literal in entry.template.literals.distinct()) {
                entriesByLiteral.getOrPut(literal) { mutableListOf() } += entry
            }
        }
        return entriesByLiteral.map { (literal, entries) ->
            val verdict = palette.keywords.explain(literal)
            KeywordInfo(
                keyword = literal,
                code = verdict.code,
                trust = verdict.trust,
                templates = entries.size,
                // A template with one literal and no slots is matched strictly however trusted
                // the keyword is elsewhere — so a keyword can be phonetic in one place and not
                // in another, and the listing has to say which.
                strictTemplates = entries.count { palette.isStrict(it) },
                commandIds = entries.map { it.command.id }.distinct().sorted(),
            )
        }.sortedWith(compareBy({ it.trust }, { it.keyword }))
    }

    private fun describe(sockId: String): SockInfo? {
        val sock = registry.socks.firstOrNull { it.id == sockId } ?: return null
        return SockInfo(
            id = sock.id,
            displayName = sock.displayName,
            status = health?.reasonFor(sock.id)?.let { SockStatus.Degraded(it) } ?: sock.status.value,
            commands = sock.commands.map { describe(it) },
            chains = sock.shared.map { ChainMembership(it.command.id, it.priority) },
        )
    }

    private fun describe(command: CommandSpec): CommandInfo {
        val subscribers = registry.chainFor(command.id)?.subscribers.orEmpty()
        val entries = registry.palette.entries.filter { it.command.id == command.id }
        // Only what Tier 1 actually matches. Spoken help is a promise — "say this and it
        // will work" — so neither a few-shot nor a held-out case belongs in it, and
        // [Example.tier2Only] is the one question that covers both.
        val sayable = command.examples.filterNot { it.tier2Only }.map { it.utterance }
        return CommandInfo(
            id = command.id,
            kind = when (command) {
                is ExclusiveCommandSpec -> CommandKind.EXCLUSIVE
                is SharedCommandSpec -> CommandKind.SHARED
            },
            description = command.description,
            params = command.params.map { describe(it) },
            // Match order, so the listing doubles as an explanation of why an utterance routed
            // the way it did.
            templates = entries
                .map { entry -> entry.contributedBy?.let { "${entry.template.source}  (+$it)" } ?: entry.template.source },
            examples = sayable,
            title = command.help?.title ?: titleOf(command.id),
            detail = command.help?.detail ?: command.description,
            // Derived from the grammar rather than written down beside it, so it cannot drift
            // away from what the matcher will actually accept.
            syntax = entries.map { Syntax.of(it.template, MAX_SYNTAX_OPTIONS) }.distinct(),
            // What to say, in one line: a declared Tier 1 example if there is one — it is real
            // German — and otherwise the shortest path through the first template.
            usage = sayable.firstOrNull() ?: entries.firstOrNull()?.let { Syntax.spoken(it.template) },
            hints = command.help?.hints.orEmpty(),
            aliases = command.help?.aliases.orEmpty(),
            ownerSockId = registry.ownerOf(command.id)?.id,
            subscribers = subscribers.map { SubscriberInfo(it.sock.id, it.subscription.priority) }
                .sortedByDescending { it.priority },
        )
    }

    /** `clock.set_timer` → "Set timer", for a command whose author declared no [CommandHelp]. */
    private fun titleOf(commandId: String): String =
        commandId.substringAfter('.').replace('_', ' ').replaceFirstChar { it.uppercase() }

    private fun describe(param: ParamSpec) = ParamInfo(
        name = param.name,
        type = when (val type = param.type) {
            is ParamType.Text -> "text"
            is ParamType.Integer -> "int"
            is ParamType.Enumeration -> "enum[${type.values.joinToString("|")}]"
        },
        required = param.required,
        default = param.default,
    )
}

/** How many branches of one alternation a rendered syntax line shows before "…". */
private const val MAX_SYNTAX_OPTIONS = 4

enum class CommandKind { EXCLUSIVE, SHARED }

/** One template literal, and whether the phonetic tier trusts it. See [Introspection.keywords]. */
data class KeywordInfo(
    val keyword: String,
    /** Kölner Phonetik code, possibly empty. */
    val code: String,
    val trust: KeywordMatcher.Trust,
    /** How many palette templates contain this literal. */
    val templates: Int,
    /** How many of those are matched with [KeywordMatcher.STRICT] whatever [trust] says. */
    val strictTemplates: Int,
    val commandIds: List<String>,
) {
    val phonetic: Boolean get() = trust == KeywordMatcher.Trust.PHONETIC && strictTemplates < templates
}

data class ParamInfo(
    val name: String,
    val type: String,
    val required: Boolean,
    val default: Any?,
) {
    override fun toString(): String = buildString {
        append(name).append(": ").append(type)
        if (!required) {
            append(
                when (default) {
                    null -> " (optional)"
                    is String -> " = \"$default\""
                    else -> " = $default"
                },
            )
        }
    }
}

data class SubscriberInfo(val sockId: String, val priority: Int)

data class ChainMembership(val commandId: String, val priority: Int)

/**
 * One command, as a person and a program both need to see it.
 *
 * The bottom half — [title] through [aliases] — is the explanation the help screen draws and
 * "erkläre das Kommando …" speaks. It is one structure with one source: whatever the Sock
 * declared in [io.dobby.core.sock.CommandHelp], plus a usage line derived from the grammar,
 * so the two surfaces cannot tell different stories about the same command.
 */
data class CommandInfo(
    val id: String,
    val kind: CommandKind,
    val description: String,
    val params: List<ParamInfo>,
    /** Templates in palette match order. */
    val templates: List<String>,
    /** Only the Tier 1 examples; nothing Tier 2-only is something a user can rely on. */
    val examples: List<String>,
    /** Null for a shared command — nobody owns it. */
    val ownerSockId: String?,
    /** Chain members in ranking order. Empty for an exclusive command. */
    val subscribers: List<SubscriberInfo>,
    /** Short German name: "Timer stellen". Falls back to the command id when none was declared. */
    val title: String = "",
    /** German prose for a person; the Sock's [io.dobby.core.sock.CommandHelp.detail] or its description. */
    val detail: String = description,
    /** Every phrasing, rendered from the grammar: `stell [einen] timer auf <amount> <unit>`. */
    val syntax: List<String> = emptyList(),
    /** One line somebody can repeat out loud, or null for a command with neither example nor template. */
    val usage: String? = null,
    /** German notes: what is optional, what happens when something is left out. */
    val hints: List<String> = emptyList(),
    /** Other names this command answers to when somebody asks about it. */
    val aliases: List<String> = emptyList(),
) {
    val signature: String
        get() = if (params.isEmpty()) id else "$id(${params.joinToString(", ")})"
}

data class SockInfo(
    val id: String,
    val displayName: String,
    val status: SockStatus,
    val commands: List<CommandInfo>,
    val chains: List<ChainMembership>,
) {
    val commandCount: Int get() = commands.size + chains.size
}
