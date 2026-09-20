package io.dobby.socks.spotify

/**
 * The catalogue lookup behind `spotify.play_music`.
 *
 * App Remote has no search at all — `playerApi.play(uri)` takes a Spotify URI and nothing else
 * — so a URI has to come from somewhere, and that somewhere is the Web API's `/v1/search`.
 * Implemented on OkHttp in this same module: no Android types, so the whole resolution path is
 * drivable from the terminal harness against the real API.
 */
interface MusicSearch {

    /**
     * @throws SearchFailure for every condition the Sock has separate German copy for. A
     *   thrown failure rather than a nullable result because "nothing found" and "rate limited"
     *   are different sentences, and an implementation that collapsed them would have to invent
     *   an error enum of its own anyway.
     */
    suspend fun find(query: String, hint: QueryHint, market: String): Hits

    companion object {
        /** Off-device. Every lookup fails as "not set up", which is what has actually happened. */
        val NONE: MusicSearch = object : MusicSearch {
            override suspend fun find(query: String, hint: QueryHint, market: String): Hits =
                throw SearchFailure(SearchError.NOT_CONFIGURED)
        }
    }
}

/**
 * What the wording of the utterance said about the query, for the search to use as a hint.
 *
 * The spec's first draft split on `" von "` and built `q="track:<title> artist:<artist>"`.
 * That is inverted here (`spotify.specs.md` §3): field filters are near-exact and the
 * transcript is not, so the `q` is always the whole phrase and the split survives only as this.
 * It changes which `type=` is asked for and how [Selection] breaks a tie — never the query.
 */
enum class QueryHint {
    /** "blinding lights **von** the weeknd" — they named both, so nothing is ambiguous. */
    TITLE_AND_ARTIST,

    /** "spiel was von queen" — the template ate the `von`, so the whole slot is an artist. */
    ARTIST_ONLY,

    /** Everything else. */
    UNKNOWN,

    ;

    companion object {
        /** The param value a template fixes, and what comes back out of the invocation. */
        val SPOKEN: List<String> = entries.map { it.name.lowercase() }

        fun of(value: String?): QueryHint =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: UNKNOWN
    }
}

/** One search result, whatever kind it is. */
data class Hit(
    val uri: String,
    val kind: Kind,
    val name: String,
    val artist: String?,
    val popularity: Int,
) {
    enum class Kind { TRACK, ARTIST, PLAYLIST, ALBUM }

    /**
     * How Dobby says it out loud (`spotify.specs.md` §3).
     *
     * A track is named on its own — "Spiele Blinding Lights." is what a person would say. An
     * artist and a playlist need the noun, or the sentence is a riddle.
     */
    val spoken: String
        get() = when (kind) {
            Kind.TRACK -> name
            Kind.ARTIST -> "Musik von $name"
            Kind.PLAYLIST -> "Playlist $name"
            Kind.ALBUM -> "Album $name"
        }
}

/** One response, still in buckets: the selection heuristic picks a *kind* before it picks a hit. */
data class Hits(
    val tracks: List<Hit> = emptyList(),
    val artists: List<Hit> = emptyList(),
    val playlists: List<Hit> = emptyList(),
) {
    val isEmpty: Boolean get() = tracks.isEmpty() && artists.isEmpty() && playlists.isEmpty()
}

/** The conditions the Sock has separate German copy for (`spotify.specs.md` §3). */
enum class SearchError {
    /** No client id or secret in the build — `local.properties` was never filled in. */
    NOT_CONFIGURED,

    /** The token request itself failed: bad credentials, or Spotify said no. */
    NO_TOKEN,

    /** 429. Somebody is burning the app's rate limit. */
    RATE_LIMITED,

    /** The socket never came up. */
    OFFLINE,

    /** The whole resolution budget ran out (`spotify.specs.md` §3). */
    TIMEOUT,

    /** Anything else the API said. */
    FAILED,
}

class SearchFailure(val error: SearchError, cause: Throwable? = null) :
    Exception(error.name, cause)
