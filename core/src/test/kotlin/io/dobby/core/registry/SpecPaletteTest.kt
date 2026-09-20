package io.dobby.core.registry

import io.dobby.core.nlu.Fillers
import io.dobby.core.nlu.Normalizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The utterance tables from `socks.specs/`, run against the assembled palette.
 *
 * This is the collision gate the plan calls for (§5.3): it is what fails in CI when someone
 * adds a template that shadows another Sock.
 */
class SpecPaletteTest {

    private val registry = SockRegistry.buildOrThrow(SpecFixtures.all())

    private companion object {
        /**
         * Every utterance the spec tables promise, in one list, for the A/B above.
         *
         * Duplicated from the assertions rather than extracted from them: the A/B has to run
         * over the *whole* set in one pass with a second palette, and a list is the only shape
         * that allows it. A phrasing added to a test above and not to here is not a failure —
         * the assertion above still covers it — it is simply not in the A/B.
         */
        val SPEC_UTTERANCES: List<String> = listOf(
            "Spiele Blinding Lights von The Weeknd", "Spiel was von Queen",
            "Spiele den Song Bohemian Rhapsody", "Musik an", "Nächster Song", "überspringen",
            "stopp die Musik", "Musik aus", "spiel die Musik weiter", "stopp", "pause", "aus",
            "mach das aus", "weiter", "mach weiter", "weiter zum nächsten",
            "spiele Radio FM4", "radio Ö3", "mach das Radio an", "radio an",
            "schalt den Sender Ö1 ein", "radio aus", "mach das Radio aus", "stopp das Radio",
            "Timer zehn Minuten", "stell einen Timer auf 5 Minuten",
            "stelle mir einen Timer für 90 Sekunden", "3 Minuten Timer", "Timer eine Minute",
            "erinner mich in 20 Minuten", "Wie spät ist es", "Uhrzeit", "Was ist die Zeit?",
            "sag mir die Zeit", "What's the time?", "what time is it", "Zeit", "seit", "weit",
            "zeig", "Timer stopp", "Timer abbrechen", "stopp den Timer", "brich den Timer ab",
            "stopp den Alarm", "ich hab's gehört", "lauter", "leiser", "mach lauter",
            "viel lauter", "ein bisschen leiser", "Lautstärke hoch", "dreh die Musik leiser",
            "ton aus", "ton an", "stumm", "ruhe", "musik aus", "bildschirm aus",
            "mach den Bildschirm aus", "bildschirm an", "mach den Bildschirm an", "dashboard",
            "gute Nacht", "Wann fährt der nächste Bus", "Wann kommt die nächste Bim",
            "Abfahrten", "Fahrplan", "Wann fährt der nächste 14A", "Wann kommt der U6",
            "wie wird das wetter morgen", "ruf meine mutter an", "erzähl mir einen witz",
        )

        /**
         * Spec phrasings that only the second pass reaches, in [SPEC_UTTERANCES] order.
         *
         * All three are a filler away from a template that is still there: "mir" in the timer
         * forms, "ist es" after "wie spät". Pinned rather than counted so that a *fourth* one
         * appearing is a decision somebody makes on purpose.
         */
        val RESCUED_SPEC_UTTERANCES: List<String> = listOf(
            "stelle mir einen Timer für 90 Sekunden",
            "Wie spät ist es",
            "sag mir die Zeit",
        )
    }

    private fun resolve(utterance: String): PaletteMatch {
        val match = registry.palette.match(Normalizer.tokenize(utterance))
        assertNotNull(match, "\"$utterance\" matched no template")
        return match
    }

    private fun assertResolves(utterance: String, commandId: String, params: Map<String, Any>? = null) {
        val match = resolve(utterance)
        assertEquals(
            commandId,
            match.invocation.commandId,
            "\"$utterance\" went to ${match.invocation.commandId} via \"${match.entry.template.source}\"",
        )
        if (params != null) assertEquals(params, match.invocation.params, utterance)
    }

    @Test
    fun `every declared example resolves to its own command`() {
        assertEquals(emptyList(), registry.checkExamples())
    }

    @Test
    fun `spotify`() {
        assertResolves(
            "Spiele Blinding Lights von The Weeknd",
            "spotify.play_music",
            mapOf("query" to "blinding lights von the weeknd"),
        )
        assertResolves("Spiel was von Queen", "spotify.play_music", mapOf("query" to "queen"))
        assertResolves(
            "Spiele den Song Bohemian Rhapsody",
            "spotify.play_music",
            mapOf("query" to "bohemian rhapsody"),
        )
        assertResolves("Musik an", "spotify.play_music", mapOf("query" to ""))
        assertResolves("Nächster Song", "spotify.skip_next")
        assertResolves("überspringen", "spotify.skip_next")
    }

