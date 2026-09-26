package io.dobby.android.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.dobby.android.DobbyUiState
import io.dobby.core.audio.TurnDuck
import io.dobby.pipeline.ListenCue
import io.dobby.pipeline.audio.MicProfile
import io.dobby.pipeline.tts.VoiceModelState
import io.dobby.pipeline.tts.VoiceOption
import io.dobby.pipeline.wakeword.WakeMode
import io.dobby.pipeline.wakeword.WakeWordOption

/**
 * The panel's own settings, as opposed to a Sock's.
 *
 * The dividing line is the one [io.dobby.android.Settings] already draws and is worth stating:
 * everything here is *below* the commands. The wake phrase, the voice, the listening cue, what
 * happens to the music during a turn and which microphone is open all apply whatever Socks are
 * fitted, and none of them belongs to anybody's Sock. A setting that would stop existing if a
 * Sock were removed belongs on that Sock's page instead, which is what the Socks tab is for.
 *
 * The last two sections are here so that the measurement `m2b-plan.md` B3 asks for can be taken
 * by somebody standing in the room, rather than by somebody rebuilding the app between cells of
 * a 2×2.
 */
@Composable
fun GeneralTab(
    state: DobbyUiState,
    onHandsFree: (Boolean) -> Unit,
    onSelect: (String) -> Unit,
    onWakeMode: (WakeMode) -> Unit,
    onWakePhrase: (String) -> Unit,
    onSelectVoice: (String) -> Unit,
    onListenCue: (ListenCue) -> Unit,
    onTurnDuck: (TurnDuck) -> Unit,
    onMicProfile: (MicProfile) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier.fillMaxSize().navigationBarsPadding()) {
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
            SectionLabel("Wie Dobby seinen Namen hört")
        }

        items(WakeMode.entries, key = { it.name }) { mode ->
            ChoiceRow(
                title = mode.title,
                subtitle = mode.subtitle,
                selected = mode == state.wakeMode,
                onSelect = { onWakeMode(mode) },
            )
        }

        item { SectionLabel("Wort") }

        // Only the chosen mode's half is shown. Both at once would be two lists of phrases
        // with one of them doing nothing, which is the kind of settings screen where people
        // change the wrong thing and conclude the panel is broken.
        if (state.wakeMode == WakeMode.TRANSCRIPT) {
            item {
                WakePhraseField(phrase = state.spokenWakePhrase, onPhrase = onWakePhrase)
            }
        } else {
            items(state.wakeWords, key = { it.id }) { option ->
                WakeWordRow(
                    option = option,
                    selected = option.phrase == state.wakePhrase,
                    onSelect = { onSelect(option.id) },
                )
            }
        }

        item {
            Note(
                if (state.wakeMode == WakeMode.TRANSCRIPT) {
                    // Where somebody is typing a phrase is where they need to be told that
                    // the recogniser gets a vote: it writes down what it heard, and a name
                    // it has never met comes out spelled the way it sounded. Typing that
                    // spelling in is not a workaround, it is how this is meant to be used.
                    "Dobby hört auf alles, was ungefähr so klingt. Wenn er nicht " +
                        "reagiert: sag den Satz, schau in den Verlauf, was verstanden " +
                        "wurde — und schreib genau das hier hinein."
                } else {
                    // The one thing someone choosing a phrase needs to know, where they are
                    // choosing it: these models were trained on English voices, and the only
                    // way to find out which one survives an Austrian accent in your room is
                    // to try it (dobby-plan.md §5.1).
                    "Alle Modelle sind auf englische Stimmen trainiert. Welches am besten " +
                        "erkannt wird, zeigt sich erst beim Ausprobieren."
                },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            SectionLabel("Stimme")
            // The voice is also the language switch: each row says which language it
            // answers in, and there is deliberately no second setting to disagree with it.
            Note(
                "Die Stimme bestimmt auch die Antwortsprache. Befehle sprichst du immer " +
                    "auf Deutsch.",
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
            // Said where it is chosen, because the alternative is somebody switching
            // profiles, hearing no difference, and concluding the setting does nothing.
            Note("Die Mikrofon-Einstellung gilt ab dem nächsten Start.")
            Spacer(Modifier.size(16.dp))
        }
    }
}

private val WakeMode.title: String
    get() = when (this) {
        WakeMode.CLASSIFIER -> "Weckwort-Modell"
        WakeMode.TRANSCRIPT -> "Spracherkennung"
    }

private val WakeMode.subtitle: String
    get() = when (this) {
        // Says what it costs you — the phrase is not yours to choose — because that is the
        // only reason anybody would leave it.
        WakeMode.CLASSIFIER ->
            "Sparsam und schnell, läuft den ganzen Tag. Aber nur die fertig trainierten " +
                "Wörter von unten."
        // And says what this one costs — the CPU — because it is the reason it is not the
        // default, and somebody who turns it on should know why the panel got warmer.
        WakeMode.TRANSCRIPT ->
            "Beliebiger Satz, und du kannst den Befehl gleich anhängen: „Hey Dobby, spiele " +
                "Musik.“ Braucht dafür dauerhaft mehr Rechenleistung."
    }

/**
 * The typed wake phrase.
 *
 * Committed when the field is left or the keyboard's Done is pressed, rather than on every
 * keystroke: every commit restarts the listener, and restarting it eight times while somebody
 * types "Hey Dobby" is eight holes in the microphone stream for no reason.
 */
@Composable
private fun WakePhraseField(phrase: String, onPhrase: (String) -> Unit) {
    val focus = LocalFocusManager.current
    var draft by remember(phrase) { mutableStateOf(phrase) }
    OutlinedTextField(
        value = draft,
        onValueChange = { draft = it },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .onFocusChanged { if (!it.isFocused && draft.trim() != phrase) onPhrase(draft) },
        label = { Text("Weckwort") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = {
            onPhrase(draft)
            focus.clearFocus()
        }),
    )
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
