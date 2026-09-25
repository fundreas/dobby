package io.dobby.socks.departures

import io.dobby.core.sock.Lang
import io.dobby.core.sock.Phrase
import java.time.Duration

/**
 * Every sentence this Sock says, and the one place a countdown becomes German.
 *
 * A [Phrase] and never a `String`, which is the i18n contract (`socks.specs/README.md` §2a):
 * „Der U6 Richtung Siebenhirten fährt in 3 Minuten, danach in 8" and "The U6 towards
 * Siebenhirten leaves in 3 minutes, then in 8" are not the same sentence with the words
 * swapped — the article carries the vehicle's gender in one language and does not exist in the
 * other, and the verb is in a different place.
 *
 * Four rules run through all of it:
 *
 * 1. **`0` is „jetzt", not „in 0 Minuten".** It is the one countdown that means something
 *    different in kind: not "soon" but "you are not catching this one".
 * 2. **The unit is said once.** „in 3 Minuten, danach in 8" — the second unit only makes the
 *    sentence longer, and a wall panel is competing with a kettle.
 * 3. **Singular exists.** „in 1 Minute". „in 1 Minuten" is what a panel sounds like when
 *    nobody read it out loud.
 * 4. **The station is named only when it disambiguates** (§3). With one station configured
 *    there is nothing to distinguish it from, and saying it every time is a word somebody
 *    hears four times a day for no reason.
 */
object DeparturesSpeech {

    // ---------------------------------------------------------------- failures and scaffolding

    /**
     * Nothing is configured, so there is nothing to have departures from.
     *
     * The one failure in this Sock the user can fix, and the card says where (§5) — this
     * sentence stays short because the panel it names is the one they are standing in front of.
     */
    val NO_STATIONS: Phrase = Phrase.of(
        "Es ist noch keine Haltestelle eingestellt.",
        "No stop has been set up yet.",
    )

    val OFFLINE: Phrase = Phrase.of(
        "Ich habe gerade keine Internetverbindung.",
        "I have no internet connection right now.",
    )

    val SERVICE_DOWN: Phrase = Phrase.of(
        "Die Wiener Linien antworten gerade nicht.",
        "Wiener Linien aren't answering right now.",
    )

    val RATE_LIMITED: Phrase = Phrase.of(
        "Die Wiener Linien lassen mich gerade nicht so oft fragen.",
        "Wiener Linien are throttling me right now.",
    )

    val NO_DEPARTURES: Phrase = Phrase.of(
        "Für die nächste Zeit sind keine Abfahrten gemeldet.",
        "No departures are reported for the next while.",
    )

    val NOT_READY: Phrase = Phrase.of(
        "Ich komme gerade nicht an die Abfahrten.",
        "I can't get at the departures right now.",
    )

    fun failure(error: DeparturesError): Phrase = when (error) {
        DeparturesError.OFFLINE -> OFFLINE
        DeparturesError.RATE_LIMITED -> RATE_LIMITED
        DeparturesError.SERVER, DeparturesError.FAILED -> SERVICE_DOWN
    }

    /**
     * Answers from a board that is older than it should be, and says so first.
     *
     * The alternative to the caveat is silence about it, and silence is what turns a stale
     * countdown into a wrong one — this is the one kind of stale data that expires while it is
     * being read out.
     */
    fun stale(age: Duration, answer: Phrase): Phrase = Phrase { lang ->
        val minutes = age.toMinutes().coerceAtLeast(1)
        val head = if (lang == Lang.EN) "From $minutes minutes ago:" else "Stand von vor $minutes Minuten:"
        "$head ${answer(lang).decapitalize()}"
    }

    /**
     * The line was not found, and the question is answered anyway (§3).
     *
     * A `Failed` here would be a panel refusing a question it can very nearly answer: somebody
     * who named a line was asking about departures, and the ones that *are* coming are the
     * next best thing to the one that is not.
     */
    fun lineNotFound(summary: Phrase): Phrase = Phrase { lang ->
        val head = if (lang == Lang.EN) {
            "I couldn't find that line, but:"
        } else {
            "Die Linie habe ich nicht gefunden, aber:"
        }
        "$head ${summary(lang).decapitalize()}"
    }

    // ------------------------------------------------------------------------------ answers

    /**
     * One line, both directions, at every configured station that has it (§3).
     *
     * [blocks] is already grouped by station and ordered; [nameStations] is true when more than
     * one of them serves the line, and is what turns „Der U1 Richtung Leopoldau fährt …" into
     * „Ab Stephansplatz fährt der U1 Richtung Leopoldau …".
     *
     * The **first** direction of each station carries the verb and the unit; every direction
     * after it is a fragment hanging off it („Richtung Floridsdorf in 5, danach in 12"). That is
     * how somebody would say it, and it is also the only way two directions fit into a sentence
     * short enough to listen to.
     */
    fun lineAnswer(
        blocks: List<List<DirectionBoard>>,
        articles: Map<String, String>,
        nameStations: Boolean,
    ): Phrase = Phrase { lang ->
        blocks.filter { it.isNotEmpty() }.joinToString(" ") { directions ->
            val head = directions.first()
            val station = head.station.display
            val subject = subject(head, articles, lang, capitalized = !nameStations)
            val destination = towards(head, lang)
            val opening = when {
                nameStations && lang == Lang.EN -> "From $station $subject $destination leaves"
                nameStations -> "Ab $station fährt $subject $destination"
                lang == Lang.EN -> "$subject $destination leaves"
                else -> "$subject $destination fährt"
            }
            val sentences = mutableListOf("$opening ${departurePair(head, lang, withUnit = true)}.")
            for (board in directions.drop(1)) {
                val label = towards(board, lang).replaceFirstChar { it.uppercase() }
                sentences += "$label ${departurePair(board, lang, withUnit = false)}."
            }
            sentences.joinToString(" ")
        }
    }

