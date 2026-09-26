package io.dobby.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.dobby.android.DobbyUiState
import io.dobby.android.data.CommandView
import io.dobby.android.ui.settings.CommandsTab

/**
 * Every command Dobby currently answers to, one tap from the dock.
 *
 * The same list as the Commands tab in settings and literally the same composable — there is
 * one generated-from-the-registry command list in this app and there will go on being one.
 * What is different is the way in: "what can I say" is asked standing in front of the panel
 * with a hand on the dock, and three taps through a settings screen is the wrong distance for
 * a question somebody has mid-sentence.
 *
 * It keeps the tab's switches. Somebody who opens this because a command is missing has found
 * the reason — a Sock in the drawer — and the way to fix it in the same row.
 */
@Composable
fun CommandsScreen(
    state: DobbyUiState,
    onBack: () -> Unit,
    onView: (CommandView) -> Unit,
    onEnabled: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)

    Column(modifier.fillMaxSize()) {
        Header(onBack)
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        CommandsTab(
            groups = state.commandGroups,
            socks = state.socks,
            view = state.commandView,
            onView = onView,
            onEnabled = onEnabled,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun Header(onBack: () -> Unit) {
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
            "Befehle",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}
