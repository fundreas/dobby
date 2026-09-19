package io.dobby.core

import io.dobby.core.nlu.llm.Tier2
import io.dobby.core.nlu.llm.Tier2Outcome
import io.dobby.core.nlu.llm.Tier2Program
import io.dobby.core.registry.SockRegistry
import io.dobby.core.sock.CommandInvocation
import io.dobby.core.sock.ExclusiveCommandSpec
import io.dobby.core.sock.ParamSpec
import io.dobby.core.sock.ParamType
import io.dobby.core.sock.SockResult
import io.dobby.core.sock.patterns
import io.dobby.core.testing.FakeSockContext
import io.dobby.core.testing.FakeTier2Resolver
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tier 2 where it actually lives: inside `handle()`, after the palette misses.
 *
 * That placement is the point. `wasUnderstood` is the *post*-Tier-2 answer, which is why the
 * Android side needs no change at all to buzz at the right moment — see `fallThrough`'s KDoc.
 */
class DobbyEngineTier2Test {

    private val sock = FixtureSock(
        id = "clock",
        commands = listOf(
            ExclusiveCommandSpec(
                id = "clock.set_timer",
                params = listOf(
                    ParamSpec("amount", ParamType.Integer),
                    ParamSpec("unit", ParamType.Enumeration(listOf("minuten", "stunden"))),
                ),
                templates = patterns("timer {amount:int} {unit:enum}"),
                description = "Stellt einen Timer.",
            ),
        ),
        handler = { SockResult.Spoken("Timer läuft.") },
    )

    private val registry = SockRegistry.buildOrThrow(listOf(sock))
    private val program = Tier2Program.of(registry)

    /**
     * @param routes normalized utterance → the command id the route step answers with.
     * @param fills normalized utterance → the params JSON the fill step answers with.
     */
    private fun engine(
        routes: Map<String, String> = emptyMap(),
        fills: Map<String, String> = emptyMap(),
        onFallthrough: (Fallthrough) -> Unit = {},
    ): DobbyEngine = DobbyEngine(
        registry = registry,
        onFallthrough = onFallthrough,
        tier2 = Tier2(registry, FakeTier2Resolver(routes, fills), program),
    )

    @Test
    fun `with no resolver the engine is exactly what it was`() = runTest {
        val engine = DobbyEngine(registry)
        engine.start(FakeSockContext(this))
        val outcome = engine.handle("mach mir bitte irgendwas")
        assertNull(outcome.invocation)
        assertEquals(Tier.NONE, outcome.tier)
        assertNull(outcome.tier2, "nothing may be consulted when there is no Tier 2")
        assertEquals(DobbyEngine.NOT_UNDERSTOOD, (outcome.result as SockResult.Spoken).text)
    }

    @Test
    fun `an utterance no template reaches is resolved by the model and dispatched normally`() = runTest {
        val engine = engine(
            routes = mapOf("weck mich in 20 minuten" to "clock.set_timer"),
            fills = mapOf("weck mich in 20 minuten" to """{"amount":20,"unit":"minuten"}"""),
        )
        engine.start(FakeSockContext(this))

        val outcome = engine.handle("Weck mich in zwanzig Minuten")
        assertTrue(outcome.wasUnderstood, "Tier 2 resolved it, so the turn must not buzz")
        assertEquals(Tier.MODEL, outcome.tier)
        assertEquals(CommandInvocation("clock.set_timer", mapOf("amount" to 20, "unit" to "minuten")), outcome.invocation)
        assertEquals("Timer läuft.", (outcome.result as SockResult.Spoken).text)
        // No template matched, so there is nothing to report as the matched entry.
        assertNull(outcome.matched)
        assertEquals(listOf("clock.set_timer"), sock.handled)
    }

    @Test
    fun `the model sees the normalized utterance, the same string Tier 1 saw`() = runTest {
        val resolver = FakeTier2Resolver()
        val engine = DobbyEngine(registry, tier2 = Tier2(registry, resolver, program))
        engine.start(FakeSockContext(this))
        engine.handle("Weck mich in ZWANZIG Minuten!")
        assertEquals(listOf("weck mich in 20 minuten"), resolver.routed)
    }

    @Test
    fun `a template still wins, and the model is never asked`() = runTest {
        val resolver = FakeTier2Resolver()
        val engine = DobbyEngine(registry, tier2 = Tier2(registry, resolver, program))
        engine.start(FakeSockContext(this))

        val outcome = engine.handle("timer 5 minuten")
        assertEquals(Tier.TEMPLATE, outcome.tier)
        assertNull(outcome.tier2)
        assertEquals(emptyList(), resolver.requests, "Tier 2 ran for an utterance Tier 1 matched")
    }

    @Test
    fun `none, timeout and a rejection all behave exactly like today's miss`() = runTest {
        for (reply in listOf("none", "Ich weiß es nicht.", "nope.nope")) {
            val engine = engine(routes = mapOf("irgendwas" to reply))
            engine.start(FakeSockContext(this))
            val outcome = engine.handle("irgendwas")
            assertNull(outcome.invocation, "\"$reply\" produced an invocation")
            assertEquals(Tier.NONE, outcome.tier)
            assertEquals(DobbyEngine.NOT_UNDERSTOOD, (outcome.result as SockResult.Spoken).text)
            // …but the trace is there, which is how you know the tier ran at all.
            assertTrue(outcome.tier2 != null)
        }
    }

