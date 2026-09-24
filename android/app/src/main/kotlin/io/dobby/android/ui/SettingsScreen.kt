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
import io.dobby.core.audio.TurnDuck
import io.dobby.socks.radio.Stations
import io.dobby.pipeline.ListenCue
import io.dobby.pipeline.audio.MicProfile
import io.dobby.pipeline.tts.VoiceModelState
import io.dobby.pipeline.tts.VoiceOption
import io.dobby.pipeline.wakeword.WakeWordOption

/**
 * The panel's settings: which phrase wakes it, which voice answers, whether it is listening at
 * all, how it says so when it starts, and what it does to the music while somebody is talking
 * to it.
 *
 * One screen, no nesting. A wall panel is not a phone — whoever is standing in front of it
 * wants to change the one thing they came for and get back to the conversation.
 *
 * The last two sections are here so that the measurement `m2b-plan.md` B3 asks for can be taken
 * by somebody standing in the room, rather than by somebody rebuilding the app between cells of
 * a 2×2.
 */
@Composable
fun SettingsScreen(
    state: DobbyUiState,
    onBack: () -> Unit,
    onHandsFree: (Boolean) -> Unit,
    onSelect: (String) -> Unit,
    onSelectVoice: (String) -> Unit,
    onListenCue: (ListenCue) -> Unit,
    onTurnDuck: (TurnDuck) -> Unit,
    onMicProfile: (MicProfile) -> Unit,
    onSpotifyMarket: (String) -> Unit,
    onSpotifyPreferTrack: (Boolean) -> Unit,
    onSpotifyAskWhenUnsure: (Boolean) -> Unit,
    onRadioStation: (String) -> Unit,
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
                SectionLabel("Stimme")
                // The voice is also the language switch: each row says which language it
                // answers in, and there is deliberately no second setting to disagree with it.
                Text(
                    "Die Stimme bestimmt auch die Antwortsprache. Befehle sprichst du immer " +
                        "auf Deutsch.",
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            items(state.voices, key = { it.id }) { option ->
                VoiceRow(
                    option = option,
                    selected = option.id == state.voiceId,
                    download = state.voiceState,
                    onSelect = { onSelectVoice(option.id) },
                )
            }

            item {
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

            item {
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                SectionLabel("Musik, während du sprichst")
            }

            items(TurnDuck.entries, key = { it.name }) { mode ->
                ChoiceRow(
                    title = mode.title,
                    subtitle = mode.subtitle,
                    selected = mode == state.turnDuck,
                    onSelect = { onTurnDuck(mode) },
                )
            }

            item {
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                SectionLabel("Mikrofon")
            }

            items(MicProfile.entries, key = { it.name }) { profile ->
                ChoiceRow(
                    title = profile.title,
                    subtitle = profile.subtitle,
                    selected = profile == state.micProfile,
                    onSelect = { onMicProfile(profile) },
                )
            }

            item {
                Text(
                    // Said where it is chosen, because the alternative is somebody switching
                    // profiles, hearing no difference, and concluding the setting does nothing.
                    "Die Mikrofon-Einstellung gilt ab dem nächsten Start.",
                    Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            item {
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                SectionLabel("Spotify")
                SwitchRow(
                    title = "Bei mehreren Versionen nachfragen",
                    // The switch exists because the trigger is a guess about human patience,
                    // and this is the sentence that says what turning it off costs.
                    subtitle = "Aus: Dobby spielt einfach den wahrscheinlichsten Treffer.",
                    checked = state.spotifyAskWhenUnsure,
                    onCheckedChange = onSpotifyAskWhenUnsure,
                )
                SwitchRow(
                    title = "Titel vor Künstler",
                    subtitle = "Aus: \"Spiele Queen\" spielt die Band, nicht den gleichnamigen Song.",
                    checked = state.spotifyPreferTrack,
                    onCheckedChange = onSpotifyPreferTrack,
                )
                SectionLabel("Land")
            }

            items(MARKETS, key = { it.first }) { (code, name) ->
                ChoiceRow(
                    title = name,
                    // Said where it is chosen, because a wrong market is invisible until it
                    // is not: the search succeeds and the track then refuses to play.
                    subtitle = if (code == state.spotifyMarket) "Suche im Katalog von $name." else null,
                    selected = code == state.spotifyMarket,
                    onSelect = { onSpotifyMarket(code) },
                )
            }

            item {
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                SectionLabel("Radio")
            }

            // The one place the UI enumerates stations, and the reason `Stations.ALL` is a list
            // with a stable order rather than a map.
            items(Stations.ALL, key = { it.id }) { station ->
                ChoiceRow(
                    title = station.displayName,
                    subtitle = if (station.id == state.radioStation) {
                        "„Radio an“ spielt diesen Sender."
                    } else {
                        null
                    },
                    selected = station.id == state.radioStation,
                    onSelect = { onRadioStation(station.id) },
                )
            }

            item { Spacer(Modifier.size(16.dp)) }
        }
    }
}

/**
 * The countries the panel's catalogue can come from.
 *
 * A short list rather than every ISO code: the panel hangs in one kitchen, and the honest set
 * of answers is "here, or one of the neighbours somebody actually has an account in".
 */
private val MARKETS: List<Pair<String, String>> = listOf(
    "AT" to "Österreich",
    "DE" to "Deutschland",
    "CH" to "Schweiz",
)

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

private val TurnDuck.title: String
    get() = when (this) {
        TurnDuck.DUCK -> "Leiser"
        TurnDuck.PAUSE -> "Pausieren"
        TurnDuck.DUCK_UNLESS_BLUETOOTH -> "Nichts über Bluetooth"
    }

private val TurnDuck.subtitle: String
    get() = when (this) {
        TurnDuck.DUCK -> "Läuft leise weiter, solange Dobby zuhört."
        TurnDuck.PAUSE -> "Hält an und läuft nach der Antwort weiter. Sicherer, aber gröber."
        // Says both halves, because a setting called "nichts" that sometimes makes the music
        // quieter looks broken unless you are told when it does.
        TurnDuck.DUCK_UNLESS_BLUETOOTH ->
            "Läuft unverändert weiter, solange die Musik auf einem Bluetooth-Lautsprecher " +
                "spielt — der steht woanders, das Mikrofon hört ihn nicht. Sonst leiser."
    }

private val MicProfile.title: String
    get() = when (this) {
        MicProfile.RECOGNITION -> "Erkennung"
        MicProfile.COMMUNICATION -> "Kommunikation"
    }

private val MicProfile.subtitle: String
    get() = when (this) {
        MicProfile.RECOGNITION -> "Unbearbeitet. Am besten im ruhigen Raum — hört aber auch sich selbst."
        MicProfile.COMMUNICATION -> "Telefonie-Kette: Echounterdrückung, Entrauschen, Pegelregelung."
    }

/** A titled switch, for the Sock settings that are genuinely two-valued. */
@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
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

/**
 * One voice, with its download where it can be watched.
 *
 * The subtitle is the voice's own line until something is happening to it, and then it is that
 * — a percentage, or the reason there is no voice. A 114 MB download started by a tap is a
 * minute of a panel that looks like it ignored the tap, unless the row says otherwise.
 */
@Composable
private fun VoiceRow(
    option: VoiceOption,
    selected: Boolean,
    download: VoiceModelState,
    onSelect: () -> Unit,
) {
    ChoiceRow(
        title = option.name,
        subtitle = if (selected) download.subtitle(option) else option.description,
        selected = selected,
        onSelect = onSelect,
    )
}

/** What the selected row says under its name while a download is in flight, or has failed. */
private fun VoiceModelState.subtitle(option: VoiceOption): String = when (this) {
    is VoiceModelState.Downloading -> "Wird geladen… $percent %"
    VoiceModelState.Verifying -> "Wird geprüft…"
    is VoiceModelState.Failed -> "Fehlgeschlagen: $reason"
    VoiceModelState.Absent, is VoiceModelState.Ready -> option.description
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
