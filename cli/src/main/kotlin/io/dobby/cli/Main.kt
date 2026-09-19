package io.dobby.cli

import io.dobby.core.DobbyEngine
import io.dobby.core.EngineOutcome
import io.dobby.core.FallthroughLog
import io.dobby.core.Tier
import io.dobby.core.nlu.Normalizer
import io.dobby.core.nlu.llm.PromptGenerator
import io.dobby.core.nlu.llm.Tier2
import io.dobby.core.nlu.llm.Tier2Program
import io.dobby.core.nlu.llm.TokenEstimate
import io.dobby.core.dispatch.Dispatcher
import io.dobby.core.dispatch.SockHealth
import io.dobby.core.registry.Introspection
import io.dobby.core.registry.RegistryValidationException
import io.dobby.core.registry.SockRegistry
import io.dobby.core.sock.Sock
import io.dobby.core.sock.SockResult
import io.dobby.socks.clock.ClockSock
import io.dobby.socks.winky.WinkySock
import io.dobby.socks.help.HelpSock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

/**
 * The terminal harness's Sock list.
 *
 * The app has its own in `io.dobby.android.DobbySocks`, which is the one that ships. They are
 * separate on purpose: this one always includes Winky, because a development harness with no
 * development Sock is pointless, while the app's puts Winky in the debug source set so it
 * cannot reach a release build.
 */
object DobbySocks {
    /**
     * The Sock list, plus the callback that hands the help Sock its view of the finished
     * registry. Help is the one Sock that needs to see the palette it is part of, so it is
     * bound immediately after the registry is built.
     */
    class Wiring(val socks: List<Sock>, val bindDirectory: (Introspection) -> Unit)

    fun create(out: (String) -> Unit = ::println): Wiring {
        var directory: Introspection? = null
        val socks = listOf(
            // The terminal has no SoundPool, so the chime prints itself. A timer is then just
            // as testable here as on the panel — which is the whole point of the interface.
            ClockSock(chime = { sound -> out("  🔔 ${sound.configValue}") }),
            WinkySock(),
            HelpSock { directory },
        )
        return Wiring(socks) { directory = it }
    }
}

/**
 * Dobby in a terminal: type German, watch it resolve.
 *
 * Everything below the microphone and above the Socks is exercised here — normalizer,
 * template matcher, palette ordering, registry, dispatcher and chain — with no Android,
 * no hardware and no network.
 */
fun main(args: Array<String>) = runBlocking {
    var verbose = args.contains("--trace")
    val scope = CoroutineScope(SupervisorJob())
    val fallthrough = FallthroughLog()

    val wiring = DobbySocks.create()
    val registry = try {
        SockRegistry.buildOrThrow(wiring.socks)
    } catch (e: RegistryValidationException) {
        System.err.println(e.message)
        return@runBlocking
    }

    val collisions = registry.checkExamples()
    if (collisions.isNotEmpty()) {
        System.err.println("Palette collisions:")
        collisions.forEach { System.err.println("  - $it") }
    }

    // Not a failure — `lauter` and `stumm` are this shape and are correct. Listed so whoever
    // added one looks at it again (`socks.specs/README.md` §6).
    val singles = registry.checkSingleKeywordTemplates()
    if (singles.isNotEmpty()) {
        System.err.println("Single-keyword templates, worth an eyeball:")
        singles.forEach { System.err.println("  - $it") }
    }

    val health = SockHealth()
    val introspection = Introspection(registry, health)
    wiring.bindDirectory(introspection)
    val discovery = Discovery(introspection)
    // Tier 2 with the registry's own few-shots standing in for the model. The terminal will
    // never have llama.cpp, but the grammar, the decoder and the gate are pure :core and this
    // is where they are cheapest to exercise.
    val program = Tier2Program.ofOrNull(registry, introspection) { System.err.println(it) }
    val scripted = ScriptedTier2(registry, introspection)
    val engine = DobbyEngine(
        registry = registry,
        dispatcher = Dispatcher(registry, health),
        onFallthrough = fallthrough::record,
        tier2 = program?.let { Tier2(registry, scripted, it) },
    )
    val context = ConsoleContext(scope) { verbose }
    engine.start(context)

    banner(registry)

    while (true) {
        // A question Dobby asked is holding the floor: the next line answers it, exactly as
        // the next utterance would on the panel. The prompt says so, because nothing else does.
        print(if (engine.awaitingAnswer) "? " else "> ")
        val line = readlnOrNull()?.trim() ?: break
        if (line.isEmpty()) continue

        // Both prefixes work: "/" is what a person expects, ":" is muscle memory from vi-likes.
        if (line.startsWith("/") || line.startsWith(":")) {
            val parts = line.drop(1).split(' ', limit = 2)
            val argument = parts.getOrNull(1)?.trim().orEmpty()
            when (parts[0].lowercase()) {
                "quit", "q", "exit" -> break
                "help", "h", "?" -> println(help())
                "socks" -> println(discovery.socks())
                "commands", "command-palette", "cmds" ->
                    println(if (argument.isEmpty()) discovery.commands() else discovery.commands(argument))

                "palette" -> println(discovery.palette())
                "grammar" -> println(program?.grammar ?: NO_TIER2)
                "prompt" -> println(
                    program?.let { promptView(it, registry.commands.size, argument) } ?: NO_TIER2,
                )

                "tier2" -> println(
                    if (program == null) NO_TIER2 else tier2View(engine, scripted, argument),
                )
                "keywords", "keyword", "phonetics" -> println(discovery.keywords())
                "find", "search" -> println(find(introspection, argument))
                "fallthrough" -> {
                    val entries = fallthrough.entries.value
                    println(
                        if (entries.isEmpty()) {
                            "  (none)"
                        } else {
                            entries.joinToString("\n") { "  $it" }
                        },
                    )
                }

                "trace" -> {
                    verbose = !verbose
                    println("  trace ${if (verbose) "on" else "off"}")
                }

                else -> println("  unknown command '${parts[0]}'. /help")
            }
            println()
        } else {
            render(engine.handle(line), verbose)
        }
    }

    engine.stop()
    scope.cancel()
}

