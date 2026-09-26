package io.dobby.android.ui.settings.socks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.dobby.android.data.SockConfig
import io.dobby.android.ui.settings.Note
import io.dobby.android.ui.settings.SectionLabel
import io.dobby.android.ui.settings.SockSettingsPage
import io.dobby.android.ui.settings.StepperRow
import io.dobby.socks.departures.DeparturesConfig
import io.dobby.socks.departures.DeparturesError
import io.dobby.socks.departures.DeparturesUnavailable
import io.dobby.socks.departures.Station
import io.dobby.socks.departures.StationDirectory
import io.dobby.socks.departures.StationInfo
import io.dobby.socks.departures.StationSearch

/**
 * The published station list, for [StationPicker].
 *
 * A composition local rather than a parameter, because the alternative is threading a Sock's
 * dependency through `SettingsScreen` and the page signature every other Sock shares — nine
 * pages paying for one page's search box. [StationDirectory.NONE] is the default, which is
 * what the previews and an off-device build get: the picker then says it cannot search and
 * the DIVA field underneath it still works.
 */
val LocalStationDirectory = staticCompositionLocalOf { StationDirectory.NONE }

/**
 * The Departures Sock's page (`departures.specs.md` §7).
 *
 * The only Sock whose settings are a *list*, and therefore the only page with an editor on it
 * rather than a column of rows. Everything else about it follows the same rule as the others:
 * it writes through [DeparturesConfig], which is the Sock's own parser, so what the page stores
 * and what the Sock reads cannot drift apart.
 */
fun departuresSettings(): SockSettingsPage = SockSettingsPage(
    sockId = "departures",
    summary = { store ->
        val stations = DeparturesConfig(store).stations
        when (stations.size) {
            0 -> "Noch keine Haltestelle"
            1 -> stations.first().display
            else -> "${stations.first().display} +${stations.size - 1}"
        }
    },
) { config ->
    SectionLabel("Haltestellen")
    StationEditor(config)

    SectionLabel("Ansage")
    StepperRow(
        title = "Abfahrten pro Antwort",
        // The card is the full answer and the speech is the summary (§3) — said here, because
        // raising this turns a one-breath answer into a recital.
        subtitle = "Wenn keine Linie genannt wurde. Die Karte zeigt immer alles.",
        value = DeparturesConfig(config).maxSpokenLines,
        range = 1..8,
        onChange = { config.put(DeparturesConfig.MAX_SPOKEN_LINES, it) },
    )

    SectionLabel("Abruf")
    StepperRow(
        title = "Intervall",
        subtitle = "Nur solange der Bildschirm an ist. Unter ${DeparturesConfig.MIN_INTERVAL_S} s geht nicht.",
        value = DeparturesConfig(config).pollInterval.seconds.toInt(),
        range = DeparturesConfig.MIN_INTERVAL_S..300,
        step = 15,
        onChange = { config.put(DeparturesConfig.MIN_POLL_INTERVAL_S, it) },
        unit = "s",
    )
    Note(
        "Die Wiener Linien erlauben 15 Sekunden; Dobby verdoppelt das. Die Untergrenze ist " +
            "keine Einstellung — sie ist die Bedingung, unter der die Schnittstelle offen ist.",
    )
}

/**
 * The stations the departure card and „wann fährt der nächste U6“ are about.
 *
 * **Found by name, not typed as a number.** The first version of this asked for a DIVA id out
 * of the Wiener Linien's CSV, on the argument that it is a one-time lookup for a panel that
 * does not move. It is — and it is also the one place in the whole panel that asked somebody
 * to leave it, open a spreadsheet and copy an eight-digit number, which is where setting up a
 * wall panel stops and doing data entry starts. The published list is 130 KB and fetched at
 * most once a month ([StationDirectory]), so the search costs about as much as one departure
 * poll and is then free forever.
 *
 * One DIVA covers the whole station: every platform, both directions, all lines. That is why
 * this is one row per *station* rather than the draft spec's two-to-four RBL ids per station,
 * and why adding the second and third station costs one query parameter each (§6.4).
 *
 * **The name stays editable**, by tapping the row. The picker fills in the official
 * `PlatformText`, which is right often enough to be the default and wrong exactly where it
 * matters: „Haltestelle“ on the card should read the way the household says it, and „Josefstädter
 * Straße" is „bei der Bim" to the people standing in front of it. Adding and removing write the
 * whole list at once; renaming rewrites the one entry.
 */
