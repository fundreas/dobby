package io.dobby.socks.clock

import io.dobby.core.nlu.Normalizer
import io.dobby.core.registry.SockRegistry
import io.dobby.core.sock.CommandInvocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The utterance tables from `clock.specs.md` §3, §4 and §6, as tests.
 *
 * The input is raw German, including number words, so the normalizer's "zehn" → "10" is part of
 * what is asserted — templates are written against normalized text and nothing else.
 */
class ClockTemplatesTest {

    private val palette = SockRegistry.buildOrThrow(listOf(ClockSock())).palette

    private fun resolve(utterance: String): CommandInvocation? =
        palette.match(Normalizer.tokenize(utterance))?.invocation

    private fun assertResolves(utterance: String, commandId: String, params: Map<String, Any> = emptyMap()) {
        val invocation = resolve(utterance)
        assertEquals(commandId, invocation?.commandId, utterance)
        if (params.isNotEmpty()) assertEquals(params, invocation?.params, utterance)
    }

    private fun timer(amount: Int, unit: String) = mapOf<String, Any>("amount" to amount, "unit" to unit)

    @Test
    fun `setting a timer`() {
        assertResolves("Timer zehn Minuten", ClockSock.SET_TIMER, timer(10, "minuten"))
        assertResolves("stell einen Timer auf 5 Minuten", ClockSock.SET_TIMER, timer(5, "minuten"))
        assertResolves("stelle mir einen Timer für 90 Sekunden", ClockSock.SET_TIMER, timer(90, "sekunden"))
        assertResolves("3 Minuten Timer", ClockSock.SET_TIMER, timer(3, "minuten"))
        assertResolves("Timer eine Minute", ClockSock.SET_TIMER, timer(1, "minuten"))
        assertResolves("setz einen Wecker auf 2 Stunden", ClockSock.SET_TIMER, timer(2, "stunden"))
        assertResolves("erinner mich in 20 Minuten", ClockSock.SET_TIMER, timer(20, "minuten"))
    }

    @Test
    fun `an amount with no unit still matches - it becomes a question, not a dead end`() {
        // Before these templates "stell einen timer auf zehn" resolved to nothing at all. The
        // unit is missing, not the intent, and the handler asks for it (§3).
        assertResolves("stell einen Timer auf zehn", ClockSock.SET_TIMER, mapOf("amount" to 10))
        assertResolves("Timer 5", ClockSock.SET_TIMER, mapOf("amount" to 5))
        assertResolves("Timer auf 90", ClockSock.SET_TIMER, mapOf("amount" to 90))
        assertResolves("setz einen Wecker auf 2", ClockSock.SET_TIMER, mapOf("amount" to 2))
    }

    @Test
    fun `a phrasing that names its unit is never demoted to the unit-less one`() {
        // The unit-less templates sit last for a reason: they are strictly less specific, and
        // a palette that reached them first would ask "10 was?" about "timer 10 minuten".
        for (utterance in listOf("Timer 10 Minuten", "stell einen Timer auf 5 Minuten", "Timer auf 2 Stunden")) {
            val params = resolve(utterance)?.params
            assertEquals(2, params?.size, "$utterance lost its unit: $params")
        }
    }

    @Test
    fun `the singular of a unit reaches the plural enum value`() {
        // Folded in by the enum matcher's closed candidate set, not by five more templates (§3).
        assertResolves("Timer 1 Minute", ClockSock.SET_TIMER, timer(1, "minuten"))
        assertResolves("Timer 30 Sekunde", ClockSock.SET_TIMER, timer(30, "sekunden"))
        assertResolves("Timer 2 Stunde", ClockSock.SET_TIMER, timer(2, "stunden"))
    }

    @Test
    fun `cancelling a timer requires naming it`() {
        assertResolves("Timer stopp", ClockSock.CANCEL_TIMER)
        assertResolves("Timer abbrechen", ClockSock.CANCEL_TIMER)
        assertResolves("stopp den Timer", ClockSock.CANCEL_TIMER)
        assertResolves("brich den Timer ab", ClockSock.CANCEL_TIMER)
        assertResolves("stopp den Alarm", ClockSock.CANCEL_TIMER)
        assertResolves("Timer aus", ClockSock.CANCEL_TIMER)
        assertResolves("stopp das Klingeln", ClockSock.CANCEL_TIMER)
    }

    @Test
    fun `bare stopp is not claimed - it belongs to the chain`() {
        // The word "timer", "alarm", "wecker" or "klingeln" is required for the exclusive
        // command, because cancelling a *running* timer must be addressed explicitly (§4).
        assertResolves("stopp", "shared.stop")
        assertResolves("aus", "shared.stop")
        assertResolves("ich hab's gehört", "shared.stop")
        assertResolves("ja ja", "shared.stop")
    }

    @Test
    fun `asking for the time`() {
        assertResolves("wie spät ist es", ClockSock.WHATS_THE_TIME)
        assertResolves("wie spät ist es jetzt", ClockSock.WHATS_THE_TIME)
        assertResolves("Wie viel Uhr ist es", ClockSock.WHATS_THE_TIME)
        assertResolves("wie spät", ClockSock.WHATS_THE_TIME)
        assertResolves("wie viel Uhr", ClockSock.WHATS_THE_TIME)
        assertResolves("Uhrzeit", ClockSock.WHATS_THE_TIME)
        assertResolves("die Uhrzeit", ClockSock.WHATS_THE_TIME)
        assertResolves("sag mir die Uhrzeit", ClockSock.WHATS_THE_TIME)
        assertResolves("sag die Uhrzeit", ClockSock.WHATS_THE_TIME)
        assertResolves("Was ist die Uhrzeit?", ClockSock.WHATS_THE_TIME)
    }

    @Test
    fun `tolerates an STT slip in a keyword`() {
        assertResolves("wie spet ist es", ClockSock.WHATS_THE_TIME)
        assertResolves("urzeit", ClockSock.WHATS_THE_TIME)
        assertResolves("timer 10 minutten", ClockSock.SET_TIMER, timer(10, "minuten"))
    }

    @Test
    fun `what the Sock deliberately does not answer`() {
        // §8: an alarm at a wall-clock time is a different command with a different param
        // shape (`set_alarm`, deferred to v2). Matching nothing is the honest outcome.
        assertNull(resolve("stell einen Wecker auf 7 Uhr"))
        assertNull(resolve("wie warm ist es"))
        assertNull(resolve("wann fährt der nächste Bus"))
    }

    @Test
    fun `every declared example resolves to the command that declared it`() {
        val registry = SockRegistry.buildOrThrow(listOf(ClockSock()))
        assertEquals(emptyList(), registry.checkExamples())
    }
}
