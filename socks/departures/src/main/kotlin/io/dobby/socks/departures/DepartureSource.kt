package io.dobby.socks.departures

/**
 * Where a departure board comes from.
 *
 * An interface for the reason every other Sock has one: [WienerLinien] is the only class in
 * this module that knows HTTP exists, so the German copy, the line resolution and the whole
 * command surface stay reachable with no network.
 *
 * It takes the **whole station list** rather than one station, and that is not a convenience —
 * it is fair-use rule 4 (`departures.specs.md` §6) expressed as a type. A source that fetched
 * one station would make "one request per poll" a thing every caller has to remember instead
 * of a thing that cannot be got wrong.
 */
fun interface DepartureSource {

    /**
     * One round trip for every station given.
     *
     * @throws DeparturesUnavailable for every condition the caller can act on, which is all of
     *   them.
     */
    suspend fun fetch(stations: List<Station>): DepartureBoard

    companion object {
        /** Off-device, and in any build with no network permission. Always offline. */
        val NONE: DepartureSource =
            DepartureSource { throw DeparturesUnavailable(DeparturesError.OFFLINE) }
    }
}

/**
 * Why a board did not arrive.
 *
 * Four cases and not one, because two of them are different German sentences and two of them
 * are the difference between trying again in thirty seconds and being told to stop asking —
 * [backsOff] is fair-use rule 6 (`departures.specs.md` §6) as a property of the failure rather
 * than as a condition somebody has to remember to write at the call site.
 */
enum class DeparturesError {
    /** No route to the host. The commonest by far, and the one a panel says plainly. */
    OFFLINE,

    /** A 4xx, a body that did not parse, or a `messageCode` that was not "OK". */
    FAILED,

    /** A 5xx. Their problem, and the one that must not be hammered at while it lasts. */
    SERVER,

    /** 429. What ignoring an unauthenticated endpoint's fair use looks like from the outside. */
    RATE_LIMITED,
    ;

    /** Whether this failure extends the backoff window rather than just costing one tick. */
    val backsOff: Boolean get() = this == SERVER || this == RATE_LIMITED
}

class DeparturesUnavailable(val error: DeparturesError, cause: Throwable? = null) :
    Exception("departures unavailable: $error", cause)