    /**
     * No line was asked for: the soonest few departures across every configured station (§3).
     *
     * One sentence with a clause each rather than one sentence each, because three full
     * sentences about three different lines is a paragraph, and the card behind it already has
     * all of them. [nameStations] adds „ab Karlsplatz" to every clause when more than one
     * station is configured — in the middle of the clause, where German puts it, and not as a
     * prefix that would make every clause start with the same two words.
     */
    fun summary(
        boards: List<DirectionBoard>,
        articles: Map<String, String>,
        nameStations: Boolean,
    ): Phrase = Phrase { lang ->
        var previousStation: String? = null
        val clauses = boards.mapIndexed { index, board ->
            val subject = subject(board, articles, lang, capitalized = index == 0)
            val station = board.station.display
            // Named only when it changes. Two clauses in a row about the same stop would say
            // its name twice, and German does not — the second clause inherits it, which is
            // both shorter and what somebody standing there would say.
            val from = if (nameStations && station != previousStation) {
                if (lang == Lang.EN) "from $station " else "ab $station "
            } else {
                ""
            }
            previousStation = station
            val leaving = countdown(board.next ?: 0, lang, withUnit = index == 0)
            if (index == 0) {
                val verb = if (lang == Lang.EN) "leaves " else "fährt "
                "$subject ${towards(board, lang)} $verb$from$leaving"
            } else {
                "$subject ${towards(board, lang)} $from$leaving"
            }
        }
        clauses.joinToString(", ") + "."
    }

    // -------------------------------------------------------------------------------- panel

    /** „jetzt" or „3 min" — the card's countdown column (§5). */
    fun panelCountdown(minutes: Int): String = if (minutes <= 0) "jetzt" else "$minutes min"

    /** „vor 2 Minuten" — the card's freshness line. Drawn, so German whatever voice is set. */
    fun panelAge(age: Duration): String {
        val minutes = age.toMinutes()
        return when {
            minutes < 1 -> "gerade eben"
            minutes == 1L -> "vor 1 Minute"
            minutes < MINUTES_PER_HOUR -> "vor $minutes Minuten"
            minutes < 2 * MINUTES_PER_HOUR -> "vor 1 Stunde"
            else -> "vor ${minutes / MINUTES_PER_HOUR} Stunden"
        }
    }

    // ---------------------------------------------------------------------------------- bits

    /**
     * „in 3 Minuten, danach in 8", or „jetzt, danach in 8", or „in 4 Minuten".
     *
     * The „danach" half disappears when the API reported only one departure for this
     * destination, which happens at the end of a line's service and on the last train of the
     * night — the two occasions where a panel inventing a second one would matter most.
     */
    private fun departurePair(board: DirectionBoard, lang: Lang, withUnit: Boolean): String {
        val next = countdown(board.next ?: 0, lang, withUnit)
        val after = board.overnext ?: return next
        val then = if (lang == Lang.EN) "then" else "danach"
        return "$next, $then ${countdown(after, lang, withUnit = false)}"
    }

    /**
     * „in 3 Minuten", „in 1 Minute", „jetzt" — and without the unit, „in 3".
     *
     * [withUnit] is false for every countdown after the first one in a sentence: the unit was
     * established by the first and repeating it is the difference between a sentence and a
     * timetable being read aloud.
     */
    private fun countdown(minutes: Int, lang: Lang, withUnit: Boolean): String = when {
        minutes <= 0 -> if (lang == Lang.EN) "now" else "jetzt"
        // One is the exception to the unit-once rule, in both languages: the bare ellipsis
        // „danach in 1" is read out as "danach in eins", which is a number and not a duration.
        minutes == 1 -> if (lang == Lang.EN) "in 1 minute" else "in 1 Minute"
        !withUnit -> "in $minutes"
        else -> if (lang == Lang.EN) "in $minutes minutes" else "in $minutes Minuten"
    }

    /**
     * „Der U6", „die Linie D", „der 14A" — and "the U6" for all three in English.
     *
     * The article comes from the vehicle's kind, which the API reports as `type`, because the
     * line *name* cannot carry it: "1" is a tram and "1A" is a bus, one letter apart and two
     * different articles. A per-line override in config wins over the derivation, for the day
     * it is wrong about one (§7) — and is empty by default, because a seed table would be a
     * second copy of this mapping with nothing keeping it honest.
     */
    private fun subject(
        board: DirectionBoard,
        articles: Map<String, String>,
        lang: Lang,
        capitalized: Boolean,
    ): String {
        if (lang == Lang.EN) return if (capitalized) "The ${board.line}" else "the ${board.line}"
        val article = articles[board.line.lowercase()] ?: when (board.kind) {
            LineKind.METRO, LineKind.BUS -> "der"
            LineKind.TRAM, LineKind.TRAIN, LineKind.OTHER -> "die Linie"
        }
        val rendered = if (capitalized) article.replaceFirstChar { it.uppercase() } else article
        return "$rendered ${board.line}"
    }

    /** „Richtung Siebenhirten" / "towards Siebenhirten". Lowercase; callers capitalise. */
    private fun towards(board: DirectionBoard, lang: Lang): String =
        if (lang == Lang.EN) "towards ${board.towards}" else "Richtung ${board.towards}"

    private fun String.decapitalize(): String = replaceFirstChar { it.lowercase() }

    private const val MINUTES_PER_HOUR = 60
}
