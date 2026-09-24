package io.dobby.android.ui

import androidx.annotation.DrawableRes
import io.dobby.android.R
import io.dobby.socks.radio.Station

/**
 * The five stations' own logos, bundled rather than fetched.
 *
 * **Why they are in the APK and not downloaded at runtime.** The station table is a constant
 * (`Stations.ALL`) — five rows that change about as often as the app does — so a logo has
 * nothing to discover. Fetching them would mean an image-loading dependency, a cache, a
 * placeholder state and a panel whose first draw after a cold boot is worse than its second,
 * all for 15 KB of artwork. The wall panel draws this card while music is playing, which is
 * exactly when nobody wants to watch a logo fade in.
 *
 * Provenance, so a dead link a year from now is one lookup and not a hunt (all fetched
 * 2026-09-24, from the stations' own sites via the `browserUuid`s in `Stations`):
 *
 * ```
 * fm4       tubestatic.orf.at/…/tube/fm4/images/touch-icon-iphone-retina.png
 * oe3       tubestatic.orf.at/…/tube/common/images/apple-icons/oe3.png
 * oe1       oe1.orf.at/static/img/logo_oe1.png
 * wien      oekastatic.orf.at/…/oeka/common/images/icon.wie.svg  (hand-converted, see the file)
 * kronehit  kronehit.at/static/base/img/apple-touch-icon.…png
 * ```
 *
 * **Null rather than a house-style placeholder** for a station that has none. The caller falls
 * back to the generic radio glyph, which is the same rule `NowPlaying.clean` follows for the
 * title line: no row here is better than a wrong one, and a future station added to the table
 * without a logo must draw *something* rather than crash on a missing resource.
 */
object StationLogos {

    @DrawableRes
    fun of(station: Station): Int? = BY_ID[station.id]

    private val BY_ID: Map<String, Int> = mapOf(
        "fm4" to R.drawable.station_fm4,
        "oe3" to R.drawable.station_oe3,
        "oe1" to R.drawable.station_oe1,
        "wien" to R.drawable.station_wien,
        "kronehit" to R.drawable.station_kronehit,
    )
}
