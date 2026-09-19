package io.dobby.android

import android.util.Log
import io.dobby.android.chat.ChatMessage
import io.dobby.android.chat.Transcript
import io.dobby.android.chat.detailLine
import io.dobby.core.DobbyEngine
import io.dobby.core.Fallthrough
import io.dobby.core.FallthroughLog
import io.dobby.core.audio.TurnAudio
import io.dobby.core.audio.TurnDuck
import io.dobby.core.dispatch.Dispatcher
import io.dobby.core.dispatch.SockHealth
import io.dobby.core.nlu.llm.Tier2
import io.dobby.core.nlu.llm.Tier2Program
import io.dobby.core.nlu.llm.Tier2Request
import io.dobby.core.nlu.llm.Tier2Resolver
import io.dobby.core.registry.Introspection
import io.dobby.core.registry.SockRegistry
import io.dobby.core.sock.SockContext
import io.dobby.core.sock.SockLog
import io.dobby.core.sock.SockResult
import io.dobby.pipeline.ListenCue
import io.dobby.pipeline.audio.MicProfile
import io.dobby.pipeline.wakeword.WakeWordOption
import io.dobby.socks.clock.ClockState
import io.dobby.pipeline.VoiceIo
import io.dobby.pipeline.VoiceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** What the chat view needs to know, in one object. */
data class DobbyUiState(
    val phase: Phase,
    /** Status line: a download percentage, the reason speech is unavailable, or "". */
    val detail: String,
    val messages: List<ChatMessage>,
    /** "2 Socks · 3 Befehle · 11 Vorlagen" — the terminal banner, on the wall. */
    val summary: String,
    val canListen: Boolean,
    /** Whether the wake word is armed. Drives the toggle in the header. */
    val handsFree: Boolean = false,
    /** The phrase the panel answers to, or null when no wake word model loaded. */
    val wakePhrase: String? = null,
    /** Everything settings offers, and which of them is active. */
    val wakeWords: List<WakeWordOption> = emptyList(),
    val wakeWordId: String? = null,
    /** How the panel acknowledges the wake word: a buzz, a pip, or nothing. */
    val listenCue: ListenCue = ListenCue.DEFAULT,
    /** What happens to the music while somebody is talking: quieter, or stopped. */
    val turnDuck: TurnDuck = TurnDuck.DEFAULT,
    /** Which microphone is open. Changing it takes effect at the next start. */
    val micProfile: MicProfile = MicProfile.DEFAULT,
)

/**
 * What the panel is busy with, when it is busy.
 *
 * A plain boolean was enough while thinking took milliseconds. Tier 2 takes seconds, and a
 * spinner over nothing for four seconds is a panel that looks broken — so the state carries
 * *which* kind of thinking it is, and the status line says so.
 */
enum class Thinking {
    NO,

    /** A Sock is running. Milliseconds, usually; nothing worth saying about it. */
    DISPATCH,

    /** The local model is reading the sentence. Seconds, and worth saying. */
    MODEL,
}

enum class Phase {
    PREPARING,
    READY,

    /** Hands-free and armed: the microphone is open, waiting for the wake phrase. */
    WAITING,
    LISTENING,
    THINKING,
    SPEAKING,
    UNAVAILABLE,
}

/**
 * One turn of conversation: microphone → transcript → Phase A engine → speech.
 *
 * Every line of Dobby's *behaviour* is in [io.dobby.core] and the Socks, and every line of
 * hardware is in [VoicePipeline]. What is left here is the join, which is deliberately small:
 * if this class starts growing logic, something has been put on the wrong side of a seam.
 */
