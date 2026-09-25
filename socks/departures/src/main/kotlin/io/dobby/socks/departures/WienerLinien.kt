package io.dobby.socks.departures

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.Clock
import java.util.concurrent.TimeUnit

/**
 * `GET /ogd_realtime/monitor` on OkHttp. The only class in this module that knows the internet
 * exists.
 *
 * **No key, no account, no registration** — verified live on 2026-09-25, which is also what
 * makes the fair-use rules load-bearing rather than polite: there is no quota to exceed and no
 * token to revoke, so the only thing standing between a badly written poll loop and an IP ban
 * is this class (`departures.specs.md` §6).
 *
 * Three things about the request are decisions rather than transcription:
 *
 * 1. **`diva` repeated, once per configured station.** One request covers every station, which
 *    is fair-use rule 4 — and the reason [fetch] takes a list rather than being called in a
 *    loop. Two ids returned 16 monitors in the live check; ten would return one response.
 * 2. **`locationStop.properties.name` is the DIVA**, so a batched response is attributable to
 *    the station that asked for it. Without that the batching would be the bug it looks like:
 *    16 monitors and no way to tell which of them is the stop outside the door. `title` is a
 *    platform name — one DIVA reports both "Karlsplatz" and "Karlsplatz U" — so it is never a
 *    key.
 * 3. **`countdown` is the truth, not `timePlanned`.** It is in minutes and already
 *    realtime-corrected, and `timePlanned` is null on an unplanned extra service — a departure
 *    that is really leaving in two minutes and would be dropped by anything that parsed the
 *    timetable instead.
 *
 * The timeouts are short on purpose. A command may fall through to a live fetch when the cache
 * is stale, and the dispatcher gives a handler five seconds total; a panel that spends all five
 * of them holding the microphone open and then says nothing is worse than one that says the
 * Wiener Linien are not answering.
 */
