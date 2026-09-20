package io.dobby.socks.spotify

import io.dobby.core.sock.SockConfigStore

/**
 * The Sock's settings (`spotify.specs.md` §9), read through [SockConfigStore].
 *
 * Read at the moment they are used rather than cached, the way `ClockConfig` is, so a change
 * takes effect on the next command instead of on the next restart.
 *
 * Gone from the spec's first draft: `spotify.auth_token` and `spotify.refresh_token`. Client
 * credentials produce no refresh token and nothing to store (§1), which also deletes the
 * encrypted-storage requirement and the settings-screen login the failure table used to point
 * at.
 */
class SpotifyConfig(private val store: SockConfigStore) {

    /**
     * The country whose catalogue is searched.
     *
     * Not cosmetic: without it the response contains tracks that are not playable here, which
     * look like a successful search and then fail silently at `play(uri)`.
     */
    val market: String
        get() = store.getString(MARKET)?.trim()?.uppercase()?.takeIf { it.length == MARKET_LENGTH }
            ?: DEFAULT_MARKET

    /** False makes a band's name beat a same-named track in the open case (§3). */
    val preferTrackOverArtist: Boolean get() = store.getBoolean(PREFER_TRACK) ?: true

    /**
     * Whether an ambiguous result may become a question (§3).
     *
     * A switch because the trigger is a guess about human patience, and the honest way to find
     * out whether the guess is right is to be able to turn it off without a rebuild — the same
     * argument `Settings.micProfile` makes for the M2b measurement.
     */
    val askWhenUnsure: Boolean get() = store.getBoolean(ASK_WHEN_UNSURE) ?: true

    companion object {
        const val MARKET: String = "spotify.market"
        const val PREFER_TRACK: String = "spotify.prefer_track_over_artist"
        const val ASK_WHEN_UNSURE: String = "spotify.ask_when_unsure"

        /** The panel is in Austria (`dobby-plan.md` §1). */
        const val DEFAULT_MARKET: String = "AT"

        private const val MARKET_LENGTH = 2
    }
}
