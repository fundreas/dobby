package io.dobby.android.ui.settings.socks

import io.dobby.android.ui.settings.ChoiceRow
import io.dobby.android.ui.settings.Note
import io.dobby.android.ui.settings.SectionLabel
import io.dobby.android.ui.settings.SockSettingsPage
import io.dobby.android.ui.settings.StepperRow
import io.dobby.socks.radio.RadioConfig
import io.dobby.socks.radio.Stations

/**
 * The Radio Sock's page (`radio.specs.md` §9).
 *
 * One of the two places the UI enumerates stations — the radio card's picker is the other —
 * and the reason `Stations.ALL` is a list with a stable order rather than a map. This one picks
 * what "Radio an" means; that one retunes what is on.
 */
fun radioSettings(): SockSettingsPage = SockSettingsPage(
    sockId = "radio",
    summary = { store ->
        val config = RadioConfig(store)
        "${config.defaultStation.displayName} · ${config.bufferTimeoutMs / MILLIS} s Puffer"
    },
) { config ->
    SectionLabel("„Radio an“ spielt")
    for (station in Stations.ALL) {
        ChoiceRow(
            title = station.displayName,
            subtitle = if (station.id == RadioConfig(config).defaultStation.id) {
                "Der Sender ohne Nachfrage."
            } else {
                null
            },
            selected = station.id == RadioConfig(config).defaultStation.id,
            onSelect = { config.put(RadioConfig.DEFAULT_STATION, station.id) },
        )
    }

    SectionLabel("Verbindung")
    StepperRow(
        title = "Puffer",
        // Says what raising it buys and what it costs, because both are audible: the wait
        // before the first note, against a stream that gives up on a slow moment.
        subtitle = "Wie lange Dobby auf den Stream wartet, bevor er aufgibt.",
        value = (RadioConfig(config).bufferTimeoutMs / MILLIS).toInt(),
        range = 2..60,
        onChange = { config.put(RadioConfig.BUFFER_TIMEOUT_S, it) },
        unit = "s",
    )
    StepperRow(
        title = "Neuversuche",
        subtitle = "Nach dem ersten Fehlschlag: 1 s, 3 s, 9 s …",
        value = RadioConfig(config).reconnectAttempts,
        range = 0..6,
        onChange = { config.put(RadioConfig.RECONNECT_ATTEMPTS, it) },
    )

    Note(
        "Null Neuversuche heißt: ein Versuch, und die Ausweich-URL des Senders entfällt — " +
            "sie ist die letzte Sprosse derselben Leiter.",
    )
}

private const val MILLIS = 1000L
