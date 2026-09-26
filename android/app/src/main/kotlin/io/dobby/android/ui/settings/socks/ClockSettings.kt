package io.dobby.android.ui.settings.socks

import io.dobby.android.ui.settings.ChoiceRow
import io.dobby.android.ui.settings.Note
import io.dobby.android.ui.settings.SectionLabel
import io.dobby.android.ui.settings.SockSettingsPage
import io.dobby.android.ui.settings.StepperRow
import io.dobby.android.ui.settings.SwitchRow
import io.dobby.socks.clock.ChimeSound
import io.dobby.socks.clock.ClockConfig

/**
 * The Clock Sock's page (`clock.specs.md` §11).
 *
 * Everything here is about the timer *ringing*, which is the one part of this Sock that
 * happens in a room somebody may be asleep in. The three chime settings together are the
 * answer to "how insistent should it be", and they are next to each other for that reason
 * rather than sorted by type.
 */
fun clockSettings(): SockSettingsPage = SockSettingsPage(
    sockId = "clock",
    summary = { store ->
        val config = ClockConfig(store)
        val seconds = if (config.showSeconds) " · mit Sekunden" else ""
        "${config.chimeSound.label} · ${config.chimeMaxDurationMs / MILLIS} s lang$seconds"
    },
) { config ->
    SectionLabel("Timer-Ton")
    for (sound in ChimeSound.entries) {
        ChoiceRow(
            title = sound.label,
            subtitle = sound.description,
            selected = sound == ClockConfig(config).chimeSound,
            onSelect = { config.put(ClockConfig.CHIME_SOUND, sound.configValue) },
        )
    }

    SectionLabel("Wie lange er läutet")
    StepperRow(
        title = "Dauer",
        // Said here because the number is a promise about the worst case: a timer nobody is
        // in the room for rings for exactly this long and then stops by itself.
        subtitle = "Danach hört der Timer von allein auf.",
        value = (ClockConfig(config).chimeMaxDurationMs / MILLIS).toInt(),
        range = 5..300,
        step = 5,
        onChange = { config.put(ClockConfig.CHIME_MAX_DURATION_S, it) },
        unit = "s",
    )
    StepperRow(
        title = "Abstand",
        subtitle = "Pause zwischen zwei Schlägen.",
        value = (ClockConfig(config).chimeIntervalMs / MILLIS).toInt(),
        range = 1..30,
        onChange = { config.put(ClockConfig.CHIME_INTERVAL_S, it) },
        unit = "s",
    )

    SectionLabel("Anzeige")
    SwitchRow(
        title = "Sekunden anzeigen",
        // The honest cost, where it is chosen: a second hand is a redraw every second on a
        // screen whose whole point is that it is mostly black and mostly still.
        subtitle = "Die Uhr auf dem Panel zählt mit. Kostet eine Neuzeichnung pro Sekunde.",
        checked = ClockConfig(config).showSeconds,
        onCheckedChange = { config.put(ClockConfig.SHOW_SECONDS, it) },
    )

    Note("Timer und Wecker selbst stellst du gesprochen: „Stell einen Timer auf zehn Minuten“.")
}

private const val MILLIS = 1000L

private val ChimeSound.label: String
    get() = when (this) {
        ChimeSound.GLOCKE -> "Glocke"
        ChimeSound.PIEP -> "Piep"
        ChimeSound.GONG -> "Gong"
    }

private val ChimeSound.description: String
    get() = when (this) {
        ChimeSound.GLOCKE -> "Hell und kurz. Hört man durch die Wohnung."
        ChimeSound.PIEP -> "Trocken und leise. Für Räume, in denen jemand schläft."
        ChimeSound.GONG -> "Tief und lang. Am schwersten zu überhören."
    }
