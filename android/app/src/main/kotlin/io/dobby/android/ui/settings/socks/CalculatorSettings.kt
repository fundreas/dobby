package io.dobby.android.ui.settings.socks

import io.dobby.android.ui.settings.Note
import io.dobby.android.ui.settings.SectionLabel
import io.dobby.android.ui.settings.SockSettingsPage
import io.dobby.android.ui.settings.StepperRow
import io.dobby.socks.calculator.ResultMemory

/**
 * The Calculator Sock's page.
 *
 * One setting, and it is the one that makes „und mal drei" mean anything: how long the last
 * result stays the thing a follow-up is about. Short enough and a conversation falls apart
 * mid-sum; long enough and a number from before lunch answers a question asked after it.
 */
fun calculatorSettings(): SockSettingsPage = SockSettingsPage(
    sockId = "calculator",
    summary = { store ->
        val minutes = store.getInt(ResultMemory.CONFIG_KEY)?.takeIf { it > 0 }
            ?: ResultMemory.DEFAULT_TTL.toMinutes().toInt()
        "Ergebnis bleibt $minutes min gültig"
    },
) { config ->
    SectionLabel("Gedächtnis")
    StepperRow(
        title = "Ergebnis merken",
        subtitle = "So lange bezieht sich „und mal drei“ auf die letzte Antwort.",
        value = config.getInt(ResultMemory.CONFIG_KEY)?.takeIf { it > 0 }
            ?: ResultMemory.DEFAULT_TTL.toMinutes().toInt(),
        range = 1..60,
        onChange = { config.put(ResultMemory.CONFIG_KEY, it) },
        unit = "min",
    )

    Note("Danach ist die Rechnung zu Ende und die nächste Zahl beginnt eine neue.")
}
