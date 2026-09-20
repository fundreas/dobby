package io.dobby.core.registry

import io.dobby.core.FixtureSock
import io.dobby.core.sock.ExclusiveCommandSpec
import io.dobby.core.sock.ParamSpec
import io.dobby.core.sock.ParamType
import io.dobby.core.sock.SharedCommands
import io.dobby.core.sock.SharedSubscription
import io.dobby.core.sock.Sock
import io.dobby.core.sock.SockResult
import io.dobby.core.sock.pattern
import io.dobby.core.sock.patterns

/**
 * Socks carrying the real templates from `socks.specs/`, with stub handlers.
 *
 * These are NOT the product Socks — no Spotify SDK, no ExoPlayer, no network. They exist so
 * Phase A can prove that the template engine, the specificity ordering and the registry
 * actually resolve the utterance tables the specs promise, long before any of those Socks is
 * implemented. When a real Sock lands, its templates move out of here and these shrink.
 */
object SpecFixtures {

    private val text = ParamType.Text
    private val int = ParamType.Integer

    fun spotify(): Sock = FixtureSock(
        id = "spotify",
        commands = listOf(
            ExclusiveCommandSpec(
                id = "spotify.play_music",
                params = listOf(ParamSpec("query", text, required = false, default = "")),
                templates = patterns(
                    "(spiele|spiel|spiele mir|spiel mir) (den song|das lied|den titel|die playlist) {query}",
                    "(spiele|spiel|spiele mir|spiel mir) (etwas|was|irgendwas) von {query}",
                    "(mach|leg|spiel) {query} (an|auf)",
                    "(spiele|spiel|spiele mir|spiel mir) {query}( ab)?",
                    "(musik|spotify) (an|einschalten)",
                ),
                description = "Spielt Musik auf Spotify ab.",
            ),
            ExclusiveCommandSpec(
                id = "spotify.pause",
                templates = patterns(
                    "(musik|spotify|wiedergabe) (aus|stoppen|anhalten|pausieren)",
                    "(stopp|stoppe|stop|pausiere|pausier|halt) (die musik|die wiedergabe|spotify)",
                    "(mach|schalt|schalte) (die musik|spotify) aus",
                ),
                description = "Pausiert Spotify, explizit adressiert.",
            ),
            ExclusiveCommandSpec(
                id = "spotify.resume",
                templates = patterns(
                    "(die musik|die wiedergabe|spotify) (weiter|fortsetzen|weiterspielen)",
                    "(spiel|spiele|mach) (die musik|spotify) weiter",
                ),
                description = "Setzt Spotify fort, explizit adressiert.",
            ),
            ExclusiveCommandSpec(
                id = "spotify.skip_next",
                templates = patterns(
                    "(nächster|nächstes|nächste) (song|lied|titel|stück|track)",
                    "(überspringen|skip|skippen)",
                    "weiter zum nächsten",
                    "(spiel|spiele) (den|das) (nächste|nächsten|nächstes) (song|lied|titel)",
                ),
                description = "Springt zum nächsten Titel.",
            ),
        ),
        shared = listOf(
            SharedSubscription(SharedCommands.STOP, priority = 50),
            SharedSubscription(SharedCommands.RESUME, priority = 50),
        ),
        handler = { SockResult.Silent },
    )

    /**
     * Radio is built (`io.dobby.socks.radio.RadioSock`), and this stays for the same reason
     * [clock] does: `:core` cannot depend on a Sock module, so the fixture is how Radio stays
     * in the cross-Sock collision matrix — which is where the "spiele radio fm4" must not be
     * Spotify" regression lives.
     *
     * The **templates** are copied verbatim, character for character, because they are what the
     * matrix tests. The one-line descriptions are not: they feed the Tier 2 prompt goldens in
     * this same module, which have no business re-recording themselves every time a Sock
     * rewords its help text. If the templates drift apart, this file is the one that is wrong.
     */
    fun radio(): Sock = FixtureSock(
        id = "radio",
        commands = listOf(
            ExclusiveCommandSpec(
                id = "radio.play_radio",
                params = listOf(ParamSpec("station", text, required = false, default = "")),
                templates = patterns(
                    "(spiele|spiel|mach|schalt|schalte) (den)? (sender|radiosender) {station} (an|ein)",
                    "(spiele|spiel|mach|schalt|schalte) (das|den)? radio {station} (an|ein)",
                    "(spiele|spiel|mach|schalt|schalte) (den)? (sender|radiosender) {station}",
                    "(spiele|spiel|mach|schalt|schalte) (das|den)? radio {station}",
                    "(spiele|spiel|mach|schalt|schalte) (das|den)? radio (an|ein)?",
                    "radio {station}",
                    "radio (an|ein)?",
                ),
                description = "Spielt einen Radiosender ab.",
            ),
            ExclusiveCommandSpec(
                id = "radio.stop_radio",
                templates = patterns(
                    "radio (aus|stopp|stop|ausschalten|abschalten|beenden)",
                    "(mach|schalt|schalte|stopp|stoppe) (das|den)? radio (aus|ab)",
                    "(stopp|stoppe|beende) (das|den)? radio",
                ),
                description = "Beendet die Radiowiedergabe.",
            ),
        ),
        shared = listOf(
            SharedSubscription(SharedCommands.STOP, priority = 50),
            SharedSubscription(SharedCommands.RESUME, priority = 40),
        ),
        handler = { SockResult.Silent },
    )

