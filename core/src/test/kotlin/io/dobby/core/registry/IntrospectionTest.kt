package io.dobby.core.registry

import io.dobby.core.FixtureSock
import io.dobby.core.dispatch.SockHealth
import io.dobby.core.sock.ExclusiveCommandSpec
import io.dobby.core.sock.ParamSpec
import io.dobby.core.sock.ParamType
import io.dobby.core.sock.SharedCommands
import io.dobby.core.sock.SharedSubscription
import io.dobby.core.sock.SockStatus
import io.dobby.core.sock.patterns
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IntrospectionTest {

    private val registry = SockRegistry.buildOrThrow(SpecFixtures.all())
    private val introspection = Introspection(registry)

    @Test
    fun `lists every sock`() {
        assertEquals(
            listOf("clock", "departures", "radio", "spotify", "system"),
            introspection.sockIds(),
        )
        assertEquals(SockStatus.Ready, introspection.sock("spotify")!!.status)
    }

    @Test
    fun `counts a sock's chains as part of what it does`() {
        val spotify = introspection.sock("spotify")!!
        assertEquals(4, spotify.commands.size)
        assertEquals(listOf("shared.stop", "shared.resume"), spotify.chains.map { it.commandId })
        assertEquals(6, spotify.commandCount)
    }

    @Test
    fun `lists every command, exclusive before shared`() {
        val commands = introspection.commands()
        val firstShared = commands.indexOfFirst { it.kind == CommandKind.SHARED }
        assertTrue(commands.take(firstShared).all { it.kind == CommandKind.EXCLUSIVE })
        assertContains(commands.map { it.id }, "shared.stop")
        assertContains(commands.map { it.id }, "clock.set_timer")
    }

    @Test
    fun `a sock's listing includes the chains it subscribes to`() {
        val ids = introspection.commands("clock")!!.map { it.id }
        assertEquals(
            listOf("clock.set_timer", "clock.cancel_timer", "clock.whats_the_time", "shared.stop"),
            ids,
        )
    }

    @Test
    fun `an unknown sock is reported, not thrown`() {
        assertNull(introspection.commands("weather"))
        assertNull(introspection.sock("weather"))
    }

    @Test
    fun `describes params with types and defaults`() {
        val timer = introspection.command("clock.set_timer")!!
        assertEquals(
            listOf("amount: int", "unit: enum[sekunden|minuten|stunden]"),
            timer.params.map { it.toString() },
        )
        assertEquals(
            "clock.set_timer(amount: int, unit: enum[sekunden|minuten|stunden])",
            timer.signature,
        )

        val play = introspection.command("spotify.play_music")!!
        assertEquals(listOf("query: text = \"\""), play.params.map { it.toString() })
    }

    @Test
    fun `a shared command reports its chain in ranking order and owns nobody`() {
        val stop = introspection.command("shared.stop")!!
        assertEquals(CommandKind.SHARED, stop.kind)
        assertNull(stop.ownerSockId)
        assertEquals(
            listOf("clock" to 100, "spotify" to 50, "radio" to 50),
            stop.subscribers.map { it.sockId to it.priority },
        )
    }

    @Test
    fun `templates are listed in match order and name their contributor`() {
        val stop = introspection.command("shared.stop")!!
        // Clock's chime phrasings are marked, so it is visible that they are not catalog-level.
        assertTrue(stop.templates.any { it.contains("(+clock)") }, stop.templates.toString())

        val radio = introspection.command("radio.play_radio")!!
        val closed = radio.templates.indexOfFirst { it.contains("radio (an|ein)?") }
        val open = radio.templates.indexOfFirst { it.contains("radio {station}") }
        assertTrue(closed < open, "listing must reflect the real match order: $radio")
    }

    @Test
    fun `only tier 1 examples are advertised`() {
        // Tier 2 paraphrases are not phrasings a user can rely on, so they are not shown.
        val stop = introspection.command("shared.stop")!!
        assertEquals(listOf("stopp", "pause"), stop.examples)
    }

    @Test
    fun `search matches ids and descriptions`() {
        assertContains(introspection.search("timer").map { it.id }, "clock.set_timer")
        assertContains(introspection.search("Radiosender").map { it.id }, "radio.play_radio")
        assertEquals(emptyList(), introspection.search("wetter"))
        assertEquals(emptyList(), introspection.search("  "))
    }

    @Test
    fun `dispatcher-observed degradation overrides the sock's own status`() {
        val health = SockHealth()
        health.degrade("radio", "Stream abgerissen")
        val degraded = Introspection(registry, health)

        assertEquals(SockStatus.Degraded("Stream abgerissen"), degraded.sock("radio")!!.status)
        assertEquals(SockStatus.Ready, degraded.sock("clock")!!.status)
    }

    @Test
    fun `a sock with no commands at all is still listed`() {
        val empty = Introspection(SockRegistry.buildOrThrow(listOf(FixtureSock("ghost"))))

        assertEquals(listOf("ghost"), empty.sockIds())
        assertEquals(emptyList(), empty.commands("ghost"))
        assertEquals(0, empty.sock("ghost")!!.commandCount)
    }

    @Test
    fun `a chain with one subscriber still reports it`() {
        val lonely = SockRegistry.buildOrThrow(
            listOf(
                FixtureSock(
                    id = "clock",
                    commands = listOf(
                        ExclusiveCommandSpec(
                            "clock.set_timer",
                            patterns("timer {n:int} minuten"),
                            "test",
                            params = listOf(ParamSpec("n", ParamType.Integer)),
                        ),
                    ),
                    shared = listOf(SharedSubscription(SharedCommands.STOP, priority = 100)),
                ),
            ),
        )

        assertEquals(
            listOf("clock" to 100),
            Introspection(lonely).command("shared.stop")!!.subscribers.map { it.sockId to it.priority },
        )
    }

    /**
     * [Introspection.promptExamples] is the Tier 2 view, and it is the inverse of the spoken one.
     */
    @Test
    fun `prompt examples put the paraphrases first and keep their params`() {
        val sock = io.dobby.core.FixtureSock(
            id = "kitchen",
            commands = listOf(
                io.dobby.core.sock.ExclusiveCommandSpec(
                    id = "kitchen.bake",
                    params = listOf(io.dobby.core.sock.ParamSpec("minutes", io.dobby.core.sock.ParamType.Integer)),
                    templates = io.dobby.core.sock.patterns("backe {minutes:int} minuten"),
                    description = "Bäckt.",
                    examples = listOf(
                        io.dobby.core.sock.Example("backe 20 minuten", mapOf("minutes" to 20)),
                        io.dobby.core.sock.Example(
                            "schieb das brot für 20 minuten rein",
                            mapOf("minutes" to 20),
                            matchedByTemplates = false,
                        ),
                    ),
                ),
            ),
            shared = listOf(
                io.dobby.core.sock.SharedSubscription(
                    io.dobby.core.sock.SharedCommands.STOP,
                    extraExamples = listOf(io.dobby.core.sock.Example("ofen aus")),
                ),
            ),
        )
        val directory = Introspection(SockRegistry.buildOrThrow(listOf(sock)))

        val examples = directory.promptExamples("kitchen.bake")
        assertEquals(
            listOf("schieb das brot für 20 minuten rein", "backe 20 minuten"),
            examples.map { it.utterance },
            "the paraphrase must come first — it is the case templates cannot reach",
        )
        assertEquals(mapOf("minutes" to 20), examples.first().params, "a few-shot without params is not one")

        // The spoken view still hides the paraphrase and still drops the params.
        assertEquals(listOf("backe 20 minuten"), directory.command("kitchen.bake")!!.examples)

        // extraExamples reach the prompt, which until now nothing read at all: the catalog's
        // own paraphrases first, then this Sock's contribution, then the catalog's Tier 1 ones.
        assertEquals(
            listOf("hör bitte auf damit", "mach das mal aus", "ofen aus", "stopp", "pause"),
            directory.promptExamples("shared.stop").map { it.utterance },
        )
        assertEquals(emptyList(), directory.promptExamples("kitchen.nope"))
    }
}
