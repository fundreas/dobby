package io.dobby.android.data

import io.dobby.core.sock.SockConfigStore

/**
 * A Sock's own settings, as a settings page sees them.
 *
 * This **is** a [SockConfigStore] — the same interface the Sock reads its configuration
 * through — which is the property the whole Socks tab rests on: a page can hand it straight to
 * the Sock's own config class (`RadioConfig(config).defaultStation`, `DeparturesConfig(config)
 * .stations = …`) and get the Sock's own parsing, clamping and defaults for free. The
 * alternative was a settings screen that re-derives "what does an empty `spotify.market` mean"
 * beside every field, and disagrees with the Sock the day one of them changes.
 *
 * Reads go straight through. A write goes through and then tells the controller, which is what
 * turns a tap into a new [revision] and a recomposition — a preferences file has no change
 * notification, and without one the switch would stay where it was until the next unrelated
 * state emission moved it.
 *
 * [revision] is also the whole of this class's identity. Compose compares state objects to
 * decide whether to redraw, and two accessors over the same store are interchangeable in every
 * way except how many writes have happened since.
 */
class SockConfig(
    private val delegate: SockConfigStore,
    private val onWrite: (key: String) -> Unit,
    private val revision: Int,
) : SockConfigStore {

    override fun getString(key: String): String? = delegate.getString(key)

    override fun getInt(key: String): Int? = delegate.getInt(key)

    override fun getBoolean(key: String): Boolean? = delegate.getBoolean(key)

    override fun put(key: String, value: String) {
        delegate.put(key, value)
        onWrite(key)
    }

    fun put(key: String, value: Int) = put(key, value.toString())

    fun put(key: String, value: Boolean) = put(key, value.toString())

    override fun equals(other: Any?): Boolean = other is SockConfig && other.revision == revision

    override fun hashCode(): Int = revision

    companion object {
        /**
         * Nothing stored, nothing writable. The default in [io.dobby.android.DobbyUiState], for
         * the one frame before the controller has emitted a real one and for the previews.
         */
        val EMPTY: SockConfig = SockConfig(
            delegate = object : SockConfigStore {
                override fun getString(key: String): String? = null

                override fun getInt(key: String): Int? = null

                override fun getBoolean(key: String): Boolean? = null

                override fun put(key: String, value: String) = Unit
            },
            onWrite = {},
            revision = -1,
        )
    }
}
