package io.dobby.socks.help

import io.dobby.core.nlu.template.Levenshtein
import io.dobby.core.registry.CommandInfo
import io.dobby.core.registry.Introspection
import io.dobby.core.registry.SockInfo
import io.dobby.core.sock.CommandHelp
import io.dobby.core.sock.CommandInvocation
import io.dobby.core.sock.Example
import io.dobby.core.sock.ExclusiveCommandSpec
import io.dobby.core.sock.ParamSpec
import io.dobby.core.sock.ParamType
import io.dobby.core.sock.Sock
import io.dobby.core.sock.SockResult
import io.dobby.core.sock.patterns

/**
 * Help — spoken discovery. "Was kannst du?" and "Was kann die Uhr?"
 *
 * Discovery is a capability, so it is a Sock rather than a special case in core. That keeps
 * the rule intact: core still knows no commands, and this Sock can be removed without
 * touching anything else.
 *
 * It is the one Sock that needs to see the whole palette, which creates a chicken-and-egg with
 * the registry that is built *from* it. Hence [directory] is a provider, bound by the app
 * immediately after the registry exists, rather than a constructor value.
 *
 * **Answers are written for the ear, not the eye.** Reading twenty commands aloud is useless,
 * so the overview names areas and the detail view offers at most three things to say. The
 * exhaustive listing is the terminal's job (`/commands`), and the dashboard's in Phase B.
 */
