package io.dobby.core.sock

/**
 * The catalog of commands no single Sock owns.
 *
 * This is plain data, not dispatcher logic — core still contains no behavior for any command.
 * A shared command reaches the palette only if at least one Sock subscribes to it.
 *
 * Specified in `socks.specs/shared-commands.specs.md`.
 */
object SharedCommands {
    /** "Stopp" — means whatever is currently running. */
    val STOP: SharedCommandSpec = SharedCommandSpec(
        id = "shared.stop",
        templates = patterns(
            "(stopp|stop|stoppe|halt|anhalten|aufhören)",
            "hör auf",
            "(pause|pausiere|pausier)",
            "(mach|leg) (eine)? pause",
            "(mach|schalt|schalte) (es|das)? aus",
            "aus",
        ),
        description = "Beendet oder pausiert das, was gerade läuft.",
        help = CommandHelp(
            title = "Stopp",
            detail = "Gilt dem, was gerade läuft: der Musik, einem klingelnden Timer, dem " +
                "Radio. Wer das ist, entscheidet sich im Moment des Sagens — deshalb gehört " +
                "dieser Befehl keinem Bereich allein.",
            hints = listOf("Läuft nichts, sage ich das."),
            aliases = listOf("stopp", "pause", "anhalten"),
        ),
        examples = listOf(
            Example("stopp"),
            Example("pause"),
            // Was a Tier 2 few-shot until the matcher learned to skip filler (M6c): "mal" is
            // noise, so this is "mach das aus" and Tier 1 reaches it for free. Kept as a Tier 1
            // example rather than deleted — it is the phrasing that pays for the filler list.
            Example("mach das mal aus"),
            // A paraphrase Tier 1 still cannot reach — "damit" is a word, not filler — which is
            // precisely what Tier 2 is for.
            Example("hör bitte auf damit", matchedByTemplates = false),
        ),
        unconsumedResponse = SockResult.Spoken("Es läuft gerade nichts.", "Nothing is running."),
    )

    /** "Weiter" — resume whatever was last paused. */
    val RESUME: SharedCommandSpec = SharedCommandSpec(
        id = "shared.resume",
        templates = patterns(
            "(weiter|weitermachen|weiterspielen|fortsetzen|fortfahren)",
            "(mach|spiel) weiter",
            "weiter gehts",
        ),
        description = "Setzt fort, was zuletzt pausiert wurde.",
        help = CommandHelp(
            title = "Weiter",
            detail = "Das Gegenstück zu „Stopp“: setzt fort, was zuletzt pausiert wurde.",
            hints = listOf("Ist nichts pausiert, sage ich das."),
            aliases = listOf("weiter", "fortsetzen"),
        ),
        examples = listOf(
            Example("weiter"),
            Example("mach weiter"),
        ),
        unconsumedResponse = SockResult.Spoken("Es ist gerade nichts pausiert.", "Nothing is paused."),
    )

