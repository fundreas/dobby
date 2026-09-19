package io.dobby.socks.conversation

import io.dobby.core.nlu.Normalizer
import io.dobby.core.registry.SockRegistry
import io.dobby.core.sock.CommandInvocation
import io.dobby.core.sock.SockResult
import io.dobby.socks.calculator.CalculatorSock
import io.dobby.socks.clock.ClockSock
import io.dobby.socks.help.HelpSock
import io.dobby.socks.winky.WinkySock
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

/**
 * The utterance table from `conversation.specs.md`, and the surface it must not touch.
 *
 * The negative half is the point of this file. One word ends the conversation, so the only way
 * this Sock can be wrong is by claiming something somebody meant for another Sock — and that
 * failure is silent: the panel does exactly nothing and goes to sleep.
 */
class ConversationSockTest {

    /** Everything that shares the palette on the device, so a collision fails here. */
    private val palette = SockRegistry.buildOrThrow(
        listOf(ConversationSock(), ClockSock(), CalculatorSock(), HelpSock { null }, WinkySock()),
    ).palette

    private fun resolve(utterance: String): CommandInvocation? =
        palette.match(Normalizer.tokenize(utterance))?.invocation

    private fun assertDismisses(utterance: String) =
        assertEquals(ConversationSock.DISMISS, resolve(utterance)?.commandId, utterance)

    private fun assertNotDismiss(utterance: String) =
        assertNotEquals(ConversationSock.DISMISS, resolve(utterance)?.commandId, utterance)

    @Test
    fun `ok, however it is said`() {
        assertDismisses("OK")
        assertDismisses("Ok.")
        assertDismisses("okay")
        assertDismisses("Okay!")
    }

    @Test
    fun `the rest of the utterance table`() {
        assertDismisses("alles klar")
        assertDismisses("alles gut")
        assertDismisses("danke")
        assertDismisses("danke dir")
        assertDismisses("danke schön")
        assertDismisses("vielen Dank")
        assertDismisses("das war's")
        assertDismisses("das wars")
        assertDismisses("danke das war's")
        assertDismisses("das ist alles")
        assertDismisses("passt schon")
        assertDismisses("passt so")
        assertDismisses("schon gut")
        assertDismisses("lass gut sein")
    }

    @Test
    fun `it does not claim what another Sock owns`() {
        // The chime-silencing phrasings on shared.stop, one word away from "schon gut".
        assertNotDismiss("ist gut")
        assertNotDismiss("ja ja")
        assertNotDismiss("ich hab's gehört")
        // Everything else that ends with the room quieter than it was.
        assertNotDismiss("stopp")
        assertNotDismiss("aus")
        assertNotDismiss("pause")
        // Spec'd to the System Sock, which is not built yet — so this asserts that the phrase
        // stays unclaimed rather than being quietly absorbed here on the way.
        assertNotDismiss("gute nacht")
        // And the ordinary traffic it sits next to.
        assertNotDismiss("hilfe")
        assertNotDismiss("wie spät ist es")
        assertNotDismiss("hello")
        assertNotDismiss("3 plus 5")
    }

    @Test
    fun `bare words too close to other German are not claimed`() {
        // §5: four and five letters at tolerance 1, with real neighbours. Left unclaimed on
        // purpose, so this asserts the gap rather than leaving it to look like an oversight.
        assertNotDismiss("klar")
        assertNotDismiss("passt")
        assertNotDismiss("fertig")
    }

    @Test
    fun `dismissing says nothing at all`() = runTest {
        val result = ConversationSock().handle(CommandInvocation(ConversationSock.DISMISS))

        // Ended closes the turn; null text is the product decision, and the silence is the
        // acknowledgement. An answer here would be a sentence nobody asked for.
        assertEquals(SockResult.Ended(null), result)
    }

    @Test
    fun `an unknown command is not this Sock's`() = runTest {
        assertEquals(SockResult.NotForMe, ConversationSock().handle(CommandInvocation("clock.whats_the_time")))
    }

    @Test
    fun `the registry accepts it alongside everyone else`() {
        assertNotNull(palette.match(Normalizer.tokenize("ok")))
    }
}
