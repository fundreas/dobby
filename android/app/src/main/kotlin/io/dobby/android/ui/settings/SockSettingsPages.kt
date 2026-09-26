package io.dobby.android.ui.settings

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import io.dobby.android.data.SockConfig
import io.dobby.android.ui.settings.socks.calculatorSettings
import io.dobby.android.ui.settings.socks.clockSettings
import io.dobby.android.ui.settings.socks.departuresSettings
import io.dobby.android.ui.settings.socks.memoSettings
import io.dobby.android.ui.settings.socks.radioSettings
import io.dobby.android.ui.settings.socks.spotifySettings
import io.dobby.android.ui.settings.socks.systemSettings
import io.dobby.android.ui.settings.socks.weatherSettings
import io.dobby.core.sock.SockConfigStore

/**
 * One Sock's settings, as the Socks tab needs them.
 *
 * A Sock cannot declare this itself and should not be able to. The Sock modules are plain JVM —
 * that is what makes them testable without an emulator, and it is the whole of
 * `socks.specs/README.md` §2 — and a `@Composable` in one of them would drag Compose, and with
 * it Android, across the seam the project is built around. So the Sock owns the settings
 * (its keys, its defaults, its clamping, all in its own `…Config` class, which is what these
 * pages read and write through) and the panel owns the drawing of them.
 *
 * "Each Sock organises its own page" is therefore one file per Sock in
 * `ui/settings/socks/`, and nothing outside that file knows what is on it.
 */
class SockSettingsPage(
    /** Must match [io.dobby.core.sock.Sock.id]; that is how a row finds its page. */
    val sockId: String,
    /**
     * The one line the Socks tab shows under the Sock's name: what is actually set right now.
     *
     * A settings list that says "Einstellungen" under every row has told nobody anything. This
     * says "Ö3 · 10 s Puffer", which is the answer to the question somebody opened the screen
     * with, and often saves them opening the page at all.
     */
    val summary: (SockConfigStore) -> String,
    val content: @Composable ColumnScope.(SockConfig) -> Unit,
)

/**
 * Every Sock that has anything to configure.
 *
 * The list is the answer to "which Socks appear with a chevron": a Sock with no page is a Sock
 * with nothing to set, and it still appears in the Socks tab because it can still be switched
 * off. Conversation, Help and Winky are the three — they have no settings and are not missing
 * any.
 */
object SockSettingsPages {

    private val pages: List<SockSettingsPage> = listOf(
        clockSettings(),
        radioSettings(),
        spotifySettings(),
        weatherSettings(),
        departuresSettings(),
        memoSettings(),
        systemSettings(),
        calculatorSettings(),
    )

    private val bySockId: Map<String, SockSettingsPage> = pages.associateBy { it.sockId }

    fun of(sockId: String): SockSettingsPage? = bySockId[sockId]

    fun has(sockId: String): Boolean = sockId in bySockId
}
