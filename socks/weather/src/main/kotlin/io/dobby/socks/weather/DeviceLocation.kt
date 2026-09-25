package io.dobby.socks.weather

/**
 * Where the phone thinks it is — the one thing this Sock cannot get for itself.
 *
 * `SockContext` offers playback, the screen, config and a log, and nothing that knows about
 * geography, which is correct: a Sock may not touch an Android API (`socks.specs/README.md`
 * §2), and exactly one Sock has ever wanted a position. So the seam is here, in the Sock's own
 * module, the way [WeatherSource] is and the way `RadioPlayer` and `VolumeControl` are — the
 * device implements it, the Sock sees this and nothing else, and every command still resolves
 * in a terminal with no phone in it.
 *
 * It is asked **twice in the life of the panel**, and that is the whole design of the setup
 * flow: once when somebody presses Setup, and again whenever they tap the card's location chip
 * because the panel has moved. A wall panel does not travel, so polling the position would be
 * a permission prompt, a radio wake-up and a battery cost for a number that is the same every
 * time (`weather.specs.md` §6).
 */
fun interface DeviceLocation {

    /**
     * The current position, or null if it cannot be had.
     *
     * Null covers every reason at once — permission refused, location services off, no fix
     * inside the timeout — because the Sock does the same thing with all of them: it says it
     * could not find out where it is, and leaves the saved location alone.
     *
     * May suspend for a few seconds while the platform gets a fix. Callers are off the turn's
     * critical path: setup is a button press and the refresh loop is its own coroutine.
     */
    suspend fun current(): Coordinates?

    companion object {
        /** Off-device: there is no phone, so there is no position. */
        val NONE: DeviceLocation = DeviceLocation { null }
    }
}
