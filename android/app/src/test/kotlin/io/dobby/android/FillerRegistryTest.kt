package io.dobby.android

import io.dobby.core.nlu.Fillers
import io.dobby.core.nlu.Normalizer
import io.dobby.core.registry.Palette
import io.dobby.core.registry.SockRegistry
import io.dobby.core.testing.NegativeSentences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Filler skipping against the Socks that actually ship.
 *
 * `:core`'s own filler tests run against `SpecFixtures`, which holds Spotify, Radio, System and
 * Departures — none of which exist yet as code. The catalog on the device is the other four:
 * Clock, Calculator, Conversation and Help. The two sets barely overlap, so a filler list that
 * is safe in one of them says nothing about the other, and this is the only module that can see
 * the real one.
 *
 * It is also where the two hardest words on the list live. `mal` is a multiplication operator in
 * `calculator.calculate`, and `ok` and `danke` end a turn in `conversation.acknowledge` — which
 * is why `danke` is not on the list and `mal` is.
 */
class FillerRegistryTest {

    private val registry = SockRegistry.buildOrThrow(DobbySocks.create().socks)

    @Test
    fun `the shipped palette survives its own filler list`() {
        // No filler is a command on its own here either, and no two shipped commands collapse
        // onto the same sentence once the fillers are dropped.
        assertEquals(emptyList(), registry.checkFillers())
        assertEquals(Fillers.DE.language, registry.palette.fillers.language)
    }

    @Test
    fun `no everyday sentence reaches a shipped command`() {
        val offences = NegativeSentences.load().mapNotNull { sentence ->
            val match = registry.palette.match(Normalizer.tokenize(sentence))
            match?.let { "\"$sentence\" → ${it.invocation.commandId} via \"${it.entry.template.source}\"" }
        }
        assertEquals(
            emptyList(),
            offences,
            "filler skipping routed ordinary German into a shipped command",
        )
    }

    @Test
    fun `mal stays an operator even though it is filler`() {
        // The one word that is content in this catalog and noise everywhere else. Both of these
        // match on the strict pass, so skipping never gets a say — which is the whole reason
        // the two passes are ordered the way they are.
        for ((utterance, commandId) in listOf(
            "6 mal 7" to "calculator.calculate",
            "mal 2" to "calculator.continue_with",
            "und jetzt mal 2" to "calculator.continue_with",
        )) {
            val match = registry.palette.match(Normalizer.tokenize(utterance))
            assertNotNull(match, "\"$utterance\" matched nothing")
            assertEquals(commandId, match.invocation.commandId, utterance)
            assertTrue(!match.skippedFillers, "\"$utterance\" needed the filler pass")
        }
    }

    @Test
    fun `the sentences the filler list was added for, on the shipped catalog`() {
        val strict = Palette(registry.palette.entries, fillers = Fillers.NONE)
        for ((utterance, commandId) in listOf(
            "wie spät ist das" to "clock.whats_the_time",
            "wie spät ist es denn jetzt" to "clock.whats_the_time",
            "was ist denn bitte gerade die uhrzeit" to "clock.whats_the_time",
            "stell mir doch mal einen timer auf 5 minuten" to "clock.set_timer",
            "timer doch bitte stopp" to "clock.cancel_timer",
        )) {
            val tokens = Normalizer.tokenize(utterance)
            assertEquals(null, strict.match(tokens)?.invocation, "\"$utterance\" already matched before M6c")
            val match = registry.palette.match(tokens)
            assertNotNull(match, "\"$utterance\" still matches nothing")
            assertEquals(commandId, match.invocation.commandId, utterance)
            assertTrue(match.skippedFillers, "\"$utterance\" was not marked as a rescue")
        }
    }
}
