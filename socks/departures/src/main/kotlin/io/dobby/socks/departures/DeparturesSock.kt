package io.dobby.socks.departures

import io.dobby.core.sock.CommandHelp
import io.dobby.core.sock.CommandInvocation
import io.dobby.core.sock.Example
import io.dobby.core.sock.ExclusiveCommandSpec
import io.dobby.core.sock.ParamSpec
import io.dobby.core.sock.ParamType
import io.dobby.core.sock.Phrase
import io.dobby.core.sock.Sock
import io.dobby.core.sock.SockContext
import io.dobby.core.sock.SockResult
import io.dobby.core.sock.SockStatus
import io.dobby.core.sock.patterns
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Clock

/**
 * Departures — when the next one goes, from the stops this household actually uses.
 *
 * One command, and the reason it is one rather than several: every departure question is the
 * same question with an optional line on it. "Wann fährt der nächste Bus" and "wann kommt der
 * U6" differ by whether a line was named, which is a parameter — splitting them would be two
 * commands, two prompts to pay for, and the same German written twice.
 *
 * What the command surface does *not* have is a station parameter, and that is the design.
 * Asking "wann fährt der U6 bei der Josefstädter Straße" would mean resolving a station name
 * out of a spoken sentence, which means shipping a two-thousand-row CSV and matching German
 * place names against a recogniser's best guess. Instead the panel knows which stops matter —
 * somebody typed them in once — and the answer covers all of them, naming the station only when
 * more than one of them serves the line (`departures.specs.md` §3). The number of places a
 * wall panel in a flat is asked about is two or three, and they do not change.
 *
 * Two halves, as with the Weather Sock: the card is the full answer and the speech is the
 * summary (§5). That is not a limitation of speech, it is what makes the speech usable — a
 * panel reading out eleven departures is one somebody walks away from, and all eleven are on
 * the screen they are standing in front of.
 *
 * **This Sock polls an unauthenticated public endpoint, and the rules about that are
 * functional requirements** (§6). The ones about time live in [DeparturesWatch]; the one about
 * the screen lives in [onStart], and it is why `ScreenController` grew an `isOn`.
 *
 * @param source where a board comes from. [DepartureSource.NONE] off-device, where every
 *   command still resolves and every one of them says it is offline.
 * @param clock injected so the poll window and the staleness caveat are testable without
 *   waiting out a tick.
 */
