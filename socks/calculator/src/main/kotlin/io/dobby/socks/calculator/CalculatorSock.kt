package io.dobby.socks.calculator

import io.dobby.core.sock.CommandInvocation
import io.dobby.core.sock.Example
import io.dobby.core.sock.ExclusiveCommandSpec
import io.dobby.core.sock.FollowUp
import io.dobby.core.sock.ParamSpec
import io.dobby.core.sock.ParamType
import io.dobby.core.sock.Sock
import io.dobby.core.sock.SockContext
import io.dobby.core.sock.SockResult
import io.dobby.core.sock.pattern
import io.dobby.core.sock.patterns
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** What the wall panel draws: the last sum, as it was spoken. */
data class CalculatorState(val equation: String? = null, val result: Double? = null)

/**
 * Calculator — arithmetic out loud, and the result that stays put.
 *
 * Two things make this worth a Sock rather than a phone. The answer always reads the question
 * back — "8 mal 2 ist 16", never "16" — because an answer nobody can check by ear is a number
 * you have to trust, and a panel across the kitchen has not earned that. And a result is
 * *kept*, so the next utterance can be the next step: "wie viel ist 250 mal 4", "und davon die
 * Hälfte", "wie oft passt 150 rein". That is how someone scaling a recipe or dividing a board
 * actually talks, one operand at a time, hands busy.
 *
 * Specified in `socks.specs/calculator.specs.md`. No network, no permissions, no audio — the
 * whole Sock is a pure function plus one remembered number.
 *
 * @param clock injected so the memory's expiry is testable without waiting ten minutes.
 * @param ttl how long a result stays continuable; `calculator.memory_minutes` overrides it.
 */
