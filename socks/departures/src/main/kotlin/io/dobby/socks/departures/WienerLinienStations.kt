package io.dobby.socks.departures

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * `GET /ogd_realtime/doku/ogd/wienerlinien-ogd-haltestellen.csv`, once, and then from disk.
 *
 * The second and last class in this module that knows the internet exists, and the one that
 * turned „DIVA-Nummer eintippen" into a search box. It is a **different kind of request** from
 * [WienerLinien]'s, and the difference is what makes it fair use rather than a hole in §6:
 *
 *  - It is a **static published file**, not the realtime endpoint. Nothing about it changes
 *    between two polls, so there is nothing to poll.
 *  - It is fetched **on a human action** — somebody opened the station picker — and then not
 *    again for [MAX_AGE]. A panel that never gains a station never asks for it at all.
 *  - It is **one file, 130 KB**, and it replaces the alternative of asking the realtime
 *    endpoint whether each of two thousand guesses is a station, which is §6.3's "never
 *    enumerate" written out.
 *
 * **Stale beats nothing.** A download that fails while a cached copy exists returns the
 * cached copy, however old: station names change a handful of times a year, DIVA ids
 * effectively never, and a picker that refuses to open because the Wiener Linien are having a
 * bad morning is worse than one listing last month's stations.
 *
 * The timeouts are long where [WienerLinien]'s are short, and for the mirror of its reason:
 * nothing here is inside a five-second dispatch budget. This runs behind a spinner on a
 * settings screen, where the only thing worse than waiting eight seconds is being told to type
 * a number out of a CSV instead.
 */
class WienerLinienStations(
    /**
     * Where the downloaded list lives between openings of the picker. Null keeps it in memory
     * only, which is the off-device case and one process's worth of caching.
     */
    private val cache: File? = null,
    private val client: OkHttpClient = defaultClient(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: Clock = Clock.systemUTC(),
    private val url: String = StationCsv.URL,
    private val maxAge: Duration = MAX_AGE,
) : StationDirectory {

    /**
     * The parsed list, held for the life of the process.
     *
     * Two thousand small objects is nothing next to re-reading and re-parsing 130 KB every
     * time somebody opens the picker, and the list cannot change underneath us — the only
     * writer is [load], under [lock].
     */
    @Volatile
    private var loaded: List<StationInfo>? = null

    /** So two taps in the same second are one download, not two. */
    private val lock = Mutex()

    override suspend fun stations(): List<StationInfo> {
        loaded?.let { return it }
        return lock.withLock { loaded ?: load().also { loaded = it } }
    }

    private suspend fun load(): List<StationInfo> = withContext(io) {
        val file = cache?.takeIf { it.isFile && it.length() > 0 }
        if (file != null && clock.millis() - file.lastModified() < maxAge.toMillis()) {
            readCache(file)?.let { return@withContext it }
        }
        val body = try {
            download()
        } catch (e: DeparturesUnavailable) {
            // Stale beats nothing — see the class KDoc. Only a first run with no network at
            // all reaches the caller as a failure.
            return@withContext file?.let { readCache(it) } ?: throw e
        }
        val parsed = StationCsv.parse(body)
        // A 200 with a body that is not the station list. Not cached: writing it would mean
        // serving it for a month.
        if (parsed.isEmpty()) throw DeparturesUnavailable(DeparturesError.FAILED)
        if (cache != null) {
            runCatching {
                cache.parentFile?.mkdirs()
                cache.writeText(body)
            }
        }
        parsed
    }

    /** Null on anything at all going wrong: a bad cache file is a cache miss, not a crash. */
    private fun readCache(file: File): List<StationInfo>? =
        runCatching { StationCsv.parse(file.readText()) }.getOrNull()?.takeIf { it.isNotEmpty() }

    private fun download(): String {
        val request = Request.Builder()
            .url(url)
            // Fair-use rule 5, and the same agent the realtime requests carry: an operator
            // reading their logs should see one panel, not two unrelated clients.
            .header("User-Agent", WienerLinien.USER_AGENT)
            .build()
        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw DeparturesUnavailable(DeparturesError.OFFLINE, e)
        }
        response.use {
            if (it.isSuccessful) return it.body.string()
            throw when {
                it.code == TOO_MANY_REQUESTS -> DeparturesUnavailable(DeparturesError.RATE_LIMITED)
                it.code >= SERVER_ERROR -> DeparturesUnavailable(DeparturesError.SERVER)
                else -> DeparturesUnavailable(DeparturesError.FAILED)
            }
        }
    }

    companion object {
        /**
         * How long a downloaded list is used without asking again.
         *
         * A month, because that is the rate the thing actually changes at: new stops and
         * renamings are a handful a year, and the failure mode of being a month behind is one
         * missing candidate in a search box — recoverable, because the DIVA field is still
         * there underneath it.
         */
        val MAX_AGE: Duration = Duration.ofDays(30)

        private const val TOO_MANY_REQUESTS = 429
        private const val SERVER_ERROR = 500

        private const val CONNECT_TIMEOUT_S = 10L
        private const val READ_TIMEOUT_S = 20L

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
            .build()
    }
}
