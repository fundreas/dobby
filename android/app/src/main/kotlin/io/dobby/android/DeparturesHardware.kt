package io.dobby.android

import android.content.Context
import io.dobby.socks.departures.DepartureSource
import io.dobby.socks.departures.StationDirectory
import io.dobby.socks.departures.WienerLinien
import io.dobby.socks.departures.WienerLinienStations
import java.io.File

/**
 * The device half of the Departures Sock: the HTTP clients that ask the Wiener Linien.
 *
 * Thinner than [WeatherHardware], and deliberately so — there is no `LocationManager` here and
 * nothing to ask the phone. A departure board is about a stop somebody typed in, not about
 * where the panel is standing, so the whole of the device half is "which client makes the
 * request".
 *
 * It exists anyway, for the rule rather than for the code: a Sock's dependencies are built by
 * the service and handed in, and a Sock that constructed its own `OkHttpClient` on Android
 * would be the one exception somebody copies. Off-device the Sock gets
 * [DepartureSource.NONE] instead and every command still resolves and says it is offline.
 *
 * **Two clients and not one.** [source] is the realtime board, on a three-second budget
 * because a spoken answer is waiting for it; [stations] is the published station list behind
 * the settings picker, downloaded at most once a month and allowed to take its time. They
 * share nothing but the `User-Agent`, and merging them would mean one of the two timeouts
 * being wrong.
 *
 * **Nothing to release.** Every request is one-shot; there is no listener, no player and no
 * buffer held between polls. That is a property of `DeparturesWatch` polling on a timer rather
 * than subscribing to anything (`departures.specs.md` §6), and it is why there is no
 * `release()` here to forget to call.
 */
class DeparturesHardware(context: Context) {

    val source: DepartureSource = WienerLinien()

    /**
     * The station list the settings picker searches (`departures.specs.md` §7).
     *
     * Cached in `filesDir` rather than `cacheDir`: the whole point of the file is that a panel
     * which has been running for six months still opens its picker instantly, and `cacheDir`
     * is the directory Android is allowed to empty whenever it wants to.
     */
    val stations: StationDirectory = WienerLinienStations(
        cache = File(context.applicationContext.filesDir, "wienerlinien-haltestellen.csv"),
    )
}