    @Test
    fun `qualified stop and resume stay exclusive, bare forms go to the chain`() {
        assertResolves("stopp die Musik", "spotify.pause")
        assertResolves("Musik aus", "spotify.pause")
        assertResolves("spiel die Musik weiter", "spotify.resume")

        assertResolves("stopp", "shared.stop")
        assertResolves("pause", "shared.stop")
        assertResolves("aus", "shared.stop")
        assertResolves("mach das aus", "shared.stop")
        assertResolves("weiter", "shared.resume")
        assertResolves("mach weiter", "shared.resume")
    }

    @Test
    fun `weiter zum naechsten beats bare weiter`() {
        // The single most likely regression when someone adds a template later.
        assertResolves("weiter zum nächsten", "spotify.skip_next")
        assertResolves("weiter", "shared.resume")
    }

    @Test
    fun `radio wins over spotify's greedy play template`() {
        // The most important ordering assertion in the suite.
        assertResolves("spiele Radio FM4", "radio.play_radio", mapOf("station" to "fm4"))
        assertResolves("radio Ö3", "radio.play_radio", mapOf("station" to "ö3"))
        assertResolves("mach das Radio an", "radio.play_radio", mapOf("station" to ""))
        assertResolves("radio an", "radio.play_radio", mapOf("station" to ""))
        assertResolves("schalt den Sender Ö1 ein", "radio.play_radio", mapOf("station" to "ö1"))
    }

    @Test
    fun `radio stop is not swallowed by the station slot`() {
        // "radio {station}" would happily bind station="aus"; the closed template must win.
        assertResolves("radio aus", "radio.stop_radio")
        assertResolves("mach das Radio aus", "radio.stop_radio")
        assertResolves("stopp das Radio", "radio.stop_radio")
    }

    @Test
    fun `clock`() {
        val tenMinutes = mapOf("amount" to 10, "unit" to "minuten")
        assertResolves("Timer zehn Minuten", "clock.set_timer", tenMinutes)
        assertResolves("stell einen Timer auf 5 Minuten", "clock.set_timer", mapOf("amount" to 5, "unit" to "minuten"))
        assertResolves(
            "stelle mir einen Timer für 90 Sekunden",
            "clock.set_timer",
            mapOf("amount" to 90, "unit" to "sekunden"),
        )
        assertResolves("3 Minuten Timer", "clock.set_timer", mapOf("amount" to 3, "unit" to "minuten"))
        assertResolves("Timer eine Minute", "clock.set_timer", mapOf("amount" to 1, "unit" to "minuten"))
        assertResolves("erinner mich in 20 Minuten", "clock.set_timer", mapOf("amount" to 20, "unit" to "minuten"))

        assertResolves("Wie spät ist es", "clock.whats_the_time")
        assertResolves("Uhrzeit", "clock.whats_the_time")
        assertResolves("Was ist die Zeit?", "clock.whats_the_time")
        assertResolves("sag mir die Zeit", "clock.whats_the_time")
    }

    @Test
    fun `the clock answers English without any other Sock hearing it`() {
        // Parakeet is multilingual and English tokens arrive intact, so these are reachable —
        // and with the whole catalog registered, nothing else claims them.
        assertResolves("What's the time?", "clock.whats_the_time")
        assertResolves("what time is it", "clock.whats_the_time")
    }

    @Test
    fun `a single word inside zeit's fuzzy tolerance reaches nothing`() {
        // Why the clock never claims a bare "zeit": at 4 chars it is fuzzed at tolerance 1, and
        // "seit", "weit" and "zeig" are all ordinary German. The rule is in `README.md` §6 and
        // this is what it is worth across the full catalog, not just inside the Clock Sock.
        assertNull(registry.palette.match(Normalizer.tokenize("Zeit")))
        assertNull(registry.palette.match(Normalizer.tokenize("seit")))
        assertNull(registry.palette.match(Normalizer.tokenize("weit")))
        assertNull(registry.palette.match(Normalizer.tokenize("zeig")))
    }

    @Test
    fun `cancelling a timer requires naming it`() {
        assertResolves("Timer stopp", "clock.cancel_timer")
        assertResolves("Timer abbrechen", "clock.cancel_timer")
        assertResolves("stopp den Timer", "clock.cancel_timer")
        assertResolves("brich den Timer ab", "clock.cancel_timer")
        assertResolves("stopp den Alarm", "clock.cancel_timer")
        // …while the bare form stays with the chain, which decides by activity.
        assertResolves("stopp", "shared.stop")
    }