@Composable
private fun StationEditor(config: SockConfig) {
    val stations = DeparturesConfig(config).stations
    var picking by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<String?>(null) }

    for (station in stations) {
        if (renaming == station.diva) {
            StationRename(
                station = station,
                onDone = { name ->
                    DeparturesConfig(config).stations =
                        stations.map { if (it.diva == station.diva) Station(it.diva, name) else it }
                    renaming = null
                },
                onCancel = { renaming = null },
            )
        } else {
            StationRow(
                station = station,
                onRename = { renaming = station.diva },
                onRemove = {
                    DeparturesConfig(config).stations =
                        stations.filterNot { it.diva == station.diva }
                },
            )
        }
    }

    Row(
        Modifier
            .fillMaxWidth()
            .clickable { picking = true }
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(
            "Haltestelle hinzufügen",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }

    if (stations.isEmpty()) {
        Note(
            "Noch keine Haltestelle. Ohne eine sagt Dobby zu jeder Abfahrtsfrage, dass keine " +
                "eingestellt ist — die Karte bleibt leer.",
        )
    } else {
        Note("Zum Umbenennen auf eine Haltestelle tippen. Die Reihenfolge ist die der Karte.")
    }

    if (picking) {
        StationPicker(
            existing = stations,
            onAdd = { picked ->
                // Read back rather than closing over `stations`: the picker stays open for the
                // second and third stop, and the list it must not duplicate is the one after
                // the previous tap, not the one this composition started with.
                val current = DeparturesConfig(config).stations
                if (current.none { it.diva == picked.diva }) {
                    DeparturesConfig(config).stations = current + picked
                }
            },
            onClose = { picking = false },
        )
    }
}

/** One configured station: what it is called, which number it is, and the X that drops it. */
@Composable
private fun StationRow(station: Station, onRename: () -> Unit, onRemove: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onRename)
            .padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                station.display,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                "DIVA ${station.diva}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onRemove) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Haltestelle entfernen",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The same row with its name open for editing.
 *
 * In place rather than in a dialog: it is one field, and a dialog for one field is two taps of
 * ceremony around a rename. An empty name is allowed through — [Station.display] falls back to
 * the id, which is ugly and true, and is the same rule the config has always had.
 */
