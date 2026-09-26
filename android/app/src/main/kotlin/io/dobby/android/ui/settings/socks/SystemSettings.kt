package io.dobby.android.ui.settings.socks

import io.dobby.android.ui.settings.Note
import io.dobby.android.ui.settings.SectionLabel
import io.dobby.android.ui.settings.SockSettingsPage
import io.dobby.android.ui.settings.StepperRow
import io.dobby.socks.system.SystemConfig

/**
 * The System Sock's page (`system.specs.md` §8).
 *
 * All four numbers are about one sentence — "lauter" — and what it is worth. The step size is
 * the interesting one: the three phrasings the templates bind ("ein bisschen lauter", "lauter",
 * "viel lauter") are one, two and five steps, so moving this moves all three together and the
 * page says what they come out as.
 */
fun systemSettings(): SockSettingsPage = SockSettingsPage(
    sockId = "system",
    summary = { store ->
        val config = SystemConfig(store)
        "„lauter“ = ${config.defaultSteps * config.stepPercent} % · max. ${config.maxVolumePercent} %"
    },
) { config ->
    SectionLabel("Lautstärke-Schritte")
    StepperRow(
        title = "Ein Schritt",
        subtitle = "Die Einheit, in der „lauter“ und „leiser“ rechnen.",
        value = SystemConfig(config).stepPercent,
        range = 1..25,
        onChange = { config.put(SystemConfig.VOLUME_STEP_PERCENT, it) },
        unit = "%",
    )
    StepperRow(
        title = "„Lauter“",
        subtitle = "Wie viele Schritte ein schlichtes „lauter“ geht.",
        value = SystemConfig(config).defaultSteps,
        range = 1..SystemConfig.MAX_STEPS,
        onChange = { config.put(SystemConfig.VOLUME_DEFAULT_STEPS, it) },
    )
    Note(
        with(SystemConfig(config)) {
            "Macht: „ein bisschen lauter“ $stepPercent %, „lauter“ ${defaultSteps * stepPercent} %, " +
                "„viel lauter“ ${MUCH_LOUDER_STEPS * stepPercent} %."
        },
    )

    SectionLabel("Grenzen")
    StepperRow(
        title = "Obergrenze",
        // A ceiling, so "volle Lautstärke" at three in the morning is still the house's
        // decision and not the speaker's.
        subtitle = "Auch „volle Lautstärke“ geht nicht darüber.",
        value = SystemConfig(config).maxVolumePercent,
        range = 10..SystemConfig.FULL,
        step = 5,
        onChange = { config.put(SystemConfig.MAX_VOLUME_PERCENT, it) },
        unit = "%",
    )
    StepperRow(
        title = "Nach dem Stummschalten",
        subtitle = "Wohin „Ton wieder an“ zurückkehrt, wenn nichts gemerkt wurde.",
        value = SystemConfig(config).unmutePercent,
        range = 5..SystemConfig.FULL,
        step = 5,
        onChange = { config.put(SystemConfig.UNMUTE_PERCENT, it) },
        unit = "%",
    )
}

/** What "viel lauter" binds in the Sock's templates, for the worked example above. */
private const val MUCH_LOUDER_STEPS = 5