class DeparturesSock(
    private val source: DepartureSource = DepartureSource.NONE,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val screenWakeSeconds: Int = DEFAULT_SCREEN_WAKE_SECONDS,
) : Sock {

    override val id: String = "departures"

    override val displayName: String = "Abfahrten"

    /** Held from [onStart], the way every other Sock holds its own: a context is service-lifetime. */
    private var context: SockContext? = null

    private val watch = DeparturesWatch(source, clock)

    /** What the wall panel draws (§5): the stations, the board, and what went wrong. */
    val state: StateFlow<DeparturesState> = watch.state

    private val _status = MutableStateFlow<SockStatus>(SockStatus.Ready)

    override val status: StateFlow<SockStatus> = _status.asStateFlow()

    /** The poll loop. Lives exactly as long as the Sock is started. */
    private var ticker: Job? = null

    override val commands: List<ExclusiveCommandSpec> = listOf(
        ExclusiveCommandSpec(
            id = DEPARTURES,
            params = listOf(ParamSpec(LINE, ParamType.Text, required = false, default = "")),
            templates = patterns(
                // The generic transport nouns are matched **first** and produce no line: "der
                // nächste Bus" means "the next departure", not a line called Bus. Ordering
                // specific before generic is what keeps the open {line} slot below from
                // swallowing them (`socks.specs/README.md` §6).
                "wann (fährt|kommt|geht) (der|die|das) (nächste|nächster) " +
                    "(bus|bim|straßenbahn|tram|ubahn|u bahn|linie)",
                "wann (fährt|kommt|geht) (der|die|das) (nächste|nächster) {line}",
                "wann (fährt|kommt|geht) (der|die) {line}",
                // Plural bare, singular anchored, and the asymmetry is §6's single-keyword rule
                // rather than a spelling preference. At seven characters „abfahrt" is fuzzed at
                // tolerance 1, which hands it „anfahrt" — a word somebody says in a kitchen
                // without addressing the panel, and the registry's own gate flags it. „abfahrten"
                // is nine characters and one more edit away from all of them, and its tolerance
                // of 2 still reaches the singular, so nothing is lost by requiring an anchor on
                // the form that is dangerous alone.
                "(nächste|die nächsten|die)? abfahrten",
                "(die|der)? (nächste|nächsten) abfahrt",
                "wann (ist|geht) (die)? nächste abfahrt",
                "(fahrplan|abfahrtszeiten)",
            ),
            description = "Sagt die nächsten Abfahrten der eingestellten Haltestellen.",
            help = CommandHelp(
                title = "Abfahrten",
                detail = "Sagt, wann die nächsten Öffis fahren. Mit einer Linie — „wann kommt " +
                    "der U6“ — nenne ich die nächste und die übernächste Abfahrt in beide " +
                    "Richtungen, ohne Linie die drei, die als nächstes wegfahren.",
                hints = listOf(
                    "Die Haltestellen stellst du in den Einstellungen ein, mit ihrer DIVA-Nummer.",
                    "Auf dem Panel steht die ganze Tafel — gesprochen bekommst du die Kurzfassung.",
                ),
                aliases = listOf("abfahrten", "fahrplan", "öffis", "wann fährt der bus"),
            ),
            examples = listOf(
                Example("wann fährt der nächste bus", mapOf(LINE to "")),
                Example("wann kommt die nächste bim", mapOf(LINE to "")),
                Example("abfahrten", mapOf(LINE to "")),
                Example("die nächsten abfahrten", mapOf(LINE to "")),
                Example("die nächste abfahrt", mapOf(LINE to "")),
                Example("wann ist die nächste abfahrt", mapOf(LINE to "")),
                Example("wann fährt der nächste 14a", mapOf(LINE to "14a")),
                Example("wann kommt der u6", mapOf(LINE to "u6")),
                Example("wann fährt die d", mapOf(LINE to "d")),
                Example("fahrplan", mapOf(LINE to "")),
                // Paraphrases Tier 1 is meant to miss — few-shots for the LLM tier, written in
                // *normalized* German because Tier 2 is handed the same string Tier 1 saw.
                Example("muss ich mich beeilen oder fährt gleich wieder einer", matchedByTemplates = false),
                Example("wie lange hab ich noch bis zur nächsten straßenbahn", matchedByTemplates = false),
                // Held out: never shown to the model, asserted on the device.
                Example("krieg ich den u6 noch", heldOut = true),
                Example("was geht denn als nächstes weg", heldOut = true),
            ),
        ),
    )

    override suspend fun onStart(ctx: SockContext) {
        context = ctx
        watch.restore(ctx)
        republishStatus()
        // Fair-use rule 1 (§6), and the only place it can live: **the fetch is skipped while
        // the screen is off**. A departure board nobody can see is worth nothing, and the cost
        // of fetching it anyway is not battery — it is somebody else's public endpoint being
        // polled two thousand times a night by a panel in a dark kitchen.
        //
        // A plain loop rather than a scheduled alarm, for the same reason the Weather Sock uses
        // one: this exists so the card is right when somebody walks past it, and the panel is
        // either running or it is not. The loop keeps ticking with the screen off — it just
        // does not fetch — so the first tick after the screen comes back is at most one
        // interval away rather than needing anything to wake it.
        ticker = ctx.scope.launch {
            while (isActive) {
                if (watch.state.value.isSetUp && ctx.screen.isOn) watch.refresh(ctx)
                republishStatus()
                delay(DeparturesConfig(ctx.config).pollInterval.toMillis())
            }
        }
    }

    override suspend fun onStop() {
        ticker?.cancel()
        ticker = null
        context = null
    }

    override suspend fun handle(invocation: CommandInvocation): SockResult {
        val ctx = context ?: return SockResult.Failed(DeparturesSpeech.NOT_READY)
        // Unreachable in practice: the dispatcher only routes commands this Sock owns.
        if (invocation.commandId != DEPARTURES) return SockResult.NotForMe
        val spoken = invocation.textOrNull(LINE)?.takeIf { it.isNotBlank() }
        val answer = when (val lookup = watch.lookup(ctx)) {
            Lookup.NoStations -> return SockResult.Failed(DeparturesSpeech.NO_STATIONS)

            is Lookup.Unavailable -> {
                // Four-minute-old departures are still departures, and the caveat is what keeps
                // them honest — the same bargain the Weather Sock makes, with a shorter fuse.
                val stale = lookup.stale
                    ?: return SockResult.Failed(DeparturesSpeech.failure(lookup.error))
                val phrase = answer(ctx, stale, spoken) ?: return SockResult.Spoken(DeparturesSpeech.NO_DEPARTURES)
                DeparturesSpeech.stale(watch.age(stale), phrase)
            }

            is Lookup.Ready -> answer(ctx, lookup.board, spoken)
                ?: return SockResult.Spoken(DeparturesSpeech.NO_DEPARTURES)
        }
        republishStatus()
        // The panel showing the board the voice just summarised is the half of the answer that
        // survives being misheard — and the half somebody checks on the way out of the door.
        ctx.screen.wakeFor(screenWakeSeconds)
        return SockResult.Spoken(answer)
    }

    /**
     * The sentence, whichever of the two questions was asked.
     *
     * Null means the board has nothing in it at all — after the last train, or for a station
     * whose lines have all stopped — which is a `Spoken` answer of its own and not a failure.
     *
     * A line that resolves to nothing does **not** fall through to null: it falls through to the
     * summary with a caveat in front of it (§3), because somebody who named a line was asking
     * about departures either way, and the ones that are coming are the next best thing to the
     * one that is not.
     */
    private fun answer(ctx: SockContext, board: DepartureBoard, spoken: String?): Phrase? {
        if (board.isEmpty) return null
        val articles = DeparturesConfig(ctx.config).lineArticles
        val stations = watch.state.value.stations
        if (spoken != null) {
            val line = LineName.resolve(spoken, board.lineNames())
            if (line != null) return lineAnswer(board, line, articles)
            ctx.log.debug("departures: no line matched \"$spoken\"")
            return DeparturesSpeech.lineNotFound(summary(ctx, board, stations, articles))
        }
        return summary(ctx, board, stations, articles)
    }

    /**
     * One line, both directions, at every configured station that serves it (§3).
     *
     * Grouped by station and ordered the way the stations are configured, because the answer is
     * read out in that order and somebody who typed their own stop first expects to hear it
     * first. The station is named only when a second one serves the same line — with one
     * station in the answer there is nothing to distinguish it from, and saying it anyway is a
     * word heard four times a day for no reason.
     */
    private fun lineAnswer(
        board: DepartureBoard,
        line: String,
        articles: Map<String, String>,
    ): Phrase {
        val blocks = board.of(line)
            .groupBy { it.station.diva }
            .values
            .map { directions -> directions.sortedBy { it.next ?: Int.MAX_VALUE } }
        return DeparturesSpeech.lineAnswer(blocks, articles, nameStations = blocks.size > 1)
    }

    /**
     * No line was named: the soonest few departures across every configured station (§3).
     *
     * **One entry per *(station, line, destination)***, which is the cap doing its real work:
     * a line that comes twice inside the window would otherwise spend two of the three places
     * saying one thing, and the second one is on the card.
     */
    private fun summary(
        ctx: SockContext,
        board: DepartureBoard,
        stations: List<Station>,
        articles: Map<String, String>,
    ): Phrase {
        val soonest = board.boards
            .filter { it.countdowns.isNotEmpty() }
            .sortedBy { it.next ?: Int.MAX_VALUE }
            .take(DeparturesConfig(ctx.config).maxSpokenLines)
        return DeparturesSpeech.summary(soonest, articles, nameStations = stations.size > 1)
    }

    /**
     * The card's own refresh, for a panel somebody is standing in front of right now.
     *
     * Subject to the same floor as everything else — a tap inside the window does nothing, and
     * that is not a bug (§6.2). The card is already showing „gerade eben" in that case, which
     * is the honest answer to "is this current".
     */
    suspend fun refreshFromPanel() {
        val ctx = context ?: return
        watch.refresh(ctx)
        republishStatus()
    }

    /**
     * The settings screen wrote a new station list.
     *
     * Straight at the Sock rather than through the engine, for the reason `RadioSock` and
     * `WeatherSock` give for their panel entry points: there is no utterance here, and
     * inventing one to put a button press back through the matcher would route it through the
     * one part of the system that can misunderstand it.
     */
    suspend fun stationsChangedFromPanel() {
        val ctx = context ?: return
        watch.stationsChanged(ctx)
        republishStatus()
    }

    /**
     * What the settings screen says about this Sock.
     *
     * Degraded rather than unavailable for both failures, and deliberately: a panel with no
     * station is one text field from working, and one that cannot reach the Wiener Linien will
     * be fine again when the router is. `Unavailable` is for a dependency that is not coming
     * back, which is what it means on the Spotify Sock and what it must keep meaning.
     */
    private fun republishStatus() {
        val current = watch.state.value
        _status.value = when {
            !current.isSetUp -> SockStatus.Degraded("Keine Haltestelle eingestellt")
            current.error != null -> SockStatus.Degraded(current.error)
            else -> SockStatus.Ready
        }
    }

    companion object {
        const val DEPARTURES: String = "departures.departures"

        /** The one param: a spoken line name, or empty for "whatever goes next". */
        const val LINE: String = "line"

        const val DEFAULT_SCREEN_WAKE_SECONDS: Int = 30
    }
}
