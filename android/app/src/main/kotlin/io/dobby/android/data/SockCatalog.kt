package io.dobby.android.data

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** How the Commands tab is laid out. Remembered, because it is a way of reading and not a filter. */
enum class CommandView {
    /** Every command of every enabled Sock, in one list, sorted by name. */
    ALPHABETIC,

    /** The same commands, under the Sock that owns them. */
    BY_SOCK,
    ;

    companion object {
        val DEFAULT: CommandView = ALPHABETIC

        /** An unreadable value is an older build's, and falls back rather than throwing. */
        fun of(name: String?): CommandView =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: DEFAULT
    }
}

/**
 * Which Socks are switched on, and how the command list is being read.
 *
 * The **only** thing in the panel that may say a Sock is off. Everything downstream — the
 * registry the engine dispatches through, the palette the template matcher searches, the Tier 2
 * system prompt, the help screen — is derived from this by
 * [io.dobby.android.DobbyController.rebuildLoadout], which is what makes "off" mean *the
 * utterance does not resolve* rather than *the handler declines politely*. A Sock that is off
 * is not in the grammar at all, so the word it used to own is free for somebody else's
 * template, and the model is never shown a tool it is not allowed to call.
 *
 * Reads are in memory and synchronous: the registry is rebuilt on a toggle, and a rebuild that
 * had to wait on a disk read would be a rebuild inside the turn lock waiting on a disk read.
 * Writes are one row on [Dispatchers.IO], after the in-memory state has already changed — the
 * switch moves under the finger, and the database catches up.
 */
interface SockCatalog {

    /** Sock id → switched on. Ids that were never touched are absent and enabled. */
    val enabled: StateFlow<Map<String, Boolean>>

    val commandView: StateFlow<CommandView>

    /** Absence is enabled: a Sock added in a later build is on until somebody says otherwise. */
    fun isEnabled(sockId: String): Boolean = enabled.value[sockId] ?: true

    fun setEnabled(sockId: String, on: Boolean)

    fun setCommandView(view: CommandView)

    companion object {
        /**
         * Everything on, nothing remembered. Off-device and in tests, where there is no
         * database — and the same bargain every other hardware seam in this project makes.
         */
        val ALL_ENABLED: SockCatalog = object : SockCatalog {
            override val enabled: StateFlow<Map<String, Boolean>> =
                MutableStateFlow<Map<String, Boolean>>(emptyMap()).asStateFlow()

            override val commandView: StateFlow<CommandView> =
                MutableStateFlow(CommandView.DEFAULT).asStateFlow()

            override fun setEnabled(sockId: String, on: Boolean) = Unit

            override fun setCommandView(view: CommandView) = Unit
        }
    }
}

/**
 * The database-backed catalogue.
 *
 * The whole table is read once, at construction, on whatever thread builds the service. That is
 * a few rows out of a file the process has open anyway, and the alternative — a suspending load
 * — would mean the first registry build happens before anybody knows which Socks are in it,
 * which is a panel that answers commands for two seconds after boot that it is not supposed to.
 */
class DatabaseSockCatalog(
    private val database: DobbyDatabase,
    private val scope: CoroutineScope,
) : SockCatalog {

    private val switched = MutableStateFlow(read { database.switchedSocks() } ?: emptyMap())

    private val view = MutableStateFlow(
        CommandView.of(read { database.preference(KEY_COMMAND_VIEW) }),
    )

    override val enabled: StateFlow<Map<String, Boolean>> = switched.asStateFlow()

    override val commandView: StateFlow<CommandView> = view.asStateFlow()

    override fun setEnabled(sockId: String, on: Boolean) {
        switched.value = switched.value + (sockId to on)
        write { database.putSock(sockId, on) }
    }

    override fun setCommandView(view: CommandView) {
        this.view.value = view
        write { database.putPreference(KEY_COMMAND_VIEW, view.name) }
    }

    /**
     * A database that will not open costs the *memory* of the setting and nothing else.
     *
     * Storage full, a corrupted file, a device in direct boot: all of them end here, and all of
     * them leave a panel with every Sock switched on — which is the state it shipped in and the
     * state somebody can change again. Refusing to start over a preferences table would be the
     * wrong trade for a wall panel whose job is to answer when spoken to.
     */
    private fun <T> read(block: () -> T): T? = try {
        block()
    } catch (e: RuntimeException) {
        Log.w(TAG, "could not read ${DobbyDatabase.NAME}; every Sock stays on", e)
        null
    }

    private fun write(block: () -> Unit) {
        scope.launch(Dispatchers.IO) {
            try {
                block()
            } catch (e: RuntimeException) {
                // In memory it already happened, and the screen already shows it. What is lost
                // is that it survives a restart, which is worth a log line and not a dialog.
                Log.w(TAG, "could not write ${DobbyDatabase.NAME}", e)
            }
        }
    }

    private companion object {
        const val TAG = "Dobby"
        const val KEY_COMMAND_VIEW = "commands.view"
    }
}
