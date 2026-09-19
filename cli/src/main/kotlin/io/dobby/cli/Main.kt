package io.dobby.cli

import io.dobby.core.DobbyEngine
import io.dobby.core.EngineOutcome
import io.dobby.core.dispatch.Dispatcher
import io.dobby.core.dispatch.SockHealth
import io.dobby.core.registry.Introspection
import io.dobby.core.registry.RegistryValidationException
import io.dobby.core.registry.SockRegistry
import io.dobby.core.sock.Sock
import io.dobby.core.sock.SockResult
import io.dobby.socks.clock.ClockSock
import io.dobby.socks.devi.DeviSock
import io.dobby.socks.help.HelpSock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

/**
 * The terminal harness's Sock list.
 *
 * The app has its own in `io.dobby.android.DobbySocks`, which is the one that ships. They are
 * separate on purpose: this one always includes Devi, because a development harness with no
 * development Sock is pointless, while the app's puts Devi in the debug source set so it
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
            DeviSock(out),
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
    val fallthrough = mutableListOf<String>()

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

    val health = SockHealth()
    val introspection = Introspection(registry, health)
    wiring.bindDirectory(introspection)
    val discovery = Discovery(introspection)
    val engine = DobbyEngine(
        registry = registry,
        dispatcher = Dispatcher(registry, health),
        onFallthrough = { fallthrough += it },
    )
    val context = ConsoleContext(scope) { verbose }
    engine.start(context)

    banner(registry)

    while (true) {
        print("> ")
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
                "find", "search" -> println(find(introspection, argument))
                "fallthrough" ->
                    println(fallthrough.ifEmpty { listOf("(none)") }.joinToString("\n") { "  $it" })

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
    |  /fallthrough         utterances Tier 1 could not match
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
        is SockResult.Failed -> println("  ✖ ${result.userMessage}")
        SockResult.Silent -> Unit
        SockResult.Deferred -> Unit
        SockResult.NotForMe -> println("  ✖ nobody handled this")
    }
    println()
}