    /**
     * Clock is built (`io.dobby.socks.clock.ClockSock`), but `:core` cannot depend on a Sock —
     * so its templates are mirrored here, verbatim, to keep it in the cross-Sock collision
     * matrix. If the two drift apart, this file is the one that is wrong.
     */
    fun clock(): Sock = FixtureSock(
        id = "clock",
        commands = listOf(
            ExclusiveCommandSpec(
                id = "clock.set_timer",
                params = listOf(
                    ParamSpec("amount", int),
                    ParamSpec("unit", ParamType.Enumeration(listOf("sekunden", "minuten", "stunden"))),
                ),
                templates = patterns(
                    "(stell|stelle|setz|setze|mach) (einen|nen)? timer (auf|für)? {amount:int} {unit:enum}",
                    "(stell|stelle|setz|setze) (einen|nen)? wecker (auf|für)? {amount:int} {unit:enum}",
                    "(erinner|erinnere) mich in {amount:int} {unit:enum}",
                    "timer (auf|für)? {amount:int} {unit:enum}",
                    "{amount:int} {unit:enum} timer",
                ),
                description = "Stellt einen Timer für eine bestimmte Dauer.",
            ),
            ExclusiveCommandSpec(
                id = "clock.cancel_timer",
                templates = patterns(
                    "timer (stopp|stop|stoppen|abbrechen|aus|löschen|beenden|abschalten)",
                    "(stopp|stoppe|brich|breche|lösch|lösche|beende) (den)? timer (ab)?",
                    "(stopp|stoppe|aus mit) (dem|den|das)? (alarm|wecker|klingeln)",
                ),
                description = "Bricht den laufenden Timer ab.",
            ),
            ExclusiveCommandSpec(
                id = "clock.whats_the_time",
                templates = patterns(
                    "wie (spät|viel uhr)",
                    "(sag|was ist) (die)? (uhrzeit|zeit)",
                    "(uhrzeit|die uhrzeit)",
                    "what time is it",
                    "(whats|what's|what is) the time (now)?",
                ),
                description = "Sagt die aktuelle Uhrzeit.",
            ),
        ),
        shared = listOf(
            SharedSubscription(
                SharedCommands.STOP,
                priority = 100,
                extraTemplates = patterns("(ich hab's gehört|ich habs gehört)", "(ja ja|ist gut)"),
            ),
        ),
        handler = { SockResult.Silent },
    )

    fun system(): Sock = FixtureSock(
        id = "system",
        commands = listOf(
            ExclusiveCommandSpec(
                id = "system.volume",
                params = listOf(
                    ParamSpec("direction", ParamType.Enumeration(listOf("lauter", "leiser"))),
                    ParamSpec("steps", int, required = false, default = 2),
                ),
                templates = listOf(
                    pattern("(viel|deutlich|wesentlich) {direction:enum}", "steps" to 5),
                    pattern("(ein bisschen|etwas) {direction:enum}", "steps" to 1),
                    pattern("lautstärke (hoch|rauf)", "direction" to "lauter"),
                    pattern("lautstärke (runter|leiser)", "direction" to "leiser"),
                ) + patterns(
                    "(mach|dreh|stell) (es|die musik|den ton|die lautstärke)? {direction:enum}",
                    "{direction:enum}",
                ),
                description = "Ändert die Lautstärke schrittweise.",
            ),
            ExclusiveCommandSpec(
                id = "system.mute",
                params = listOf(
                    ParamSpec(
                        "state",
                        ParamType.Enumeration(listOf("an", "aus")),
                        required = false,
                        default = "an",
                    ),
                ),
                templates = listOf(
                    pattern("(stumm|stummschalten|mach stumm|sei still|ruhe)", "state" to "an"),
                    pattern("(ton|lautstärke) aus", "state" to "an"),
                    pattern("(ton|lautstärke) (an|ein)", "state" to "aus"),
                    pattern("(nicht mehr stumm|stumm aus|entstummen|wieder laut)", "state" to "aus"),
                ),
                description = "Schaltet den Ton stumm oder wieder an.",
            ),
            ExclusiveCommandSpec(
                id = "system.turn_on_screen",
                templates = patterns(
                    "(bildschirm|display|schirm) (an|ein|einschalten)",
                    "(mach|schalt|schalte) (den)? (bildschirm|display|schirm) (an|ein)",
                    "(wach auf|aufwachen|dashboard)",
                ),
                description = "Schaltet den Bildschirm ein.",
            ),
            ExclusiveCommandSpec(
                id = "system.turn_off_screen",
                templates = patterns(
                    "(bildschirm|display|schirm) (aus|ausschalten|abschalten)",
                    "(mach|schalt|schalte) (den)? (bildschirm|display|schirm) aus",
                    "gute nacht",
                ),
                description = "Schaltet den Bildschirm aus.",
            ),
        ),
        handler = { SockResult.Silent },
    )

    fun departures(): Sock = FixtureSock(
        id = "departures",
        commands = listOf(
            ExclusiveCommandSpec(
                id = "departures.departures",
                params = listOf(ParamSpec("line", text, required = false, default = "")),
                templates = patterns(
                    // Generic transport nouns mean "the next departure", not a line called "Bus".
                    "wann (fährt|kommt|geht) (der|die|das) (nächste|nächster) " +
                        "(bus|bim|straßenbahn|tram|ubahn|u bahn|linie)",
                    "wann (fährt|kommt|geht) (der|die|das) (nächste|nächster) {line}",
                    "wann (fährt|kommt|geht) (der|die) {line}",
                    "(nächste|die nächsten)? (abfahrten|abfahrt)",
                    "(fahrplan|abfahrtszeiten)",
                ),
                description = "Sagt die nächsten Abfahrten.",
            ),
        ),
        handler = { SockResult.Silent },
    )

    /** Registration order matters only as the final tiebreak; specificity does the real work. */
    fun all(): List<Sock> = listOf(spotify(), radio(), clock(), system(), departures())
}
