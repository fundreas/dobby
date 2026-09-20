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
import io.dobby.socks.calculator.CalculatorSock
import io.dobby.socks.clock.ClockSock
import io.dobby.socks.conversation.ConversationSock
import io.dobby.socks.spotify.SpotifySock
import io.dobby.socks.system.SystemSock
import io.dobby.socks.system.VolumeControl
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
            CalculatorSock(),
            ConversationSock(),
            // No App Remote and no credentials in a terminal, so every command fails with the
            // spoken line from the spec — which is the point: the palette is complete, the
            // utterance tables are asserted, and "spiele blinding lights" routes correctly
            // long before a device is involved.
            SpotifySock(),
            // A stream that exists only in memory, so "lauter" and "volle Lautstärke" really
            // change a number here — the same reason the terminal's chime prints itself
            // instead of being silent.
            SystemSock(VolumeControl.inMemory()),
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

    // A failure, unlike the list below: a filler that is a command on its own, or a pair of
    // commands that only a filler tells apart, is a misroute waiting for the right sentence.
    val fillers = registry.checkFillers()
    if (fillers.isNotEmpty()) {
        System.err.println("Filler list conflicts:")
        fillers.forEach { System.err.println("  - $it") }
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
        onRescue = fallthrough::record,
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
                "grammar" -> println(program?.let { grammarView(it, argument) } ?: NO_TIER2)
                "prompt" -> println(
                    program?.let { promptView(it, registry.commands.size, argument) } ?: NO_TIER2,
                )

                "tier2" -> println(
                    if (program == null) NO_TIER2 else tier2View(engine, scripted, argument),
                )
                "keywords", "keyword", "phonetics" -> println(discovery.keywords())
                "find", "search" -> println(find(introspection, argument))
                "fallthrough" -> println(fallthroughView(fallthrough))

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

/**
 * `/prompt` — the route prompt and its headroom. `/prompt <command>` — that command's fill turn.
 *
 * Two budgets, because they are two different costs. The route prefix is prefilled once per
 * process and lives in the KV cache; a fill turn is re-decoded on every request that needs one,
 * which is why it gets the much smaller [PromptGenerator.FILL_TURN_MAX_TOKENS].
 */
private fun promptView(program: Tier2Program, commandCount: Int, argument: String): String {
    if (argument.isNotEmpty()) return fillView(program, argument)
    val prefix = program.route.systemPrefix
    val estimate = TokenEstimate.of(prefix)
    val perCommand = estimate.toDouble() / commandCount
    val headroom = ((PromptGenerator.MAX_TOKENS - estimate) / perCommand).toInt()
    return buildString {
        appendLine(prefix + "<utterance>" + PromptGenerator.ASSISTANT_SUFFIX)
        appendLine("  ── route: $estimate tokens of ${PromptGenerator.MAX_TOKENS}")
        appendLine("     $commandCount commands at ~${"%.0f".format(perCommand)} tokens each")
        appendLine("     room for about $headroom more before the budget tripwire fires")
        appendLine("     ${program.fill.size} of $commandCount commands need a fill step")
        append("     fingerprint ${program.fingerprint}   (/prompt <command> for a fill turn)")
    }
}

private fun fillView(program: Tier2Program, commandId: String): String {
    val fill = program.fill[commandId] ?: return noFill(program, commandId)
    val estimate = TokenEstimate.of(fill.userTurn)
    return buildString {
        appendLine(fill.userTurn)
        appendLine("  ── fill turn: $estimate tokens of ${PromptGenerator.FILL_TURN_MAX_TOKENS}")
        append("     uncached — this is re-decoded on every request that routes to $commandId")
    }
}

private fun noFill(program: Tier2Program, commandId: String): String = when (commandId) {
    in program.commandIds ->
        "  $commandId has no parameters — the route answers it whole, with no second request"

    else -> "  unknown command '$commandId'. with params: " + program.fill.keys.sorted().joinToString(", ")
}

/** `/grammar` — the route grammar. `/grammar <command>` — that command's fill grammar. */
private fun grammarView(program: Tier2Program, commandId: String): String {
    if (commandId.isEmpty()) return program.route.grammar
    return program.fill[commandId]?.grammar ?: noFill(program, commandId)
}

/**
 * `/tier2 <utterance>` — both steps and the gate's verdict.
 *
 * `/tier2 <utterance> = <route label>` forces step 1, and `= <label> | <params json>` forces
 * both. That is how a hostile answer is tried without a model:
 * `/tier2 mach was = nope.nope` shows the gate rejecting an invented label.
 */
private suspend fun tier2View(engine: DobbyEngine, scripted: ScriptedTier2, argument: String): String {
    if (argument.isEmpty()) {
        return "  usage: /tier2 <utterance> [= <label> [| <params json>]]\n" +
            "  scripted: " + scripted.utterances.sorted().joinToString("\n            ")
    }
    val forced = argument.substringAfter(" = ", "")
    val utterance = argument.substringBefore(" = ")
    scripted.overrideRoute = forced.substringBefore(" | ").trim().ifEmpty { null }
    scripted.overrideFill = forced.substringAfter(" | ", "").trim().ifEmpty { null }
    return try {
        val outcome = engine.handle(utterance)
        val trace = outcome.tier2
        buildString {
            appendLine("  normalized: ${Normalizer.normalize(utterance)}")
            if (trace == null) {
                appendLine("  tier 1 matched; the model was never asked")
            } else {
                appendLine("  route said:  ${trace.route?.raw ?: "(never ran)"}")
                appendLine("  fill said:   ${trace.fill?.raw ?: "(not needed)"}")
                appendLine("  $trace")
            }
            append("  → ${outcome.invocation?.let { "${it.commandId} ${it.params}" } ?: "nothing"}")
        }
    } finally {
        scripted.overrideRoute = null
        scripted.overrideFill = null
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
    |  /grammar [command]   the route GBNF, or one command's fill GBNF
    |  /prompt [command]    the route prompt and its headroom, or one command's fill turn
    |  /tier2 <utterance>   run both Tier 2 steps; "= <label> | <json>" forces the answers
    |  /fallthrough         utterances Tier 1 could not match, plus the ones filler skipping saved
    |  /trace               toggle normalizer and template output
    |  /quit                exit
""".trimMargin()

/**
 * `/fallthrough` — what Tier 1 missed, and what the second pass caught.
 *
 * Both halves in one view because they are the two ends of the same question. A phrasing that
 * keeps appearing under "missed" is a template somebody should add; a phrasing that keeps
 * appearing under "skipped filler" is the filler list earning its keep — or, if it looks wrong,
 * a word that should come off it.
 */
private fun fallthroughView(log: FallthroughLog): String {
    val missed = log.entries.value
    val rescued = log.rescues.value
    return buildString {
        appendLine("  missed (${missed.size}):")
        appendLine(if (missed.isEmpty()) "    (none)" else missed.joinToString("\n") { "    $it" })
        appendLine("  skipped filler (${rescued.size}):")
        append(if (rescued.isEmpty()) "    (none)" else rescued.joinToString("\n") { "    $it" })
    }
}

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