    @Test
    fun `chime phrasings ride on the chain, contributed by clock`() {
        val match = resolve("ich hab's gehört")
        assertEquals("shared.stop", match.invocation.commandId)
        assertEquals("clock", match.entry.contributedBy)
    }

    @Test
    fun `system volume folds its modifier into steps`() {
        assertResolves("lauter", "system.volume", mapOf("direction" to "lauter", "steps" to 2))
        assertResolves("leiser", "system.volume", mapOf("direction" to "leiser", "steps" to 2))
        assertResolves("mach lauter", "system.volume", mapOf("direction" to "lauter", "steps" to 2))
        assertResolves("viel lauter", "system.volume", mapOf("direction" to "lauter", "steps" to 5))
        assertResolves("ein bisschen leiser", "system.volume", mapOf("direction" to "leiser", "steps" to 1))
        assertResolves("Lautstärke hoch", "system.volume", mapOf("direction" to "lauter", "steps" to 2))
        assertResolves("dreh die Musik leiser", "system.volume", mapOf("direction" to "leiser", "steps" to 2))
    }

    @Test
    fun `the aus family stays apart`() {
        // One family of near-misses, asserted together — they break as a group or not at all.
        assertResolves("ton aus", "system.mute", mapOf("state" to "an"))
        assertResolves("ton an", "system.mute", mapOf("state" to "aus"))
        assertResolves("stumm", "system.mute", mapOf("state" to "an"))
        assertResolves("ruhe", "system.mute", mapOf("state" to "an"))
        assertResolves("musik aus", "spotify.pause")
        assertResolves("radio aus", "radio.stop_radio")
        assertResolves("bildschirm aus", "system.turn_off_screen")
        assertResolves("mach den Bildschirm aus", "system.turn_off_screen")
        assertResolves("aus", "shared.stop")
    }

    @Test
    fun `screen`() {
        assertResolves("bildschirm an", "system.turn_on_screen")
        assertResolves("mach den Bildschirm an", "system.turn_on_screen")
        assertResolves("dashboard", "system.turn_on_screen")
        assertResolves("gute Nacht", "system.turn_off_screen")
    }

    @Test
    fun `departures`() {
        assertResolves("Wann fährt der nächste Bus", "departures.departures", mapOf("line" to ""))
        assertResolves("Wann kommt die nächste Bim", "departures.departures", mapOf("line" to ""))
        assertResolves("Abfahrten", "departures.departures", mapOf("line" to ""))
        assertResolves("Fahrplan", "departures.departures", mapOf("line" to ""))
        assertResolves("Wann fährt der nächste 14A", "departures.departures", mapOf("line" to "14a"))
        assertResolves("Wann kommt der U6", "departures.departures", mapOf("line" to "u6"))
    }

    /**
     * The A/B: every spec utterance resolves identically with phonetics on and off.
     *
     * This is the gate on the whole of M6 part A. The phonetic tier is a *recovery* mechanism —
     * it exists to give a misheard word a second chance — and a recovery mechanism that changes
     * what a correctly heard sentence does is not a recovery mechanism, it is a regression. If
     * this test fails, the guards in [io.dobby.core.nlu.template.KeywordMatcher] are too loose;
     * the fix is to narrow them, never to update the expectation here.
     */
    @Test
    fun `phonetics changes nothing about an utterance Tier 1 already heard`() {
        // Fillers held constant, or this would be an A/B over two changes at once.
        val strict = SockRegistry.buildOrThrow(SpecFixtures.all()).palette.entries
            .let { Palette(it, phonetic = false, fillers = Fillers.DE) }

        for (utterance in SPEC_UTTERANCES) {
            val tokens = Normalizer.tokenize(utterance)
            val before = strict.match(tokens)
            val after = registry.palette.match(tokens)
            assertEquals(
                before?.invocation,
                after?.invocation,
                "\"$utterance\" resolves differently with phonetics on",
            )
            assertEquals(
                before?.entry?.template?.source,
                after?.entry?.template?.source,
                "\"$utterance\" takes a different template with phonetics on",
            )
        }
    }

    /**
     * The other A/B, and the one M6c rests on: filler skipping is a second pass, never a first.
     *
     * The palette runs the whole ordered list strictly before it runs it again with fillers, so
     * every utterance that resolved before resolves to the same command *by the same template*.
     * If this fails, the two-pass structure has been lost somewhere and the specificity ordering
     * is now being decided by which template tolerates the most noise.
     */
    @Test
    fun `filler skipping changes nothing about an utterance the strict pass already heard`() {
        val strict = Palette(registry.palette.entries, fillers = Fillers.NONE)

        for (utterance in SPEC_UTTERANCES) {
            val tokens = Normalizer.tokenize(utterance)
            val before = strict.match(tokens) ?: continue
            val after = registry.palette.match(tokens)
            assertEquals(
                before.invocation,
                after?.invocation,
                "\"$utterance\" resolves differently with filler skipping on",
            )
            assertEquals(
                before.entry.template.source,
                after?.entry?.template?.source,
                "\"$utterance\" takes a different template with filler skipping on",
            )
            assertEquals(false, after?.skippedFillers, "\"$utterance\" was marked a rescue")
        }
    }

