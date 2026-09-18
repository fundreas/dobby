package io.dobby.core.registry

import io.dobby.core.nlu.Normalizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The utterance tables from `socks.specs/`, run against the assembled palette.
 *
 * This is the collision gate the plan calls for (§5.3): it is what fails in CI when someone
 * adds a template that shadows another Sock.
 */
class SpecPaletteTest {

    private val registry = SockRegistry.buildOrThrow(SpecFixtures.all())

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

    @Test
    fun `unknown utterances match nothing rather than something wrong`() {
        for (utterance in listOf(
            "wie wird das wetter morgen",
            "ruf meine mutter an",
            "erzähl mir einen witz",
        )) {
            val match = registry.palette.match(Normalizer.tokenize(utterance))
            assertTrue(match == null, "\"$utterance\" wrongly matched ${match?.invocation?.commandId}")
        }
    }
}
