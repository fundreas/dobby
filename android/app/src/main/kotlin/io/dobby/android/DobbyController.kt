package io.dobby.android

import android.util.Log
import io.dobby.android.chat.ChatMessage
import io.dobby.android.chat.Transcript
import io.dobby.android.chat.detailLine
import io.dobby.core.DobbyEngine
import io.dobby.core.dispatch.Dispatcher
import io.dobby.core.dispatch.SockHealth
import io.dobby.core.registry.Introspection
import io.dobby.core.registry.SockRegistry
import io.dobby.core.sock.SockContext
import io.dobby.core.sock.SockResult
import io.dobby.pipeline.ListenCue
import io.dobby.pipeline.wakeword.WakeWordOption
import io.dobby.socks.clock.ClockState
import io.dobby.pipeline.VoiceIo
import io.dobby.pipeline.VoiceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
)

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
    sockContext: (announce: suspend (String) -> Unit) -> SockContext,
) {
    private val transcript = Transcript()

    /** Dispatch is single-file: two overlapping turns would interleave speech. */
    private val turn = Mutex()

    private val thinking = MutableStateFlow(false)

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
            engine = DobbyEngine(
                registry = registry,
                dispatcher = Dispatcher(registry, health),
                // §5.4's flywheel, in the only form it has until Tier 2 exists: every
                // utterance Tier 1 could not match, in logcat, ready to be promoted into a
                // template in the owning Sock's spec.
                onFallthrough = { Log.i(TAG, "fallthrough: $it") },
            )
            summary = "${registry.socks.size} Socks · ${registry.commands.size} Befehle · " +
                "${registry.palette.entries.size} Vorlagen"
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
                detail = detailOf(voice),
                messages = messages,
                summary = summary,
                canListen = pipeline.canListen && engine != null,
                handsFree = armed,
                wakePhrase = pipeline.wakePhrase,
                wakeWords = wakeWordOptions,
                wakeWordId = pipeline.selectedWakeWordId,
                listenCue = pipeline.listenCue,
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
     * A turn is also not always one utterance. An utterance Tier 1 could not match is answered
     * with two buzzes and the microphone stays open for the same window — which is what
     * somebody who has just been buzzed at does anyway: say it again, differently, without
     * waiting to be invited.
     *
     * [VoiceIo.endTurn] closes the turn in a `finally`, because the wake word stays off the
     * microphone until it is called and a turn that throws must not leave it off forever.
     */
    fun listen(): Job = scope.launch {
        turn.withLock {
            try {
                var attempts = 0
                while (true) {
                    transcript.beginListening()
                    val heard = pipeline.listen(SPEECH_WINDOW)
                    if (heard.isNullOrBlank()) {
                        transcript.abandonListening()
                        return@withLock
                    }
                    transcript.heard(heard)
                    if (respondTo(heard)) return@withLock

                    // A television is a speaker that never runs out of unmatched sentences, and
                    // without a bound it would hold the microphone open all evening.
                    if (++attempts >= MAX_CLARIFY_ROUNDS) return@withLock
                }
            } finally {
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
     */
    fun submit(text: String): Job = scope.launch {
        if (text.isBlank()) return@launch
        turn.withLock {
            transcript.heard(text.trim())
            respondTo(text.trim())
        }
    }

    /**
     * Dispatches one utterance and delivers the answer. Returns whether it was understood —
     * which is what decides if the turn is over or if the microphone stays open for another go.
     *
     * "Understood" means Tier 1 matched a command, not that the command succeeded. A timer that
     * fails to start is an answer worth speaking; an utterance nothing claimed is not.
     */
    private suspend fun respondTo(utterance: String): Boolean {
        val dobby = engine ?: return true
        thinking.value = true
        val outcome = try {
            dobby.handle(utterance)
        } finally {
            thinking.value = false
        }

        if (!outcome.wasUnderstood) {
            // Two buzzes instead of the sentence. The chat still shows it, because the chat is
            // the log of what happened and a buzz leaves no mark on it — this is the one place
            // where what is shown and what is said deliberately differ.
            pipeline.buzz()
            val text = (outcome.result as? SockResult.Spoken)?.text ?: DobbyEngine.NOT_UNDERSTOOD
            transcript.said(text, failed = true)
            return false
        }

        when (val result = outcome.result) {
            is SockResult.Spoken -> {
                transcript.said(result.text, outcome.detailLine())
                pipeline.say(result.text)
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

            SockResult.NotForMe -> transcript.said("Das kann ich gerade nicht.", outcome.detailLine(), failed = true)
        }
        return true
    }

    private fun phaseOf(voice: VoiceState, isThinking: Boolean): Phase = when {
        engine == null -> Phase.UNAVAILABLE
        voice is VoiceState.Listening -> Phase.LISTENING
        // Transcription is thinking as far as the panel is concerned: the microphone is shut,
        // nothing is expected of the person, and something is working on what they said.
        voice is VoiceState.Transcribing -> Phase.THINKING
        isThinking -> Phase.THINKING
        voice is VoiceState.Speaking -> Phase.SPEAKING
        voice is VoiceState.Preparing -> Phase.PREPARING
        voice is VoiceState.Unavailable -> Phase.UNAVAILABLE
        voice is VoiceState.Waiting -> Phase.WAITING
        else -> Phase.READY
    }

    private fun detailOf(voice: VoiceState): String = when (voice) {
        is VoiceState.Preparing -> voice.detail
        is VoiceState.Unavailable -> voice.reason
        is VoiceState.Waiting -> "Sag \"${voice.phrase}\""
        // The one live signal a batch recogniser leaves: whether the microphone is picking the
        // speaker up. From three metres away that is the difference between waiting and
        // walking closer.
        is VoiceState.Listening -> if (voice.speaking) "Ich höre dich…" else "Sprich jetzt"
        VoiceState.Transcribing -> "Verstehe…"
        else -> ""
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
         * How many unmatched utterances one turn will sit through: the first, and two retries.
         *
         * Past that, "it is still trying" has become "it is stuck", and the useful thing is to
         * stop and let the person start again on their own terms. It is also the bound that
         * keeps a television from holding the microphone open indefinitely.
         */
        const val MAX_CLARIFY_ROUNDS = 3
    }
}
