package io.dobby.core.registry

import io.dobby.core.FixtureSock
import io.dobby.core.sock.ExclusiveCommandSpec
import io.dobby.core.sock.ParamSpec
import io.dobby.core.sock.ParamType
import io.dobby.core.sock.SharedCommandSpec
import io.dobby.core.sock.SharedSubscription
import io.dobby.core.sock.Sock
import io.dobby.core.sock.SockResult
import io.dobby.core.sock.pattern
import io.dobby.core.sock.patterns
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RegistryValidationTest {

    private fun sock(
        id: String,
        commands: List<ExclusiveCommandSpec> = emptyList(),
        shared: List<SharedSubscription> = emptyList(),
    ): Sock = FixtureSock(id, commands, shared, handler = { SockResult.Silent })

    private fun command(id: String, vararg templates: String) =
        ExclusiveCommandSpec(id, patterns(*templates), "test")

    private fun errorsOf(vararg socks: Sock): List<String> = SockRegistry.build(socks.toList()).errors

    private fun assertRejected(vararg socks: Sock, because: String) {
        val errors = errorsOf(*socks)
        assertTrue(
            errors.any { it.contains(because) },
            "expected an error mentioning \"$because\", got $errors",
        )
    }

    @Test
    fun `accepts a well-formed sock`() {
        assertEquals(emptyList(), errorsOf(sock("devi", listOf(command("devi.hello", "hello")))))
    }

    @Test
    fun `rejects duplicate sock ids`() {
        assertRejected(
            sock("devi", listOf(command("devi.a", "a b"))),
            sock("devi", listOf(command("devi.b", "c d"))),
            because = "duplicate sock id",
        )
    }

    @Test
    fun `rejects a command id that is not prefixed with its sock`() {
        assertRejected(
            sock("devi", listOf(command("spotify.play", "spiel das"))),
            because = "must be prefixed with 'devi.'",
        )
    }

    @Test
    fun `rejects duplicate command ids`() {
        assertRejected(
            sock("devi", listOf(command("devi.hello", "hello"), command("devi.hello", "hallo"))),
            because = "duplicate command id",
        )
    }

    @Test
    fun `rejects a sock squatting on the shared namespace`() {
        assertRejected(
            sock("devi", listOf(command("shared.stop", "stopp"))),
            because = "reserved shared namespace",
        )
    }

    @Test
    fun `rejects the reserved none command`() {
        assertRejected(sock("devi", listOf(command("none", "nichts"))), because = "reserved by core")
    }

    @Test
    fun `rejects a malformed template`() {
        assertRejected(sock("devi", listOf(command("devi.a", "(unclosed"))), because = "invalid template")
    }

    @Test
    fun `rejects a command with no templates`() {
        assertRejected(
            sock("devi", listOf(ExclusiveCommandSpec("devi.a", emptyList(), "test"))),
            because = "declares no templates",
        )
    }

    @Test
    fun `rejects a slot with no matching param`() {
        assertRejected(
            sock("devi", listOf(command("devi.a", "spiele {query}"))),
            because = "declares no such param",
        )
    }

    @Test
    fun `rejects a static param with no matching param`() {
        assertRejected(
            sock(
                "devi",
                listOf(ExclusiveCommandSpec("devi.a", listOf(pattern("lauter", "steps" to 5)), "test")),
            ),
            because = "fixes param 'steps'",
        )
    }

    @Test
    fun `rejects a bare text slot, which would match every utterance`() {
        assertRejected(
            sock(
                "devi",
                listOf(
                    ExclusiveCommandSpec(
                        "devi.a",
                        patterns("{anything}"),
                        "test",
                        params = listOf(ParamSpec("anything", ParamType.Text)),
                    ),
                ),
            ),
            because = "bare text slot",
        )
    }

    @Test
    fun `accepts a bare enum slot, whose candidate set is closed`() {
        val errors = errorsOf(
            sock(
                "system",
                listOf(
                    ExclusiveCommandSpec(
                        "system.volume",
                        patterns("{direction:enum}"),
                        "test",
                        params = listOf(
                            ParamSpec("direction", ParamType.Enumeration(listOf("lauter", "leiser"))),
                        ),
                    ),
                ),
            ),
        )
        assertEquals(emptyList(), errors)
    }

    @Test
    fun `two socks may share a command - that is the point`() {
        val stop = SharedCommandSpec(
            id = "shared.stop",
            templates = patterns("stopp"),
            description = "test",
            unconsumedResponse = SockResult.Silent,
        )
        val errors = errorsOf(
            sock("a", shared = listOf(SharedSubscription(stop, priority = 10))),
            sock("b", shared = listOf(SharedSubscription(stop, priority = 20))),
        )
        assertEquals(emptyList(), errors)

        val registry = SockRegistry.buildOrThrow(
            listOf(
                sock("a", shared = listOf(SharedSubscription(stop, priority = 10))),
                sock("b", shared = listOf(SharedSubscription(stop, priority = 20))),
            ),
        )
        assertEquals(2, registry.chainFor("shared.stop")?.subscribers?.size)
        // …and the template is registered once, not once per subscriber.
        assertEquals(1, registry.palette.entries.count { it.command.id == "shared.stop" })
    }

    @Test
    fun `rejects subscribers that disagree about a shared command's params`() {
        val base = SharedCommandSpec(
            id = "shared.stop",
            templates = patterns("stopp"),
            description = "test",
            unconsumedResponse = SockResult.Silent,
        )
        val divergent = base.copy(params = listOf(ParamSpec("what", ParamType.Text)))
        assertRejected(
            sock("a", shared = listOf(SharedSubscription(base))),
            sock("b", shared = listOf(SharedSubscription(divergent))),
            because = "declare different params",
        )
    }

    @Test
    fun `buildOrThrow reports every problem at once`() {
        val failure = assertFailsWith<RegistryValidationException> {
            SockRegistry.buildOrThrow(listOf(sock("Devi", listOf(command("wrong.id", "(bad")))))
        }
        assertTrue(failure.errors.size >= 3, failure.errors.toString())
    }
}