@Composable
private fun StationRename(station: Station, onDone: (String) -> Unit, onCancel: () -> Unit) {
    val focus = LocalFocusManager.current
    val requester = remember { FocusRequester() }
    var name by remember(station.diva) { mutableStateOf(station.label) }

    LaunchedEffect(station.diva) { requester.requestFocus() }

    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            modifier = Modifier.weight(1f).focusRequester(requester),
            label = { Text("Name für DIVA ${station.diva}") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = {
                    focus.clearFocus()
                    onDone(name.trim())
                },
            ),
        )
        IconButton(
            onClick = {
                focus.clearFocus()
                onDone(name.trim())
            },
        ) {
            Icon(
                Icons.Filled.Check,
                contentDescription = "Name übernehmen",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        IconButton(
            onClick = {
                focus.clearFocus()
                onCancel()
            },
        ) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Abbrechen",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Find a stop by typing its name.
 *
 * A full-screen dialog rather than a third level of the settings navigation, and that is a
 * decision about where the state lives: „welche Haltestelle wird gerade gesucht“ is the
 * departures page's business and nothing else's, and `SettingsScreen`'s two levels — tabs,
 * then a Sock — are the shape the whole screen is argued from. A `Dialog` keeps the back
 * gesture working and leaves that shape alone.
 *
 * **It stays open after an add.** The reason the station list is a list at all (§1) is
 * somebody deciding between the U-Bahn two streets away and the bus at the corner, and those
 * are two searches in one sitting. So a tapped row turns into a tick and the list behind fills
 * up; back is how you leave.
 */
@Composable
private fun StationPicker(existing: List<Station>, onAdd: (Station) -> Unit, onClose: () -> Unit) {
    val directory = LocalStationDirectory.current
    val requester = remember { FocusRequester() }
    var query by remember { mutableStateOf("") }
    var attempt by remember { mutableIntStateOf(0) }
    var all by remember { mutableStateOf<List<StationInfo>?>(null) }
    var error by remember { mutableStateOf<DeparturesError?>(null) }
    // Tracked here as well as in the config, because the config's own list is what the caller
    // writes and this is what the row it was tapped on has to show immediately.
    val added = remember { mutableStateOf(existing.map { it.diva }.toSet()) }

    LaunchedEffect(attempt) {
        error = null
        all = try {
            directory.stations()
        } catch (e: DeparturesUnavailable) {
            error = e.error
            null
        }
    }
    val results = remember(all, query) { StationSearch.find(all.orEmpty(), query) }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            // The dialog draws to the edges and pads itself, like every other screen here —
            // otherwise the keyboard covers the results it was opened to show.
            decorFitsSystemWindows = false,
        ),
    ) {
        LaunchedEffect(Unit) { requester.requestFocus() }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.weight(1f).focusRequester(requester),
                        placeholder = { Text("Haltestelle suchen") },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { query = "" }) {
                                    Icon(Icons.Filled.Close, contentDescription = "Leeren")
                                }
                            }
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

                when {
                    all == null && error == null -> Loading()

                    error != null -> Unavailable(
                        error = error!!,
                        query = query,
                        onRetry = { attempt++ },
                        onAddDiva = { diva ->
                            onAdd(Station(diva, ""))
                            added.value = added.value + diva
                        },
                        alreadyAdded = added.value,
                    )

                    else -> Results(
                        results = results,
                        query = query,
                        total = all?.size ?: 0,
                        added = added.value,
                        onPick = { station ->
                            onAdd(station.toStation())
                            added.value = added.value + station.diva
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun Loading() {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.size(16.dp))
        Text(
            "Haltestellenliste wird geladen…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * No list — and the way out that existed before there was a picker.
 *
 * The search field doubles as the DIVA field here, which is why the offline case is a
 * degradation rather than a dead end: whoever has the number can still type it, and the name
 * is a tap away in the list behind.
 */
@Composable
private fun Unavailable(
    error: DeparturesError,
    query: String,
    onRetry: () -> Unit,
    onAddDiva: (String) -> Unit,
    alreadyAdded: Set<String>,
) {
    val diva = query.trim().takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
    Column(Modifier.fillMaxSize().padding(top = 8.dp)) {
        Note(
            when (error) {
                DeparturesError.OFFLINE ->
                    "Keine Verbindung — die Haltestellenliste konnte nicht geladen werden."
                DeparturesError.RATE_LIMITED, DeparturesError.SERVER ->
                    "Die Wiener Linien antworten gerade nicht. Später nochmal versuchen."
                DeparturesError.FAILED ->
                    "Die Haltestellenliste war nicht lesbar."
            } + " Die DIVA-Nummer kann oben direkt eingetippt werden.",
        )
        Row(Modifier.padding(horizontal = 12.dp)) {
            TextButton(onClick = onRetry) { Text("Erneut versuchen") }
        }
        if (diva != null) {
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            ResultRow(
                title = "DIVA $diva",
                subtitle = "Ohne Namen hinzufügen — umbenennen geht in der Liste",
                added = diva in alreadyAdded,
                onPick = { onAddDiva(diva) },
            )
        }
    }
}

/** What the typing found. */
@Composable
private fun Results(
    results: List<StationInfo>,
    query: String,
    total: Int,
    added: Set<String>,
    onPick: (StationInfo) -> Unit,
) {
    if (query.isBlank()) {
        Note(
            "$total Haltestellen aus der Liste der Wiener Linien. Namen eintippen — " +
                "„florids“ reicht für „Floridsdorf“.",
        )
        return
    }
    if (results.isEmpty()) {
        Note(
            "Keine Haltestelle gefunden. Die Liste kennt nur den offiziellen Namen — " +
                "Wien Mitte heißt darin „Mitte-Landstraße“.",
        )
        return
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        items(results, key = { it.diva }) { station ->
            ResultRow(
                title = station.name,
                subtitle = if (station.municipality.isBlank() || station.municipality == WIEN) {
                    "DIVA ${station.diva}"
                } else {
                    "DIVA ${station.diva} · ${station.municipality}"
                },
                added = station.diva in added,
                onPick = { onPick(station) },
            )
        }
    }
}

/**
 * One candidate.
 *
 * An added row stays where it is, ticked and unclickable, rather than vanishing: a row that
 * disappears under the finger that tapped it reads as a misfire, and the tick is the only
 * confirmation this screen gives — the list it was added to is behind the dialog.
 */
@Composable
private fun ResultRow(title: String, subtitle: String, added: Boolean, onPick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = !added, onClick = onPick),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (added) FontWeight.Normal else FontWeight.Medium,
                    color = if (added) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onBackground
                    },
                )
                Text(
                    if (added) "Schon in der Liste" else subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                if (added) Icons.Filled.Check else Icons.Filled.Add,
                contentDescription = if (added) null else "Hinzufügen",
                tint = if (added) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

private const val WIEN = "Wien"
