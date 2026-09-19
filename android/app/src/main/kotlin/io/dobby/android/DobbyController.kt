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
    sockContext: (announce: suspend (String) -> Unit) -> SockContext,
) {
    private val transcript = Transcript()

    /** Dispatch is single-file: two overlapping turns would interleave speech. */
    private val turn = Mutex()

    private val thinking = MutableStateFlow(false)

    private val health = SockHealth()

    private val wiring = DobbySocks.create({ text -> transcript.note(text) }, hardware)

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

    val state: StateFlow<DobbyUiState> =
        combine(
            pipeline.state,
            transcript.messages,
            thinking,
            pipeline.handsFree,
        ) { voice, messages, isThinking, armed ->
            DobbyUiState(
                phase = phaseOf(voice, isThinking),
                detail = detailOf(voice),
                messages = messages,
                summary = summary,
                canListen = pipeline.canListen && engine != null,
                handsFree = armed,
                wakePhrase = pipeline.wakePhrase,
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
        if (engine != null) pipeline.startHandsFree()
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

    /** Arms or disarms the wake word. */
    fun setHandsFree(enabled: Boolean) {
        if (enabled) pipeline.startHandsFree() else pipeline.stopHandsFree()
    }

    /** Push to talk: one utterance, dispatched and answered. */
    fun listen(): Job = scope.launch {
        turn.withLock {
            transcript.beginListening()
            val mirror = scope.launch {
                pipeline.state.collect { if (it is VoiceState.Listening) transcript.partial(it.partial) }
            }
            val heard = try {
                pipeline.listen()
            } finally {
                mirror.cancel()
            }

            if (heard.isNullOrBlank()) {
                transcript.abandonListening()
                return@withLock
            }
            transcript.heard(heard)
            respondTo(heard)
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

    private suspend fun respondTo(utterance: String) {
        val dobby = engine ?: return
        thinking.value = true
        val outcome = try {
            dobby.handle(utterance)
        } finally {
            thinking.value = false
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
    }

    private fun phaseOf(voice: VoiceState, isThinking: Boolean): Phase = when {
        engine == null -> Phase.UNAVAILABLE
        voice is VoiceState.Listening -> Phase.LISTENING
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
        else -> ""
    }

    private companion object {
        const val TAG = "Dobby"

        /** Long enough to read the answer that is about to appear. */
        const val WAKE_SCREEN_SECONDS = 30
    }
}
