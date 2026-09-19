package io.dobby.core.nlu.llm

import io.dobby.core.registry.SockRegistry
import io.dobby.core.registry.SpecFixtures
import io.dobby.core.testing.FakeTier2Resolver
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Route, fill, and the gate between them and a Sock.
 *
 * The claim under test is still "the LLM gets no route into a Sock that a template does not
 * also have" — now with two chances to get it wrong, which is the trade the split makes. Every
 * case here is the model trying to take one, plus the case the split exists for: a zero-param
 * command must never ask the second question.
 */
class Tier2Test {

    private val registry = SockRegistry.buildOrThrow(SpecFixtures.all())
    private val program = Tier2Program.of(registry)

    private fun tier2(
        routes: Map<String, String> = emptyMap(),
        fills: Map<String, String> = emptyMap(),
        resolver: FakeTier2Resolver = FakeTier2Resolver(routes, fills),
        timeout: Duration = Tier2.DEFAULT_TIMEOUT,
    ) = resolver to Tier2(registry, resolver, program, timeout)

    @Test
    fun `a zero-param command is answered by the route alone`() {
        runTest {
            val (resolver, tier2) = tier2(routes = mapOf("mach das mal aus" to "shared.stop"))
            val trace = tier2.resolve("mach das mal aus")

            val resolved = assertIs<Tier2Outcome.Resolved>(trace.outcome)
            assertEquals("shared.stop", resolved.invocation.commandId)
            // The number that makes the split cheap: no second request, no second prefill, no
            // second decode. Seven of eleven commands in the real registry end here.
            assertEquals(emptyList(), resolver.filled, "fill was asked about a zero-param command")
            assertNotNull(trace.route)
            assertNull(trace.fill, "a zero-param command has no fill step to report")
        }
    }

    @Test
    fun `a command with params asks both questions`() {
        runTest {
            val (resolver, tier2) = tier2(
                routes = mapOf("weck mich in 20 minuten" to "clock.set_timer"),
                fills = mapOf("weck mich in 20 minuten" to """{"amount":20,"unit":"minuten"}"""),
            )
            val trace = tier2.resolve("weck mich in 20 minuten")

            val resolved = assertIs<Tier2Outcome.Resolved>(trace.outcome)
            assertEquals("clock.set_timer", resolved.invocation.commandId)
            // Typed, not strings: the JSON 20 went through the same ParamCoercion a template uses.
            assertEquals(mapOf("amount" to 20, "unit" to "minuten"), resolved.invocation.params)
            assertEquals(listOf("clock.set_timer"), resolver.filled)
            assertNotNull(trace.fill)
        }
    }

    @Test
    fun `route none ends it, and fill is never asked`() {
        runTest {
            val (resolver, tier2) = tier2(routes = mapOf("wie wird das wetter" to "none"))
            val trace = tier2.resolve("wie wird das wetter")

            assertEquals(Tier2Outcome.NoCommand, trace.outcome)
            assertEquals(emptyList(), resolver.filled)
            // SockRegistry.build already rejects any Sock declaring "none", so the label cannot
            // also be a command. That is the loop the reserved id was opened for, closed here.
            assertTrue("none" !in registry.commands)
        }
    }

    @Test
    fun `fill none is the honest answer, not an invented parameter`() {
        runTest {
            // "stell einen timer" with no duration. Without the escape in the fill grammar the
            // model would be forced to invent one, because cmd-none is gone once the command
            // has been chosen.
            val (_, tier2) = tier2(
                routes = mapOf("stell mal einen timer" to "clock.set_timer"),
                fills = mapOf("stell mal einen timer" to "none"),
            )
            val trace = tier2.resolve("stell mal einen timer")

            assertEquals(Tier2Outcome.NoCommand, trace.outcome)
            // …and the trace still says the route knew the command, which is what tells you the
            // fill prompt is where to look.
            assertEquals("clock.set_timer", trace.route?.raw)
            assertEquals("none", trace.fill?.raw)
        }
    }