    @Test
    fun `useTier2 false skips the model entirely`() = runTest {
        val resolver = FakeTier2Resolver()
        val engine = DobbyEngine(registry, tier2 = Tier2(registry, resolver, program))
        engine.start(FakeSockContext(this))

        // Rounds two and three of a turn: the person is rephrasing toward a command they
        // believe exists, which is Tier 1's best case, and a second five-second wait is what
        // turns "it is thinking" into "it is stuck".
        val outcome = engine.handle("mach irgendwas", useTier2 = false)
        assertNull(outcome.tier2)
        assertEquals(emptyList(), resolver.requests)
    }

    @Test
    fun `the fallthrough carries what Tier 2 made of it`() = runTest {
        val log = FallthroughLog()
        val engine = engine(
            routes = mapOf("weck mich in 20 minuten" to "clock.set_timer"),
            fills = mapOf("weck mich in 20 minuten" to """{"amount":20,"unit":"minuten"}"""),
            onFallthrough = log::record,
        )
        engine.start(FakeSockContext(this))
        engine.handle("weck mich in 20 minuten")
        engine.handle("erzähl mir einen witz")

        assertEquals(2, log.size)
        val resolved = log.entries.value.first()
        assertEquals("weck mich in 20 minuten", resolved.utterance)
        assertIs<Tier2Outcome.Resolved>(resolved.tier2!!.outcome)
        // The line a person reads to decide whether to promote a phrasing into a template.
        assertTrue(
            resolved.toString().startsWith("weck mich in 20 minuten → clock.set_timer (amount=20, unit=minuten)"),
            resolved.toString(),
        )
        assertTrue(log.entries.value[1].toString().endsWith("→ none 0ms"))
    }

    @Test
    fun `the log is a bounded ring, because it is a record of a household`() {
        val log = FallthroughLog(capacity = 3)
        repeat(10) { log.record(Fallthrough("satz $it", null)) }
        assertEquals(3, log.size)
        assertEquals(listOf("satz 7", "satz 8", "satz 9"), log.entries.value.map { it.utterance })
        log.clear()
        assertEquals(0, log.size)
    }

    @Test
    fun `a model answer abandons an open question rather than answering it`() = runTest {
        val asker = FixtureSock(
            id = "kitchen",
            commands = listOf(
                ExclusiveCommandSpec(
                    id = "kitchen.ask",
                    // Optional, or the bare template could never satisfy its own spec and the
                    // palette would skip the entry before the question was ever asked.
                    params = listOf(
                        ParamSpec("groesse", ParamType.Enumeration(listOf("klein", "gross")), required = false),
                    ),
                    templates = patterns("frag mich was"),
                    description = "Fragt.",
                ),
            ),
            handler = { invocation ->
                if (invocation.answering != null) {
                    SockResult.Spoken("Alles klar.")
                } else {
                    SockResult.Asked(
                        "Klein oder gross?",
                        io.dobby.core.sock.FollowUp(
                            commandId = "kitchen.ask",
                            // A *closed* follow-up palette, so an unrelated sentence genuinely
                            // misses it — which is the only way to reach the Tier 2 path with a
                            // question standing.
                            templates = patterns("{groesse:enum}"),
                            params = listOf(ParamSpec("groesse", ParamType.Enumeration(listOf("klein", "gross")))),
                            token = "t1",
                        ),
                    )
                }
            },
        )
        val registry = SockRegistry.buildOrThrow(listOf(asker, sock))
        val engine = DobbyEngine(
            registry = registry,
            tier2 = Tier2(
                registry,
                FakeTier2Resolver(
                    routes = mapOf("weck mich gleich" to "clock.set_timer"),
                    fills = mapOf("weck mich gleich" to """{"amount":5,"unit":"minuten"}"""),
                ),
                Tier2Program.of(registry),
            ),
        )
        engine.start(FakeSockContext(this))

        assertIs<SockResult.Asked>(engine.handle("frag mich was").result)
        assertTrue(engine.awaitingAnswer)

        // Something else entirely, which only Tier 2 can place. Same rule as a Tier 1 match:
        // it wins, and the question is abandoned rather than answered — otherwise a person
        // stuck inside "Klein oder gross?" could say nothing that got them out of it.
        val outcome = engine.handle("weck mich gleich")
        assertEquals("clock.set_timer", outcome.invocation?.commandId)
        assertEquals(Tier.MODEL, outcome.tier)
        assertTrue(!engine.awaitingAnswer, "the question outlived a command that displaced it")
        assertEquals(listOf("t1"), asker.cancelled, "the asker was not told its question died")
    }

    @Test
    fun `a question still gets first refusal on an utterance it can hear`() = runTest {
        val resolver = FakeTier2Resolver()
        val engine = DobbyEngine(registry, tier2 = Tier2(registry, resolver, program))
        engine.start(FakeSockContext(this))
        // No question open here, but the ordering it protects is asserted in FollowUpTest; what
        // matters for Tier 2 is that a matched utterance never reaches the model at all.
        engine.handle("timer 5 minuten")
        assertEquals(emptyList(), resolver.requests)
    }
}
