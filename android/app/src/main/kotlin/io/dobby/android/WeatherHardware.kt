package io.dobby.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import io.dobby.socks.weather.Coordinates
import io.dobby.socks.weather.DeviceLocation
import io.dobby.socks.weather.OpenMeteo
import io.dobby.socks.weather.WeatherSource
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * The device half of the Weather Sock: where the phone is, and the HTTP client that asks about it.
 *
 * Exactly parallel to [ClockHardware], [RadioHardware] and [SpotifyHardware]: created by
 * [DobbyService], invisible to the Sock — which sees [DeviceLocation] and [WeatherSource] and
 * nothing else.
 *
 * Unlike those three it has **nothing to release**. `LocationManager` is a system service and
 * every request this class makes is one-shot with its own cancellation signal, so there is no
 * listener left registered and no player left holding buffers. That is a property of asking for
 * a position twice in the life of the panel rather than subscribing to it
 * (`weather.specs.md` §6), and it is the reason there is no `release()` here to forget to call.
 *
 * [OpenMeteo] is constructed here for symmetry rather than necessity — it is plain JVM and
 * would work anywhere — but the rule that a Sock's dependencies are built by the service and
 * handed in is worth more than the one exception.
 */
class WeatherHardware(context: Context) {

    private val appContext = context.applicationContext

    val location: DeviceLocation = AndroidLocation(appContext)

    val source: WeatherSource = OpenMeteo()
}

/**
 * `LocationManager`, wrapped so a Sock never sees an Android `Location`.
 *
 * Three decisions, and none of them is the obvious one:
 *
 * **Coarse permission only.** A forecast grid cell is kilometres across, so a fine fix buys
 * nothing and costs the scariest permission dialog Android has. `ACCESS_COARSE_LOCATION` is
 * what the manifest declares and what [granted] checks, and `FUSED_PROVIDER` honours it by
 * returning a deliberately blurred position — which is exactly the position this feature wants.
 *
 * **`getCurrentLocation`, not `requestLocationUpdates`.** A subscription would be a listener to
 * unregister, a battery cost, and a stream of positions for a device screwed to a wall. This
 * asks once, with a cancellation signal, and is done.
 *
 * **A short timeout, and the last known fix behind it.** The spoken `weather.update_location`
 * runs inside the dispatcher's five-second budget, so waiting for a cold GPS fix is not an
 * option; [FIX_TIMEOUT_MS] gives the platform a couple of seconds and then falls back to
 * whatever the system already had. For a panel that has not moved, the last known fix *is* the
 * right answer — and for one that has, two seconds of network location is plenty.
 */
private class AndroidLocation(private val context: Context) : DeviceLocation {

    private val manager = context.getSystemService(LocationManager::class.java)

    override suspend fun current(): Coordinates? {
        // Null covers every reason at once, because the Sock says the same thing to all of
        // them: permission refused, location switched off, no fix in time.
        if (!granted()) return null
        val provider = provider() ?: return lastKnown()
        val fresh = withTimeoutOrNull(FIX_TIMEOUT_MS) { awaitFix(provider) }
        return fresh ?: lastKnown()
    }

    private fun granted(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * The best enabled provider, most accurate first.
     *
     * Fused is the platform's own blend and the right answer on any modern device; network is
     * the fallback on one where it is missing or switched off; GPS is last deliberately —
     * indoors, on a wall, it is the provider least likely to return anything at all, and this
     * panel is indoors on a wall by definition.
     */
    private fun provider(): String? = PROVIDERS.firstOrNull { name ->
        runCatching { manager.isProviderEnabled(name) }.getOrDefault(false)
    }

    private suspend fun awaitFix(provider: String): Coordinates? =
        suspendCancellableCoroutine { continuation ->
            val signal = CancellationSignal()
            continuation.invokeOnCancellation { signal.cancel() }
            try {
                manager.getCurrentLocation(provider, signal, context.mainExecutor) { location ->
                    if (continuation.isActive) continuation.resume(location?.coordinates())
                }
            } catch (e: SecurityException) {
                // The permission was revoked between [granted] and here, which is a real race
                // on a device the user is holding. Not a crash: this is the same "I don't know
                // where I am" the Sock already has a sentence for.
                if (continuation.isActive) continuation.resume(null)
            }
        }

    /** The freshest position the system already had, whoever produced it. */
    private fun lastKnown(): Coordinates? = PROVIDERS
        .mapNotNull { name -> lastKnownFrom(name) }
        .maxByOrNull { it.time }
        ?.coordinates()

    /**
     * One provider's cached fix, or null for any of the three ways asking can fail.
     *
     * `try`/`catch` rather than `runCatching` because lint reads this one: `MissingPermission`
     * is satisfied by an explicit `SecurityException` branch and not by a swallowed `Throwable`,
     * and it is right to insist — the permission really can be revoked between [granted] and
     * here, on a device somebody is holding.
     */
    private fun lastKnownFrom(provider: String): Location? = try {
        manager.getLastKnownLocation(provider)
    } catch (e: SecurityException) {
        null
    } catch (e: IllegalArgumentException) {
        // A provider this device does not have. Fused is standard and network is not.
        null
    }

    private fun Location.coordinates() = Coordinates(latitude, longitude)

    private companion object {
        val PROVIDERS = listOf(
            LocationManager.FUSED_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.GPS_PROVIDER,
        )

        /**
         * How long the platform gets before the last known fix wins.
         *
         * Two and a half seconds, which is most of a dispatch budget of five and leaves room
         * for the Sock to answer. A panel that stands still does not need more than this, and
         * one that has just been carried to another flat can be asked twice.
         */
        const val FIX_TIMEOUT_MS = 2_500L
    }
}
