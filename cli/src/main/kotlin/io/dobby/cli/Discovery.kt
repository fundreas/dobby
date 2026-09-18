package io.dobby.cli

import io.dobby.core.registry.CommandInfo
import io.dobby.core.registry.CommandKind
import io.dobby.core.registry.Introspection
import io.dobby.core.registry.SockInfo
import io.dobby.core.sock.SockStatus

/**
 * Renders [Introspection] for the terminal.
 *
 * Only the formatting lives here — the questions themselves are answered by core, so the
 * Android settings screen will ask exactly the same ones.
 */
class Discovery(private val introspection: Introspection) {

    /** `/socks` */
    fun socks(): String = buildString {
        val socks = introspection.socks()
        appendLine("${socks.size} sock(s):")
        for (sock in socks) {
            appendLine("  ${sock.id.padEnd(12)} ${sock.displayName.padEnd(14)} ${status(sock)}")
            appendLine("  ${"".padEnd(12)} ${sock.commandCount} command(s)")
        }
        append("  /commands <sock> for detail")
    }

    /** `/commands` */
    fun commands(): String = buildString {
        val all = introspection.commands()
        val (exclusive, shared) = all.partition { it.kind == CommandKind.EXCLUSIVE }

        appendLine("${all.size} command(s):")
        var currentSock: String? = null
        for (command in exclusive) {
            if (command.ownerSockId != currentSock) {
                currentSock = command.ownerSockId
                appendLine("  $currentSock")
            }
            appendLine("    ${command.signature.padEnd(46)} ${command.description}")
        }
        if (shared.isNotEmpty()) {
            appendLine("  shared  (chain — resolved at dispatch by who is active)")
            for (command in shared) {
                appendLine("    ${command.signature.padEnd(46)} ${command.description}")
                appendLine("    ${"".padEnd(46)} ${chainOf(command)}")
            }
        }
        append("  /commands <sock> for templates and examples")
    }

    /** `/commands <sock>` */
    fun commands(sockId: String): String {
        val sock = introspection.sock(sockId)
        val commands = introspection.commands(sockId)
        if (sock == null || commands == null) {
            return "unknown sock '$sockId'. known: ${introspection.sockIds().joinToString(", ")}"
        }

        return buildString {
            appendLine("${sock.id} — ${sock.displayName}  ${status(sock)}")
            if (commands.isEmpty()) {
                append("  no commands")
                return@buildString
            }
            for (command in commands) {
                appendLine()
                val marker = if (command.kind == CommandKind.SHARED) "  [chain] " else "  "
                appendLine("$marker${command.signature}")
                appendLine("      ${command.description}")
                if (command.kind == CommandKind.SHARED) {
                    appendLine("      ${chainOf(command)}")
                }
                if (command.params.isNotEmpty()) {
                    appendLine("      params:")
                    command.params.forEach { appendLine("        $it") }
                }
                appendLine("      say:")
                command.examples.ifEmpty { listOf("(no examples declared)") }
                    .forEach { appendLine("        \"$it\"") }
                appendLine("      templates (match order):")
                command.templates.forEach { appendLine("        $it") }
            }
        }.trimEnd()
    }

    /** `/palette` — every template across every Sock, in the order the matcher tries them. */
    fun palette(): String = buildString {
        appendLine("templates in match order:")
        for (command in introspection.commands()) {
            for (template in command.templates) {
                appendLine("  ${command.id.padEnd(26)} $template")
            }
        }
    }.trimEnd()

    private fun chainOf(command: CommandInfo): String =
        "chain: " + command.subscribers.joinToString(" → ") { "${it.sockId}(${it.priority})" }
            .ifEmpty { "no subscribers — unreachable" }

    private fun status(sock: SockInfo): String = when (val s = sock.status) {
        is SockStatus.Ready -> "ready"
        is SockStatus.Degraded -> "DEGRADED: ${s.reason}"
        is SockStatus.Unavailable -> "UNAVAILABLE: ${s.reason}"
    }
}