    @Test
    fun `an invented label is rejected`() {
        runTest {
            val (resolver, tier2) = tier2(routes = mapOf("mach das licht an" to "lights.turn_on"))
            val trace = tier2.resolve("mach das licht an")

            val rejected = assertIs<Tier2Outcome.Rejected>(trace.outcome)
            assertTrue("unknown label" in rejected.reason, rejected.reason)
            assertEquals(emptyList(), resolver.filled, "a rejected label must not reach fill")
        }
    }

    @Test
    fun `params that do not satisfy the spec are rejected, not coerced into something`() {
        runTest {
            // A required param missing.
            assertIs<Tier2Outcome.Rejected>(
                tier2(mapOf("x" to "clock.set_timer"), mapOf("x" to """{"unit":"minuten"}"""))
                    .second.resolve("x").outcome,
            )
            // An enum value that is not in the enum. The grammar could not have produced it,
            // which is exactly why the gate re-checks.
            assertIs<Tier2Outcome.Rejected>(
                tier2(mapOf("x" to "system.volume"), mapOf("x" to """{"direction":"lauterer","steps":2}"""))
                    .second.resolve("x").outcome,
            )
            // A text where an int belongs.
            assertIs<Tier2Outcome.Rejected>(
                tier2(mapOf("x" to "clock.set_timer"), mapOf("x" to """{"amount":"viele","unit":"minuten"}"""))
                    .second.resolve("x").outcome,
            )
        }
    }