class WienerLinien(
    private val client: OkHttpClient = defaultClient(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: Clock = Clock.systemUTC(),
    private val endpoint: HttpUrl = ENDPOINT.toHttpUrl(),
) : DepartureSource {

    override suspend fun fetch(stations: List<Station>): DepartureBoard {
        if (stations.isEmpty()) return DepartureBoard(emptyList(), clock.instant())
        val url = endpoint.newBuilder()
            .addPathSegments("ogd_realtime/monitor")
            .apply { stations.forEach { addQueryParameter("diva", it.diva) } }
            .build()
        val request = Request.Builder()
            .url(url)
            // Fair-use rule 5. A default OkHttp agent says "okhttp/5.3.0" and nothing about who
            // is asking, which is exactly the traffic an operator blocks first when they go
            // looking for whoever is hammering an open endpoint.
            .header("User-Agent", USER_AGENT)
            .build()
        val body = call(request)
        val parsed = try {
            JSON.decodeFromString(MonitorJson.serializer(), body)
        } catch (e: IllegalArgumentException) {
            throw DeparturesUnavailable(DeparturesError.FAILED, e)
        }
        // The endpoint answers 200 with a body that says it went wrong, so the status code is
        // half the health check. `messageCode` 1 is OK; anything else is a failure wearing a
        // success's clothes.
        if (parsed.message?.messageCode != OK_MESSAGE_CODE) {
            throw DeparturesUnavailable(DeparturesError.FAILED)
        }
        return parsed.toBoard(stations)
    }

    /** One round trip, off the calling thread, with every HTTP condition mapped to a reason. */
    private suspend fun call(request: Request): String = withContext(io) {
        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw DeparturesUnavailable(DeparturesError.OFFLINE, e)
        }
        response.use {
            if (it.isSuccessful) return@withContext it.body.string()
            throw when {
                it.code == TOO_MANY_REQUESTS -> DeparturesUnavailable(DeparturesError.RATE_LIMITED)
                it.code >= SERVER_ERROR -> DeparturesUnavailable(DeparturesError.SERVER)
                else -> DeparturesUnavailable(DeparturesError.FAILED)
            }
        }
    }

    /**
     * The response, turned into the model the rest of the module speaks.
     *
     * The merge is the substance of this method. Several monitors can carry the same line and
     * destination at one station — two platforms, or one service listed under both direction
     * codes — and to somebody waiting they are one question, so their countdowns are pooled,
     * de-duplicated and sorted. The platform survives only when the monitors agreed about it,
     * because a board that names one platform for departures leaving from two is worse than a
     * board that names none.
     *
     * Monitors for a DIVA nobody configured are dropped rather than guessed at. That cannot
     * happen against a healthy server and costs one `firstOrNull` to be sure of.
     */
    private fun MonitorJson.toBoard(stations: List<Station>): DepartureBoard {
        val byDiva = stations.associateBy { it.diva }
        val merged = LinkedHashMap<Key, Builder>()
        for (monitor in data?.monitors.orEmpty()) {
            val station = byDiva[monitor.locationStop?.properties?.name] ?: continue
            for (line in monitor.lines) {
                val name = line.name?.takeIf { it.isNotBlank() } ?: continue
                val towards = line.towards?.trim()?.takeIf { it.isNotBlank() } ?: continue
                val key = Key(station.diva, name.uppercase(), towards.lowercase())
                val countdowns = line.departures?.departure.orEmpty()
                    .mapNotNull { it.departureTime?.countdown }
                    .filter { it >= 0 }
                val builder = merged.getOrPut(key) {
                    Builder(station, name, towards, LineKind.of(line.type), line.direction, line.platform)
                }
                builder.add(countdowns, line.platform, line.barrierFree == true)
            }
        }
        val boards = merged.values.map { it.build() }
        // Configured order, then soonest first inside a station: the card draws stations in the
        // order somebody typed them, and a response that arrives in the API's order would
        // reshuffle the panel every time a monitor moved.
        val order = stations.withIndex().associate { (index, station) -> station.diva to index }
        return DepartureBoard(
            boards = boards.sortedWith(
                compareBy({ order[it.station.diva] ?: Int.MAX_VALUE }, { it.next ?: Int.MAX_VALUE }),
            ),
            fetchedAt = clock.instant(),
        )
    }

    private data class Key(val diva: String, val line: String, val towards: String)

    /** One *(station, line, destination)* while its monitors are still arriving. */
    private class Builder(
        val station: Station,
        val line: String,
        val towards: String,
        val kind: LineKind,
        val direction: String?,
        platform: String?,
    ) {
        private val countdowns = sortedSetOf<Int>()
        private var platform: String? = platform
        private var platformAgrees = true
        private var barrierFree = false

        fun add(more: List<Int>, otherPlatform: String?, accessible: Boolean) {
            countdowns += more
            if (otherPlatform != platform) platformAgrees = false
            // Accessible if any vehicle on this destination is: the question somebody in a
            // wheelchair is asking is "can I take one of these", not "are they all".
            barrierFree = barrierFree || accessible
        }

        fun build(): DirectionBoard = DirectionBoard(
            station = station,
            line = line,
            towards = towards,
            kind = kind,
            direction = direction,
            platform = platform?.takeIf { platformAgrees },
            barrierFree = barrierFree,
            countdowns = countdowns.toList(),
        )
    }

    // The wire format, and nothing else. Nullable everywhere a healthy server may still leave a
    // hole, because `ignoreUnknownKeys` protects against new fields and not against absent
    // values — a null `timePlanned` on an unplanned extra is documented behaviour, not a fault.
    @Serializable
    private data class MonitorJson(
        val data: DataJson? = null,
        val message: MessageJson? = null,
    )

    @Serializable
    private data class MessageJson(
        val value: String? = null,
        val messageCode: Int? = null,
    )

    @Serializable
    private data class DataJson(val monitors: List<MonitorEntryJson> = emptyList())

    @Serializable
    private data class MonitorEntryJson(
        val locationStop: LocationStopJson? = null,
        val lines: List<LineJson> = emptyList(),
    )

    @Serializable
    private data class LocationStopJson(val properties: PropertiesJson? = null)

    @Serializable
    private data class PropertiesJson(
        /** The DIVA id, despite the field name. The station's *title* is [title]. */
        val name: String? = null,
        val title: String? = null,
    )

    @Serializable
    private data class LineJson(
        val name: String? = null,
        val towards: String? = null,
        val direction: String? = null,
        val platform: String? = null,
        val barrierFree: Boolean? = null,
        val type: String? = null,
        val departures: DeparturesJson? = null,
    )

    @Serializable
    private data class DeparturesJson(val departure: List<DepartureJson> = emptyList())

    @Serializable
    private data class DepartureJson(
        @SerialName("departureTime") val departureTime: DepartureTimeJson? = null,
    )

    @Serializable
    private data class DepartureTimeJson(
        val timePlanned: String? = null,
        val timeReal: String? = null,
        /** Minutes, realtime-corrected. The one field this Sock trusts. */
        val countdown: Int? = null,
    )

    companion object {
        const val ENDPOINT: String = "https://www.wienerlinien.at/"

        /**
         * Who is asking, for an operator reading their logs (fair-use rule 5).
         *
         * Names the thing, says it is one wall panel in a flat, and gives somebody a place to
         * complain that is not a firewall rule.
         */
        const val USER_AGENT: String =
            "Dobby/0.2 (wall panel, private household; https://github.com/fundreas/dobby)"

        private const val OK_MESSAGE_CODE = 1

        private const val TOO_MANY_REQUESTS = 429
        private const val SERVER_ERROR = 500

        private const val CONNECT_TIMEOUT_S = 3L
        private const val READ_TIMEOUT_S = 3L

        private val JSON = Json { ignoreUnknownKeys = true }

        /**
         * Short timeouts, for the reason in the class KDoc: a stale cache plus a live fetch has
         * to fit inside one dispatch, and the dispatcher's budget is five seconds for the whole
         * handler.
         */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
            .build()
    }
}
