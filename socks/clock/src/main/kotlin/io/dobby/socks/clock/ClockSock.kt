package io.dobby.socks.clock

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
 * Clock — kitchen timer and time of day.
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

    /** Never `Unavailable`: a clock with no permissions is still a clock (§10). */
    override val status: StateFlow<SockStatus> = _status.asStateFlow()

    private val _state = MutableStateFlow(ClockState(LocalDateTime.now(clock)))

    /** What the wall panel draws (§7). Also the timer state the chain reads. */
    val state: StateFlow<ClockState> = _state.asStateFlow()

    private var ticker: Job? = null
    private var mirror: Job? = null

    /**
     * Amounts spoken without a unit, waiting for the follow-up that names one.
     *
     * This is the whole of "the Sock holding partial state": there is no waiting instance and
     * nothing blocks, because `handle()` runs under core's timeout and returning
     * [SockResult.Asked] is how a Sock waits. Concurrent by type rather than by need — core
     * dispatches one utterance at a time, and a map that is wrong under a race would be a very
     * quiet bug.
     */
    private val askedAmounts = ConcurrentHashMap<String, Int>()

    private val askCounter = AtomicLong()

    override val commands: List<ExclusiveCommandSpec> = listOf(
        ExclusiveCommandSpec(
            id = SET_TIMER,
            params = listOf(
                ParamSpec("amount", ParamType.Integer),
                // Optional, because "stell einen timer auf zehn" is a sentence German people
                // really say. A missing unit is not a parse failure, it is a question (§3).
                ParamSpec("unit", ParamType.Enumeration(TimerUnit.SPOKEN), required = false),
            ),
            templates = patterns(
                // No "(mir)?" anywhere below: "mir" is filler and the matcher skips it (M6c).
                // "(einen|nen)?" stays — it is what keeps the commonest phrasing of all,
                // "stell einen timer auf 5 minuten", on the strict first pass.
                "(stell|stelle|setz|setze|mach) (einen|nen)? timer (auf|für)? {amount:int} {unit:enum}",
                "(stell|stelle|setz|setze) (einen|nen)? wecker (auf|für)? {amount:int} {unit:enum}",
                "(erinner|erinnere) mich in {amount:int} {unit:enum}",
                "timer (auf|für)? {amount:int} {unit:enum}",
                "{amount:int} {unit:enum} timer",
                // Unit-less forms, last: they are strictly less specific than the ones above,
                // and the palette only reaches them once no fuller phrasing fits.
                "(stell|stelle|setz|setze|mach) (einen|nen)? timer (auf|für)? {amount:int}",
                "(stell|stelle|setz|setze) (einen|nen)? wecker (auf|für)? {amount:int}",
                "timer (auf|für)? {amount:int}",
            ),
            description = "Stellt einen Timer für eine bestimmte Dauer.",
            examples = listOf(
                // The normalizer has already turned "zehn" into "10" before templates run.
                Example("timer zehn minuten", mapOf("amount" to 10, "unit" to "minuten")),
                Example("stell einen timer auf 5 minuten", mapOf("amount" to 5, "unit" to "minuten")),
                Example("stelle mir einen timer für 90 sekunden", mapOf("amount" to 90, "unit" to "sekunden")),
                Example("3 minuten timer", mapOf("amount" to 3, "unit" to "minuten")),
                Example("timer eine minute", mapOf("amount" to 1, "unit" to "minuten")),
                Example("setz einen wecker auf 2 stunden", mapOf("amount" to 2, "unit" to "stunden")),
                Example("erinner mich in 20 minuten", mapOf("amount" to 20, "unit" to "minuten")),
                // No unit: matched, then asked about. Before this template the utterance
                // resolved to nothing at all, which is the one answer that helps nobody.
                Example("stell einen timer auf zehn", mapOf("amount" to 10)),
                Example("timer 5", mapOf("amount" to 5)),
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
                // Held out: the model never sees these, and the device accuracy test measures
                // it against them. Without them the test measures whether Qwen can repeat the
                // two sentences its own prompt just handed it.
                Example("sag mir in 10 minuten bescheid", mapOf("amount" to 10, "unit" to "minuten"), heldOut = true),
                Example("in einer halben stunde bitte klingeln", mapOf("amount" to 30, "unit" to "minuten"), heldOut = true),
                Example("ich will in 5 minuten dran erinnert werden", mapOf("amount" to 5, "unit" to "minuten"), heldOut = true),
            ),
        ),
        ExclusiveCommandSpec(
            id = CANCEL_TIMER,
            templates = patterns(
                "timer (stopp|stop|stoppen|abbrechen|aus|löschen|beenden|abschalten)",
                "(stopp|stoppe|brich|breche|lösch|lösche|beende) (den)? timer (ab)?",
                "(stopp|stoppe|aus mit) (dem|den|das)? (alarm|wecker|klingeln)",
            ),
            description = "Bricht den laufenden Timer ab.",
            examples = listOf(
                Example("timer stopp"),
                Example("timer abbrechen"),
                Example("stopp den timer"),
                Example("brich den timer ab"),
                Example("stopp den alarm"),
                Example("der timer kann weg", heldOut = true),
                Example("ich brauche den timer doch nicht mehr", heldOut = true),
                Example("mach das gebimmel weg", heldOut = true),
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
     * room, so "stopp" means *that* first — and only while it is actually ringing (§5).
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
        // A timer whose process was killed is picked up here — the coroutine countdown lives in
        // memory, and a kitchen timer that silently does not go off is the one failure that
        // matters (§10).
        timers.restore(ctx)
        mirror = ctx.scope.launch {
            timers.timer.collect { timer -> _state.update { it.copy(timer = timer) } }
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
        _state.update { it.copy(timer = null) }
        context = null
    }

    /**
     * `ACTIVE` only while the chime is ringing — never for a counting-down timer.
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

            CANCEL_TIMER -> cancelTimer()

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
     * short sentence. The amount is stashed under the follow-up token and the same handler runs
     * again with the answer, so the two-utterance path and the one-utterance path converge
     * before anything touches the timer.
     */
    private suspend fun setTimer(invocation: CommandInvocation): SockResult {
        val answering = invocation.answering
        val amount = if (answering != null) {
            // Core routes an answer straight back here, but the state it answers is ours. A
            // token we no longer hold means the turn moved on without us; there is nothing
            // left to set and nothing sensible to say about a duration we cannot reconstruct.
            askedAmounts.remove(answering) ?: return SockResult.Failed(BAD_DURATION)
        } else {
            invocation.intOrNull("amount") ?: return SockResult.Failed(BAD_DURATION)
        }
        val spoken = invocation.textOrNull("unit")
            ?: return askForUnit(amount)
        val unit = TimerUnit.of(spoken) ?: return SockResult.Failed(BAD_DURATION)
        val durationMs = amount.toLong() * unit.seconds * MILLIS_PER_SECOND
        if (amount !in MIN_AMOUNT..MAX_AMOUNT || durationMs !in MILLIS_PER_SECOND..MAX_DURATION_MS) {
            return SockResult.Failed(BAD_DURATION)
        }

        val replaced = timers.start(started(), durationMs)
        refreshStatus()
        return SockResult.Spoken(
            buildString {
                if (replaced) append("Alter Timer ersetzt. ")
                append("Timer läuft: ${GermanTime.duration(amount, unit)}.")
                // The timer still runs off the coroutine; only the backstop is weaker (§10).
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
    private fun askForUnit(amount: Int): SockResult {
        val token = "unit-${askCounter.incrementAndGet()}"
        askedAmounts[token] = amount
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

    /** The turn ended, or the user said something else entirely. Drop the half-built timer. */
    override suspend fun onAskCancelled(token: String) {
        askedAmounts.remove(token)
    }

    /**
     * Two jobs behind one command, because that is what the user means in both situations:
     * silence a chime that is sounding, or call off a timer that is still counting.
     */
    private suspend fun cancelTimer(): SockResult = when (timers.cancel(started())) {
        // The silence is its own feedback.
        CancelOutcome.SILENCED -> SockResult.Silent
        CancelOutcome.CANCELLED -> SockResult.Spoken("Timer abgebrochen.")
        CancelOutcome.NOTHING -> SockResult.Spoken("Es läuft gerade kein Timer.")
    }

    /**
     * `shared.stop`.
     *
     * The `isRinging` check comes first and does no I/O: a Sock that reported `INACTIVE` must
     * pass the command on without touching anything (`socks.specs/README.md` §4).
     */
    private suspend fun stopChime(): SockResult {
        if (!timers.isRinging) return SockResult.NotForMe
        timers.cancel(started())
        return SockResult.Silent
    }

    /** The dashboard's clock, ticking no faster than it has to (§7). */
    private suspend fun tick(config: ClockConfig) {
        while (coroutineContext.isActive) {
            _state.update { it.copy(now = LocalDateTime.now(clock)) }
            val perSecond = config.showSeconds || timers.timer.value != null
            val period = if (perSecond) MILLIS_PER_SECOND else MILLIS_PER_MINUTE
            // Aligned to the boundary, so the displayed minute changes when the minute does.
            delay(period - clock.millis() % period)
        }
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
        const val WHATS_THE_TIME: String = "clock.whats_the_time"

        const val DEFAULT_SCREEN_WAKE_SECONDS: Int = 30

        /** Highest in the catalog — see [shared]. */
        const val STOP_PRIORITY: Int = 100

        /** German copy, verbatim from the spec (§3). */
        const val BAD_DURATION: String = "Diese Dauer kann ich nicht stellen."

        private const val MIN_AMOUNT = 1
        private const val MAX_AMOUNT = 600
        private const val MILLIS_PER_SECOND = 1000L
        private const val MILLIS_PER_MINUTE = 60_000L

        /** 12 hours. Past that it is an alarm, not a kitchen timer — and `set_alarm` is v2 (§12). */
        private const val MAX_DURATION_MS = 12 * 60 * 60 * 1000L
    }
}
