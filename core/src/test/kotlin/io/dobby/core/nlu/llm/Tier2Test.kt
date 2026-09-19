package io.dobby.core.nlu.llm

import io.dobby.core.registry.SockRegistry
import io.dobby.core.registry.SpecFixtures
import io.dobby.core.testing.FakeTier2Resolver
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The gate — everything between the model's opinion and a Sock actually running.
 *
 * The claim under test is "the LLM gets no route into a Sock that a template does not also
 * have". Every case here is the model trying to take one.
 */
class Tier2Test {

    private val registry = SockRegistry.buildOrThrow(SpecFixtures.all())
    private val program = Tier2Program.of(registry)

    private fun tier2(
        vararg replies: Pair<String, String>,
        available: Boolean = true,
        latency: kotlin.time.Duration = kotlin.time.Duration.ZERO,
        timeout: kotlin.time.Duration = Tier2.DEFAULT_TIMEOUT,
    ) = Tier2(
        registry,
        FakeTier2Resolver(replies.toMap(), available = available, latency = latency),
        program,
        timeout,
    )

    @Test
    fun `a good answer becomes an invocation with coerced params`() = runTest {
        val trace = tier2(
            "weck mich in 20 minuten" to """{"c":"clock.set_timer","amount":20,"unit":"minuten"}""",
        ).resolve("weck mich in 20 minuten")

        val resolved = assertIs<Tier2Outcome.Resolved>(trace.outcome)
        assertEquals("clock.set_timer", resolved.invocation.commandId)
        // Typed, not strings: the JSON 20 went through the same ParamCoercion a template uses.
        assertEquals(mapOf("amount" to 20, "unit" to "minuten"), resolved.invocation.params)
    }

    @Test
    fun `none is the one branch that means no command`() = runTest {
        val trace = tier2("wie wird das wetter" to """{"c":"none"}""").resolve("wie wird das wetter")
        assertEquals(Tier2Outcome.NoCommand, trace.outcome)
        // SockRegistry.build already rejects any Sock declaring "none", so the id cannot also
        // be a command. That is the loop the reserved id was opened for, closed here.
        assertTrue("none" !in registry.commands)
    }

    @Test
    fun `an invented command is rejected`() = runTest {
        val trace = tier2("mach das licht an" to """{"c":"lights.turn_on","room":"küche"}""")
            .resolve("mach das licht an")
        val rejected = assertIs<Tier2Outcome.Rejected>(trace.outcome)
        assertTrue("unknown command" in rejected.reason, rejected.reason)
    }

    @Test
    fun `params that do not satisfy the spec are rejected, not coerced into something`() = runTest {
        // A required param missing.
        assertIs<Tier2Outcome.Rejected>(
            tier2("x" to """{"c":"clock.set_timer","unit":"minuten"}""").resolve("x").outcome,
        )
        // An enum value that is not in the enum. The grammar could not have produced it, which
        // is exactly why the gate re-checks.
        assertIs<Tier2Outcome.Rejected>(
            tier2("x" to """{"c":"system.volume","direction":"lauterer","steps":2}""").resolve("x").outcome,
        )
        // A text where an int belongs.
        assertIs<Tier2Outcome.Rejected>(
            tier2("x" to """{"c":"clock.set_timer","amount":"viele","unit":"minuten"}""").resolve("x").outcome,
        )
    }

    @Test
    fun `a dropped null lets the spec's own default apply`() = runTest {
        val trace = tier2("mach lauter" to """{"c":"system.volume","direction":"lauter","steps":null}""")
            .resolve("mach lauter")
        val resolved = assertIs<Tier2Outcome.Resolved>(trace.outcome)
        // steps is optional with default 2 — supplied by ParamCoercion, not by the model.
        assertEquals(mapOf("direction" to "lauter", "steps" to 2), resolved.invocation.params)
    }

    @Test
    fun `prose and malformed output are rejected rather than thrown`() = runTest {
        for (raw in listOf("Ich glaube, du willst Musik hören.", "", "{", """{"c":}""")) {
            val trace = tier2("x" to raw).resolve("x")
            assertIs<Tier2Outcome.Rejected>(trace.outcome, "\"$raw\" did not reject")
        }
    }

    @Test
    fun `an unavailable resolver is never called`() = runTest {
        val resolver = FakeTier2Resolver(available = false, unavailableReason = "kein Modell")
        val trace = Tier2(registry, resolver, program).resolve("hallo")
        val unavailable = assertIs<Tier2Outcome.Unavailable>(trace.outcome)
        assertEquals("kein Modell", unavailable.reason)
        assertEquals(emptyList(), resolver.asked)
    }

    @Test
    fun `the deadline holds even if the resolver ignores it`() = runTest {
        val trace = tier2(
            "langsam" to """{"c":"shared.stop"}""",
            latency = 30.seconds,
            timeout = 200.milliseconds,
        ).resolve("langsam")
        assertEquals(Tier2Outcome.Timeout, trace.outcome)
    }

    @Test
    fun `every outcome carries the fingerprint of the program that produced it`() = runTest {
        val resolver = FakeTier2Resolver(mapOf("x" to """{"c":"shared.stop"}"""))
        val trace = Tier2(registry, resolver, program).resolve("x")
        assertEquals(program.fingerprint, trace.fingerprint)
        assertEquals(listOf(program.fingerprint), resolver.fingerprints)
    }

    @Test
    fun `the trace reads as a line somebody would put in a log`() = runTest {
        val trace = tier2("x" to """{"c":"clock.set_timer","amount":15,"unit":"minuten"}""").resolve("x")
        assertTrue(
            trace.toString().startsWith("tier2 0.0s · clock.set_timer (amount=15, unit=minuten)"),
            trace.toString(),
        )
        assertTrue(tier2("x" to """{"c":"none"}""").resolve("x").toString().endsWith("· none"))
    }
}
