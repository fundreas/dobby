package io.dobby.android.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class Voice {
    /** What Dobby heard. */
    USER,

    /** What Dobby answered. Exactly the text that was spoken aloud. */
    DOBBY,

    /** Neither — a status note, a validation error, a development Sock's output. */
    SYSTEM,
}

/**
 * One line in the chat view.
 *
 * [detail] is the resolved command and the chain trace. It is the terminal harness's `/trace`
 * output, kept because the reason Phase A had it — "stopp stopped the wrong thing" is invisible
 * in code review and obvious in use (`dobby-plan.md` §9) — is exactly as true on the wall.
 */
data class ChatMessage(
    val id: Long,
    val voice: Voice,
    val text: String,
    val detail: String? = null,
    /** A partial transcript: still being spoken, and replaced on the next frame. */
    val live: Boolean = false,
    val failed: Boolean = false,
)

/**
 * The conversation, as the chat view renders it.
 *
 * Pure state over a [StateFlow] — no Android types, so the awkward parts (a partial transcript
 * that turns into a final one, or into nothing at all when the user says nothing) are unit
 * tests rather than things you find out by talking to a wall.
 */
class Transcript(private val limit: Int = DEFAULT_LIMIT) {

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private var nextId = 0L

    /** Opens the live bubble the partial transcript writes into. */
    fun beginListening() {
        _messages.update { current ->
            current.withoutLive() + ChatMessage(nextId++, Voice.USER, "", live = true)
        }
    }

    /** Vosk's running guess. Called several times a second while someone is speaking. */
    fun partial(text: String) {
        _messages.update { current ->
            val live = current.lastOrNull()?.takeIf { it.live } ?: return@update current
            current.dropLast(1) + live.copy(text = text)
        }
    }

    /** The final transcript. Replaces the live bubble in place, so nothing jumps. */
    fun heard(text: String) {
        _messages.update { current ->
            (current.withoutLive() + ChatMessage(nextId++, Voice.USER, text)).trimmed()
        }
    }

    /** Nothing was said, or the microphone never opened. The live bubble leaves no trace. */
    fun abandonListening() {
        _messages.update { it.withoutLive() }
    }

    fun said(text: String, detail: String? = null, failed: Boolean = false) {
        _messages.update { current ->
            (current.withoutLive() + ChatMessage(nextId++, Voice.DOBBY, text, detail, failed = failed))
                .trimmed()
        }
    }

    fun note(text: String) {
        _messages.update { current ->
            (current.withoutLive() + ChatMessage(nextId++, Voice.SYSTEM, text)).trimmed()
        }
    }

    fun clear() {
        _messages.value = emptyList()
    }

    private fun List<ChatMessage>.withoutLive() = if (lastOrNull()?.live == true) dropLast(1) else this

    /** A wall panel runs for weeks. The scrollback is bounded on purpose. */
    private fun List<ChatMessage>.trimmed() = if (size > limit) takeLast(limit) else this

    companion object {
        const val DEFAULT_LIMIT: Int = 200
    }
}