class CalculatorSock(
    private val clock: Clock = Clock.systemDefaultZone(),
    ttl: Duration = ResultMemory.DEFAULT_TTL,
    private val screenWakeSeconds: Int = DEFAULT_SCREEN_WAKE_SECONDS,
) : Sock {

    override val id: String = "calculator"

    override val displayName: String = "Rechner"

    private var context: SockContext? = null

    /**
     * Replaced in [onStart] once the config store is reachable.
     *
     * Not a `lateinit`: a Sock must answer sensibly before `onStart` for the same reason
     * `ClockSock.whats_the_time` does — the dispatcher should never get there, and "should
     * never" is not a reason to lose the answer if it does.
     */
    private var memory: ResultMemory = ResultMemory(clock, ttl)

    private val _state = MutableStateFlow(CalculatorState())

    /** The last sum, for the dashboard card (§8). */
    val state: StateFlow<CalculatorState> = _state.asStateFlow()

    /**
     * Half-built sums waiting for the one operand that was not said.
     *
     * Exactly the shape `ClockSock` uses for its missing unit, and for the same reason: a Sock
     * never blocks on an answer, it returns [SockResult.Asked] and keeps what it has under the
     * token until core calls `handle` again.
     */
    private val pending = ConcurrentHashMap<String, Pending>()

    private val askCounter = AtomicLong()

    /** One operand short. Exactly one of [a] and [b] is null — that is what is being asked for. */
    private data class Pending(val a: Double?, val op: Operation, val b: Long?)

    override val commands: List<ExclusiveCommandSpec> = listOf(
        ExclusiveCommandSpec(
            id = CALCULATE,
            params = listOf(
                ParamSpec("a", ParamType.Integer),
                ParamSpec("op", ParamType.Enumeration(Operation.VALUES)),
                // Optional for the same reason `clock.set_timer` has an optional unit: "wie
                // viel ist 3 plus" is a sentence people really produce, especially when the
                // recogniser clips the tail. A missing operand is a question, not a failure.
                ParamSpec("b", ParamType.Integer, required = false),
            ),
            templates = listOf(
                // Wordings that fix the second operand by what they mean. A slot could never
                // carry these — there is no number spoken to capture.
                pattern("$ASK (die)? (hälfte|haelfte) von {a:int} $TAIL", "op" to DIV, "b" to 2),
                pattern("$ASK (das)? doppelte von {a:int} $TAIL", "op" to MUL, "b" to 2),
                pattern("$ASK {a:int} (zum|im)? quadrat $TAIL", "op" to POW, "b" to 2),

                // Division that keeps both halves of its answer (§3, `ganzzahlig`), and
                // division that keeps only the remainder (`modulo`). Different questions, so
                // different operators — see [Arithmetic] and §3 of the spec.
                pattern("wie oft (passt|geht) {b:int} in {a:int} (rein|hinein)?", "op" to INT_DIV),
                pattern("$ASK {a:int} ganzzahlig (geteilt)? durch {b:int}", "op" to INT_DIV),
                pattern("$ASK (der|den)? rest von {a:int} (geteilt|dividiert)? durch {b:int}", "op" to MOD),
                pattern("was bleibt von {a:int} (geteilt)? durch {b:int} (übrig|uebrig)?", "op" to MOD),

                // The six operators, each one template wide because the alternation carries
                // every phrasing of it.
                pattern("$ASK {a:int} $PLUS_OP {b:int} $TAIL", "op" to ADD),
                pattern("$ASK {a:int} $MINUS_OP {b:int} $TAIL", "op" to SUB),
                pattern("$ASK {a:int} $TIMES_OP {b:int} $TAIL", "op" to MUL),
                pattern("$ASK {a:int} $DIVIDE_OP {b:int} $TAIL", "op" to DIV),
                pattern("$ASK {a:int} $POWER_OP {b:int} $TAIL", "op" to POW),
                pattern("$ASK {a:int} $MODULO_OP {b:int} $TAIL", "op" to MOD),

                // Second operand missing. Strictly less specific than the forms above and
                // unreachable for any utterance that names one — the match is anchored at both
                // ends, so "3 plus 5" can never fall through to "3 plus".
                pattern("$ASK {a:int} $PLUS_OP", "op" to ADD),
                pattern("$ASK {a:int} $MINUS_OP", "op" to SUB),
                pattern("$ASK {a:int} $TIMES_OP", "op" to MUL),
                pattern("$ASK {a:int} $DIVIDE_OP", "op" to DIV),
                pattern("$ASK {a:int} $POWER_OP", "op" to POW),
                pattern("$ASK {a:int} $MODULO_OP", "op" to MOD),
            ),
            description = "Rechnet eine Aufgabe aus und sagt Aufgabe und Ergebnis.",
            examples = listOf(
                Example("wie viel ist 3 plus 5", sum(3, ADD, 5)),
                Example("was ist 12 minus 4", sum(12, SUB, 4)),
                Example("rechne 7 mal 8", sum(7, MUL, 8)),
                Example("was ergibt 6 multipliziert mit 7", sum(6, MUL, 7)),
                Example("20 geteilt durch 4", sum(20, DIV, 4)),
                Example("100 dividiert durch 8", sum(100, DIV, 8)),
                Example("90 durch 6", sum(90, DIV, 6)),
                Example("2 hoch 10", sum(2, POW, 10)),
                Example("9 zum quadrat", sum(9, POW, 2)),
                Example("die hälfte von 17", sum(17, DIV, 2)),
                Example("das doppelte von 21", sum(21, MUL, 2)),
                // The normalizer has already turned the number words into digits.
                Example("drei plus fünf", sum(3, ADD, 5)),
                // Modulo, in both shapes (§3).
                Example("17 modulo 5", sum(17, MOD, 5)),
                Example("der rest von 17 geteilt durch 5", sum(17, MOD, 5)),
                Example("was bleibt von 17 durch 5 übrig", sum(17, MOD, 5)),
                Example("wie oft passt 5 in 17", sum(17, INT_DIV, 5)),
                Example("wie oft geht 250 in 1000 rein", sum(1000, INT_DIV, 250)),
                Example("1000 ganzzahlig geteilt durch 250", sum(1000, INT_DIV, 250)),
                // No second operand: matched, then asked about.
                Example("was ist 3 plus", mapOf("a" to 3, "op" to ADD)),
                Example("1000 geteilt durch", mapOf("a" to 1000, "op" to DIV)),
                // Paraphrases Tier 1 is meant to miss — few-shots for the LLM tier (M6).
                // Written in *normalized* German, digits included: Tier 2 is handed the same
                // string Tier 1 saw, so a few-shot in raw German teaches the model a surface
                // form it will never be shown. PromptGenerator makes that a build failure.
                Example(
                    "kannst du mir ausrechnen was 3 und 5 zusammen sind",
                    sum(3, ADD, 5),
                    matchedByTemplates = false,
                ),
                Example("addiere mir mal eben 12 und 30", sum(12, ADD, 30), matchedByTemplates = false),
                Example(
                    "teil mir 1000 gramm auf 4 portionen auf",
                    sum(1000, DIV, 4),
                    matchedByTemplates = false,
                ),
                // Held out: never shown to the model, asserted on the device (m6b-plan §B4).
                Example("was kommt raus wenn ich 14 und 9 zusammenzähle", sum(14, ADD, 9), heldOut = true),
                Example("nimm mal 6 und 7 zusammen", sum(6, MUL, 7), heldOut = true),
                Example("wie viel bleibt von 100 wenn ich 35 abziehe", sum(100, SUB, 35), heldOut = true),
            ),
        ),
        ExclusiveCommandSpec(
            id = CONTINUE_WITH,
            params = listOf(
                ParamSpec("op", ParamType.Enumeration(Operation.VALUES)),
                ParamSpec("b", ParamType.Integer),
                // Never spoken in the same breath — it is the answer to "von welcher Zahl?"
                // and arrives through the follow-up palette (§4).
                ParamSpec("a", ParamType.Integer, required = false),
            ),
            templates = listOf(
                pattern("$CONT die (hälfte|haelfte)", "op" to DIV, "b" to 2),
                pattern("$CONT das doppelte", "op" to MUL, "b" to 2),
                pattern("$CONT (zum|im) quadrat", "op" to POW, "b" to 2),
                pattern("(und)? wie oft passt (da|das)? {b:int} (rein|hinein)?", "op" to INT_DIV),
                pattern("$CONT $PLUS_OP {b:int}", "op" to ADD),
                pattern("$CONT $MINUS_OP {b:int}", "op" to SUB),
                pattern("$CONT $TIMES_OP {b:int}", "op" to MUL),
                pattern("$CONT $DIVIDE_OP {b:int}", "op" to DIV),
                pattern("$CONT $POWER_OP {b:int}", "op" to POW),
                pattern("$CONT $MODULO_OP {b:int}", "op" to MOD),
            ),
            description = "Rechnet mit dem letzten Ergebnis weiter.",
            examples = listOf(
                Example("mal 2", mapOf("op" to MUL, "b" to 2)),
                Example("und jetzt mal 2", mapOf("op" to MUL, "b" to 2)),
                Example("plus 5", mapOf("op" to ADD, "b" to 5)),
                Example("und dann durch 3", mapOf("op" to DIV, "b" to 3)),
                Example("und davon die hälfte", mapOf("op" to DIV, "b" to 2)),
                Example("das doppelte", mapOf("op" to MUL, "b" to 2)),
                Example("und das im quadrat", mapOf("op" to POW, "b" to 2)),
                Example("modulo 3", mapOf("op" to MOD, "b" to 3)),
                Example("und wie oft passt da 250 rein", mapOf("op" to INT_DIV, "b" to 250)),
                Example("weiter mit mal 3", mapOf("op" to MUL, "b" to 3), matchedByTemplates = false),
                Example("und davon dann noch die hälfte", mapOf("op" to DIV, "b" to 2), heldOut = true),
                Example("nimm das ergebnis mal 4", mapOf("op" to MUL, "b" to 4), heldOut = true),
                Example("davon bitte noch 12 abziehen", mapOf("op" to SUB, "b" to 12), heldOut = true),
            ),
        ),
        ExclusiveCommandSpec(
            id = LAST_RESULT,
            templates = patterns(
                "(was|wie viel) war das ergebnis (nochmal)?",
                "wie war das ergebnis (nochmal)?",
                "(was|wie viel) war (das|es) nochmal",
                "(was|wie viel) war (das|es)",
                "wie war das nochmal",
                "was kam (da|dabei)? raus",
                "(das|dein|mein|letztes) ergebnis (bitte|nochmal)?",
                "(sag|nenn) mir (nochmal)? das ergebnis",
                "was (hab|habe) ich (zuletzt)? gerechnet",
                "was haben wir (zuletzt)? gerechnet",
            ),
            description = "Sagt das zuletzt gerechnete Ergebnis noch einmal.",
            examples = listOf(
                Example("was war das ergebnis"),
                Example("wie war das ergebnis nochmal"),
                Example("wie viel war das nochmal"),
                Example("wie war das nochmal"),
                Example("was kam raus"),
                Example("das ergebnis bitte"),
                Example("sag mir nochmal das ergebnis"),
                Example("was hab ich zuletzt gerechnet"),
                Example("wie war nochmal die zahl von vorhin", matchedByTemplates = false),
                Example("was kam da nochmal raus", heldOut = true),
                Example("wie hieß das ergebnis gleich wieder", heldOut = true),
                Example("nochmal die zahl bitte", heldOut = true),
            ),
        ),
        ExclusiveCommandSpec(
            id = CLEAR,
            templates = patterns(
                "(vergiss|vergesse|lösch|lösche|loesch|loesche) (das|die|den|mein|dein)? " +
                    "(ergebnis|zahl|rechnung)",
                "(rechner|taschenrechner) (zurücksetzen|zuruecksetzen|leeren|reset)",
                "(fang|fange) (neu|von vorne|noch mal) an",
                "(neu|von vorne) anfangen",
            ),
            description = "Vergisst das letzte Ergebnis.",
            examples = listOf(
                Example("vergiss das ergebnis"),
                Example("vergiss die zahl"),
                Example("lösche die rechnung"),
                Example("rechner zurücksetzen"),
                Example("fang neu an"),
                Example("von vorne anfangen"),
            ),
        ),
    )

    override suspend fun onStart(ctx: SockContext) {
        context = ctx
        ctx.config.getInt(ResultMemory.CONFIG_KEY)?.let { minutes ->
            memory = ResultMemory(clock, ResultMemory.ttlFrom(minutes))
        }
    }

    override suspend fun onStop() {
        context = null
        pending.clear()
    }

    override suspend fun handle(invocation: CommandInvocation): SockResult =
        when (invocation.commandId) {
            CALCULATE -> calculate(invocation)
            CONTINUE_WITH -> continueWith(invocation)
            LAST_RESULT -> lastResult()
            CLEAR -> clear()
            // Unreachable: the dispatcher only routes commands this Sock owns. Reported as a
            // bug rather than silently swallowed.
            else -> SockResult.NotForMe
        }

    /** The turn moved on. Drop the half-built sum. */
    override suspend fun onAskCancelled(token: String) {
        pending.remove(token)
    }

    /**
     * `calculator.calculate` — everything said in one breath, or all but the last number.
     *
     * The two paths converge before any arithmetic happens: whichever operand arrived late,
     * [finish] sees a complete sum.
     */
    private fun calculate(invocation: CommandInvocation): SockResult {
        invocation.answering?.let { token ->
            // Core routes an answer straight back here, but the state it answers is ours. A
            // token we no longer hold means the turn moved on without us, and there is nothing
            // sensible to say about a sum we cannot reconstruct.
            val held = pending.remove(token) ?: return SockResult.Failed(LOST_THE_THREAD)
            val b = invocation.intOrNull("b") ?: return SockResult.Failed(LOST_THE_THREAD)
            val a = held.a ?: return SockResult.Failed(LOST_THE_THREAD)
            return finish(a, held.op, b.toLong())
        }

        val a = invocation.intOrNull("a") ?: return SockResult.Failed(CANNOT_CALCULATE)
        val op = Operation.of(invocation.textOrNull("op")) ?: return SockResult.Failed(CANNOT_CALCULATE)
        val b = invocation.intOrNull("b") ?: return askForOperand(a.toDouble(), op)
        return finish(a.toDouble(), op, b.toLong())
    }

    /**
     * `calculator.continue_with` — an operator and one number, continuing from the last result.
     *
     * When there is no last result (nothing yet, or it has gone stale — see [ResultMemory]),
     * this asks rather than refuses. "Mal 2" is a perfectly clear instruction with one thing
     * missing, and "Ich habe noch nichts gerechnet" would be a dead end where one short
     * question finishes the job.
     */
    private fun continueWith(invocation: CommandInvocation): SockResult {
        invocation.answering?.let { token ->
            val held = pending.remove(token) ?: return SockResult.Failed(LOST_THE_THREAD)
            val a = invocation.intOrNull("a") ?: return SockResult.Failed(LOST_THE_THREAD)
            val b = held.b ?: return SockResult.Failed(LOST_THE_THREAD)
            return finish(a.toDouble(), held.op, b)
        }

        val op = Operation.of(invocation.textOrNull("op")) ?: return SockResult.Failed(CANNOT_CALCULATE)
        val b = invocation.intOrNull("b") ?: return SockResult.Failed(CANNOT_CALCULATE)
        val a = memory.recall() ?: return askForStart(op, b.toLong())
        return finish(a, op, b.toLong())
    }

    private fun lastResult(): SockResult {
        val last = memory.recall() ?: return SockResult.Spoken(NOTHING_YET)
        return SockResult.Spoken("Das Ergebnis war ${CalcNumber.speak(last)}.")
    }

    private fun clear(): SockResult {
        val had = memory.forget()
        _state.value = CalculatorState()
        return SockResult.Spoken(if (had) "Vergessen." else NOTHING_TO_FORGET)
    }

    /**
     * The one place a result is spoken, remembered and drawn.
     *
     * A refused sum touches none of the three: the memory still holds whatever it held, so
     * "durch null" does not also cost the number somebody was working with.
     */
    private fun finish(a: Double, op: Operation, b: Long): SockResult =
        when (val outcome = Arithmetic.evaluate(a, op, b)) {
            is CalcResult.Refused -> SockResult.Failed(outcome.message)

            is CalcResult.Value -> {
                memory.remember(outcome.value)
                _state.value = CalculatorState(outcome.sentence, outcome.value)
                // The panel shows the sum written out, which is the half of the answer that
                // survives being misheard.
                context?.screen?.wakeFor(screenWakeSeconds)
                SockResult.Spoken(outcome.sentence)
            }
        }

    /** "3 plus was?" — the second operand was not said. */
    private fun askForOperand(a: Double, op: Operation): SockResult {
        val token = stash(Pending(a, op, null))
        return SockResult.Asked(
            text = "${CalcNumber.speak(a)} ${op.spoken} was?",
            follow = FollowUp(
                commandId = CALCULATE,
                // Bare in a scoped palette, which is legal there and reckless anywhere else:
                // these templates are only ever tried against the one utterance that answers
                // this question (`socks.specs/README.md` §5).
                templates = patterns("(durch|mit|um|plus|mal|minus|hoch)? {b:int}"),
                params = listOf(ParamSpec("b", ParamType.Integer)),
                token = token,
            ),
        )
    }

    /** "Mal 2 — von welcher Zahl?" — a continuation with nothing to continue from. */
    private fun askForStart(op: Operation, b: Long): SockResult {
        val token = stash(Pending(null, op, b))
        return SockResult.Asked(
            text = "${op.spoken} ${CalcNumber.speak(b.toDouble())} — von welcher Zahl?",
            follow = FollowUp(
                commandId = CONTINUE_WITH,
                templates = patterns("(von|ab)? {a:int}"),
                params = listOf(ParamSpec("a", ParamType.Integer)),
                token = token,
            ),
        )
    }

    private fun stash(half: Pending): String {
        val token = "sum-${askCounter.incrementAndGet()}"
        pending[token] = half
        return token
    }

    companion object {
        const val CALCULATE: String = "calculator.calculate"
        const val CONTINUE_WITH: String = "calculator.continue_with"
        const val LAST_RESULT: String = "calculator.last_result"
        const val CLEAR: String = "calculator.clear"

        const val DEFAULT_SCREEN_WAKE_SECONDS: Int = 30

        /** German copy, verbatim from the spec (§3–§6). */
        const val CANNOT_CALCULATE: String = "Das kann ich nicht rechnen."
        const val LOST_THE_THREAD: String = "Ich weiß nicht mehr, was ich rechnen sollte."
        const val NOTHING_YET: String = "Ich habe noch nichts gerechnet."
        const val NOTHING_TO_FORGET: String = "Da war nichts zu vergessen."

        /** `op` values, spelled once so a template and its example cannot drift apart. */
        private val ADD = Operation.PLUS.value
        private val SUB = Operation.MINUS.value
        private val MUL = Operation.TIMES.value
        private val DIV = Operation.DIVIDE.value
        private val POW = Operation.POWER.value
        private val MOD = Operation.MODULO.value
        private val INT_DIV = Operation.INT_DIVIDE.value

        /**
         * The optional run-up to a sum.
         *
         * Optional because "3 plus 5" on its own is the commonest thing anybody says to a
         * calculator, and a template that insists on "wie viel ist" would miss it. Breadth
         * here is nearly free — more templates only enlarge a lookup table
         * (`socks.specs/README.md` §6).
         */
        private const val ASK =
            "((wie viel|wieviel|wie viele|was) (ist|sind|ergibt|ergeben|macht|machen) (denn)?|" +
                "(rechne|berechne) (mir)?|sag mir (mal)? (wie viel|was) (ist|ergibt)?)?"

        /**
         * The optional run-up to a continuation: "und jetzt davon mal 2".
         *
         * Every part optional, so bare "mal 2" works — which is the phrasing that makes the
         * memory worth having in the first place.
         */
        private const val CONT = "(und)? (jetzt|dann|weiter)? (davon|das|vom ergebnis)?"

        /**
         * The verb German strands at the end: "sag mir mal, was 6 mal 7 **ist**".
         *
         * Optional and trailing, so it costs the templates nothing — an omitted optional
         * contributes no keywords, so specificity and match order are unchanged.
         */
        private const val TAIL = "(ist|sind|ergibt|ergeben|macht|machen)?"

        private const val PLUS_OP = "(plus|addiert mit|addiert zu|addiert)"
        private const val MINUS_OP = "(minus|weniger|abzüglich|abzueglich)"
        private const val TIMES_OP = "(mal|multipliziert mit|multipliziert)"
        private const val DIVIDE_OP =
            "(geteilt durch|dividiert durch|geteilt mit|dividiert mit|geteilt|dividiert|durch)"

        /**
         * "hoch" is four characters and therefore fuzzed at tolerance 1, which also hands it
         * "noch", "doch" and "koch" (`socks.specs/README.md` §6). It is claimed anyway, and the
         * reason is the anchoring: a template matches the *whole* utterance, so the cost is
         * only somebody saying "noch 2" and nothing else, with the wake word in front of it.
         * "Noch zwei Minuten" and every other real use of the word sits inside a longer
         * sentence and cannot reach this template. The failure it does allow is a spoken sum
         * that is audibly not what was asked — recoverable by ear in a way a started timer or
         * a stopped song is not.
         */
        private const val POWER_OP = "(hoch|potenziert mit)"
        private const val MODULO_OP = "(modulo|mod)"

        private fun sum(a: Int, op: String, b: Int): Map<String, Any> =
            mapOf("a" to a, "op" to op, "b" to b)
    }
}
