package io.dobby.android.ui.settings.socks

import io.dobby.android.ui.settings.Note
import io.dobby.android.ui.settings.SectionLabel
import io.dobby.android.ui.settings.SockSettingsPage
import io.dobby.android.ui.settings.StepperRow
import io.dobby.socks.memo.MemoConfig

/**
 * The Memo Sock's page (`memo.specs.md` §9).
 *
 * Two numbers, and the first one is a safety setting rather than a convenience: it is how long
 * „das Memo" keeps meaning the one that was just read out, and therefore how long „Memo
 * erledigt" is allowed to throw a note away without asking.
 */
fun memoSettings(): SockSettingsPage = SockSettingsPage(
    sockId = "memo",
    summary = { store ->
        val config = MemoConfig(store)
        "${config.sessionTtl.toMinutes()} min Kontext · max. ${config.maxMemos}"
    },
) { config ->
    SectionLabel("„Das Memo“")
    StepperRow(
        title = "Kontext",
        subtitle = "So lange meint „das Memo“ das zuletzt vorgelesene.",
        value = MemoConfig(config).sessionTtl.toMinutes().toInt(),
        range = 1..60,
        onChange = { config.put(MemoConfig.SESSION_MINUTES, it) },
        unit = "min",
    )
    Note(
        "Danach fragt Dobby nach, statt zu löschen — ein Wort, das eine halbe Stunde später " +
            "im Raum fällt, kann so keine Notiz wegwerfen.",
    )

    SectionLabel("Stapel")
    StepperRow(
        title = "Höchstzahl",
        // The limit is about the listener and not about the disk, and the subtitle says so —
        // otherwise raising it looks free.
        subtitle = "Ist er voll, lehnt Dobby ab, statt das älteste zu überschreiben.",
        value = MemoConfig(config).maxMemos,
        range = 5..200,
        step = 5,
        onChange = { config.put(MemoConfig.MAX_MEMOS, it) },
    )
}