    @Test
    fun `a rejection carries the raw that caused it, and the step that produced it`() {
        runTest {
            val (_, tier2) = tier2(
                routes = mapOf("x" to "clock.set_timer"),
                fills = mapOf("x" to """{"amount":"viele","unit":"minuten"}"""),
            )
            val trace = tier2.resolve("x")
            val rejected = assertIs<Tier2Outcome.Rejected>(trace.outcome)

            // The information the single shot could not give: the model knew the command and
            // could not fill it, which points at the fill prompt and not at routing.
            assertEquals("clock.set_timer", trace.route?.raw)
            assertTrue("""{"amount":"viele"""" in rejected.raw, rejected.raw)
        }
    }

    @Test
    fun `a dropped null lets the spec's own default apply`() {
        runTest {
            val (_, tier2) = tier2(
                routes = mapOf("mach lauter" to "system.volume"),
                fills = mapOf("mach lauter" to """{"direction":"lauter","steps":null}"""),
            )
            val resolved = assertIs<Tier2Outcome.Resolved>(tier2.resolve("mach lauter").outcome)
            // steps is optional with default 2 — supplied by ParamCoercion, not by the model.
            assertEquals(mapOf("direction" to "lauter", "steps" to 2), resolved.invocation.params)
        }
    }

    @Test
    fun `prose and malformed output are rejected rather than thrown`() {
        runTest {
            // A route that answers with a sentence.
            assertIs<Tier2Outcome.Rejected>(
                tier2(routes = mapOf("x" to "Ich glaube, du willst Musik hören."))
                    .second.resolve("x").outcome,
            )
            // A fill that answers with anything but an object.
            for (raw in listOf("Ich weiß es nicht.", "", "{", """{"amount":}""")) {
                val trace = tier2(mapOf("x" to "clock.set_timer"), mapOf("x" to raw)).second.resolve("x")
                assertIs<Tier2Outcome.Rejected>(trace.outcome, "\"$raw\" did not reject")
            }
        }
    }

    @Test
    fun `whitespace around a label is not a different label`() {
        runTest {
            val (_, tier2) = tier2(routes = mapOf("x" to "  shared.stop\n"))
            assertIs<Tier2Outcome.Resolved>(tier2.resolve("x").outcome)
        }
    }

    @Test
    fun `an unavailable resolver is never called`() {
        runTest {
            val resolver = FakeTier2Resolver(available = false, unavailableReason = "kein Modell")
            val trace = Tier2(registry, resolver, program).resolve("hallo")

            val unavailable = assertIs<Tier2Outcome.Unavailable>(trace.outcome)
            assertEquals("kein Modell", unavailable.reason)
            assertEquals(emptyList(), resolver.requests)
            assertNull(trace.route)
        }
    }

    @Test
    fun `one deadline covers both steps, and the trace says how far it got`() {
        runTest {
            val slow = FakeTier2Resolver(
                routes = mapOf("langsam" to "clock.set_timer"),
                latency = 30.seconds,
            )
            val trace = Tier2(registry, slow, program, 200.milliseconds).resolve("langsam")
            assertEquals(Tier2Outcome.Timeout, trace.outcome)
            // It expired inside the route, so there is nothing to report about either step.
            assertNull(trace.fill)
        }
    }

    @Test
    fun `a deadline that expires during fill still reports the route`() {
        runTest {
            // The route is instant, the fill is not. This is the case the per-step record was
            // added for: a timeout that still tells you the model got as far as the command.
            val resolver = object : Tier2Resolver {
                override val available = true
                override val unavailableReason: String? = null

                override suspend fun generate(request: Tier2Request): String {
                    if (request.step == Step.FILL) kotlinx.coroutines.delay(30.seconds)
                    return "clock.set_timer"
                }
            }
            val trace = Tier2(registry, resolver, program, 200.milliseconds).resolve("langsam")

            assertEquals(Tier2Outcome.Timeout, trace.outcome)
            assertEquals("clock.set_timer", trace.route?.raw, "the route's answer was lost")
        }
    }

    @Test
    fun `every request carries the fingerprint and the prefix the cache must hold`() {
        runTest {
            val (resolver, tier2) = tier2(
                routes = mapOf("x" to "clock.set_timer"),
                fills = mapOf("x" to """{"amount":5,"unit":"minuten"}"""),
            )
            tier2.resolve("x")

            assertEquals(2, resolver.requests.size)
            for (request in resolver.requests) {
                assertEquals(program.fingerprint, request.fingerprint)
                // Both steps are prefixed by the *route* prefix: only that one is prefilled,
                // and a fill turn rides in the tail rather than in the cache.
                assertEquals(program.route.systemPrefix, request.systemPrefix)
                assertTrue(request.maxTokens > 0)
            }
        }
    }

    @Test
    fun `the fill tail replays the route exchange rather than continuing it`() {
        runTest {
            val (resolver, tier2) = tier2(
                routes = mapOf("weck mich" to "clock.set_timer"),
                fills = mapOf("weck mich" to """{"amount":5,"unit":"minuten"}"""),
            )
            tier2.resolve("weck mich")

            val fill = resolver.requests.single { it.step == Step.FILL }
            // The utterance, the assistant scaffold, the label the route gave, then the fill
            // turn. Replayed because nativeGenerate truncates the KV cache to n_system at the
            // start of every request, and that invariant is worth more than ~20 tokens of
            // batched prefill.
            assertTrue(fill.tail.startsWith("weck mich" + PromptGenerator.ASSISTANT_SUFFIX))
            assertTrue("clock.set_timer" in fill.tail)
            assertTrue("Befehl clock.set_timer" in fill.tail, "the fill turn is missing")
            assertTrue(fill.tail.endsWith(PromptGenerator.ASSISTANT_SUFFIX))
        }
    }

    @Test
    fun `the trace reads as a line somebody would put in a log`() {
        runTest {
            val (_, withParams) = tier2(
                routes = mapOf("x" to "clock.set_timer"),
                fills = mapOf("x" to """{"amount":15,"unit":"minuten"}"""),
            )
            val line = withParams.resolve("x").toString()
            assertTrue(line.startsWith("tier2 0.0s · clock.set_timer (amount=15, unit=minuten)"), line)
            assertTrue("· route 0.0s · fill 0.0s" in line, line)

            val (_, none) = tier2(routes = mapOf("x" to "none"))
            val noneLine = none.resolve("x").toString()
            assertTrue(noneLine.endsWith("· none · route 0.0s"), noneLine)
        }
    }
}
