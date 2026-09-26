package io.dobby.android.ui.settings.socks

import io.dobby.android.ui.settings.Note
import io.dobby.android.ui.settings.SectionLabel
import io.dobby.android.ui.settings.SockSettingsPage
import io.dobby.android.ui.settings.StepperRow
import io.dobby.socks.weather.WeatherConfig

/**
 * The Weather Sock's page (`weather.specs.md` §9).
 *
 * The **location is not editable here**, on purpose. It is not a preference — it is the answer
 * the phone gave when somebody pressed Setup on the weather card, and the card is where that
 * question belongs, because that is where the permission dialog can be shown. This page reads
 * it back so the number is visible somewhere, and says where to change it.
 */
fun weatherSettings(): SockSettingsPage = SockSettingsPage(
    sockId = "weather",
    summary = { store ->
        val config = WeatherConfig(store)
        val place = config.place
        val where = if (place == null) {
            "Noch nicht eingerichtet"
        } else {
            "%.2f, %.2f".format(place.latitude, place.longitude)
        }
        "$where · alle ${config.refreshInterval.toMinutes()} min"
    },
) { config ->
    SectionLabel("Ort")
    val place = WeatherConfig(config).place
    Note(
        if (place == null) {
            "Noch kein Ort. Auf der Wetterkarte „Einrichten“ antippen — dort darf Dobby nach " +
                "dem Standort fragen, hier nicht."
        } else {
            "%.4f, %.4f — vom Gerät, einmal abgefragt. Ändern: auf der Wetterkarte auf das " +
                "Ortssymbol tippen.".format(place.latitude, place.longitude)
        },
    )

    SectionLabel("Aktualisierung")
    StepperRow(
        title = "Intervall",
        // The number that makes this a fair-use setting and not a preference, said where it
        // is set: Open-Meteo updates its `current` block about this often, and polling faster
        // burns a free service to redraw the same temperature.
        subtitle = "Open-Meteo rechnet selbst etwa alle 10 Minuten neu.",
        value = WeatherConfig(config).refreshInterval.toMinutes().toInt(),
        range = 5..120,
        step = 5,
        onChange = { config.put(WeatherConfig.REFRESH_MINUTES, it) },
        unit = "min",
    )

    Note(
        "Eine gesprochene Frage wartet nur dann auf einen frischen Abruf, wenn der letzte " +
            "älter ist als das Doppelte dieses Intervalls.",
    )
}
