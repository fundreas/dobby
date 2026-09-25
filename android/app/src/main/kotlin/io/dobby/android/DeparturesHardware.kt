package io.dobby.android

import io.dobby.socks.departures.DepartureSource
import io.dobby.socks.departures.WienerLinien

/**
 * The device half of the Departures Sock: the HTTP client that asks the Wiener Linien.
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
 * **Nothing to release.** Every request is one-shot; there is no listener, no player and no
 * buffer held between polls. That is a property of `DeparturesWatch` polling on a timer rather
 * than subscribing to anything (`departures.specs.md` §6), and it is why there is no
 * `release()` here to forget to call.
 */
class DeparturesHardware {

    val source: DepartureSource = WienerLinien()
}
