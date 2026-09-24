package io.dobby.socks.clock

import io.dobby.core.sock.CommandHelp
import io.dobby.core.sock.CommandInvocation
import io.dobby.core.sock.Example
import io.dobby.core.sock.ExclusiveCommandSpec
import io.dobby.core.sock.FollowUp
import io.dobby.core.sock.Lang
import io.dobby.core.sock.ParamSpec
import io.dobby.core.sock.ParamType
import io.dobby.core.sock.Phrase
import io.dobby.core.sock.SharedCommands
import io.dobby.core.sock.SharedSubscription
import io.dobby.core.sock.Sock
import io.dobby.core.sock.SockActivity
import io.dobby.core.sock.SockContext
import io.dobby.core.sock.SockResult
import io.dobby.core.sock.SockStatus
import io.dobby.core.sock.pattern
import io.dobby.core.sock.patterns
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

/**
 * Clock — kitchen timers and time of day.
 *
 * The first product Sock, and still the cheapest proof that the registry, the palette, the
 * dispatcher and the shared-command chain compose with something real: no network, no account,
 * no SDK. Specified in `socks.specs/clock.specs.md`.
 *
 * Everything the device provides sits behind an interface — [TimerAlarm] for `AlarmManager`,
 * [ChimePlayer] for `SoundPool`, and `SockContext` for screen, audio focus, config and speech —
 * so the whole timer lifecycle is tested on a plain JVM with a virtual clock.
 *
 * @param clock injected so tests are deterministic; on the device this stays the default.
 */
