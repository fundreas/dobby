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
        unconsumedResponse = SockResult.Spoken("Es läuft gerade nichts."),
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
        examples = listOf(
            Example("weiter"),
            Example("mach weiter"),
        ),
        unconsumedResponse = SockResult.Spoken("Es ist gerade nichts pausiert."),
    )

    val all: List<SharedCommandSpec> = listOf(STOP, RESUME)

    fun byId(id: String): SharedCommandSpec? = all.firstOrNull { it.id == id }
}
