package io.dobby.socks.departures

import io.dobby.core.sock.SockConfigStore
import java.time.Duration

/**
 * The Sock's settings (`departures.specs.md` §7), read through [SockConfigStore].
 *
 * Read at the moment they are used rather than captured at `onStart`, the way `RadioConfig`
 * and `WeatherConfig` are: a station somebody has just typed in should appear on the next tick,
 * not on the next boot.
 *
 * The station list is a **string**, and that is a deliberate flattening rather than a
 * limitation of the store. `SockConfigStore` keeps strings, and a Sock that needed a list type
 * would be a core change made for one Sock's convenience — the encoding is two separators and
 * lives entirely in [stations] and [encode], which is a smaller surface than a second store
 * shape would be.
 */
class DeparturesConfig(private val store: SockConfigStore) {

    /**
     * The configured stations, in the order they were typed.
     *
     * Everything malformed is dropped rather than repaired: an entry whose DIVA is not a number
     * would go into a batched request and take the **whole** request with it — every other
     * station's departures lost to one typo — so it never gets that far. An entry with no label
     * keeps its id and displays as the id, which is ugly and true.
     */
    var stations: List<Station>
        get() = store.getString(STATIONS).orEmpty()
            .split(ENTRY_SEPARATOR)
            .mapNotNull { entry ->
                val raw = entry.trim()
                if (raw.isEmpty()) return@mapNotNull null
                val diva = raw.substringBefore(FIELD_SEPARATOR).trim()
                if (diva.isEmpty() || !diva.all { it.isDigit() }) return@mapNotNull null
                val label = raw.substringAfter(FIELD_SEPARATOR, "").trim()
                Station(diva, label)
            }
            .distinctBy { it.diva }
        set(value) = store.put(STATIONS, encode(value))

    /**
     * How often the board is re-fetched while somebody could be looking at it.
     *
     * **The floor of 30 s is fair-use rule 2 and is enforced here**, which is why this is a
     * `coerceAtLeast` and not a default: a setting of 5 written into the config file by hand is
     * not a preference, it is the thing that gets the panel's IP blocked, and the store is
     * readable by anybody with the device. The published minimum is 15 s; Dobby doubles it.
     */
    val pollInterval: Duration
        get() = Duration.ofSeconds(
            (store.getInt(MIN_POLL_INTERVAL_S) ?: MIN_INTERVAL_S).coerceAtLeast(MIN_INTERVAL_S)
                .toLong(),
        )

    /**
     * How many departures the spoken summary names when no line was asked for.
     *
     * Three, because the card is the full answer and the speech is the summary — see
     * `departures.specs.md` §3. Zero or negative reads as the default rather than as a Sock
     * that has been configured into silence.
     */
    val maxSpokenLines: Int
        get() = store.getInt(MAX_SPOKEN_LINES)?.takeIf { it > 0 } ?: DEFAULT_MAX_SPOKEN_LINES

    /**
     * Per-line overrides for the German article, for the day the derivation in [LineSpeech] is
     * wrong about one.
     *
     * `u6=der,d=die Linie`. Empty by default and expected to stay that way: the article follows
     * from the line's `type`, which the API reports, and a seed table would be a second copy of
     * that mapping with nothing keeping it honest.
     */
    val lineArticles: Map<String, String>
        get() = store.getString(LINE_ARTICLES).orEmpty()
            .split(',')
            .mapNotNull { entry ->
                val line = entry.substringBefore(FIELD_SEPARATOR).trim().lowercase()
                val article = entry.substringAfter(FIELD_SEPARATOR, "").trim()
                if (line.isEmpty() || article.isEmpty()) null else line to article
            }
            .toMap()

    companion object {
        const val STATIONS: String = "departures.stations"
        const val MIN_POLL_INTERVAL_S: String = "departures.min_poll_interval_s"
        const val MAX_SPOKEN_LINES: String = "departures.max_spoken_lines"
        const val LINE_ARTICLES: String = "departures.line_articles"

        /** Fair-use rule 2, as a number. Nothing in this module may poll faster. */
        const val MIN_INTERVAL_S: Int = 30

        const val DEFAULT_MAX_SPOKEN_LINES: Int = 3

        /** How long a failed fetch may push the next attempt out to (fair-use rule 6). */
        val MAX_BACKOFF: Duration = Duration.ofMinutes(15)

        private const val ENTRY_SEPARATOR = '|'
        private const val FIELD_SEPARATOR = '='

        /**
         * The station list, written back the way [stations] reads it.
         *
         * Separators are stripped from the label rather than escaped. A station called
         * "Karlsplatz|U" is not a thing, and an escaping scheme would be a parser for a problem
         * nobody has.
         */
        fun encode(stations: List<Station>): String = stations.joinToString(ENTRY_SEPARATOR.toString()) {
            val label = it.label.replace(ENTRY_SEPARATOR, ' ').replace(FIELD_SEPARATOR, ' ').trim()
            "${it.diva}$FIELD_SEPARATOR$label"
        }
    }
}
