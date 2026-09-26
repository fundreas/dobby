package io.dobby.socks.departures

import java.text.Normalizer

/**
 * One station as the **published station list** knows it, which is not the same thing as
 * [Station].
 *
 * [Station] is a configured station: a number the panel asks about and the name the household
 * gave it. This is a candidate — a row out of the Wiener Linien's own CSV, with the official
 * name and the municipality that tells two "Bahnhof"s apart. It becomes a [Station] the moment
 * somebody picks it, and nothing stores it.
 */
data class StationInfo(
    val diva: String,
    /** `PlatformText` — the official name, e.g. "Josefstädter Straße". */
    val name: String,
    /** "Wien" for all but about seventy of them; the rest are why this field is shown. */
    val municipality: String,
) {
    /** What gets written into the config when this row is tapped. */
    fun toStation(): Station = Station(diva, name)
}

/**
 * Every station there is, so somebody can find one by name instead of by number.
 *
 * An interface for the same reason [DepartureSource] is one: [WienerLinienStations] is the
 * only implementation that knows HTTP exists, so the search itself ([StationSearch]) and the
 * settings page that uses it stay reachable with no network — the page then falls back to the
 * DIVA field, which is what it was before this existed.
 *
 * It hands over the **whole list** rather than taking a query, and that is the point: the
 * published list is one 130 KB static file, fetched at most once a month (§6.8), and matching
 * two thousand names against a search box is microseconds of pure Kotlin. A `search(query)`
 * on this interface would be a network round trip per keystroke against an endpoint that has
 * no search — the exact traffic the fair-use rules exist to prevent.
 */
fun interface StationDirectory {

    /**
     * The published station list.
     *
     * @throws DeparturesUnavailable when it cannot be had — never an empty list, which would
     *   be indistinguishable from "Wien has no stations" at the call site.
     */
    suspend fun stations(): List<StationInfo>

    companion object {
        /** Off-device, and in any build with no network. The picker then says so. */
        val NONE: StationDirectory =
            StationDirectory { throw DeparturesUnavailable(DeparturesError.OFFLINE) }
    }
}

/**
 * Name → stations, for the settings picker's search box.
 *
 * Pure, and deliberately so: this is the half of station search that has opinions about German
 * ("wahringer" must find "Währinger Straße"), and opinions are the half worth being able to
 * read and change without a device or a network in the loop.
 *
 * Three tiers and no fuzzy matching. Somebody typing into a wall panel knows what their stop
 * is called; what they do not want is to type all of it, and what they must not get is
 * "Siebenbrunnengasse" ranked above "Siebensterngasse" for `sieben` because an edit distance
 * said so. Prefix beats word-start beats contains; inside a tier Vienna beats the seventy-odd
 * stops that are not in it, and then the shorter name wins — which is what puts "Floridsdorf"
 * above "Floridsdorfer Markt", and Vienna's "Josefstädter Straße" above Baden's "Josefsplatz".
 */
object StationSearch {

    /** Enough to scroll, few enough to draw. `ga` alone matches several hundred. */
    const val LIMIT: Int = 60

    fun find(stations: List<StationInfo>, query: String, limit: Int = LIMIT): List<StationInfo> {
        val needle = fold(query)
        if (needle.isEmpty()) return emptyList()
        return stations
            .mapNotNull { station ->
                val rank = rank(fold(station.name), needle) ?: return@mapNotNull null
                Ranked(station, rank)
            }
            .sortedWith(
                compareBy(
                    { it.rank },
                    // The panel hangs in Vienna. The list covers Schwechat and Baden too,
                    // which is right — the 60-something stops out there are real and
                    // reachable — but they are not what „josef“ was typed for.
                    { if (it.station.municipality == VIENNA) 0 else 1 },
                    { it.station.name.length },
                    { it.station.name },
                ),
            )
            .take(limit)
            .map { it.station }
    }

    private fun rank(name: String, needle: String): Int? = when {
        name.startsWith(needle) -> 0
        // A word start, not just any position: "strasse" should find "Straßenbahn…" stops by
        // their second word, but "asse" should not find every Gasse in Vienna at rank 1.
        name.split(' ', '-', ',', '/').any { it.startsWith(needle) } -> 1
        name.contains(needle) -> 2
        else -> null
    }

    /**
     * The comparison form: lowercase, accents dropped, ß spelled out.
     *
     * Umlauts are dropped rather than transliterated (ä → a, not ae) because that is how
     * people type them when the keyboard is a wall panel's on-screen one — and typing the
     * umlaut still works, because the haystack is folded the same way.
     */
    fun fold(text: String): String = Normalizer
        .normalize(text.lowercase().replace("ß", "ss"), Normalizer.Form.NFD)
        .replace(COMBINING_MARKS, "")
        .trim()

    private val COMBINING_MARKS = Regex("\\p{Mn}+")

    /** `Municipality` for all but about seventy of the two thousand rows. */
    private const val VIENNA = "Wien"

    private class Ranked(val station: StationInfo, val rank: Int)
}

/**
 * The published station list, as a file format.
 *
 * `DIVA;PlatformText;Municipality;MunicipalityID;Longitude;Latitude`, semicolon-separated,
 * UTF-8, CRLF, about two thousand rows — verified live on 2026-09-26. No quoting and no
 * embedded separators in any row, which is why this is a `split` and not a CSV library.
 *
 * Everything malformed is dropped for the same reason [DeparturesConfig.stations] drops it: a
 * DIVA that is not a number cannot be asked about, and a row that is half a row is not worth
 * showing somebody who is trying to find their stop.
 */
object StationCsv {

    /** Named in `departures.specs.md` §7 as where the ids come from. Now also where they are read from. */
    const val URL: String =
        "https://www.wienerlinien.at/ogd_realtime/doku/ogd/wienerlinien-ogd-haltestellen.csv"

    fun parse(text: String): List<StationInfo> = text
        .lineSequence()
        .drop(1) // The header. Its first field is literally "DIVA", which no parse would keep.
        .mapNotNull { line ->
            val fields = line.trim().split(';')
            if (fields.size < 3) return@mapNotNull null
            val diva = fields[0].trim()
            if (diva.isEmpty() || !diva.all { it.isDigit() }) return@mapNotNull null
            val name = fields[1].trim()
            if (name.isEmpty()) return@mapNotNull null
            StationInfo(diva, name, fields[2].trim())
        }
        .distinctBy { it.diva }
        .toList()
}
