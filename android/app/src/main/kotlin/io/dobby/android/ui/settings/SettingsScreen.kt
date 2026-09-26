package io.dobby.android.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.dobby.android.DobbyUiState
import io.dobby.android.data.CommandView
import io.dobby.core.audio.TurnDuck
import io.dobby.pipeline.ListenCue
import io.dobby.pipeline.audio.MicProfile
import io.dobby.pipeline.wakeword.WakeMode

/** The three things a settings screen for this panel is actually asked about. */
private enum class SettingsTab(val label: String) {
    /** Everything that is Dobby's own and would be there with no Socks fitted at all. */
    GENERAL("Allgemein"),

    /** Which Socks are fitted, and each one's own page. */
    SOCKS("Socks"),

    /** What the fitted Socks add up to: every command Dobby currently answers to. */
    COMMANDS("Befehle"),
}

/**
 * Settings: the panel, its Socks, and what they add up to.
 *
 * The screen this replaced was one scroll with nine sections, and it stopped working the moment
 * a Sock could be switched off — because "which Socks are there" and "what does the Radio Sock
 * do" are different questions from "how loud is the chime", and a single list answers the last
 * one well and the first two not at all.
 *
 * So: three tabs, and the split between them is a rule rather than a tidy-up.
 *
 *  - **Allgemein** is what would still be here with no Socks fitted: the wake word, the voice,
 *    the microphone, the music during a turn.
 *  - **Socks** is the fitting itself, plus one page per Sock that has anything to set. A Sock's
 *    own page is written in `ui/settings/socks/` and nothing outside that file knows what is
 *    on it.
 *  - **Befehle** is the result, generated from the live registry — which is why it can only
 *    ever show commands that would really resolve if spoken.
 *
 * Two levels deep and no more, like the help screen: tabs, then a Sock. A wall panel is not a
 * documentation site, and whoever is standing in front of it came to change one thing.
 */
@Composable
fun SettingsScreen(
    state: DobbyUiState,
    onBack: () -> Unit,
    onHandsFree: (Boolean) -> Unit,
    onSelect: (String) -> Unit,
    onWakeMode: (WakeMode) -> Unit,
    onWakePhrase: (String) -> Unit,
    onSelectVoice: (String) -> Unit,
    onListenCue: (ListenCue) -> Unit,
    onTurnDuck: (TurnDuck) -> Unit,
    onMicProfile: (MicProfile) -> Unit,
    onSockEnabled: (String, Boolean) -> Unit,
    onCommandView: (CommandView) -> Unit,
    modifier: Modifier = Modifier,
) {
    var tab by rememberSaveable { mutableStateOf(SettingsTab.GENERAL) }
    var openSock by rememberSaveable { mutableStateOf<String?>(null) }

    // One handler for both levels, so the back gesture retraces the way in.
    BackHandler { if (openSock != null) openSock = null else onBack() }

    val sock = state.socks.firstOrNull { it.id == openSock }

    Column(modifier.fillMaxSize()) {
        Header(
            title = sock?.displayName ?: "Einstellungen",
            onBack = { if (openSock != null) openSock = null else onBack() },
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

        if (sock != null) {
            SockSettings(
                sockId = sock.id,
                displayName = sock.displayName,
                enabled = sock.enabled,
                state = state,
                onEnabled = { onSockEnabled(sock.id, it) },
            )
            return@Column
        }

        PrimaryTabRow(
            selectedTabIndex = tab.ordinal,
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.primary,
        ) {
            for (option in SettingsTab.entries) {
                Tab(
                    selected = option == tab,
                    onClick = { tab = option },
                    text = {
                        Text(
                            option.label,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (option == tab) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    },
                    selectedContentColor = MaterialTheme.colorScheme.primary,
                    unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        when (tab) {
            SettingsTab.GENERAL -> GeneralTab(
                state = state,
                onHandsFree = onHandsFree,
                onSelect = onSelect,
                onWakeMode = onWakeMode,
                onWakePhrase = onWakePhrase,
                onSelectVoice = onSelectVoice,
                onListenCue = onListenCue,
                onTurnDuck = onTurnDuck,
                onMicProfile = onMicProfile,
                modifier = Modifier.weight(1f),
            )

            SettingsTab.SOCKS -> SocksTab(
                socks = state.socks,
                onEnabled = onSockEnabled,
                onOpen = { openSock = it },
                modifier = Modifier.weight(1f),
            )

            SettingsTab.COMMANDS -> CommandsTab(
                groups = state.commandGroups,
                socks = state.socks,
                view = state.commandView,
                onView = onCommandView,
                onEnabled = onSockEnabled,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * One Sock's own page, with the panel's frame around it.
 *
 * The frame is two things the page itself must not have to think about: the scroll, and the
 * banner that appears when the Sock whose settings these are is switched off. Hiding the page
 * for a disabled Sock was the other option and it is the worse one — somebody who has just
 * switched Radio off and wants to change its station should not have to switch it back on to
 * find out where the setting was. So the page stays, and the banner says what is true.
 */
@Composable
private fun SockSettings(
    sockId: String,
    displayName: String,
    enabled: Boolean,
    state: DobbyUiState,
    onEnabled: (Boolean) -> Unit,
) {
    val page = SockSettingsPages.of(sockId)

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding(),
    ) {
        if (!enabled) {
            DisabledBanner(displayName, onEnabled)
        }

        if (page == null) {
            Note("$displayName hat nichts einzustellen.")
        } else {
            with(page) { content(state.sockConfig) }
        }

        Spacer(Modifier.size(24.dp))
    }
}

/** The Sock is off. Says so, and offers the one thing somebody would want from here. */
@Composable
private fun DisabledBanner(displayName: String, onEnabled: (Boolean) -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Row(
            Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "$displayName ist in der Schublade. Die Einstellungen hier werden gespeichert, " +
                    "gelten aber erst, wenn der Sock wieder an ist.",
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { onEnabled(true) }) { Text("Anziehen") }
        }
    }
}

@Composable
private fun Header(title: String, onBack: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
        }
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}