    /**
     * "Wie heißt das Lied?" — whatever is coming out of the speaker, from wherever.
     *
     * Shared for the reason §1 of the spec lists: the same sentence addresses Spotify and the
     * radio, which one is right depends on what is playing rather than on the wording, and
     * "wie heißt das Lied im Radio" is not a thing anybody says at a wall panel. Both sources
     * carry a title and an artist — Spotify from the App Remote snapshot, the radio from the
     * stream's ICY `StreamTitle` — so both can answer, and only the one holding the channel
     * ever does.
     */
    val WHATS_THE_SONG: SharedCommandSpec = SharedCommandSpec(
        id = "shared.whats_the_song",
        templates = patterns(
            // Written with the article in, although `das`, `der` and `die` are fillers:
            // skipping drops *extra* words from the utterance, it does not make a template's
            // own literals optional. So "wie heißt das lied" needs its `das`, and what the
            // filler list buys is "wie heißt denn gerade das lied" for free (M6c) — the
            // particles, not the articles.
            //
            // Both spellings of `heißt`. The phonetic tier folds ß to ss and would reach
            // `heisst` anyway, but the commonest phrasing here belongs on the strict first
            // pass rather than on the fallback (`socks.specs/README.md` §6).
            "was ist das für ein (lied|song|titel|stück)",
            "was für ein (lied|song|titel|stück) ist das",
            "(wie|welches) (heißt|heisst) (das lied|das stück|die nummer)",
            "wie (heißt|heisst) (der song|der titel)",
            "welches (lied|stück) ist das",
            "welcher (song|titel) ist das",
            "was (läuft|spielt) (da|hier)?",
            "welche musik (läuft|spielt)",
            // The loosest wording here, and it resolves to the song rather than the artist
            // deliberately: somebody pointing at a speaker with two words is asking what the
            // thing is called, and the answer names the artist as well anyway.
            "wie (heißt|heisst) das",
        ),
        description = "Sagt, welcher Titel gerade läuft — mit Künstler.",
        help = CommandHelp(
            title = "Was läuft gerade?",
            detail = "Nennt den laufenden Titel und den Künstler dazu — aus Spotify oder aus " +
                "dem Radiostream, je nachdem, was gerade spielt.",
            hints = listOf(
                "„Wer spielt das?“ nennt nur den Künstler.",
                "Im Radio hängt es am Sender: manche senden Titel und Künstler, andere nur " +
                    "den Sendungsnamen.",
            ),
            aliases = listOf("was läuft", "wie heißt das lied", "welcher song ist das"),
        ),
        examples = listOf(
            Example("was ist das für ein lied"),
            Example("wie heißt der song"),
            Example("wie heißt das"),
            Example("welches lied ist das"),
            Example("was läuft gerade"),
            Example("welche musik läuft"),
            // Paraphrases Tier 1 is meant to miss — few-shots for the LLM tier (M6).
            Example("kannst du mir sagen wie das lied heißt", matchedByTemplates = false),
            Example("sag mir mal den titel von dem song hier", matchedByTemplates = false),
            // Held out: never rendered into a prompt, measured against on the device.
            Example("ich kenne das lied aber komme nicht auf den namen", heldOut = true),
            Example("was für musik ist das denn", heldOut = true),
        ),
        unconsumedResponse = SockResult.Spoken("Es läuft gerade nichts.", "Nothing is playing."),
    )

    /** "Wer spielt das?" — the same question, asking for half the answer. */
    val WHATS_THE_ARTIST: SharedCommandSpec = SharedCommandSpec(
        id = "shared.whats_the_artist",
        templates = patterns(
            "was ist das für ein (künstler|interpret|sänger|artist)",
            "was ist das für eine (band|sängerin|gruppe)",
            "wie (heißt|heisst) (der künstler|der interpret|der sänger|der artist)",
            "wie (heißt|heisst) (die band|die sängerin|die gruppe)",
            // Bare "wer ist das" is deliberately absent, and it is the one wording from the
            // original request that is not here. It is a question about a person at least as
            // often as about a song — at a door, in a photo, on the radio news — and nothing
            // here can tell which was meant. "Wer spielt das" and "wer singt das" name the act
            // of playing and cannot be about anything else.
            "wer (spielt|singt) (das|den song|das lied|hier)?",
            "von wem ist (das|der song|das lied|das stück)",
            "wer ist (der|die) (künstler|interpret|sänger|band|sängerin)",
        ),
        description = "Sagt, wer den Titel spielt, der gerade läuft.",
        help = CommandHelp(
            title = "Wer spielt das?",
            detail = "Nennt den Künstler des laufenden Titels, aus Spotify oder aus dem " +
                "Radiostream.",
            hints = listOf("„Wie heißt das Lied?“ nennt Titel und Künstler."),
            aliases = listOf("wer spielt das", "wie heißt der künstler", "von wem ist das"),
        ),
        examples = listOf(
            Example("wer spielt das"),
            Example("wer singt das"),
            Example("wie heißt der künstler"),
            Example("was ist das für eine band"),
            Example("von wem ist der song"),
            Example("kannst du mir sagen von wem das ist", matchedByTemplates = false),
            Example("den künstler kenne ich gar nicht", heldOut = true),
        ),
        unconsumedResponse = SockResult.Spoken("Es läuft gerade nichts.", "Nothing is playing."),
    )

    val all: List<SharedCommandSpec> = listOf(STOP, RESUME, WHATS_THE_SONG, WHATS_THE_ARTIST)

    fun byId(id: String): SharedCommandSpec? = all.firstOrNull { it.id == id }
}