private const val NO_TIER2 = "  Tier 2 is disabled — see the error printed at startup"

/** `/prompt [utterance]` — the generated prompt, and how much room is left in the budget. */
private fun promptView(program: Tier2Program, commandCount: Int, utterance: String): String {
    val text = program.promptFor(utterance.ifEmpty { "<utterance>" })
    val system = TokenEstimate.of(program.systemPrefix)
    val perCommand = system.toDouble() / commandCount
    val headroom = ((PromptGenerator.MAX_TOKENS - system) / perCommand).toInt()
    return buildString {
        appendLine(text)
        appendLine("  ── estimate: $system tokens of ${PromptGenerator.MAX_TOKENS} for the system prefix")
        appendLine("     $commandCount commands at ~${"%.0f".format(perCommand)} tokens each")
        appendLine("     room for about $headroom more before the budget tripwire fires")
        append("     fingerprint ${program.fingerprint}")
    }
}

/**
 * `/tier2 <utterance>` — the whole Tier 2 path, with the script standing in for the model.
 *
 * `/tier2 <utterance> = <json>` forces the reply instead, which is how a hostile one is tried:
 * `/tier2 mach was = {"c":"nope"}` shows the gate rejecting an invented command.
 */
private suspend fun tier2View(engine: DobbyEngine, scripted: ScriptedTier2, argument: String): String {
    if (argument.isEmpty()) {
        return "  usage: /tier2 <utterance> [= <json the model would emit>]\n" +
            "  scripted: " + scripted.utterances.sorted().joinToString("\n            ")
    }
    val forced = argument.substringAfter(" = ", "").ifEmpty { null }
    val utterance = argument.substringBefore(" = ")
    scripted.override = forced
    return try {
        val outcome = engine.handle(utterance)
        buildString {
            appendLine("  normalized: ${Normalizer.normalize(utterance)}")
            appendLine("  model said: ${outcome.tier2?.raw ?: "(Tier 1 matched; the model was never asked)"}")
            appendLine("  ${outcome.tier2 ?: "tier: ${outcome.tier}"}")
            append("  → ${outcome.invocation?.let { "${it.commandId} ${it.params}" } ?: "nothing"}")
        }
    } finally {
        scripted.override = null
    }
}

private fun find(introspection: Introspection, query: String): String {
    if (query.isEmpty()) return "  usage: /find <text>"
    val hits = introspection.search(query)
    if (hits.isEmpty()) return "  nothing matches \"$query\""
    return hits.joinToString("\n") { "  ${it.signature.padEnd(46)} ${it.description}" }
}

private fun banner(registry: SockRegistry) {
    println("Dobby — terminal harness (core only, no audio)")
    println(
        "${registry.socks.size} sock(s), ${registry.commands.size} command(s), " +
            "${registry.palette.entries.size} template(s)",
    )
    println("Type an utterance, or /help")
    println()
}

private fun help() = """
    |  /socks               every sock, with status and command count
    |  /commands            every command Dobby knows
    |  /commands <sock>     one sock in detail: params, phrasings, templates
    |  /find <text>         commands matching a word
    |  /palette             every template, in the order the matcher tries them
    |  /keywords            every keyword, its phonetic code, and whether it is trusted
    |  /grammar             the GBNF the local model is constrained to
    |  /prompt [utterance]  the Tier 2 prompt, with its token estimate and headroom
    |  /tier2 <utterance>   run the Tier 2 path; "<utterance> = <json>" forces the reply
    |  /fallthrough         utterances Tier 1 could not match, and what Tier 2 made of them
    |  /trace               toggle normalizer and template output
    |  /quit                exit
""".trimMargin()

private fun render(outcome: EngineOutcome, verbose: Boolean) {
    if (verbose) {
        println("  normalized: ${outcome.normalized}")
        outcome.matched?.let { println("  template:   \"${it.template.source}\"") }
    }
    outcome.invocation?.let { invocation ->
        val params = if (invocation.params.isEmpty()) "" else " ${invocation.params}"
        println("  → ${invocation.commandId}$params")
    }
    outcome.trace.forEach { step ->
        val where = step.activity?.let { "chain: ${step.sockId} $it" } ?: "handled by ${step.sockId}"
        println("      $where ${step.outcome}")
    }

    when (val result = outcome.result) {
        is SockResult.Spoken -> println("  🔊 ${result.text}")
        // The floor is Dobby's until the next line: core is holding the question open and will
        // route whatever is typed next back to the Sock that asked it.
        is SockResult.Asked -> println("  ❓ ${result.text}")
        is SockResult.Failed -> println("  ✖ ${result.userMessage}")
        // On the panel this is where the microphone closes and the wake word comes back. In a
        // terminal there is nothing to close, so it reads as an ordinary answer with a full
        // stop after it — which is exactly what it is.
        is SockResult.Ended -> result.text?.let { println("  🔊 $it") }
        SockResult.Silent -> Unit
        SockResult.Deferred -> Unit
        SockResult.NotForMe -> println("  ✖ nobody handled this")
    }
    println()
}
