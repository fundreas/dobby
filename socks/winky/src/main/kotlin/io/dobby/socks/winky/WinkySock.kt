package io.dobby.socks.winky

import io.dobby.core.sock.CommandHelp
import io.dobby.core.sock.CommandInvocation
import io.dobby.core.sock.Example
import io.dobby.core.sock.ExclusiveCommandSpec
import io.dobby.core.sock.Sock
import io.dobby.core.sock.patterns
import io.dobby.core.sock.SockResult

/**
 * Winky — the development Sock.
 *
 * Exists to prove the wiring end to end without dragging in Spotify, a network or an account.
 * It is a real Sock (it goes through the registry, the palette and the dispatcher like any
 * other), but it is not a product Sock and must not ship in a release build.
 */
class WinkySock : Sock {

    override val id: String = "winky"

    override val displayName: String = "Winky"

    override val commands: List<ExclusiveCommandSpec> = listOf(
        ExclusiveCommandSpec(
            id = HELLO,
            templates = patterns(
                "(hello|hallo|hi) winky",
                "hello",
            ),
            description = "Begrüßt Winky, den Entwickler-Sock.",
            help = CommandHelp(
                title = "Winky grüßen",
                detail = "Der Entwickler-Sock. Antwortet und tut sonst nichts — er ist der " +
                    "Beweis, dass die Kette vom Mikrofon bis zur Stimme steht.",
                aliases = listOf("winky", "hallo"),
            ),
            examples = listOf(
                Example("hello"),
                Example("hallo winky"),
                Example("hello winky"),
            ),
        ),
    )

    override suspend fun handle(invocation: CommandInvocation): SockResult = when (invocation.commandId) {
        // Spoken, not Silent: the greeting IS the answer, so it belongs in the chat and in the
        // room. Core decides how to deliver it — a Sock never speaks or prints on its own.
        HELLO -> SockResult.Spoken(GREETING)

        else -> SockResult.NotForMe
    }

    companion object {
        const val HELLO: String = "winky.hello"
        const val GREETING: String = "Hallo Meister"
    }
}
