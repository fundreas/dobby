package io.dobby.android.chat

import io.dobby.core.EngineOutcome
import io.dobby.core.dispatch.ChainStep
import io.dobby.core.dispatch.StepOutcome
import io.dobby.core.sock.CommandInvocation
import io.dobby.core.sock.SockActivity
import io.dobby.core.sock.SockResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OutcomeDetailTest {

    private fun outcome(
        invocation: CommandInvocation?,
        trace: List<ChainStep> = emptyList(),
    ) = EngineOutcome(
        raw = "egal",
        normalized = "egal",
        invocation = invocation,
        matched = null,
        result = SockResult.Silent,
        trace = trace,
    )

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
}
