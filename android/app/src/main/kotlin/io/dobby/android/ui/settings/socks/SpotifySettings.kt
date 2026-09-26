package io.dobby.android.ui.settings.socks

import io.dobby.android.ui.settings.ChoiceRow
import io.dobby.android.ui.settings.Note
import io.dobby.android.ui.settings.SectionLabel
import io.dobby.android.ui.settings.SockSettingsPage
import io.dobby.android.ui.settings.SwitchRow
import io.dobby.socks.spotify.SpotifyConfig

/**
 * The Spotify Sock's page (`spotify.specs.md` §9).
 *
 * Both switches are about what happens when the search result is not obvious, which is most of
 * the interesting behaviour of this Sock — and `ask_when_unsure` exists precisely to be turned
 * off from the room: its trigger is a guess about human patience, and the honest way to find
 * out whether the guess is right is to be able to switch it off without a rebuild.
 */
fun spotifySettings(): SockSettingsPage = SockSettingsPage(
    sockId = "spotify",
    summary = { store ->
        val config = SpotifyConfig(store)
        val ask = if (config.askWhenUnsure) "fragt nach" else "spielt sofort"
        "${marketName(config.market)} · $ask"
    },
) { config ->
    SectionLabel("Wenn mehrere Treffer passen")
    SwitchRow(
        title = "Nachfragen",
        subtitle = "Aus: Dobby spielt einfach den wahrscheinlichsten Treffer.",
        checked = SpotifyConfig(config).askWhenUnsure,
        onCheckedChange = { config.put(SpotifyConfig.ASK_WHEN_UNSURE, it) },
    )
    SwitchRow(
        title = "Titel vor Künstler",
        subtitle = "Aus: „Spiele Queen“ spielt die Band, nicht den gleichnamigen Song.",
        checked = SpotifyConfig(config).preferTrackOverArtist,
        onCheckedChange = { config.put(SpotifyConfig.PREFER_TRACK, it) },
    )

    SectionLabel("Land")
    for ((code, name) in MARKETS) {
        ChoiceRow(
            title = name,
            // Said where it is chosen, because a wrong market is invisible until it is not:
            // the search succeeds and the track then refuses to play.
            subtitle = if (code == SpotifyConfig(config).market) "Suche im Katalog von $name." else null,
            selected = code == SpotifyConfig(config).market,
            onSelect = { config.put(SpotifyConfig.MARKET, code) },
        )
    }

    Note("Angemeldet wird Dobby über die Spotify-App auf diesem Gerät. Hier gibt es kein Login.")
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

private fun marketName(code: String): String =
    MARKETS.firstOrNull { it.first == code }?.second ?: code