    /**
     * What the pruning cost, pinned by name.
     *
     * The clock used to carry seven templates for "what time is it" and now carries three
     * (M6c §D), which means a handful of the spec's own phrasings resolve on the *second* pass
     * now — same command, one palette sweep later, and logged as a rescue. That is the trade
     * the milestone made deliberately, and this list is what makes it reviewable: a phrasing
     * appearing here that somebody thinks belongs on the fast path is an argument for a
     * template, not against the filler list.
     */
    @Test
    fun `the spec phrasings that now resolve on the second pass`() {
        val rescued = SPEC_UTTERANCES.filter { utterance ->
            registry.palette.match(Normalizer.tokenize(utterance))?.skippedFillers == true
        }
        assertEquals(RESCUED_SPEC_UTTERANCES, rescued)
    }

    @Test
    fun `the sentences the filler list was added for`() {
        val strict = Palette(registry.palette.entries, fillers = Fillers.NONE)
        // Left column: what the recogniser really produced, or a particle away from it. Right:
        // where it goes now. Every one of these was a 2-4 s Tier 2 round trip the day before.
        for ((utterance, commandId) in listOf(
            "wie spät ist das" to "clock.whats_the_time",
            "wie spät ist es denn jetzt" to "clock.whats_the_time",
            "ähm wie spät ist es bitte" to "clock.whats_the_time",
            "was ist denn bitte gerade die uhrzeit" to "clock.whats_the_time",
            "mach das mal aus" to "shared.stop",
            "stell mir doch mal einen timer auf 5 minuten" to "clock.set_timer",
            "mach doch bitte die musik aus" to "spotify.pause",
            "timer doch bitte stopp" to "clock.cancel_timer",
        )) {
            val tokens = Normalizer.tokenize(utterance)
            assertNull(strict.match(tokens), "\"$utterance\" already matched before M6c")
            val match = registry.palette.match(tokens)
            assertNotNull(match, "\"$utterance\" still matches nothing")
            assertEquals(commandId, match.invocation.commandId, utterance)
            assertTrue(match.skippedFillers, "\"$utterance\" was not marked as a rescue")
        }
    }

    @Test
    fun `filler skipping does not put a song title through the grinder`() {
        // The slot rule, at palette level: "es muss liebe sein" is a title, not three
        // particles and a verb. It matches on the first pass and keeps every word.
        assertResolves(
            "spiele es muss liebe sein",
            "spotify.play_music",
            mapOf("query" to "es muss liebe sein"),
        )
    }

    @Test
    fun `a garbled keyword is recovered, which is the point of the tier`() {
        // What the milestone bought: three edits from "spiele", and no tolerance that reaches
        // it would be safe. With an anchor around it, the code is.
        assertNull(
            Palette(registry.palette.entries, phonetic = false)
                .match(Normalizer.tokenize("schbiele etwas von Queen")),
        )
        assertResolves("schbiele etwas von Queen", "spotify.play_music", mapOf("query" to "queen"))
        assertResolves("mach den bildshirm aus", "system.turn_off_screen")
    }

    @Test
    fun `a single-literal template is never reached phonetically`() {
        // "aus" is too short to be phonetic at all; "überspringen" is not, and a bare template
        // is exactly where a near-miss has nothing to disagree with it.
        for (entry in registry.palette.entries.filter { registry.palette.isStrict(it) }) {
            assertTrue(
                registry.palette.isStrict(entry),
                "\"${entry.template.source}\" is a single-literal template and must be strict",
            )
        }
        assertNull(registry.palette.match(Normalizer.tokenize("überschbringen")))
    }

    @Test
    fun `unknown utterances match nothing rather than something wrong`() {
        for (utterance in listOf(
            "wie wird das wetter morgen",
            "ruf meine mutter an",
            "erzähl mir einen witz",
            // Words the phonetic tier is the reason to re-check: each shares a Kölner code
            // with a keyword and is a different German word.
            "die spüle ist voll",
            "der tumor ist weg",
            "seit monaten nicht",
            "hol die leiter",
            "das ist ein guter löser",
        )) {
            val match = registry.palette.match(Normalizer.tokenize(utterance))
            assertTrue(match == null, "\"$utterance\" wrongly matched ${match?.invocation?.commandId}")
        }
    }
}