class ClockSock(
    private val clock: Clock = Clock.systemDefaultZone(),
    private val alarm: TimerAlarm = TimerAlarm.None,
    chime: ChimePlayer = ChimePlayer.SILENT,
    private val screenWakeSeconds: Int = DEFAULT_SCREEN_WAKE_SECONDS,
) : Sock {

    override val id: String = "clock"

    override val displayName: String = "Uhr"

    /**
     * Held from [onStart] so [handle] can reach the screen, the audio focus and the scope.
     *
     * Not passed into `handle` by design: a Sock's context is service-lifetime, while an
     * invocation is a single utterance.
     */
    private var context: SockContext? = null

    private val timers = TimerEngine(clock, alarm, chime, id, screenWakeSeconds)

    private val _status = MutableStateFlow<SockStatus>(SockStatus.Ready)

    /** Never `Unavailable`: a clock with no permissions is still a clock (§12). */
    override val status: StateFlow<SockStatus> = _status.asStateFlow()

    private val _state = MutableStateFlow(ClockState(LocalDateTime.now(clock)))

    /** What the wall panel draws (§9). Also the timer state the chain reads. */
    val state: StateFlow<ClockState> = _state.asStateFlow()

    private var ticker: Job? = null
    private var mirror: Job? = null

    /**
     * The questions this Sock has open, by follow-up token.
     *
     * This is the whole of "the Sock holding partial state": there is no waiting instance and
     * nothing blocks, because `handle()` runs under core's timeout and returning
     * [SockResult.Asked] is how a Sock waits. Concurrent by type rather than by need — core
     * dispatches one utterance at a time, and a map that is wrong under a race would be a very
     * quiet bug.
     */
    private val asked = ConcurrentHashMap<String, Pending>()

    private val askCounter = AtomicLong()

    /** Half-built state behind an open question. */
    private sealed interface Pending {
        /** An amount was spoken without a unit — and possibly a name with it (§3). */
        data class MissingUnit(val amount: Int, val name: String?) : Pending

        /**
         * Several timers are running and "brich den Timer ab" did not say which (§4).
         *
         * Carries nothing: the answer is a name, and a name is resolved against the timers that
         * are running when it arrives, not against the ones that were running when Dobby asked.
         */
        data object WhichTimer : Pending
    }

    override val commands: List<ExclusiveCommandSpec> = listOf(
        ExclusiveCommandSpec(
            id = SET_TIMER,
            params = listOf(
                ParamSpec("amount", ParamType.Integer),
                // Optional, because "stell einen timer auf zehn" is a sentence German people
                // really say. A missing unit is not a parse failure, it is a question (§3).
                ParamSpec("unit", ParamType.Enumeration(TimerUnit.SPOKEN), required = false),
                // Optional, and the only slot here that is free text. See the template comment.
                ParamSpec("name", ParamType.Text, required = false),
            ),
            templates = patterns(
                // No "(mir)?" anywhere below: "mir" is filler and the matcher skips it (M6c).
                // "(einen|nen)?" stays — it is what keeps the commonest phrasing of all,
                // "stell einen timer auf 5 minuten", on the strict first pass.
                "($SET_VERBS) (einen|nen)? timer (auf|für)? {amount:int} {unit:enum}",
                "(stell|stelle|setz|setze) (einen|nen)? wecker (auf|für)? {amount:int} {unit:enum}",
                "(erinner|erinnere) mich in {amount:int} {unit:enum}",
                "timer (auf|für)? {amount:int} {unit:enum}",
                "{amount:int} {unit:enum} timer",
                // Named. The preposition is *not* optional here, and that is the whole safety
                // argument: a `{name}` slot takes whatever tokens sit in it, so it needs a
                // keyword on both sides or it swallows the rest of the sentence. With "auf"
                // required, "timer auf 5 minuten" cannot match — "auf" would have to be the
                // name and then there is no preposition left for the template.
                //
                // These sort *after* the unnamed forms above regardless of where they are
                // written, because a template with a text slot is less specific than one
                // without (`Specificity.ORDER`). An unnamed phrasing therefore never reaches
                // them, which is what keeps `name = "auf"` impossible rather than merely
                // unlikely.
                "($SET_VERBS) (einen|nen)? timer {name} (auf|für) {amount:int} {unit:enum}",
                "timer {name} (auf|für) {amount:int} {unit:enum}",
                // Unit-less forms, last: they are strictly less specific than the ones above,
                // and the palette only reaches them once no fuller phrasing fits.
                "($SET_VERBS) (einen|nen)? timer (auf|für)? {amount:int}",
                "(stell|stelle|setz|setze) (einen|nen)? wecker (auf|für)? {amount:int}",
                "timer (auf|für)? {amount:int}",
                "($SET_VERBS) (einen|nen)? timer {name} (auf|für) {amount:int}",
                "timer {name} (auf|für) {amount:int}",
            ),
            description = "Stellt einen Timer für eine bestimmte Dauer, auf Wunsch unter einem Namen.",
            help = CommandHelp(
                title = "Timer stellen",
                detail = "Stellt einen Küchentimer. Mehrere gleichzeitig sind erlaubt — dann " +
                    "lohnt sich ein Name, damit du den richtigen wieder abbrechen kannst.",
                hints = listOf(
                    "Ohne Einheit frage ich nach: Sekunden, Minuten oder Stunden?",
                    "Der Name steht vor der Dauer: „stell einen Timer Nudeln auf 10 Minuten“.",
                ),
                aliases = listOf("timer", "wecker", "timer stellen", "wecker stellen"),
            ),
            examples = listOf(
                // The normalizer has already turned "zehn" into "10" before templates run.
                Example("timer zehn minuten", mapOf("amount" to 10, "unit" to "minuten")),
                Example("stell einen timer auf 5 minuten", mapOf("amount" to 5, "unit" to "minuten")),
                Example("stelle mir einen timer für 90 sekunden", mapOf("amount" to 90, "unit" to "sekunden")),
                Example("3 minuten timer", mapOf("amount" to 3, "unit" to "minuten")),
                Example("timer eine minute", mapOf("amount" to 1, "unit" to "minuten")),
                Example("setz einen wecker auf 2 stunden", mapOf("amount" to 2, "unit" to "stunden")),
                Example("erinner mich in 20 minuten", mapOf("amount" to 20, "unit" to "minuten")),
                // Named, in both the verb form and the bare one.
                Example(
                    "neuer timer nudeln auf 10 minuten",
                    mapOf("amount" to 10, "unit" to "minuten", "name" to "nudeln"),
                ),
                Example(
                    "erstelle timer wäsche auf 45 minuten",
                    mapOf("amount" to 45, "unit" to "minuten", "name" to "wäsche"),
                ),
                Example(
                    "stell einen timer tee auf 3 minuten",
                    mapOf("amount" to 3, "unit" to "minuten", "name" to "tee"),
                ),
                Example("timer nudeln auf 10 minuten", mapOf("amount" to 10, "unit" to "minuten", "name" to "nudeln")),
                // No unit: matched, then asked about. Before this template the utterance
                // resolved to nothing at all, which is the one answer that helps nobody.
                Example("stell einen timer auf zehn", mapOf("amount" to 10)),
                Example("timer 5", mapOf("amount" to 5)),
                Example("neuer timer nudeln auf 10", mapOf("amount" to 10, "name" to "nudeln")),
                // Paraphrases Tier 1 is meant to miss — few-shots for the LLM tier (M6).
                //
                // Params are filled, unlike a Tier 1 example where they are optional: these are
                // rendered into the system prompt as worked examples, and a few-shot that shows
                // the model an empty params object teaches it to emit one.
                //
                // Written in *normalized* German — "20", not "zwanzig". Tier 2 is handed the
                // same string Tier 1 saw, which the normalizer has already been through, so a
                // few-shot in raw German teaches a surface form the model will never be shown.
                Example(
                    "gib mir in einer viertelstunde bescheid",
                    mapOf("amount" to 15, "unit" to "minuten"),
                    matchedByTemplates = false,
                ),
                Example(
                    "weck mich in 20 minuten",
                    mapOf("amount" to 20, "unit" to "minuten"),
                    matchedByTemplates = false,
                ),
                // Third, and deliberately so: the route prompt carries the first two paraphrases
                // per command and the fill turn the first one (`PromptGenerator`), so this
                // teaches nothing today and displaces nothing either. It is here because it is
                // the sentence a named timer is really asked for in, and because moving it up
                // one line is the whole lever the day the fallthrough log says Tier 2 is missing
                // names. Until then a named timer reaches Tier 2 through the param list in the
                // fill turn, the way every other optional param does.
                Example(
                    "die nudeln brauchen 10 minuten",
                    mapOf("amount" to 10, "unit" to "minuten", "name" to "nudeln"),
                    matchedByTemplates = false,
                ),
                // Tier 1 reaches this one, and the slot captures "für die nudeln" — the
                // template's anchor is the *second* preposition. `TimerNames.clean` strips the
                // scaffolding off the front, so the timer is called "Nudeln"; the example
                // asserts the raw capture, which is what the palette produces.
                Example(
                    "stell einen timer für die nudeln auf 10 minuten",
                    mapOf("amount" to 10, "unit" to "minuten", "name" to "für die nudeln"),
                ),
                // Held out: the model never sees these, and the device accuracy test measures
                // it against them. Without them the test measures whether Qwen can repeat the
                // two sentences its own prompt just handed it.
                Example("sag mir in 10 minuten bescheid", mapOf("amount" to 10, "unit" to "minuten"), heldOut = true),
                Example("in einer halben stunde bitte klingeln", mapOf("amount" to 30, "unit" to "minuten"), heldOut = true),
                Example("ich will in 5 minuten dran erinnert werden", mapOf("amount" to 5, "unit" to "minuten"), heldOut = true),
                Example(
                    "der tee braucht 3 minuten",
                    mapOf("amount" to 3, "unit" to "minuten", "name" to "tee"),
                    heldOut = true,
                ),
            ),
        ),
        ExclusiveCommandSpec(
            id = CANCEL_TIMER,
            params = listOf(ParamSpec("name", ParamType.Text, required = false)),
            templates = patterns(
                "timer (stopp|stop|aus|$CANCEL_VERBS)",
                "($CANCEL_PREFIX) (den)? timer (ab)?",
                "(stopp|stoppe|aus mit) (dem|den|das)? (alarm|wecker|klingeln)",
                // Named. Two shapes, and the difference between them is not cosmetic.
                //
                // The trailing form drops "stopp", "stop" and "aus" from the verb list its
                // unnamed sibling above carries. Those three are one word each, and a one-word
                // verb behind a `{name}` slot means "timer doch bitte stopp" matches with the
                // name "doch bitte" — on the *strict* pass, before filler skipping ever gets
                // its say. The long verbs cannot be reached that way by anything anybody says.
                //
                // The leading form has no such escape — "stopp den timer bitte" really does
                // arrive here with the name "bitte" — which is what `TimerNames.clean` is for:
                // a name made of nothing but filler is not a name, and the command falls back
                // to meaning "the timer".
                "timer {name} ($CANCEL_VERBS)",
                "($CANCEL_PREFIX) (den)? timer {name} (ab)?",
            ),
            description = "Bricht einen laufenden Timer ab, auf Wunsch den mit einem bestimmten Namen.",
            help = CommandHelp(
                title = "Timer abbrechen",
                detail = "Bricht einen laufenden Timer ab — und bringt einen zum Schweigen, der " +
                    "gerade klingelt.",
                hints = listOf("Laufen mehrere und du sagst keinen Namen, frage ich nach welchem."),
                aliases = listOf("timer abbrechen", "timer stoppen", "wecker abbrechen"),
            ),
            examples = listOf(
                Example("timer stopp"),
                Example("timer abbrechen"),
                Example("stopp den timer"),
                Example("brich den timer ab"),
                Example("stopp den alarm"),
                Example("brich timer nudeln ab", mapOf("name" to "nudeln")),
                Example("timer nudeln abbrechen", mapOf("name" to "nudeln")),
                Example("stopp den timer nudeln", mapOf("name" to "nudeln")),
                Example("brich timer 2 ab", mapOf("name" to "2")),
                Example("der timer kann weg", heldOut = true),
                Example("ich brauche den timer doch nicht mehr", heldOut = true),
                Example("mach das gebimmel weg", heldOut = true),
                Example("die nudeln sind fertig nimm den timer raus", mapOf("name" to "nudeln"), heldOut = true),
            ),
        ),
        ExclusiveCommandSpec(
            id = CANCEL_ALL_TIMERS,
            templates = patterns(
                "($CANCEL_PREFIX) (alle|sämtliche) (timer|wecker) (ab)?",
                "(alle|sämtliche) (timer|wecker) ($CANCEL_VERBS|stopp|stop|aus|weg)",
            ),
            description = "Bricht alle laufenden Timer ab.",
            help = CommandHelp(
                title = "Alle Timer abbrechen",
                detail = "Räumt auf einen Schlag ab: jeder laufende Timer ist danach weg.",
                aliases = listOf("alle timer abbrechen", "alle timer"),
            ),
            examples = listOf(
                Example("brich alle timer ab"),
                Example("alle timer abbrechen"),
                Example("stopp alle timer"),
                Example("alle timer löschen"),
                Example("lösch alle wecker"),
                Example("ich will keinen einzigen timer mehr", matchedByTemplates = false),
                Example("mach mal überall die timer aus", heldOut = true),
            ),
        ),
        ExclusiveCommandSpec(
            id = TIMER_REMAINING,
            params = listOf(ParamSpec("name", ParamType.Text, required = false)),
            templates = patterns(
                // "noch" anchors the named form on its right, the way "auf" anchors the named
                // set_timer. It is safe to lean on: repetition and sequence words are the one
                // group that may never be filler (`socks.specs/README.md` §6), so "noch" is
                // always in the transcript when it was said.
                "wie lange ($RUNS_SINGULAR) (der|die)? timer noch",
                "wie lange (gehen|laufen) (die)? timer noch",
                "wie lange ($RUNS_SINGULAR) (der)? timer {name} noch",
                "wie viel zeit (bleibt|ist) noch",
                "wie lange noch",
            ),
            description = "Sagt, wie lange ein Timer oder alle Timer noch laufen.",
            help = CommandHelp(
                title = "Restzeit",
                detail = "Sagt, wie lange es noch dauert. Ohne Namen antworte ich über alle " +
                    "laufenden Timer.",
                aliases = listOf("restzeit", "wie lange noch", "verbleibende zeit"),
            ),
            examples = listOf(
                Example("wie lange geht der timer noch"),
                Example("wie lange läuft der timer noch"),
                Example("wie lange gehen die timer noch"),
                Example("wie lange noch"),
                Example("wie viel zeit bleibt noch"),
                Example("wie lange geht timer nudeln noch", mapOf("name" to "nudeln")),
                Example("wie lange läuft timer 2 noch", mapOf("name" to "2")),
                Example("was ist mit meinem timer", matchedByTemplates = false),
                Example("sind die nudeln bald fertig", mapOf("name" to "nudeln"), heldOut = true),
                Example("wann klingelt es denn", heldOut = true),
            ),
        ),
        ExclusiveCommandSpec(
            id = WHATS_THE_TIME,
            templates = patterns(
                // Three German templates, where there were seven. The matcher skips filler
                // (M6c), so "ist", "es", "jetzt", "mir" and "denn" no longer need a template
                // each: "wie spät" covers "wie spät ist es", "wie spät ist es denn jetzt" and
                // the "wie spät ist das" the recogniser keeps producing. Enumerating the
                // variants was the thing that did not scale — every command would have needed
                // the same cross-product (`socks.specs/README.md` §6).
                "wie (spät|viel uhr)",
                // "zeit" appears only with keywords around it, never on its own. Bare, it is
                // 4 chars and therefore fuzzed at tolerance 1, which would hand this command
                // "seit", "weit" and "zeig" — all words somebody says in a kitchen without
                // addressing the panel (`socks.specs/README.md` §6). "(die)?" stays: it keeps
                // "was ist die zeit" on the strict first pass, where the common phrasings belong.
                "(sag|was ist) (die)? (uhrzeit|zeit)",
                // "die" is spelled out rather than skipped: a one-word template has no anchor,
                // so nothing is skipped in front of it (`socks.specs/README.md` §6).
                "(uhrzeit|die uhrzeit)",
                // English, because Parakeet is multilingual and the tokens arrive intact
                // (`dobby-plan.md` §4). Safe here and nowhere with an int or enum slot: the
                // normalizer is German-only, so "ten" would never become 10. Dobby still
                // answers in German — the panel has one voice, whoever addressed it.
                //
                // The normalizer keeps apostrophes (they are inside its KEEP class), so
                // "what's" survives as one token and is spelled out rather than relied upon to
                // fuzz into "whats" — the same reason `ich hab's gehört` lists both forms.
                "what time is it",
                "(whats|what's|what is) the time (now)?",
            ),
            description = "Sagt die aktuelle Uhrzeit.",
            help = CommandHelp(
                title = "Uhrzeit",
                detail = "Sagt, wie spät es ist.",
                aliases = listOf("uhrzeit", "zeit", "wie spät ist es"),
            ),
            examples = listOf(
                Example("wie spät ist es"),
                Example("wie viel uhr ist es"),
                Example("wie spät"),
                Example("uhrzeit"),
                Example("sag mir die uhrzeit"),
                Example("was ist die zeit"),
                Example("sag mir die zeit"),
                Example("whats the time"),
                Example("what's the time"),
                Example("whats the time now"),
                Example("what time is it"),
                // Paraphrases Tier 1 is meant to miss — few-shots for the LLM tier (M6).
                Example("kannst du mir sagen wie spät es ist", matchedByTemplates = false),
                Example("hast du die genaue zeit", matchedByTemplates = false),
                // Held out until M6c, when the matcher learned to skip "denn", "bitte" and
                // "gerade" — this is "was ist die uhrzeit" with three particles in it, Tier 1
                // reaches it, and a held-out case Tier 1 can reach measures the template
                // matcher rather than the model. Promoted, and replaced below so the accuracy
                // suite keeps three cases for this command.
                Example("was ist denn bitte gerade die uhrzeit"),
                Example("wie viel uhr haben wir gerade", heldOut = true),
                Example("weißt du zufällig wie spät wir haben", heldOut = true),
                Example("ich wollte nur wissen wie spät es ist", heldOut = true),
            ),
        ),
        ExclusiveCommandSpec(
            id = WHATS_THE_DATE,
            templates = patterns(
                // Content words only, as everywhere in this Sock: "welcher tag ist heute" and
                // "welcher tag ist denn heute" are this template plus filler, and the matcher
                // skips filler on the second pass (`socks.specs/README.md` §6).
                //
                // One "welcher" covers the whole declension. At 7 characters it tolerates one
                // edit, so "welchen", "welches" and "welche" are the same keyword — writing
                // them out would be the cross-product the filler pass exists to avoid.
                //
                // "(haben wir)?" is not filler tolerance: neither word is on the list, and
                // "welchen tag haben wir heute" is the phrasing half of Austria uses.
                "welcher (tag|wochentag) (haben wir)? (heute)?",
                // "der wievielte ist heute". The recogniser writes the word either way, so
                // both spellings are keywords; "wievielte" at 9 characters tolerates two
                // edits and covers "wievielten" and "wievielter" with it.
                "(wievielte|wie vielte) heute",
                "(wievielte|wie vielte) haben wir (heute)?",
                "welches datum (haben wir)? (heute)?",
                // Mirrors the time's "(sag|was ist) (die)? (uhrzeit|zeit)", down to spelling
                // out the article: "datum" is 5 characters and would be fuzzed at tolerance 1
                // as a bare template, and nothing is skipped in front of a one-word template
                // anyway (`socks.specs/README.md` §6).
                "(sag|was ist) (das)? datum",
                // "was ist heute für ein tag", "was haben wir heute für ein datum". "heute"
                // is required here and that is the point: without it this is "was für ein
                // lied ist das" territory, which belongs to Spotify.
                "was (haben wir)? heute für (ein)? (tag|datum)",
                // The other word order, which needs "heute" at the end rather than in the
                // middle: "was für ein tag ist heute".
                "was für (ein)? (tag|datum) heute",
                // English, by the same rule that admits it on `whats_the_time` (§8): no int
                // and no enum slot, so the German-only normalizer cannot silently drop a
                // value on the floor. The answer is still the voice's language, not the
                // question's.
                "what day is it (today|now)?",
                "(whats|what's|what is) the date (today|now)?",
                "(whats|what's|what is) (todays|today's) date",
            ),
            description = "Sagt, welcher Tag heute ist.",
            help = CommandHelp(
                title = "Datum",
                detail = "Sagt, welcher Wochentag heute ist und der wievielte.",
                hints = listOf("Ohne Jahr — Wochentag und Datum sind, wonach gefragt wird."),
                aliases = listOf("datum", "tag", "welcher tag ist heute", "wochentag"),
            ),
            examples = listOf(
                Example("welcher tag ist heute"),
                Example("welchen tag haben wir heute"),
                Example("welcher wochentag ist heute"),
                Example("der wievielte ist heute"),
                Example("den wievielten haben wir heute"),
                Example("welches datum haben wir heute"),
                Example("sag mir das datum"),
                Example("was ist heute für ein tag"),
                Example("was für ein tag ist heute"),
                Example("what day is it today"),
                Example("what's the date"),
                // Paraphrases Tier 1 is meant to miss — few-shots for the LLM tier (M6).
                Example("kannst du mir sagen was heute für ein datum ist", matchedByTemplates = false),
                Example("weißt du welcher tag gerade ist", matchedByTemplates = false),
                Example("ich hab total den überblick verloren welcher tag ist", heldOut = true),
                Example("sag mal was steht heute im kalender für ein datum", heldOut = true),
            ),
        ),
        ExclusiveCommandSpec(
            id = WEEKDAY_OF_DATE,
            params = listOf(
                ParamSpec("day", ParamType.Integer),
                // Optional, and the difference between "der 14." and "der 14. Juli" is a year,
                // not a month: without one the answer is the next 14th there is (§8b).
                ParamSpec("month", ParamType.Enumeration(MonthName.SPOKEN), required = false),
            ),
            templates = patterns(
                // The month-bearing forms first. Ordering is belt and braces here — a
                // month-less template cannot match "welcher tag ist der 14 juli" anyway,
                // because "juli" would be left over at the end anchor and is not filler — but
                // written this way the list reads in the order somebody would guess it.
                "welcher (tag|wochentag) (ist|haben wir)? ($ON_THE) {day:int} {month:enum}",
                // "auf welchen Tag fällt der 24. Dezember" — the phrasing a calendar question
                // is really asked in. "welcher" covers "welchen" at tolerance 1, so the
                // declension is not written out (`socks.specs/README.md` §6).
                "(auf)? welcher (tag|wochentag) fällt ($ON_THE) {day:int} {month:enum}",
                "was für (ein)? (tag|wochentag) (ist)? ($ON_THE) {day:int} {month:enum}",
                "was ist ($ON_THE) {day:int} {month:enum} für (ein)? (tag|wochentag)",
                "welcher (tag|wochentag) (ist|haben wir)? ($ON_THE) {day:int}",
                "(auf)? welcher (tag|wochentag) fällt ($ON_THE) {day:int}",
                "was für (ein)? (tag|wochentag) (ist)? ($ON_THE) {day:int}",
                "was ist ($ON_THE) {day:int} für (ein)? (tag|wochentag)",
                // No English, and this is the rule rather than an omission: every template
                // here feeds an `{x:int}` and an `{x:enum}` slot, the normalizer is German-only
                // and would never turn "fourteenth" into 14, and `MonthName` holds the German
                // month names. "what day is the 14th" would match nothing, or worse, match and
                // then fail to coerce (`socks.specs/README.md` §6). §8 and §8a can have English
                // because they have no slots at all.
            ),
            description = "Sagt, auf welchen Wochentag ein bestimmtes Datum fällt.",
            help = CommandHelp(
                title = "Wochentag eines Datums",
                detail = "Sagt, welcher Wochentag ein Datum ist — mit Monat oder ohne.",
                hints = listOf(
                    "Ohne Monat meine ich den nächsten, der dieses Datum hat.",
                    "„Auf welchen Tag fällt der 24. Dezember“ geht auch.",
                ),
                aliases = listOf("wochentag", "welcher tag ist der", "datum wochentag"),
            ),
            examples = listOf(
                Example("welcher tag ist der 26.", mapOf("day" to 26)),
                Example("welcher wochentag ist der 24. dezember", mapOf("day" to 24, "month" to "dezember")),
                Example("auf welchen tag fällt der 1. mai", mapOf("day" to 1, "month" to "mai")),
                Example("was für ein tag ist der 31. oktober", mapOf("day" to 31, "month" to "oktober")),
                Example("welchen tag haben wir am 14.", mapOf("day" to 14)),
                Example("was ist der 24. dezember für ein tag", mapOf("day" to 24, "month" to "dezember")),
                // The ordinal spelled out, which is how somebody says it and how Parakeet
                // writes it back. `GermanNumbers.parseOrdinal` is reached from the int slot and
                // from nowhere else, so "der Vierzehnte" is a number here and a word elsewhere.
                Example("welcher tag ist der vierzehnte", mapOf("day" to 14)),
                Example("auf welchen wochentag fällt der dritte oktober", mapOf("day" to 3, "month" to "oktober")),
                // Paraphrases Tier 1 is meant to miss — few-shots for the LLM tier (M6).
                Example(
                    "sag mal ist heiligabend heuer ein wochentag",
                    mapOf("day" to 24, "month" to "dezember"),
                    matchedByTemplates = false,
                ),
                Example(
                    "mein geburtstag ist am 8. märz auf welchen tag fällt der",
                    mapOf("day" to 8, "month" to "märz"),
                    matchedByTemplates = false,
                ),
                Example("ich will wissen ob der 26. ein wochenende ist", mapOf("day" to 26), heldOut = true),
                Example("weißt du zufällig auf welchen tag der 20. fällt", mapOf("day" to 20), heldOut = true),
            ),
        ),
        ExclusiveCommandSpec(
            id = DATE_OF_WEEKDAY,
            params = listOf(ParamSpec("weekday", ParamType.Enumeration(Weekday.SPOKEN))),
            templates = patterns(
                // "nächste" at 7 characters tolerates one edit and therefore covers
                // "nächsten", "nächster" and "nächstes"; "kommende" at 8 tolerates two. All
                // three alternatives mean the same thing to this command — the next one there
                // is — so none of them is a param, and §8c says why that is not a shortcut.
                "wann (ist|haben wir)? ($ON_THE)? ($UPCOMING)? {weekday:enum}",
                "welches datum (ist|hat|haben wir)? ($ON_THE)? ($UPCOMING)? {weekday:enum}",
                // The user's own phrasing: "welcher Tag ist der nächste Samstag" asks for a
                // date, not for a weekday, and the enum slot is what tells it apart from §8b.
                "welcher (tag|wochentag) (ist|haben wir)? ($ON_THE)? ($UPCOMING)? {weekday:enum}",
                "($ON_THE)? ($HOW_MANYETH) (ist|haben wir)? ($ON_THE)? ($UPCOMING)? {weekday:enum}",
                "(auf)? welches datum fällt ($ON_THE)? ($UPCOMING)? {weekday:enum}",
                // No English, for the reason spelled out on `weekday_of_date`: `Weekday` holds
                // the German names, so "saturday" would reach no candidate.
            ),
            description = "Sagt das Datum des nächsten genannten Wochentags.",
            help = CommandHelp(
                title = "Datum eines Wochentags",
                detail = "Sagt, den wievielten der nächste Montag, Samstag oder Sonntag hat.",
                hints = listOf("Ist der genannte Wochentag heute, sage ich „heute“ dazu."),
                aliases = listOf("wann ist samstag", "datum wochentag", "nächster samstag"),
            ),
            examples = listOf(
                Example("wann ist der nächste samstag", mapOf("weekday" to "samstag")),
                Example("wann ist samstag", mapOf("weekday" to "samstag")),
                Example("wann ist kommenden mittwoch", mapOf("weekday" to "mittwoch")),
                Example("welches datum ist am freitag", mapOf("weekday" to "freitag")),
                Example("welches datum haben wir am donnerstag", mapOf("weekday" to "donnerstag")),
                Example("welcher tag ist der nächste samstag", mapOf("weekday" to "samstag")),
                Example("der wievielte ist am sonntag", mapOf("weekday" to "sonntag")),
                Example("auf welches datum fällt der nächste dienstag", mapOf("weekday" to "dienstag")),
                // Paraphrases Tier 1 is meant to miss — few-shots for the LLM tier (M6).
                Example(
                    "sag mir bitte den termin vom kommenden samstag",
                    mapOf("weekday" to "samstag"),
                    matchedByTemplates = false,
                ),
                Example(
                    "ich brauch das datum für den montag drauf",
                    mapOf("weekday" to "montag"),
                    matchedByTemplates = false,
                ),
                Example(
                    "ich hab vergessen welches datum der samstag hat",
                    mapOf("weekday" to "samstag"),
                    heldOut = true,
                ),
                Example("wann genau fällt der nächste freitag", mapOf("weekday" to "freitag"), heldOut = true),
            ),
        ),
        ExclusiveCommandSpec(
            id = DATE_IN,
            params = listOf(
                ParamSpec("amount", ParamType.Integer),
                ParamSpec("unit", ParamType.Enumeration(SpanUnit.SPOKEN)),
            ),
            templates = listOf(
                // "morgen" and "übermorgen" are the same question with the amount spoken as a
                // word rather than a number, so they are static params rather than a second
                // command (`socks.specs/README.md` §6). They sit first because they are the
                // shorter sentences and the ones somebody actually says.
                pattern("welcher (tag|wochentag) (ist|haben wir)? morgen", "amount" to 1, "unit" to "tage"),
                pattern("welcher (tag|wochentag) (ist|haben wir)? übermorgen", "amount" to 2, "unit" to "tage"),
                pattern("($ON_THE)? ($HOW_MANYETH) (ist|haben wir)? morgen", "amount" to 1, "unit" to "tage"),
                pattern("($ON_THE)? ($HOW_MANYETH) (ist|haben wir)? übermorgen", "amount" to 2, "unit" to "tage"),
                pattern("welches datum (ist|hat|haben wir)? morgen", "amount" to 1, "unit" to "tage"),
                pattern("welches datum (ist|hat|haben wir)? übermorgen", "amount" to 2, "unit" to "tage"),
                pattern("was für (ein)? (tag|datum) (ist)? morgen", "amount" to 1, "unit" to "tage"),
                pattern("was (ist|haben wir)? morgen für (ein)? (tag|datum)", "amount" to 1, "unit" to "tage"),
            ) + patterns(
                // "in" is a keyword and the number sits right behind it, which is the one
                // ordering constraint here: filler is skipped in front of a keyword and never
                // in front of a slot, so the preposition has to be spelled out.
                "($ON_THE)? ($HOW_MANYETH) (ist|haben wir)? (heute)? in {amount:int} {unit:enum}",
                "welches datum (ist|hat|haben wir)? (heute)? in {amount:int} {unit:enum}",
                "welcher (tag|wochentag) (ist|haben wir)? (heute)? in {amount:int} {unit:enum}",
                "was (ist|haben wir)? (heute)? in {amount:int} {unit:enum} für (ein)? (tag|datum)",
                "was für (ein)? (tag|datum) (ist)? (heute)? in {amount:int} {unit:enum}",
            ),
            description = "Sagt das Datum, das eine bestimmte Zahl von Tagen, Wochen, Monaten oder Jahren in der Zukunft liegt.",
            help = CommandHelp(
                title = "Datum in der Zukunft",
                detail = "Rechnet vom heutigen Tag aus vorwärts und sagt Wochentag und Datum.",
                hints = listOf(
                    "Tage, Wochen, Monate und Jahre — „in 3 Wochen“, „in einem Monat“.",
                    "„Welcher Tag ist morgen“ und „übermorgen“ gehen auch.",
                ),
                aliases = listOf("datum in", "welcher tag ist morgen", "in drei wochen"),
            ),
            examples = listOf(
                Example("der wievielte ist heute in 3 wochen", mapOf("amount" to 3, "unit" to "wochen")),
                Example("der wievielte ist in einer woche", mapOf("amount" to 1, "unit" to "wochen")),
                Example("welches datum haben wir in 2 monaten", mapOf("amount" to 2, "unit" to "monate")),
                Example("welcher tag ist in 10 tagen", mapOf("amount" to 10, "unit" to "tage")),
                Example("was ist in 10 tagen für ein datum", mapOf("amount" to 10, "unit" to "tage")),
                Example("welches datum ist in einem jahr", mapOf("amount" to 1, "unit" to "jahre")),
                Example("welcher tag ist morgen", mapOf("amount" to 1, "unit" to "tage")),
                Example("welcher tag ist übermorgen", mapOf("amount" to 2, "unit" to "tage")),
                Example("welches datum haben wir morgen", mapOf("amount" to 1, "unit" to "tage")),
                Example("der wievielte ist morgen", mapOf("amount" to 1, "unit" to "tage")),
                // Paraphrases Tier 1 is meant to miss — few-shots for the LLM tier (M6).
                Example(
                    "was ist das datum heute in 2 wochen und 3 tagen",
                    mapOf("amount" to 17, "unit" to "tage"),
                    matchedByTemplates = false,
                ),
                Example(
                    "der urlaub geht in 6 wochen los welches datum ist das",
                    mapOf("amount" to 6, "unit" to "wochen"),
                    matchedByTemplates = false,
                ),
                Example("sag mal was für ein datum ist in 100 tagen", mapOf("amount" to 100, "unit" to "tage"), heldOut = true),
                Example("wenn ich in einem halben jahr wieder frage welcher tag ist dann", mapOf("amount" to 6, "unit" to "monate"), heldOut = true),
            ),
        ),
        ExclusiveCommandSpec(
            id = TIME_UNTIL,
            params = listOf(
                // Defaulted rather than required: "wie lange noch bis Samstag" names no unit
                // and means days, which is the only unit a two-word answer could be in.
                ParamSpec("unit", ParamType.Enumeration(SpanUnit.SPOKEN), required = false, default = "tage"),
                ParamSpec("day", ParamType.Integer, required = false),
                ParamSpec("month", ParamType.Enumeration(MonthName.SPOKEN), required = false),
                ParamSpec("weekday", ParamType.Enumeration(Weekday.SPOKEN), required = false),
            ),
            templates = patterns(
                "$HOW_MANY {unit:enum} (sind|ist)? (es)? (noch)? $UNTIL ($ON_THE)? {day:int} {month:enum}",
                "$HOW_MANY {unit:enum} (sind|ist)? (es)? (noch)? $UNTIL ($ON_THE)? {day:int}",
                "$HOW_MANY {unit:enum} (sind|ist)? (es)? (noch)? $UNTIL ($UPCOMING)? {weekday:enum}",
                // "wie lange" with no unit. The `bis` anchor is what keeps this off
                // `timer_remaining`'s "wie lange noch": an utterance that ends there has
                // nothing left over, and one that goes on to "bis Samstag" has two tokens the
                // end anchor will not accept as filler.
                "wie lange (ist|dauert|geht)? (es)? (noch)? $UNTIL ($ON_THE)? {day:int} {month:enum}",
                "wie lange (ist|dauert|geht)? (es)? (noch)? $UNTIL ($ON_THE)? {day:int}",
                "wie lange (ist|dauert|geht)? (es)? (noch)? $UNTIL ($UPCOMING)? {weekday:enum}",
            ),
            description = "Zählt, wie viele Tage, Wochen oder Monate es noch bis zu einem Datum oder Wochentag sind.",
            help = CommandHelp(
                title = "Countdown bis zu einem Datum",
                detail = "Zählt von heute bis zu einem Datum oder dem nächsten genannten Wochentag.",
                hints = listOf(
                    "Ohne Einheit zähle ich in Tagen.",
                    "Wochen, Monate und Jahre bekommen den Rest dazu: „13 Wochen und 2 Tage“.",
                ),
                aliases = listOf("countdown", "wie viele tage bis", "wie lange bis"),
            ),
            examples = listOf(
                Example(
                    "wie viele tage sind es noch bis zum 24. dezember",
                    mapOf("unit" to "tage", "day" to 24, "month" to "dezember"),
                ),
                Example("wie viele wochen bis zum 1. mai", mapOf("unit" to "wochen", "day" to 1, "month" to "mai")),
                Example("wie viele tage sind es bis samstag", mapOf("unit" to "tage", "weekday" to "samstag")),
                Example("wie viele tage noch bis zum 31.", mapOf("unit" to "tage", "day" to 31)),
                Example("wie viele monate sind es bis zum 1. januar", mapOf("unit" to "monate", "day" to 1, "month" to "januar")),
                Example(
                    "wie lange ist es noch bis zum 24. dezember",
                    mapOf("unit" to "tage", "day" to 24, "month" to "dezember"),
                ),
                Example("wie lange noch bis freitag", mapOf("unit" to "tage", "weekday" to "freitag")),
                Example("wie lange dauert es bis zum 1. mai", mapOf("unit" to "tage", "day" to 1, "month" to "mai")),
                // Paraphrases Tier 1 is meant to miss — few-shots for the LLM tier (M6).
                Example(
                    "wie viel zeit bleibt mir noch bis weihnachten",
                    mapOf("unit" to "tage", "day" to 24, "month" to "dezember"),
                    matchedByTemplates = false,
                ),
                Example(
                    "sind es noch viele tage bis zum ersten mai",
                    mapOf("unit" to "tage", "day" to 1, "month" to "mai"),
                    matchedByTemplates = false,
                ),
                Example("ich zähle die tage bis zum 15. august wie viele sind es", mapOf("unit" to "tage", "day" to 15, "month" to "august"), heldOut = true),
                Example("in wie vielen wochen ist eigentlich der 3. oktober", mapOf("unit" to "wochen", "day" to 3, "month" to "oktober"), heldOut = true),
            ),
        ),
    )

    /**
     * Priority 100, the highest in the catalog: a ringing alarm is the most salient thing in the
     * room, so "stopp" means *that* first — and only while it is actually ringing (§7).
     */
    override val shared: List<SharedSubscription> = listOf(
        SharedSubscription(
            SharedCommands.STOP,
            priority = STOP_PRIORITY,
            // Only meaningful while something is ringing, which is exactly what the chain tests.
            extraTemplates = patterns("(ich hab's gehört|ich habs gehört)", "(ja ja|ist gut)"),
            extraExamples = listOf(Example("ich hab's gehört"), Example("ja ja")),
        ),
    )

    override suspend fun onStart(ctx: SockContext) {
        context = ctx
        refreshStatus()
        // Timers whose process was killed are picked up here — the coroutine countdowns live in
        // memory, and a kitchen timer that silently does not go off is the one failure that
        // matters (§12).
        timers.restore(ctx)
        mirror = ctx.scope.launch {
            timers.running.collect { running -> _state.update { it.copy(timers = running) } }
        }
        ticker = ctx.scope.launch { tick(ClockConfig(ctx.config)) }
    }

    override suspend fun onStop() {
        context?.let { timers.shutdown(it) }
        ticker?.cancel()
        mirror?.cancel()
        ticker = null
        mirror = null
        // The mirror is gone, so the last transition is copied over by hand rather than left
        // to a coroutine that is being cancelled in the same breath.
        _state.update { it.copy(timers = emptyList()) }
        context = null
    }

    /**
     * `ACTIVE` only while a chime is ringing — never for a counting-down timer.
     *
     * "Stopp" with a 10-minute timer running and music playing means *pause the music*.
     * Cancelling a running timer is destructive and hard to undo, so it must be addressed
     * explicitly ("timer stopp"). A pure state read, as the contract requires.
     */
    override fun activityFor(invocation: CommandInvocation): SockActivity =
        if (invocation.commandId == SharedCommands.STOP.id && timers.isRinging) {
            SockActivity.ACTIVE
        } else {
            SockActivity.INACTIVE
        }

    override suspend fun handle(invocation: CommandInvocation): SockResult =
        when (invocation.commandId) {
            WHATS_THE_TIME -> {
                // The dashboard clock is the visual half of the answer, so wake the screen
                // even though the spoken reply stands on its own.
                context?.screen?.wakeFor(screenWakeSeconds)
                // Read at the moment it is spoken, not at the moment it is dispatched — which
                // is also why the clock is read inside the phrase and not outside it.
                SockResult.Spoken { lang -> SpokenTime.speak(LocalTime.now(clock), lang) }
            }

            WHATS_THE_DATE -> {
                // The dashboard's date line is the visual half of this one, exactly as the
                // clock face is for the time.
                context?.screen?.wakeFor(screenWakeSeconds)
                // Read inside the phrase for the same reason as above — and here it also means
                // a question asked at 23:59:59 is answered with the day it is spoken on.
                SockResult.Spoken { lang -> SpokenTime.speakDate(LocalDate.now(clock), lang) }
            }

            WEEKDAY_OF_DATE -> weekdayOfDate(invocation)

            DATE_OF_WEEKDAY -> dateOfWeekday(invocation)

            DATE_IN -> dateIn(invocation)

            TIME_UNTIL -> timeUntil(invocation)

            SET_TIMER -> setTimer(invocation)

            CANCEL_TIMER -> cancelTimer(invocation)

            CANCEL_ALL_TIMERS -> cancelAllTimers()

            TIMER_REMAINING -> timerRemaining(invocation)

            SharedCommands.STOP.id -> stopChime()

            // Unreachable in practice: the dispatcher only routes commands this Sock owns.
            // Reported as a bug rather than silently swallowed.
            else -> SockResult.NotForMe
        }

    /**
     * `clock.weekday_of_date` (§8b) — "welcher Tag ist der 26.?"
     *
     * The clock is read **once**, before the phrase rather than inside it, and that is the one
     * place this Sock departs from §8 and §8a. Those two answer with a single instant and can
     * read it at the moment they speak; this one has to resolve a date first, and an answer
     * assembled from two different "today"s would be worse than one that is a tenth of a second
     * stale. The date is resolved here, the sentence is still built late (`README` §2a).
     */
    private fun weekdayOfDate(invocation: CommandInvocation): SockResult {
        val today = LocalDate.now(clock)
        val day = invocation.intOrNull("day") ?: return SockResult.Failed(BAD_DATE)
        val date = resolveDate(today, day, monthOf(invocation)) ?: return SockResult.Failed(BAD_DATE)
        context?.screen?.wakeFor(screenWakeSeconds)
        return SockResult.Spoken { lang -> SpokenTime.speakWeekdayOf(date, today, lang) }
    }

    /** `clock.date_of_weekday` (§8c) — "wann ist der nächste Samstag?" */
    private fun dateOfWeekday(invocation: CommandInvocation): SockResult {
        val today = LocalDate.now(clock)
        val weekday = weekdayOf(invocation) ?: return SockResult.Failed(BAD_DATE)
        val date = CalendarDates.nextWeekday(today, weekday.day)
        context?.screen?.wakeFor(screenWakeSeconds)
        return SockResult.Spoken { lang -> SpokenTime.speakDateOfWeekday(date, today, lang) }
    }

    /** `clock.date_in` (§8d) — "der Wievielte ist heute in 3 Wochen?", and "morgen". */
    private fun dateIn(invocation: CommandInvocation): SockResult {
        val today = LocalDate.now(clock)
        val amount = invocation.intOrNull("amount") ?: return SockResult.Failed(BAD_DATE)
        val unit = invocation.textOrNull("unit")?.let(SpanUnit::of) ?: return SockResult.Failed(BAD_DATE)
        val date = CalendarDates.plus(today, amount, unit) ?: return SockResult.Failed(BAD_DATE)
        context?.screen?.wakeFor(screenWakeSeconds)
        return SockResult.Spoken { lang -> SpokenTime.speakDateIn(date, today, amount, unit, lang) }
    }

    /**
     * `clock.time_until` (§8e) — "wie viele Tage sind es noch bis zum 24. Dezember?"
     *
     * One command for both kinds of target, because "bis Samstag" and "bis zum 24. Dezember"
     * are the same question with a different way of naming the day. Which slot was filled is
     * what decides the German case around the answer, and that is the only thing the two
     * branches below disagree about.
     */
    private fun timeUntil(invocation: CommandInvocation): SockResult {
        val today = LocalDate.now(clock)
        val unit = invocation.textOrNull("unit")?.let(SpanUnit::of) ?: SpanUnit.TAGE
        val weekday = weekdayOf(invocation)
        val target = when {
            weekday != null -> CalendarDates.nextWeekday(today, weekday.day)
            else -> invocation.intOrNull("day")?.let { resolveDate(today, it, monthOf(invocation)) }
        } ?: return SockResult.Failed(BAD_DATE)
        context?.screen?.wakeFor(screenWakeSeconds)
        return SockResult.Spoken { lang ->
            val english = lang == Lang.EN
            val date = SpokenTime.dayAndMonth(target, lang)
            // Nominative for "… ist heute", dative behind "bis". English needs neither, which
            // is why the two are built here and not in `SpokenTime`.
            val subject = when {
                weekday != null -> SpokenTime.weekday(target, lang)
                english -> date
                else -> "Der $date"
            }
            when {
                target == today && english -> "$subject is today."
                target == today -> "$subject ist heute."
                else -> {
                    val until = when {
                        weekday != null -> SpokenTime.weekday(target, lang)
                        english -> date
                        else -> "zum $date"
                    }
                    val span = SpokenTime.span(today, target, unit, lang)
                    if (english) "$span until $until." else "Noch $span bis $until."
                }
            }
        }
    }

    /**
     * The date somebody meant by a day number and maybe a month (§8b).
     *
     * Forwards, today included, in both shapes — the reasoning is on [CalendarDates].
     */
    private fun resolveDate(today: LocalDate, day: Int, month: MonthName?): LocalDate? =
        if (month == null) {
            CalendarDates.nextWithDay(today, day)
        } else {
            CalendarDates.nextWithMonthDay(today, month.month, day)
        }

    private fun monthOf(invocation: CommandInvocation): MonthName? =
        invocation.textOrNull("month")?.let(MonthName::of)

    private fun weekdayOf(invocation: CommandInvocation): Weekday? =
        invocation.textOrNull("weekday")?.let(Weekday::of)

    /**
     * `clock.set_timer`, in one or in two utterances.
     *
     * "Stell einen Timer auf zehn" carries an amount and no unit, so it cannot be executed and
     * must not be refused either — ten *what* is the only thing missing, and asking is one
     * short sentence. The amount is stashed under the follow-up token, together with the name if
     * one was spoken, and the same handler runs again with the answer, so the two-utterance path
     * and the one-utterance path converge before anything touches a timer.
     */
    private suspend fun setTimer(invocation: CommandInvocation): SockResult {
        val answering = invocation.answering
        val (amount, name) = if (answering != null) {
            // Core routes an answer straight back here, but the state it answers is ours. A
            // token we no longer hold means the turn moved on without us; there is nothing
            // left to set and nothing sensible to say about a duration we cannot reconstruct.
            val pending = asked.remove(answering) as? Pending.MissingUnit
                ?: return SockResult.Failed(BAD_DURATION)
            pending.amount to pending.name
        } else {
            val amount = invocation.intOrNull("amount") ?: return SockResult.Failed(BAD_DURATION)
            amount to TimerNames.clean(invocation.textOrNull("name"))?.let(TimerNames::display)
        }
        val spoken = invocation.textOrNull("unit")
            ?: return askForUnit(amount, name)
        val unit = TimerUnit.of(spoken) ?: return SockResult.Failed(BAD_DURATION)
        val durationMs = amount.toLong() * unit.seconds * MILLIS_PER_SECOND
        if (amount !in MIN_AMOUNT..MAX_AMOUNT || durationMs !in MILLIS_PER_SECOND..MAX_DURATION_MS) {
            return SockResult.Failed(BAD_DURATION)
        }

        val outcome = timers.start(started(), durationMs, name)
        refreshStatus()
        if (outcome !is StartOutcome.Started) return SockResult.Failed(TOO_MANY_TIMERS)
        return SockResult.Spoken { lang ->
            val english = lang == Lang.EN
            buildString {
                if (outcome.replaced) append(if (english) "Old timer replaced. " else "Alter Timer ersetzt. ")
                val duration = SpokenTime.duration(amount, unit, lang)
                append("${outcome.timer.spoken} ")
                append(if (english) "is running: $duration." else "läuft: $duration.")
                // The timer still runs off the coroutine; only the backstop is weaker (§12).
                if (!alarm.canScheduleExact) {
                    append(
                        if (english) {
                            " Careful, it isn't guaranteed to be exact."
                        } else {
                            " Achtung, er ist nicht garantiert genau."
                        },
                    )
                }
            }
        }
    }

    /**
     * "10 was — Sekunden, Minuten oder Stunden?"
     *
     * The scoped palette hears the unit on its own ("minuten") and with the preposition the
     * question invites ("in minuten"). Both are bare enough that they would be reckless in the
     * global palette and are perfectly safe here, where they are only ever tried against the
     * one utterance that answers this question.
     */
    private fun askForUnit(amount: Int, name: String?): SockResult {
        val token = ask(Pending.MissingUnit(amount, name))
        return SockResult.Asked(
            phrase = { lang ->
                if (lang == Lang.EN) {
                    "$amount what — seconds, minutes or hours?"
                } else {
                    "$amount was — Sekunden, Minuten oder Stunden?"
                }
            },
            follow = FollowUp(
                commandId = SET_TIMER,
                templates = patterns("{unit:enum}", "(in|auf|für) {unit:enum}"),
                params = listOf(ParamSpec("unit", ParamType.Enumeration(TimerUnit.SPOKEN))),
                token = token,
            ),
        )
    }

    /** The turn ended, or the user said something else entirely. Drop the half-built state. */
    override suspend fun onAskCancelled(token: String) {
        asked.remove(token)
    }

    /**
     * `clock.cancel_timer`.
     *
     * Three jobs behind one command, because that is what the user means in all three
     * situations: silence a chime that is sounding, call off the one timer that is counting, or
     * call off the one they named. The fourth case is not a job at all — several timers, no name
     * — and it is a question (§4).
     */
    private suspend fun cancelTimer(invocation: CommandInvocation): SockResult {
        // The answer to "welchen soll ich abbrechen" carries the whole of its own meaning, so a
        // token we no longer hold costs nothing: the name still resolves against what is running.
        invocation.answering?.let { asked.remove(it) }
        val name = TimerNames.clean(invocation.textOrNull("name"))
        return when (val outcome = timers.cancel(started(), name)) {
            // The silence is its own feedback.
            is CancelOutcome.Silenced -> SockResult.Silent
            is CancelOutcome.Cancelled -> SockResult.Spoken { lang ->
                val named = SpokenTime.list(outcome.timers.map { it.spoken }, lang)
                if (lang == Lang.EN) "$named cancelled." else "$named abgebrochen."
            }

            CancelOutcome.Nothing -> SockResult.Spoken(NO_TIMER)
            is CancelOutcome.Unknown -> SockResult.Spoken(noSuchTimer(outcome.name))
            is CancelOutcome.Ambiguous -> askWhichTimer(outcome.timers)
        }
    }

    /**
     * "Es laufen Timer Nudeln und Timer 2. Welchen soll ich abbrechen?"
     *
     * The same contract the missing unit uses, for the same reason: cancelling a timer is not
     * undoable by saying it again, so a Sock that cannot tell which one was meant asks instead
     * of guessing.
     *
     * The slot is an **enum over the names that are running**, and deliberately not the `{name}`
     * text slot the command itself carries. A question's own palette gets first refusal on the
     * next utterance, ahead of every command Dobby knows ([io.dobby.core.DobbyEngine]) — so a
     * bare text slot here would match literally anything and the question would be a trap: "wie
     * spät ist es", said while it is open, would become a timer called "Wie Spät Ist Es". A
     * closed candidate set is what lets everything else fall through to the global palette and
     * abandon the question, which is exactly why `{unit:enum}` is safe in the same position.
     *
     * The price is that a multi-word name cannot be answered on its own — an enum slot takes one
     * token. Those timers are still listed in the question and still reachable by the full
     * sentence ("brich Timer grüner Tee ab"), which goes to the global palette.
     */
    private fun askWhichTimer(running: List<TimerState>): SockResult {
        val question = Phrase { lang ->
            val named = SpokenTime.list(running.map { it.spoken }, lang)
            if (lang == Lang.EN) {
                "$named are running. Which one should I cancel?"
            } else {
                "Es laufen $named. Welchen soll ich abbrechen?"
            }
        }
        val candidates = running.flatMap { it.keys }.filter { ' ' !in it }
        // Nothing sayable in one word: ask nothing, and let the list be the answer. Holding the
        // floor for a question no utterance could satisfy is worse than not holding it.
        if (candidates.isEmpty()) return SockResult.Spoken(question)
        val token = ask(Pending.WhichTimer)
        return SockResult.Asked(
            phrase = question,
            follow = FollowUp(
                commandId = CANCEL_TIMER,
                templates = patterns("{name:enum}", "(den)? timer {name:enum}"),
                params = listOf(ParamSpec("name", ParamType.Enumeration(candidates))),
                token = token,
            ),
        )
    }

    /** `clock.cancel_all_timers`. No name, no tie to break, no question worth asking. */
    private suspend fun cancelAllTimers(): SockResult {
        val cancelled = timers.cancelAll(started())
        return when (cancelled.size) {
            0 -> SockResult.Spoken(NO_TIMER)
            1 -> SockResult.Spoken { lang ->
                val name = cancelled.single().spoken
                if (lang == Lang.EN) "$name cancelled." else "$name abgebrochen."
            }

            else -> SockResult.Spoken { lang ->
                if (lang == Lang.EN) {
                    "All ${cancelled.size} timers cancelled."
                } else {
                    "Alle ${cancelled.size} Timer abgebrochen."
                }
            }
        }
    }

    /**
     * `clock.timer_remaining`.
     *
     * Read-only, so it needs no [started] context — a question about timers that are not running
     * is answerable before `onStart` ever happened. The screen still wakes where it can: the
     * countdown ring is the visual half of this answer the way the clock face is of the time.
     */
    private fun timerRemaining(invocation: CommandInvocation): SockResult {
        val running = timers.snapshot()
        if (running.isEmpty()) return SockResult.Spoken(NO_TIMER)
        context?.screen?.wakeFor(screenWakeSeconds)
        val name = TimerNames.clean(invocation.textOrNull("name"))
        if (name != null) {
            val timer = TimerNames.find(running, name) ?: return SockResult.Spoken(noSuchTimer(name))
            return SockResult.Spoken { lang -> sentence(timer, lang) }
        }
        if (running.size == 1) return SockResult.Spoken { lang -> sentence(running.single(), lang) }
        return SockResult.Spoken { lang ->
            running.joinToString(" ") { "${it.spoken}: ${left(it, lang)}." }
        }
    }

    /** "Der Timer läuft noch 9 Minuten." / "The timer has 9 minutes left." */
    private fun sentence(timer: TimerState, lang: Lang): String {
        val subject = timer.subject(lang)
        return when {
            timer.isRinging && lang == Lang.EN -> "$subject has just finished."
            timer.isRinging -> "$subject ist gerade abgelaufen."
            lang == Lang.EN -> "$subject has ${SpokenTime.remaining(timer.remainingMs, lang)} left."
            else -> "$subject läuft noch ${SpokenTime.remaining(timer.remainingMs, lang)}."
        }
    }

    /** "noch 9 Minuten" / "9 minutes left" — one entry in a list of several. */
    private fun left(timer: TimerState, lang: Lang): String = when {
        timer.isRinging && lang == Lang.EN -> "finished"
        timer.isRinging -> "abgelaufen"
        lang == Lang.EN -> "${SpokenTime.remaining(timer.remainingMs, lang)} left"
        else -> "noch ${SpokenTime.remaining(timer.remainingMs, lang)}"
    }

    /**
     * `shared.stop`.
     *
     * The `isRinging` check comes first and does no I/O: a Sock that reported `INACTIVE` must
     * pass the command on without touching anything (`socks.specs/README.md` §4).
     */
    private suspend fun stopChime(): SockResult {
        if (!timers.isRinging) return SockResult.NotForMe
        timers.cancel(started(), name = null)
        return SockResult.Silent
    }

    /**
     * The X on a timer row (§9): the same cancel, from a finger instead of a sentence.
     *
     * The precedent is `RadioSock.stopFromPanel`, and so is the reasoning — straight at the
     * Sock rather than through the engine, across the same seam the card already reads [state]
     * over. Public because the panel holds this Sock by name.
     *
     * No context means nothing has started and so nothing is running; that is a no-op rather
     * than the [started] exception, because unlike a spoken command a button press has nobody
     * to apologise to.
     */
    suspend fun cancelFromPanel(id: Long) {
        timers.cancelById(context ?: return, id)
    }

    /** The dashboard's clock, ticking no faster than it has to (§9). */
    private suspend fun tick(config: ClockConfig) {
        while (coroutineContext.isActive) {
            _state.update { it.copy(now = LocalDateTime.now(clock)) }
            val perSecond = config.showSeconds || timers.snapshot().isNotEmpty()
            val period = if (perSecond) MILLIS_PER_SECOND else MILLIS_PER_MINUTE
            // Aligned to the boundary, so the displayed minute changes when the minute does.
            delay(period - clock.millis() % period)
        }
    }

    private fun ask(pending: Pending): String {
        val token = "clock-${askCounter.incrementAndGet()}"
        asked[token] = pending
        return token
    }

    private fun refreshStatus() {
        _status.value = if (alarm.canScheduleExact) {
            SockStatus.Ready
        } else {
            SockStatus.Degraded("Timer nicht garantiert genau")
        }
    }

    /**
     * The context a timer needs.
     *
     * `whats_the_time` degrades gracefully without one; a timer cannot — it needs a scope to
     * count in. The dispatcher never calls `handle` on a Sock it did not start, so this is a
     * programming error, and core turns the exception into "Das hat gerade nicht geklappt."
     */
    private fun started(): SockContext =
        checkNotNull(context) { "clock: timer command before onStart()" }

    companion object {
        const val SET_TIMER: String = "clock.set_timer"
        const val CANCEL_TIMER: String = "clock.cancel_timer"
        const val CANCEL_ALL_TIMERS: String = "clock.cancel_all_timers"
        const val TIMER_REMAINING: String = "clock.timer_remaining"
        const val WHATS_THE_TIME: String = "clock.whats_the_time"
        const val WHATS_THE_DATE: String = "clock.whats_the_date"
        const val WEEKDAY_OF_DATE: String = "clock.weekday_of_date"
        const val DATE_OF_WEEKDAY: String = "clock.date_of_weekday"
        const val DATE_IN: String = "clock.date_in"
        const val TIME_UNTIL: String = "clock.time_until"

        const val DEFAULT_SCREEN_WAKE_SECONDS: Int = 30

        /** Highest in the catalog — see [shared]. */
        const val STOP_PRIORITY: Int = 100

        /** Spec copy (§3), German verbatim and English alongside it. */
        val BAD_DURATION: Phrase =
            Phrase.of("Diese Dauer kann ich nicht stellen.", "I can't set that duration.")

        /** §4–§6. Said when nothing is running at all, whatever was asked about it. */
        val NO_TIMER: Phrase = Phrase.of("Es läuft gerade kein Timer.", "No timer is running.")

        /** §3. The cap is a guard against a misheard sentence, not a limit of the engine. */
        val TOO_MANY_TIMERS: Phrase = Phrase.of(
            "Ich kann nicht mehr als ${TimerEngine.MAX_TIMERS} Timer gleichzeitig stellen.",
            "I can't run more than ${TimerEngine.MAX_TIMERS} timers at once.",
        )

        /**
         * §8b–§8e. One phrase for every way a calendar question can fail to name a real day.
         *
         * "Der 32." and "der 30. Februar" are the whole of it, plus a span past the horizon.
         * Deliberately one sentence and not four: they are all the same thing from the room's
         * point of view — the panel did not understand the date — and a person who said "der
         * 30. Februar" does not need to be told which half of it was wrong.
         */
        val BAD_DATE: Phrase =
            Phrase.of("Dieses Datum kenne ich nicht.", "I don't know that date.")

        /** §4 and §6. [name] arrives lowercase from the matcher and is spoken the way it is written. */
        fun noSuchTimer(name: String): Phrase = Phrase { lang ->
            val display = TimerNames.display(name)
            if (lang == Lang.EN) {
                "There's no timer called $display."
            } else {
                "Es läuft kein Timer namens $display."
            }
        }

        /**
         * Shared alternations, written once because they appear in both the named and the
         * unnamed shape of the same command and drifting apart would be a silent hole.
         */
        private const val SET_VERBS = "stell|stelle|setz|setze|mach|neuer|neuen|neue|erstell|erstelle|starte|start"

        /**
         * The articles and prepositions that stand in front of a spoken day number.
         *
         * Most of them are on [io.dobby.core.nlu.Fillers.DE], and they are written out anyway:
         * skipping happens in front of a *keyword* and never in front of a slot, so without
         * "der" spelled out here "welcher Tag ist der 14." would have to reach the `{day:int}`
         * slot with "der" still sitting in it (`socks.specs/README.md` §6).
         */
        private const val ON_THE = "der|den|dem|am|vom|zum"

        /**
         * "der nächste Samstag", "kommenden Samstag", "diesen Samstag".
         *
         * All three mean the next one there is, which is why none of them is a param: §8c
         * resolves forwards and today counts, so there is nothing for a direction slot to say.
         * "nächste" at 7 characters covers "nächsten" and "nächster" at tolerance 1, and
         * "kommende" at 8 covers its own declension at tolerance 2.
         */
        private const val UPCOMING = "nächste|kommende|diese"

        /** "der wievielte", written both ways because the recogniser writes it both ways (§8a). */
        private const val HOW_MANYETH = "wievielte|wie vielte"

        /** "wie viele Tage" — and "wie viel Tage", which is what half the country says. */
        private const val HOW_MANY = "wie (viele|viel)"

        /** The anchor that keeps §8e off `timer_remaining`'s "wie lange noch". */
        private const val UNTIL = "bis (zum|zur|auf)?"
        private const val CANCEL_VERBS = "stoppen|abbrechen|löschen|beenden|abschalten"
        private const val CANCEL_PREFIX = "stopp|stoppe|brich|breche|lösch|lösche|beende"
        private const val RUNS_SINGULAR = "geht|läuft|dauert"

        private const val MIN_AMOUNT = 1
        private const val MAX_AMOUNT = 600
        private const val MILLIS_PER_SECOND = 1000L
        private const val MILLIS_PER_MINUTE = 60_000L

        /** 12 hours. Past that it is an alarm, not a kitchen timer — and `set_alarm` is v2 (§14). */
        private const val MAX_DURATION_MS = 12 * 60 * 60 * 1000L
    }
}
