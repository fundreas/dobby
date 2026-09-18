package io.dobby.core.registry

import io.dobby.core.dispatch.SockHealth
import io.dobby.core.sock.CommandSpec
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
            templates = registry.palette.entries
                .filter { it.command.id == command.id }
                .map { entry -> entry.contributedBy?.let { "${entry.template.source}  (+$it)" } ?: entry.template.source },
            examples = command.examples.filter { it.matchedByTemplates }.map { it.utterance },
            ownerSockId = registry.ownerOf(command.id)?.id,
            subscribers = subscribers.map { SubscriberInfo(it.sock.id, it.subscription.priority) }
                .sortedByDescending { it.priority },
        )
    }

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

enum class CommandKind { EXCLUSIVE, SHARED }

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

data class CommandInfo(
    val id: String,
    val kind: CommandKind,
    val description: String,
    val params: List<ParamInfo>,
    /** Templates in palette match order. */
    val templates: List<String>,
    /** Only the Tier 1 examples; Tier 2 paraphrases are not things a user can rely on. */
    val examples: List<String>,
    /** Null for a shared command — nobody owns it. */
    val ownerSockId: String?,
    /** Chain members in ranking order. Empty for an exclusive command. */
    val subscribers: List<SubscriberInfo>,
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