class DobbyController(
    private val scope: CoroutineScope,
    private val pipeline: VoiceIo,
    /**
     * Builds the Sock context around the announce callback, which cannot exist before this
     * object does — a Sock announcing a fired timer reaches the transcript and the speaker,
     * both of which live here. Injected rather than constructed so a test can hand over
     * core's own `FakeSockContext`.
     */
    /**
     * The Clock Sock's `AlarmManager` and `SoundPool`. Null in tests and in any build where the
     * device half does not exist; the timer then still counts, it just chimes into the void.
     */
    hardware: ClockHardware? = null,
    /** Remembers the wake phrase and whether it is armed, across restarts. */
    private val settings: Settings? = null,
    /**
     * The local model, or null on a device that cannot run one.
     *
     * Constructed by the caller rather than here, because it is the caller that owns the
     * lifecycle a 1 GB mmap needs — and because a null here is the whole of "Tier 2 degrades to
     * Tier 1", with no branch anywhere else in this class.
     */
    private val tier2Resolver: Tier2Resolver? = null,
    /**
     * Quietens whatever is playing for the length of a turn.
     *
     * Here rather than in [VoicePipeline], which knows nothing about Socks or playback and
     * should keep not knowing, and rather than in `PlaybackCoordinator`, which is Sock-facing
     * and single-slot. [TurnAudio.NONE] off-device, where there is nothing to duck.
     */
    private val turnAudio: TurnAudio = TurnAudio.NONE,
    sockContext: (announce: suspend (String) -> Unit) -> SockContext,
) {
    private val transcript = Transcript()

    /** Dispatch is single-file: two overlapping turns would interleave speech. */
    private val turn = Mutex()

    private val thinking = MutableStateFlow(Thinking.NO)

    /**
     * Utterances Tier 1 could not match, bounded and in memory. Readable by the settings screen.
     *
     * See [FallthroughLog]'s KDoc for why it is bounded, why it is not persisted, and why that
     * is a decision rather than an omission.
     */
    val fallthrough: FallthroughLog = FallthroughLog()

    private val health = SockHealth()

    private val wiring = DobbySocks.create(hardware)

    /** The panel's clock and timer countdown, straight from the Sock that owns them. */
    val clock: StateFlow<ClockState> get() = wiring.clock.state

    private val registry: SockRegistry?
    private val engine: DobbyEngine?
    private val summary: String

    init {
        val build = SockRegistry.build(wiring.socks)
        registry = build.registry
        if (registry == null) {
            // §3.5: fatal in development, a surfaced degraded state in the field. Crashing a
            // START_STICKY foreground service would restart-loop, so it is surfaced either
            // way — loudly in the log, and in the chat where it cannot be missed.
            build.errors.forEach { Log.e(TAG, "registry: $it") }
            transcript.note("Dobby kann nicht starten — die Socks passen nicht zusammen:")
            build.errors.forEach { transcript.note("• $it") }
            engine = null
            summary = "Registry ungültig"
        } else {
            val introspection = Introspection(registry, health)
            wiring.bindDirectory(introspection)
            registry.checkExamples().forEach { Log.w(TAG, "palette collision: $it") }
            // Never fatal: `lauter` and `stumm` are this shape and are correct. Logged so the
            // judgement behind each one stays visible (`socks.specs/README.md` §6).
            registry.checkSingleKeywordTemplates().forEach { Log.w(TAG, "single keyword: $it") }
            // Null when a Sock declares a few-shot the prompt cannot render, and null when no
            // resolver was supplied at all. Both leave the engine byte-for-byte what it was.
            val program = Tier2Program.ofOrNull(registry, introspection) { Log.w(TAG, it) }
            engine = DobbyEngine(
                registry = registry,
                dispatcher = Dispatcher(registry, health),
                // §5.4's flywheel: every utterance Tier 1 could not match, with what Tier 2
                // made of it, ready to be promoted into a template in the owning Sock's spec.
                onFallthrough = { entry: Fallthrough ->
                    Log.i(TAG, "fallthrough: $entry")
                    fallthrough.record(entry)
                },
                log = object : SockLog {
                    override fun debug(message: String) = Unit

                    override fun warn(message: String, cause: Throwable?) {
                        Log.w(TAG, message, cause)
                    }
                },
                tier2 = if (tier2Resolver != null && program != null) {
                    Tier2(registry, announcing(tier2Resolver), program)
                } else {
                    null
                },
            )
            summary = "${registry.socks.size} Socks · ${registry.commands.size} Befehle · " +
                "${registry.palette.entries.size} Vorlagen"
        }
    }

    /**
     * Wraps a resolver so the panel says "Ich denke nach…" for exactly as long as it thinks.
     *
     * The state flips as `generate` is entered and back as it returns, so the text is true by
     * construction rather than by a timer guessing when the model probably started. A decorator
     * rather than a callback on the resolver itself, so it holds for every implementation —
     * the real one, a scripted one in a test, and whatever replaces them.
     *
     * It wraps a *request*, not a turn, so "Ich denke nach…" covers both Tier 2 steps: the
     * state is set again as the fill request is entered and only cleared once it returns.
     *
     * The engine stays ignorant of the UI: it sees a [Tier2Resolver] and nothing else.
     */
    private fun announcing(resolver: Tier2Resolver): Tier2Resolver = object : Tier2Resolver {
        override val available: Boolean get() = resolver.available

        override val unavailableReason: String? get() = resolver.unavailableReason

        override suspend fun generate(request: Tier2Request): String? {
            thinking.value = Thinking.MODEL
            return try {
                resolver.generate(request)
            } finally {
                // Back to DISPATCH rather than NO: handle() has not returned yet, and a Sock
                // still has to run whatever the model just named.
                thinking.value = Thinking.DISPATCH
            }
        }
    }

    private val sockContext = sockContext { text ->
        // Asynchronous speech from a Sock — a timer firing. It is Dobby talking, so it belongs
        // in the chat exactly like an answer does, just without a command that caused it.
        transcript.said(text)
        pipeline.say(text)
    }

    /**
     * The selectable phrases, and a counter that makes a change to them re-emit.
     *
     * The list is read from a directory rather than a flow — there is nothing to observe, and
     * it changes only when a phrase is selected or a file is pushed. The counter is what turns
     * that into something [combine] notices.
     */
    private var wakeWordOptions: List<WakeWordOption> = emptyList()
    private val refresh = MutableStateFlow(0)

    val state: StateFlow<DobbyUiState> =
        combine(
            pipeline.state,
            transcript.messages,
            thinking,
            pipeline.handsFree,
            refresh,
        ) { voice, messages, isThinking, armed, _ ->
            DobbyUiState(
                phase = phaseOf(voice, isThinking),
                detail = detailOf(voice, isThinking),
                messages = messages,
                summary = summary,
                canListen = pipeline.canListen && engine != null,
                handsFree = armed,
                wakePhrase = pipeline.wakePhrase,
                wakeWords = wakeWordOptions,
                wakeWordId = pipeline.selectedWakeWordId,
                listenCue = pipeline.listenCue,
                // From settings rather than from the pipeline: the pipeline took its profile at
                // construction and cannot change it, and the duck is not the pipeline's at all.
                turnDuck = settings?.turnDuck ?: TurnDuck.DEFAULT,
                micProfile = settings?.micProfile ?: MicProfile.DEFAULT,
            )
        }.stateIn(
            scope,
            SharingStarted.Eagerly,
            DobbyUiState(Phase.PREPARING, "Starte…", emptyList(), summary, canListen = false),
        )

    /**
     * Loads the Socks and the models, then arms the wake word.
     *
     * Hands-free is the default because that is what the product is: a panel you talk to. The
     * button remains for a noisy room, and the toggle for when you would rather it not listen.
     */
    suspend fun start() {
        engine?.start(sockContext)

        // Listening starts before prepare() finishes, so a wake word spoken during the first
        // run's download is not lost — it just waits for the turn it triggers.
        scope.launch {
            pipeline.wakeWords.collect { onWakeWord() }
        }

        pipeline.prepare()
        wakeWordOptions = pipeline.wakeWordOptions()
        refresh.value = refresh.value + 1

        // Armed unless someone deliberately turned it off: switching the microphone off is a
        // decision, and a reboot is not a reason to overrule it.
        if (engine != null && settings?.handsFree != false) pipeline.startHandsFree()
    }

    suspend fun stop() {
        engine?.stop()
        pipeline.shutdown()
    }

    /**
     * The wake phrase was heard: wake the screen and take the same turn the button takes.
     *
     * The screen is woken here rather than in the pipeline because the wake lock belongs to
     * `SockContext` (§7.2) — the pipeline has no business holding one, and a Sock asking for
     * the screen and the wake word asking for it must go through the same controller.
     */
    private fun onWakeWord() {
        sockContext.screen.wakeFor(WAKE_SCREEN_SECONDS)
        listen()
    }

    /** Chooses how the wake word is acknowledged. Takes effect on the next one. */
    fun setListenCue(cue: ListenCue) {
        pipeline.listenCue = cue
        settings?.listenCue = cue
        refresh.value = refresh.value + 1
    }

    /**
     * Chooses whether the music ducks or stops for a turn. Takes effect on the next turn.
     *
     * Which of the two is right is a measurement and not a preference — but it is the person
     * standing in the room who takes it, so the switch has to be reachable from the room.
     */
    fun setTurnDuck(mode: TurnDuck) {
        settings?.turnDuck = mode
        refresh.value = refresh.value + 1
    }

    /**
     * Chooses which microphone the panel opens. **Takes effect at the next start.**
     *
     * The source is fixed for the life of the pipeline — [io.dobby.pipeline.audio.AudioSource]
     * is the only thing allowed to open the recorder, and swapping it mid-life is a deaf gap in
     * the middle of whatever was listening. The settings screen says so where it is chosen.
     */
    fun setMicProfile(profile: MicProfile) {
        settings?.micProfile = profile
        refresh.value = refresh.value + 1
    }

    /** Arms or disarms the wake word. */
    fun setHandsFree(enabled: Boolean) {
        if (enabled) pipeline.startHandsFree() else pipeline.stopHandsFree()
        settings?.handsFree = enabled
    }

    /**
     * Switches the phrase the panel answers to.
     *
     * The first use of a phrase downloads its ~200 KB classifier, so this suspends for a moment
     * on a slow connection; the settings screen shows the pipeline's own progress while it does.
     */
    fun selectWakeWord(id: String): Job = scope.launch {
        pipeline.selectWakeWord(id)
        settings?.wakeWordId = id
        wakeWordOptions = pipeline.wakeWordOptions()
        refresh.value = refresh.value + 1
    }

    /**
     * Push to talk: one utterance, dispatched and answered.
     *
     * The live bubble opens before the microphone does and holds "…" until the transcript
     * lands. There is nothing to fill it with in between — Parakeet answers once, for the whole
     * utterance (`dobby-plan.md` §5.2) — so what the panel shows is the status line: whether
     * the VAD can hear a voice, then that the recogniser is running.
     *
     * Every utterance in a turn gets [SPEECH_WINDOW] to *begin*. A wake word that fired at the
     * television is the common case, not the rare one, and a microphone that then stays open
     * for the ten-second hard cap is ten seconds of a panel listening to a room that is not
     * talking to it. Nothing said in the window: the turn ends and the wake word comes back.
     *
     * A turn is also not one utterance. After anything Dobby actually handled the microphone
     * stays open for another window, because the common thing after one instruction is a second
     * one — "wie spät ist es", then "stell einen Timer auf zehn Minuten" — and making somebody
     * say the wake word between them is the difference between talking to a panel and operating
     * it. Three things end a turn instead: silence, a Sock that returns [SockResult.Ended]
     * because what it just said was a finished sentence, and [MAX_CLARIFY_ROUNDS] utterances
     * nothing could match.
     *
     * An utterance Tier 1 could not match is answered with two buzzes and the same open
     * microphone — which is what somebody who has just been buzzed at does anyway: say it
     * again, differently, without waiting to be invited.
     *
     * Whatever is playing is ducked for the whole turn and released in the same `finally` that
     * re-arms the wake word. Without it the microphone hears the panel's own speaker: the VAD
     * never finds its trailing silence, every turn runs to the ten-second cap, and Parakeet is
     * handed a haystack. The duck is what puts the endpoint back within reach — it does nothing
     * for the wake word, which had to be heard before this method was ever called.
     *
     * [VoiceIo.endTurn] closes the turn in a `finally`, because the wake word stays off the
     * microphone until it is called and a turn that throws must not leave it off forever.
     */
    fun listen(): Job = scope.launch {
        turn.withLock {
            // A question left open by the typed path belongs to that exchange, not to this
            // one. Somebody walking up and saying the wake word has not agreed to answer it.
            engine?.endTurn()
            try {
                // Before the first utterance, not before each one: a turn is up to three
                // utterances plus Dobby's spoken answers, and letting the music swell back up
                // in the gaps is worse than not ducking at all — it happens exactly while the
                // person is waiting to speak again. Inside the try so that a duck that somehow
                // fails half-way is still released below.
                turnAudio.duck()
                var attempts = 0
                var window = SPEECH_WINDOW
                while (true) {
                    transcript.beginListening()
                    val heard = pipeline.listen(window)
                    if (heard.isNullOrBlank()) {
                        transcript.abandonListening()
                        return@withLock
                    }
                    transcript.heard(heard)
                    // Tier 2 on the first utterance of a turn only. After a buzz the person is
                    // deliberately rephrasing toward a command they believe exists, which is
                    // Tier 1's best case and not Tier 2's — and a second and third five-second
                    // wait is what turns "it is thinking" into "it is stuck". A whole turn
                    // stays bounded at about fifteen seconds.
                    when (respondTo(heard, useTier2 = attempts == 0)) {
                        // Handled, and the floor stays open for whatever comes next. Back to
                        // the ordinary window: nothing is pending, so this is somebody starting
                        // a sentence again rather than answering a question.
                        TurnOutcome.CONTINUE -> window = SPEECH_WINDOW

                        // A question was asked, so the rest of the turn is somebody thinking
                        // about it. The wider window stays until it is answered: an unmatched
                        // answer leaves the question standing, and a person who is still being
                        // asked something has not been given less time to reply.
                        TurnOutcome.AWAITING_ANSWER -> window = ANSWER_WINDOW

                        // A television is a speaker that never runs out of unmatched sentences,
                        // and without a bound it would hold the microphone open all evening.
                        // It is also what ends a turn the room has stopped taking part in:
                        // continuous listening renews on speech nobody can place at most twice.
                        TurnOutcome.RETRY -> if (++attempts >= MAX_CLARIFY_ROUNDS) return@withLock

                        // The Sock said the conversation is over. Taking it at its word is the
                        // whole point of the result existing.
                        TurnOutcome.CLOSED -> return@withLock
                    }
                }
            } finally {
                // NonCancellable, and first: the scope this runs in is cancelled when the
                // service dies, and a suspending release in a plain finally would be cancelled
                // before it did anything — leaving the panel switched off and the music still
                // quiet, with nothing left running to put it back.
                withContext(NonCancellable) { turnAudio.release() }
                // Both, in this order and in a finally: a question that outlived its turn would
                // be answered by whatever the next wake word picked up (`socks.specs/README.md`
                // §5), and the wake word stays off the microphone until endTurn() puts it back.
                engine?.endTurn()
                pipeline.endTurn()
            }
        }
    }

    /**
     * The same turn, typed.
     *
     * Not a debug affordance: it is how Dobby is usable while the model downloads, on a device
     * with no German voice, and in a room too loud to talk in. It goes through the identical
     * path — normalize, match, dispatch, speak.
     *
     * A question asked here holds the floor for the *next typed line* rather than for a window
     * of time: there is no microphone open and no wake word to come back to, so there is no
     * turn boundary to hang an expiry on. [listen] clears it before opening the microphone, so
     * a question left dangling in the text box can never be answered by whatever somebody says
     * out loud an hour later.
     */
    fun submit(text: String): Job = scope.launch {
        if (text.isBlank()) return@launch
        // No duck: no microphone is open, so there is nothing the music can drown out.
        turn.withLock {
            transcript.heard(text.trim())
            respondTo(text.trim())
        }
    }

    /** What one utterance did to the turn it was spoken in. */
    private enum class TurnOutcome {
        /** Handled. The microphone stays open for the next instruction. */
        CONTINUE,

        /** Not understood. The microphone stays open for another go at the same thing. */
        RETRY,

        /** Dobby asked something back, and core is holding the floor for the reply. */
        AWAITING_ANSWER,

        /** A Sock ended the conversation. Back to the wake word, now. */
        CLOSED,
    }

    /**
     * Dispatches one utterance and delivers the answer, and says what that did to the turn.
     *
     * "Understood" means Tier 1 matched a command, not that the command succeeded. A timer that
     * fails to start is an answer worth speaking; an utterance nothing claimed is not.
     */
    private suspend fun respondTo(utterance: String, useTier2: Boolean = true): TurnOutcome {
        val dobby = engine ?: return TurnOutcome.CLOSED
        thinking.value = Thinking.DISPATCH
        val outcome = try {
            dobby.handle(utterance, useTier2 = useTier2)
        } finally {
            thinking.value = Thinking.NO
        }

        if (!outcome.wasUnderstood) {
            // Two buzzes instead of the sentence. The chat still shows it, because the chat is
            // the log of what happened and a buzz leaves no mark on it — this is the one place
            // where what is shown and what is said deliberately differ.
            pipeline.buzz()
            val text = (outcome.result as? SockResult.Spoken)?.text ?: DobbyEngine.NOT_UNDERSTOOD
            transcript.said(text, failed = true)
            return TurnOutcome.RETRY
        }

        when (val result = outcome.result) {
            is SockResult.Spoken -> {
                transcript.said(result.text, outcome.detailLine())
                pipeline.say(result.text)
            }

            // say() suspends until the sentence has finished playing, so the microphone is
            // reopened by the loop above only once Dobby has stopped talking — otherwise the
            // first thing it would hear answering its question is itself.
            is SockResult.Asked -> {
                transcript.said(result.text, outcome.detailLine())
                pipeline.say(result.text)
                return TurnOutcome.AWAITING_ANSWER
            }

            is SockResult.Failed -> {
                Log.w(TAG, "${outcome.invocation?.commandId} failed", result.cause)
                transcript.said(result.userMessage, outcome.detailLine(), failed = true)
                pipeline.say(result.userMessage)
            }

            // The side effect is its own feedback, but the chat would otherwise look like
            // nothing happened — so the trace line stands in for the answer.
            SockResult.Silent -> outcome.detailLine()?.let { transcript.note(it) }

            // The Sock will speak later, through announce().
            SockResult.Deferred -> outcome.detailLine()?.let { transcript.note(it) }

            // The one result that closes the microphone. Spoken first if there is anything to
            // say — "Gute Nacht" is still an answer, it is just the last one.
            is SockResult.Ended -> {
                val text = result.text
                if (text == null) {
                    outcome.detailLine()?.let { transcript.note(it) }
                } else {
                    transcript.said(text, outcome.detailLine())
                    pipeline.say(text)
                }
                return TurnOutcome.CLOSED
            }

            SockResult.NotForMe -> transcript.said("Das kann ich gerade nicht.", outcome.detailLine(), failed = true)
        }
        return TurnOutcome.CONTINUE
    }

    private fun phaseOf(voice: VoiceState, isThinking: Thinking): Phase = when {
        engine == null -> Phase.UNAVAILABLE
        voice is VoiceState.Listening -> Phase.LISTENING
        // Transcription is thinking as far as the panel is concerned: the microphone is shut,
        // nothing is expected of the person, and something is working on what they said.
        voice is VoiceState.Transcribing -> Phase.THINKING
        isThinking != Thinking.NO -> Phase.THINKING
        voice is VoiceState.Speaking -> Phase.SPEAKING
        voice is VoiceState.Preparing -> Phase.PREPARING
        voice is VoiceState.Unavailable -> Phase.UNAVAILABLE
        voice is VoiceState.Waiting -> Phase.WAITING
        else -> Phase.READY
    }

    private fun detailOf(voice: VoiceState, thinking: Thinking): String = when (voice) {
        is VoiceState.Preparing -> voice.detail
        is VoiceState.Unavailable -> voice.reason
        is VoiceState.Waiting -> "Sag \"${voice.phrase}\""
        // The one live signal a batch recogniser leaves: whether the microphone is picking the
        // speaker up. From three metres away that is the difference between waiting and
        // walking closer.
        is VoiceState.Listening -> if (voice.speaking) "Ich höre dich…" else "Sprich jetzt"
        VoiceState.Transcribing -> "Verstehe…"
        // The four seconds the local model takes, said out loud. The text is true by
        // construction rather than by a timer: the resolver flips the state as it is entered,
        // so the panel says "Ich denke nach" exactly while it is.
        else -> if (thinking == Thinking.MODEL) "Ich denke nach…" else ""
    }

    private companion object {
        const val TAG = "Dobby"

        /** Long enough to read the answer that is about to appear. */
        const val WAKE_SCREEN_SECONDS = 30

        /**
         * How long the microphone stays open waiting for somebody to start talking.
         *
         * Long enough to draw a breath — after the wake word, or after a buzz — and short
         * enough that a panel nobody is talking to any more is back to listening for its name
         * before they have left the room. It is a deadline on *starting* to speak, so a slow
         * sentence is never cut off by it.
         */
        val SPEECH_WINDOW: Duration = 5.seconds

        /**
         * The same deadline, for somebody who was just asked a question.
         *
         * [SPEECH_WINDOW] is tuned for "start talking after a wake word", where the person
         * already knows what they came to say. "Zehn was — Sekunden, Minuten oder Stunden?"
         * is the opposite situation: the panel interrupted with a choice, and the pause
         * before the reply is the person reading it back to themselves. Cutting that off
         * after five seconds turns a question into a dead end, so an answer gets longer.
         */
        val ANSWER_WINDOW: Duration = 8.seconds

        /**
         * How many unmatched utterances one turn will sit through: the first, and two retries.
         *
         * Past that, "it is still trying" has become "it is stuck", and the useful thing is to
         * stop and let the person start again on their own terms. It is also the bound that
         * keeps a television from holding the microphone open indefinitely.
         */
        const val MAX_CLARIFY_ROUNDS = 3
    }
}