class HelpSock(
    private val directory: () -> Introspection?,
) : Sock {

    override val id: String = "help"

    override val displayName: String = "Hilfe"

    override val commands: List<ExclusiveCommandSpec> = listOf(
        ExclusiveCommandSpec(
            id = OVERVIEW,
            templates = patterns(
                // "denn" and "so" are filler the matcher skips (M6c), so this one line covers
                // "was kannst du denn alles" too; the alternation carries the words that mean
                // something.
                "was kannst du (alles|tun|machen)?",
                "(hilfe|hilf mir)",
                "welche befehle (gibt es|hast du|kennst du|gibt's)",
                "was gibt es für befehle",
                "wobei kannst du helfen",
                "welche (bereiche|module|socks) (gibt es|hast du)",
                "was für (bereiche|module|socks) (gibt es|hast du)",
            ),
            description = "Sagt, welche Bereiche es gibt.",
            help = CommandHelp(
                title = "Übersicht",
                detail = "Nennt die Bereiche, die es gibt. Danach fragst du einen davon " +
                    "einzeln ab.",
                aliases = listOf("übersicht", "hilfe", "was kannst du"),
            ),
            examples = listOf(
                Example("was kannst du"),
                Example("was kannst du alles"),
                Example("hilfe"),
                Example("welche befehle gibt es"),
                Example("welche bereiche gibt es"),
                Example("erzähl mir mal was du so drauf hast", matchedByTemplates = false),
                // Held out: never shown to the model, asserted on the device.
                Example("was geht denn hier alles", heldOut = true),
                Example("womit kann ich dich beauftragen", heldOut = true),
                Example("sag mal was du drauf hast", heldOut = true),
            ),
        ),
        ExclusiveCommandSpec(
            id = SOCK_COMMANDS,
            params = listOf(ParamSpec("sock", ParamType.Text)),
            templates = patterns(
                "was kann (die|der|das)? {sock} (alles)?",
                "welche befehle hat (die|der|das)? {sock}",
                "hilfe (zu|für) (die|der|das|den)? {sock}",
                "was kann ich (mit|bei) (der|dem|die|das)? {sock} (machen|sagen)",
            ),
            description = "Sagt, was ein bestimmter Bereich kann.",
            help = CommandHelp(
                title = "Bereich erklären",
                detail = "Nennt für einen Bereich bis zu drei Sätze, die du sagen kannst.",
                hints = listOf("Die vollständige Liste steht auf dem Hilfe-Bildschirm des Panels."),
                aliases = listOf("bereich erklären", "was kann die uhr"),
            ),
            examples = listOf(
                Example("was kann die uhr", mapOf("sock" to "uhr")),
                Example("welche befehle hat die uhr", mapOf("sock" to "uhr")),
                Example("hilfe zu spotify", mapOf("sock" to "spotify")),
                Example("erzähl mir was die uhr so kann", mapOf("sock" to "uhr"), heldOut = true),
                Example("womit kann mir der rechner helfen", mapOf("sock" to "rechner"), heldOut = true),
                Example("was geht denn mit spotify", mapOf("sock" to "spotify"), heldOut = true),
            ),
        ),
        ExclusiveCommandSpec(
            id = EXPLAIN_COMMAND,
            params = listOf(ParamSpec("command", ParamType.Text)),
            templates = patterns(
                // Every phrasing carries "kommando" or "befehl". That word is the anchor: the
                // slot behind it is open and greedy, and without a keyword in front of it this
                // template would answer every sentence starting with "erkläre".
                "(erkläre|erklär) (das|den)? (kommando|befehl) {command}",
                "was (macht|bedeutet|tut) (das|der)? (kommando|befehl) {command}",
                "wie (benutze|benutzt|verwende) ich (das|den)? (kommando|befehl) {command}",
                "wie (geht|funktioniert) (das|der)? (kommando|befehl) {command}",
                "hilfe (zum|zu dem)? (kommando|befehl) {command}",
            ),
            description = "Erklärt ein einzelnes Kommando und wie man es sagt.",
            help = CommandHelp(
                title = "Kommando erklären",
                detail = "Erklärt ein einzelnes Kommando: was es tut und wie du es sagst. Den " +
                    "Namen findest du auf dem Hilfe-Bildschirm des Panels.",
                hints = listOf("Der Name darf ungenau sein — „Timer“ genügt für „Timer stellen“."),
                aliases = listOf("kommando erklären", "befehl erklären"),
            ),
            examples = listOf(
                Example("erkläre das kommando timer stellen", mapOf("command" to "timer stellen")),
                Example("erklär den befehl uhrzeit", mapOf("command" to "uhrzeit")),
                Example("was macht das kommando restzeit", mapOf("command" to "restzeit")),
                Example(
                    "wie benutze ich den befehl musik abspielen",
                    mapOf("command" to "musik abspielen"),
                ),
                Example("hilfe zum kommando rechnen", mapOf("command" to "rechnen")),
                // Tier 2 few-shots: asking about a command without naming it as one.
                Example(
                    "erklär mir mal wie das mit dem timer geht",
                    mapOf("command" to "timer"),
                    matchedByTemplates = false,
                ),
                Example(
                    "wie sag ich dir nochmal dass du musik spielen sollst",
                    mapOf("command" to "musik abspielen"),
                    matchedByTemplates = false,
                ),
                // Held out: never shown to the model, asserted on the device.
                Example("sag mir nochmal wie der timer befehl geht", mapOf("command" to "timer"), heldOut = true),
                Example("wie funktioniert das mit der restzeit", mapOf("command" to "restzeit"), heldOut = true),
            ),
        ),
    )

    override suspend fun handle(invocation: CommandInvocation): SockResult {
        val introspection = directory()
            ?: return SockResult.Failed("Ich kann meine Befehle gerade nicht nachschlagen.")

        return when (invocation.commandId) {
            OVERVIEW -> SockResult.Spoken(overview(introspection))
            SOCK_COMMANDS -> SockResult.Spoken(describeSock(introspection, invocation.text("sock")))
            EXPLAIN_COMMAND ->
                SockResult.Spoken(explainCommand(introspection, invocation.text("command")))
            else -> SockResult.NotForMe
        }
    }

    private fun overview(introspection: Introspection): String {
        val socks = introspection.socks().filter { it.commandCount > 0 }.sortedBy { it.displayName }
        if (socks.isEmpty()) return "Ich kann im Moment noch nichts."

        val names = germanList(socks.map { it.displayName })
        val example = socks.firstOrNull { it.id != id } ?: socks.first()
        return if (socks.size == 1) {
            "Ich habe einen Bereich: $names. Frag: Was kann ${example.displayName}?"
        } else {
            "Ich habe ${socks.size} Bereiche: $names. " +
                "Frag zum Beispiel: Was kann ${example.displayName}?"
        }
    }

    private fun describeSock(introspection: Introspection, spoken: String): String {
        val sock = resolve(introspection, spoken)
            ?: return "Den Bereich kenne ich nicht. Ich habe: " +
                germanList(introspection.socks().map { it.displayName }.sorted()) + "."

        val commands = introspection.commands(sock.id).orEmpty()
        if (commands.isEmpty()) return "${sock.displayName} kann im Moment nichts."

        val phrases = commands.mapNotNull { phraseFor(it) }.take(MAX_SPOKEN_EXAMPLES)
        if (phrases.isEmpty()) {
            return "${sock.displayName} hat ${count(commands.size)}, aber ich weiß gerade nicht, " +
                "wie man sie sagt."
        }

        val rest = commands.size - phrases.size
        val tail = if (rest > 0) " Und $rest weitere." else ""
        return "${sock.displayName} hat ${count(commands.size)}. Sag zum Beispiel: " +
            germanList(phrases.map { "‚$it‘" }) + ".$tail"
    }

    /**
     * One command, out loud: what it is called, what it does, and one sentence to repeat.
     *
     * Every part of that comes from [CommandInfo] — the same structure the panel's help screen
     * draws. Nothing about a command is written twice, which is the only reason the spoken
     * answer and the screen can be trusted to agree.
     *
     * At most one hint is spoken. A screen can carry three; an answer to a question somebody
     * asked out loud cannot, and the rest is on the panel.
     */
    private fun explainCommand(introspection: Introspection, spoken: String): String {
        val command = resolveCommand(introspection, spoken)
            ?: return "Das Kommando kenne ich nicht. Frag zum Beispiel: Was kann " +
                (introspection.socks().firstOrNull { it.id != id }?.displayName ?: "Hilfe") + "?"

        return buildString {
            append(command.title).append(": ").append(command.detail.trimEnd('.')).append('.')
            command.usage?.let { append(" Sag zum Beispiel: ‚").append(it).append("‘.") }
            command.hints.firstOrNull()?.let { append(' ').append(it) }
        }
    }

    /**
     * Resolves a spoken command name against everything a person might have called it.
     *
     * Wider than [resolve] for areas, because there is no article to strip and no short list to
     * hear: somebody says "Timer", or the title, or an alias, or the sentence they remember
     * saying last time. Exact beats fuzzy beats partial, in that order, so a name that is
     * genuinely one command's never loses to a longer one that merely contains it.
     *
     * Command **ids** are matched too. Nobody says "clock.set_timer" to a wall panel, but it is
     * what the terminal and the fallthrough log print, and somebody reading one of those is
     * exactly the person who then asks the panel about it.
     */
    private fun resolveCommand(introspection: Introspection, spoken: String): CommandInfo? {
        val needle = spoken.lowercase()
            .removePrefix("das ").removePrefix("den ").removePrefix("der ")
            .removePrefix("kommando ").removePrefix("befehl ")
            .trim()
        if (needle.isEmpty()) return null

        val commands = introspection.commands()
        fun names(command: CommandInfo): List<String> =
            listOf(command.title.lowercase(), command.id, command.id.substringAfter('.').replace('_', ' ')) +
                command.aliases.map { it.lowercase() }

        return commands.firstOrNull { command -> names(command).any { it == needle } }
            ?: commands.firstOrNull { it.examples.any { example -> example == needle } }
            ?: commands.firstOrNull { command ->
                names(command).any { Levenshtein.fuzzyEquals(needle, it) }
            }
            // Partial last, and shortest title first: "timer" is closer to "Timer stellen" than
            // to "Alle Timer abbrechen", and a listener who said one word meant the plain one.
            ?: commands.filter { command -> names(command).any { needle in it || it in needle } }
                .minByOrNull { it.title.length }
    }

    /** What to actually say is more useful over a speaker than what the command is called. */
    private fun phraseFor(command: CommandInfo): String? = command.examples.firstOrNull()

    /**
     * Resolves a spoken name against display names and ids.
     *
     * STT will hand over "die uhr", "uhr" or a near miss, so articles are stripped and the
     * comparison is fuzzy — the candidate set is closed, which makes that safe.
     */
    private fun resolve(introspection: Introspection, spoken: String): SockInfo? {
        val needle = spoken.lowercase()
            .removePrefix("die ").removePrefix("der ").removePrefix("das ")
            .removePrefix("den ").removePrefix("dem ")
            .trim()
        if (needle.isEmpty()) return null

        val socks = introspection.socks()
        return socks.firstOrNull { it.id == needle || it.displayName.lowercase() == needle }
            ?: socks.firstOrNull {
                Levenshtein.fuzzyEquals(needle, it.displayName.lowercase()) ||
                    Levenshtein.fuzzyEquals(needle, it.id)
            }
    }

    private fun count(n: Int): String = if (n == 1) "einen Befehl" else "$n Befehle"

    private fun germanList(items: List<String>): String = when (items.size) {
        0 -> ""
        1 -> items.single()
        else -> items.dropLast(1).joinToString(", ") + " und " + items.last()
    }

    companion object {
        const val OVERVIEW: String = "help.overview"
        const val SOCK_COMMANDS: String = "help.sock_commands"
        const val EXPLAIN_COMMAND: String = "help.explain_command"
        private const val MAX_SPOKEN_EXAMPLES = 3
    }
}
