package io.dobby.android.chat

import io.dobby.core.EngineOutcome
import io.dobby.core.Tier
import io.dobby.core.dispatch.ChainStep
import io.dobby.core.dispatch.StepOutcome
import io.dobby.core.sock.CommandInvocation
import io.dobby.core.sock.SockActivity
import io.dobby.core.nlu.llm.Tier2Outcome
import io.dobby.core.nlu.llm.Tier2Trace
import io.dobby.core.sock.SockResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OutcomeDetailTest {

    private fun outcome(
        invocation: CommandInvocation?,
        trace: List<ChainStep> = emptyList(),
        tier2: Tier2Trace? = null,
    ) = EngineOutcome(
        raw = "egal",
        normalized = "egal",
        invocation = invocation,
        matched = null,
        result = SockResult.Silent,
        trace = trace,
        tier = if (tier2?.outcome is Tier2Outcome.Resolved) Tier.MODEL else Tier.TEMPLATE,
        tier2 = tier2,
    )

    private fun consulted(
        outcome: Tier2Outcome,
        seconds: Double,
        route: Double? = null,
        fill: Double? = null,
    ) = Tier2Trace(
        outcome = outcome,
        latency = duration(seconds),
        fingerprint = "deadbeef",
        route = route?.let { io.dobby.core.nlu.llm.StepTrace(null, duration(it)) },
        fill = fill?.let { io.dobby.core.nlu.llm.StepTrace(null, duration(it)) },
    )

    private fun duration(seconds: Double) =
        kotlin.time.Duration.parse("${(seconds * 1000).toLong()}ms")

    @Test
    fun `an exclusive command shows who ran it`() {
        val detail = outcome(
            CommandInvocation("clock.whats_the_time"),
            listOf(ChainStep("clock", null, StepOutcome.CONSUMED)),
        ).detailLine()

        assertEquals("clock.whats_the_time · clock CONSUMED", detail)
    }

    @Test
    fun `params are shown, because a wrong slot looks exactly like a wrong command`() {
        val detail = outcome(CommandInvocation("clock.set_timer", mapOf("amount" to 10))).detailLine()

        assertEquals("clock.set_timer (amount=10)", detail)
    }

    @Test
    fun `a chain shows every candidate and its activity`() {
        // This line is the whole reason the trace survived from the terminal harness: it is
        // what tells you "stopp" went to the timer and not to the music.
        val detail = outcome(
            CommandInvocation("shared.stop"),
            listOf(
                ChainStep("clock", SockActivity.ACTIVE, StepOutcome.CONSUMED),
                ChainStep("spotify", SockActivity.IDLE, StepOutcome.PASSED),
            ),
        ).detailLine()

        assertEquals("shared.stop · clock ACTIVE CONSUMED → spotify IDLE PASSED", detail)
    }

    @Test
    fun `an unmatched utterance has no detail line at all`() {
        assertNull(outcome(invocation = null).detailLine())
    }

    @Test
    fun `a Tier 2 resolution says so, on the left, where the eye lands`() {
        val detail = outcome(
            CommandInvocation("clock.set_timer", mapOf("amount" to 15, "unit" to "minuten")),
            listOf(ChainStep("clock", null, StepOutcome.CONSUMED)),
            consulted(
                Tier2Outcome.Resolved(
                    CommandInvocation("clock.set_timer", mapOf("amount" to 15, "unit" to "minuten")),
                ),
                seconds = 2.9,
                route = 0.8,
                fill = 2.1,
            ),
        ).detailLine()

        // The per-step latencies are what the single shot could not report: a slow turn now
        // says whether the time went into choosing the command or into filling it.
        assertEquals(
            "tier2 2.9s · clock.set_timer (amount=15, unit=minuten) · route 0.8s · fill 2.1s · clock CONSUMED",
            detail,
        )
    }

    @Test
    fun `a miss gets a detail line when the model was consulted, and only then`() {
        // "The model looked and said no" is what tells you the tier is running at all.
        assertEquals(
            "tier2 0.9s · none · route 0.9s",
            outcome(null, tier2 = consulted(Tier2Outcome.NoCommand, 0.9, route = 0.9)).detailLine(),
        )
        // A timeout during fill still says the route had an answer, which is the whole point of
        // recording the steps separately.
        assertEquals(
            "tier2 5.0s · timeout · route 0.8s",
            outcome(null, tier2 = consulted(Tier2Outcome.Timeout, 5.0, route = 0.8)).detailLine(),
        )
        // …and with no resolver, the existing behaviour: nothing to say.
        assertNull(outcome(invocation = null).detailLine())
    }
}
