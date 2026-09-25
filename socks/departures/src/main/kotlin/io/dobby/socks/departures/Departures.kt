package io.dobby.socks.departures

import java.time.Instant

/**
 * One configured station: the number the API is asked with, and the name the household uses.
 *
 * A **DIVA** and not an RBL, which is the whole of what changed between the draft spec and
 * this one (`departures.specs.md` §1). An RBL is one platform in one direction — four numbers
 * per station, looked up in a CSV and easy to get wrong. A DIVA is the station: every
 * platform, both directions, all lines, one number, and the response says which DIVA each
 * monitor came from, so it is both the configuration and the grouping key.
 *
 * [label] is typed by whoever set the panel up rather than taken from the response, and that
 * is deliberate: one DIVA answers under several titles — Karlsplatz's U-Bahn platforms report
 * "Karlsplatz" and its tram and bus platforms "Karlsplatz U" — so a card built from the API's
 * own names would draw one station as two. The household's name for it is also the one that
 * makes „ab Josefstädter Straße" a sentence somebody recognises.
 */
data class Station(val diva: String, val label: String) {
    /** What the card and the spoken answer call this station. Never empty. */
    val display: String get() = label.ifBlank { diva }
}

/**
 * What kind of vehicle a line is, as far as German grammar and a colour are concerned.
 *
 * Mapped from the API's `type` (`ptMetro`, `ptTram`, `ptBusCity`, …) rather than guessed from
 * the line name, because the name is exactly what cannot carry it: "1" is a tram, "1A" is a
 * bus, "U1" is a U-Bahn, and "WLB" is a Badner Bahn — four lines whose names differ by a
 * letter and whose articles, colours and platforms do not follow from it.
 */
enum class LineKind {
    METRO,
    TRAM,
    BUS,
    /** The Badner Bahn and anything else on rails that is not a Wiener Linien tram. */
    TRAIN,
    OTHER,
    ;

    companion object {
        /** `ptMetro` → [METRO], and so on. An unknown prefix is [OTHER], never a crash. */
        fun of(type: String?): LineKind {
            val raw = type.orEmpty().lowercase()
            return when {
                raw.contains("metro") -> METRO
                raw.contains("tramwlb") -> TRAIN
                raw.contains("tram") -> TRAM
                raw.contains("bus") -> BUS
                raw.contains("train") || raw.contains("bahn") -> TRAIN
                else -> OTHER
            }
        }
    }
}

/**
 * One line, at one station, going one way — and when it next goes.
 *
 * The unit of everything this Sock says and draws, and the reason the grouping key is
 * *(station, line, destination)* rather than the API's own `direction` code: one station can
 * report the same line and the same destination on two monitors — two platforms, or the same
 * service listed under both direction codes, which the WLB does at Karlsplatz — and to
 * somebody waiting those are one question with one answer. [direction] is kept for the record
 * and is never grouped on.
 *
 * @param countdowns minutes until departure, ascending, realtime-corrected by the API. The
 *   horizon is roughly 70 minutes, and `0` means the vehicle is leaving now.
 */
data class DirectionBoard(
    val station: Station,
    val line: String,
    val towards: String,
    val kind: LineKind,
    /** "H" or "R" on the first monitor that reported this destination. Display only. */
    val direction: String?,
    /** The platform, or null when two monitors disagreed about it. */
    val platform: String?,
    val barrierFree: Boolean,
    val countdowns: List<Int>,
) {
    val next: Int? get() = countdowns.firstOrNull()

    /** The one after the next, which is half of what this Sock exists to say (§3). */
    val overnext: Int? get() = countdowns.getOrNull(1)
}

/** One fetch, whole: every configured station, every line, in the order they were configured. */
data class DepartureBoard(
    val boards: List<DirectionBoard>,
    val fetchedAt: Instant,
) {
    val isEmpty: Boolean get() = boards.all { it.countdowns.isEmpty() }

    /** This station's directions, soonest first. The card draws one block per station. */
    fun at(station: Station): List<DirectionBoard> =
        boards.filter { it.station.diva == station.diva }
            .sortedBy { it.next ?: Int.MAX_VALUE }

    /** Every direction of one line, at every configured station that has it. */
    fun of(line: String): List<DirectionBoard> =
        boards.filter { it.line.equals(line, ignoreCase = true) && it.countdowns.isNotEmpty() }

    /** The line names the API reported, which is what a spoken line is resolved against (§3). */
    fun lineNames(): List<String> = boards.map { it.line }.distinct()
}

/**
 * What the wall panel draws (`departures.specs.md` §5), and the only view anybody outside gets.
 *
 * Four fields in one object rather than a sealed hierarchy, for the reason `WeatherState` gives:
 * three of them are true at once often enough that a card able to draw only one would have to
 * pick the least useful — a panel holding a four-minute-old board, refreshing, and showing the
 * error from the attempt before is an ordinary Tuesday on a kitchen's Wi-Fi.
 */
data class DeparturesState(
    val stations: List<Station> = emptyList(),
    /** The last successful fetch. Survives a failed one — §5, "stale is marked, never blanked". */
    val board: DepartureBoard? = null,
    val refreshing: Boolean = false,
    /** German, drawn rather than spoken (`socks.specs/README.md` §2a). Null when all is well. */
    val error: String? = null,
) {
    val isSetUp: Boolean get() = stations.isNotEmpty()
}
