package io.dobby.socks.clock

import io.dobby.core.sock.CommandHelp
import io.dobby.core.sock.CommandInvocation
import io.dobby.core.sock.Example
import io.dobby.core.sock.ExclusiveCommandSpec
import io.dobby.core.sock.FollowUp
import io.dobby.core.sock.ParamSpec
import io.dobby.core.sock.ParamType
import io.dobby.core.sock.SharedCommands
import io.dobby.core.sock.SharedSubscription
import io.dobby.core.sock.Sock
import io.dobby.core.sock.SockActivity
import io.dobby.core.sock.SockContext
import io.dobby.core.sock.SockResult
import io.dobby.core.sock.SockStatus
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
                SockResult.Spoken(GermanTime.speak(LocalTime.now(clock)))
            }

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
        return SockResult.Spoken(
            buildString {
                if (outcome.replaced) append("Alter Timer ersetzt. ")
                append("${outcome.timer.spoken} läuft: ${GermanTime.duration(amount, unit)}.")
                // The timer still runs off the coroutine; only the backstop is weaker (§12).
                if (!alarm.canScheduleExact) append(" Achtung, er ist nicht garantiert genau.")
            },
        )
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
            text = "$amount was — Sekunden, Minuten oder Stunden?",
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
            is CancelOutcome.Cancelled ->
                SockResult.Spoken("${GermanTime.list(outcome.timers.map { it.spoken })} abgebrochen.")

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
        val question = "Es laufen ${GermanTime.list(running.map { it.spoken })}. Welchen soll ich abbrechen?"
        val candidates = running.flatMap { it.keys }.filter { ' ' !in it }
        // Nothing sayable in one word: ask nothing, and let the list be the answer. Holding the
        // floor for a question no utterance could satisfy is worse than not holding it.
        if (candidates.isEmpty()) return SockResult.Spoken(question)
        val token = ask(Pending.WhichTimer)
        return SockResult.Asked(
            text = question,
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
            1 -> SockResult.Spoken("${cancelled.single().spoken} abgebrochen.")
            else -> SockResult.Spoken("Alle ${cancelled.size} Timer abgebrochen.")
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
            return SockResult.Spoken(sentence(timer))
        }
        if (running.size == 1) return SockResult.Spoken(sentence(running.single()))
        return SockResult.Spoken(running.joinToString(" ") { "${it.spoken}: ${phrase(it)}." })
    }

    /** "Der Timer läuft noch 9 Minuten." — the whole answer, for one timer. */
    private fun sentence(timer: TimerState): String =
        if (timer.isRinging) {
            "${timer.subject} ist gerade abgelaufen."
        } else {
            "${timer.subject} läuft noch ${GermanTime.remaining(timer.remainingMs)}."
        }

    /** "noch 9 Minuten" — one entry in a list of several. */
    private fun phrase(timer: TimerState): String =
        if (timer.isRinging) "abgelaufen" else "noch ${GermanTime.remaining(timer.remainingMs)}"

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

        const val DEFAULT_SCREEN_WAKE_SECONDS: Int = 30

        /** Highest in the catalog — see [shared]. */
        const val STOP_PRIORITY: Int = 100

        /** German copy, verbatim from the spec (§3). */
        const val BAD_DURATION: String = "Diese Dauer kann ich nicht stellen."

        /** §4–§6. Said when nothing is running at all, whatever was asked about it. */
        const val NO_TIMER: String = "Es läuft gerade kein Timer."

        /** §3. The cap is a guard against a misheard sentence, not a limit of the engine. */
        const val TOO_MANY_TIMERS: String =
            "Ich kann nicht mehr als ${TimerEngine.MAX_TIMERS} Timer gleichzeitig stellen."

        /** §4 and §6. [name] arrives lowercase from the matcher and is spoken the way it is written. */
        fun noSuchTimer(name: String): String = "Es läuft kein Timer namens ${TimerNames.display(name)}."

        /**
         * Shared alternations, written once because they appear in both the named and the
         * unnamed shape of the same command and drifting apart would be a silent hole.
         */
        private const val SET_VERBS = "stell|stelle|setz|setze|mach|neuer|neuen|neue|erstell|erstelle|starte|start"
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
