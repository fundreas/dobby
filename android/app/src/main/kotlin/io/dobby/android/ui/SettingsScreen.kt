package io.dobby.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.dobby.android.DobbyUiState
import io.dobby.pipeline.ListenCue
import io.dobby.pipeline.wakeword.WakeWordOption

/**
 * The panel's settings: which phrase wakes it, whether it is listening at all, and how it says
 * so when it starts.
 *
 * One screen, three decisions, no nesting. A wall panel is not a phone — whoever is standing in
 * front of it wants to change the one thing they came for and get back to the conversation.
 */
@Composable
fun SettingsScreen(
    state: DobbyUiState,
    onBack: () -> Unit,
    onHandsFree: (Boolean) -> Unit,
    onSelect: (String) -> Unit,
    onListenCue: (ListenCue) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
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
                "Einstellungen",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

        LazyColumn(Modifier.weight(1f).navigationBarsPadding()) {
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Weckwort",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        Text(
                            if (state.handsFree) {
                                "Dobby hört auf seinen Namen."
                            } else {
                                "Aus — Dobby hört nur auf Knopfdruck."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = state.handsFree,
                        onCheckedChange = onHandsFree,
                        enabled = state.wakePhrase != null,
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                SectionLabel("Wort")
            }

            items(state.wakeWords, key = { it.id }) { option ->
                WakeWordRow(
                    option = option,
                    selected = option.phrase == state.wakePhrase,
                    onSelect = { onSelect(option.id) },
                )
            }

            item {
                Text(
                    // The one thing someone choosing a phrase needs to know, where they are
                    // choosing it: these models were trained on English voices, and the only
                    // way to find out which one survives an Austrian accent in your room is to
                    // try it (dobby-plan.md §5.1).
                    "Alle Modelle sind auf englische Stimmen trainiert. Welches am besten " +
                        "erkannt wird, zeigt sich erst beim Ausprobieren.",
                    Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                SectionLabel("Wenn Dobby zu hören beginnt")
            }

            items(ListenCue.entries, key = { it.name }) { cue ->
                ChoiceRow(
                    title = cue.title,
                    subtitle = cue.subtitle,
                    selected = cue == state.listenCue,
                    onSelect = { onListenCue(cue) },
                )
            }

            item { Spacer(Modifier.size(16.dp)) }
        }
    }
}

private val ListenCue.title: String
    get() = when (this) {
        ListenCue.VIBRATE -> "Vibrieren"
        ListenCue.TONE -> "Ton"
        ListenCue.NONE -> "Nichts"
    }

private val ListenCue.subtitle: String
    get() = when (this) {
        ListenCue.VIBRATE -> "Ein kurzer, kräftiger Impuls. Lautlos."
        ListenCue.TONE -> "Ein kurzer Piep. Hörbar durch den ganzen Raum."
        ListenCue.NONE -> "Kein Signal — nur der Bildschirm geht an."
    }

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        Modifier.padding(start = 20.dp, top = 18.dp, bottom = 6.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun WakeWordRow(option: WakeWordOption, selected: Boolean, onSelect: () -> Unit) {
    ChoiceRow(
        title = option.phrase,
        // A phrase with no URL is a file somebody pushed to the device. Worth saying: it is the
        // only kind that can disappear, and the only kind nobody else has tested.
        subtitle = if (option.url.isEmpty()) "Eigenes Modell · ${option.file}" else null,
        selected = selected,
        onSelect = onSelect,
    )
}

/** One radio option, big enough to hit from a step back with a wet hand. */
@Composable
private fun ChoiceRow(title: String, subtitle: String?, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.size(4.dp))
    }
}
