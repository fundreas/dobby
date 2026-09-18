package io.dobby.cli

import io.dobby.core.DobbyEngine
import io.dobby.core.EngineOutcome
import io.dobby.core.dispatch.Dispatcher
import io.dobby.core.registry.RegistryValidationException
import io.dobby.core.registry.SockRegistry
import io.dobby.core.sock.Sock
import io.dobby.core.sock.SockResult
import io.dobby.socks.devi.DeviSock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

/**
 * The one place that knows which Socks exist.
 *
 * In Phase B this moves into the Android app, with the development Socks confined to the
 * debug source set.
 */
object DobbySocks {
    fun all(out: (String) -> Unit = ::println): List<Sock> = listOf(
        DeviSock(out),
    )
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

    val registry = try {
        SockRegistry.buildOrThrow(DobbySocks.all())
    } catch (e: RegistryValidationException) {
        System.err.println(e.message)
        return@runBlocking
    }

    val collisions = registry.checkExamples()
    if (collisions.isNotEmpty()) {
        System.err.println("Palette collisions:")
        collisions.forEach { System.err.println("  - $it") }
    }

    val engine = DobbyEngine(
        registry = registry,
        dispatcher = Dispatcher(registry),
        onFallthrough = { fallthrough += it },
    )
    val context = ConsoleContext(scope) { verbose }
    engine.start(context)

    banner(registry)

    while (true) {
        print("> ")
        val line = readlnOrNull()?.trim() ?: break
        when {
            line.isEmpty() -> continue
            line == ":quit" || line == ":q" -> break
            line == ":help" -> help()
            line == ":socks" -> socks(registry)
            line == ":palette" -> palette(registry)
            line == ":fallthrough" -> {
                if (fallthrough.isEmpty()) println("  (none)")
                fallthrough.forEach { println("  $it") }
            }

            line == ":trace" -> {
                verbose = !verbose
                println("  trace ${if (verbose) "on" else "off"}")
            }

            line.startsWith(":") -> println("  unknown command. :help")
            else -> render(engine.handle(line), verbose)
        }
    }

    engine.stop()
    scope.cancel()
}

private fun banner(registry: SockRegistry) {
    println("Dobby — Phase A (core only, no audio)")
    println("${registry.socks.size} sock(s), ${registry.commands.size} command(s), ${registry.palette.entries.size} template(s)")
    println("Type an utterance, or :help")
    println()
}

private fun help() = println(
    """
    |  :socks        registered socks and their commands
    |  :palette      every template, in match order
    |  :fallthrough  utterances Tier 1 could not match
    |  :trace        toggle chain/debug output
    |  :quit         exit
    """.trimMargin(),
)

private fun socks(registry: SockRegistry) {
    for (sock in registry.socks) {
        println("  ${sock.id} (${sock.displayName}) — ${sock.status.value}")
        sock.commands.forEach { println("      ${it.id}") }
        sock.shared.forEach { println("      ${it.command.id}  [chain, priority ${it.priority}]") }
    }
}

private fun palette(registry: SockRegistry) {
    for (entry in registry.palette.entries) {
        val from = entry.contributedBy?.let { " +$it" } ?: ""
        println("  ${entry.command.id.padEnd(24)} \"${entry.template.source}\"$from")
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
        is SockResult.Failed -> println("  ✖ ${result.userMessage}")
        SockResult.Silent -> Unit
        SockResult.Deferred -> Unit
        SockResult.NotForMe -> println("  ✖ nobody handled this")
    }
    println()
}
