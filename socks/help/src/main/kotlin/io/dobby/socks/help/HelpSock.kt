package io.dobby.socks.help

import io.dobby.core.nlu.template.Levenshtein
import io.dobby.core.registry.CommandInfo
import io.dobby.core.registry.Introspection
import io.dobby.core.registry.SockInfo
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
            examples = listOf(
                Example("was kann die uhr", mapOf("sock" to "uhr")),
                Example("welche befehle hat die uhr", mapOf("sock" to "uhr")),
                Example("hilfe zu spotify", mapOf("sock" to "spotify")),
                Example("erzähl mir was die uhr so kann", mapOf("sock" to "uhr"), heldOut = true),
                Example("womit kann mir der rechner helfen", mapOf("sock" to "rechner"), heldOut = true),
                Example("was geht denn mit spotify", mapOf("sock" to "spotify"), heldOut = true),
            ),
        ),
    )

    override suspend fun handle(invocation: CommandInvocation): SockResult {
        val introspection = directory()
            ?: return SockResult.Failed("Ich kann meine Befehle gerade nicht nachschlagen.")

        return when (invocation.commandId) {
            OVERVIEW -> SockResult.Spoken(overview(introspection))
            SOCK_COMMANDS -> SockResult.Spoken(describeSock(introspection, invocation.text("sock")))
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
        private const val MAX_SPOKEN_EXAMPLES = 3
    }
}
