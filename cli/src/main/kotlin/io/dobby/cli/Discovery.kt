package io.dobby.cli

import io.dobby.core.registry.CommandInfo
import io.dobby.core.registry.CommandKind
import io.dobby.core.nlu.template.KeywordMatcher
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

    /**
     * `/keywords` — every template literal, its Kölner code, and whether phonetics trusts it.
     *
     * Reach for this when a new Sock stops something matching. The phonetic tier retires
     * keywords *automatically* when a collision appears, both sides of it, so the cause of
     * "schbiele stopped working" is usually a word somebody else added last week.
     */
    fun keywords(): String = buildString {
        val all = introspection.keywords()
        val phonetic = all.count { it.phonetic }
        appendLine("${all.size} literal(s), $phonetic matched phonetically:")
        appendLine()
        var currentTrust: KeywordMatcher.Trust? = null
        for (info in all) {
            if (info.trust != currentTrust) {
                currentTrust = info.trust
                appendLine("  ${reasonOf(currentTrust)}")
            }
            val strict = when {
                info.strictTemplates == 0 -> ""
                info.strictTemplates == info.templates -> "  [single-literal: strict]"
                else -> "  [strict in ${info.strictTemplates}/${info.templates} templates]"
            }
            appendLine(
                "    ${info.keyword.padEnd(18)} ${info.code.padEnd(10)} " +
                    "${info.commandIds.joinToString(", ")}$strict",
            )
        }
        append("  a keyword is phonetic only if nothing else in the palette — or in ordinary German — shares its code")
    }.trimEnd()

    private fun reasonOf(trust: KeywordMatcher.Trust): String = when (trust) {
        KeywordMatcher.Trust.PHONETIC -> "phonetic — a garble of this word still matches"
        KeywordMatcher.Trust.TOO_SHORT ->
            "not phonetic: under ${KeywordMatcher.MIN_LENGTH} characters"

        KeywordMatcher.Trust.CODE_TOO_SHORT ->
            "not phonetic: code under ${KeywordMatcher.MIN_CODE_LENGTH} digits (mostly vowels)"

        KeywordMatcher.Trust.CONTESTED ->
            "not phonetic: another palette keyword has the same code (both sides retired)"

        KeywordMatcher.Trust.CORPUS ->
            "not phonetic: an ordinary German word has the same code (KeywordMatcher.RETIRED_BY_CORPUS)"

        KeywordMatcher.Trust.STRICT_MATCHER -> "phonetics off"
    }

    private fun chainOf(command: CommandInfo): String =
        "chain: " + command.subscribers.joinToString(" → ") { "${it.sockId}(${it.priority})" }
            .ifEmpty { "no subscribers — unreachable" }

    private fun status(sock: SockInfo): String = when (val s = sock.status) {
        is SockStatus.Ready -> "ready"
        is SockStatus.Degraded -> "DEGRADED: ${s.reason}"
        is SockStatus.Unavailable -> "UNAVAILABLE: ${s.reason}"
    }
}
