package io.dobby.socks.devi

import io.dobby.core.sock.CommandInvocation
import io.dobby.core.sock.Example
import io.dobby.core.sock.ExclusiveCommandSpec
import io.dobby.core.sock.Sock
import io.dobby.core.sock.patterns
import io.dobby.core.sock.SockResult

/**
 * Devi — the development Sock.
 *
 * Exists to prove the wiring end to end without dragging in Spotify, a network or an account.
 * It is a real Sock (it goes through the registry, the palette and the dispatcher like any
 * other), but it is not a product Sock and must not ship in a release build.
 *
 * The greeting goes to [out] rather than straight to `println` so the same Sock works on
 * Android, where "print" will have to mean something else.
 */
class DeviSock(
    private val out: (String) -> Unit = ::println,
) : Sock {

    override val id: String = "devi"

    override val displayName: String = "Devi"

    override val commands: List<ExclusiveCommandSpec> = listOf(
        ExclusiveCommandSpec(
            id = HELLO,
            templates = patterns(
                "(hello|hallo|hi) devi",
                "hello",
            ),
            description = "Begrüßt Devi, den Entwickler-Sock.",
            examples = listOf(
                Example("hello"),
                Example("hallo devi"),
                Example("hello devi"),
            ),
        ),
    )

    override suspend fun handle(invocation: CommandInvocation): SockResult = when (invocation.commandId) {
        HELLO -> {
            out(GREETING)
            // Silent, not Spoken: the printing IS the action here. When this moves to the phone,
            // `out` changes and the result type does not.
            SockResult.Silent
        }

        else -> SockResult.NotForMe
    }

    companion object {
        const val HELLO: String = "devi.hello"
        const val GREETING: String = "hello Devi"
    }
}
