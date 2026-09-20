package io.dobby.socks.spotify

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.time.Clock
import java.util.concurrent.TimeUnit

/**
 * `/v1/search` on OkHttp, with a client-credentials token in front of it.
 *
 * **The whole phrase goes in `q`, never a field filter.** The spec's first draft built
 * `q="track:<title> artist:<artist>"` out of the `" von "` split; that is inverted here
 * (`spotify.specs.md` §3) because field filters are near-exact and an ASR transcript is not.
 * `q=track:bleinding leitz artist:se weeknd` returns an empty array, while the free-text
 * `q=bleinding leitz se weeknd` still ranks the right track first — Spotify's own ranker is
 * fuzzy and the filter syntax is not. The hint survives as a `type=` choice and as a tie-break
 * in [Selection], and nowhere else.
 *
 * `market` matters more than it looks: without it the response contains tracks that are not
 * playable in the user's country, which produce a successful search and a silent failure to
 * play.
 */
class WebMusicSearch(
    credentials: SpotifyCredentials,
    clock: Clock = Clock.systemUTC(),
    private val client: OkHttpClient = defaultClient(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val accountsUrl: HttpUrl = ACCOUNTS_URL.toHttpUrl(),
    private val apiUrl: HttpUrl = API_URL.toHttpUrl(),
) : MusicSearch {

    private val tokens = SpotifyTokens(credentials, clock) { fetchToken(it) }

    override suspend fun find(query: String, hint: QueryHint, market: String): Hits {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return Hits()
        // One retry, and only for the one condition a retry can fix: the token we cached was
        // refused. Anything else is reported as it stands — a panel that quietly retries a 500
        // is a panel that takes eight seconds to say it failed.
        return try {
            search(trimmed, hint, market, tokens.bearer())
        } catch (unauthorized: Unauthorized) {
            tokens.invalidate()
            try {
                search(trimmed, hint, market, tokens.bearer())
            } catch (again: Unauthorized) {
                throw SearchFailure(SearchError.NO_TOKEN, again)
            }
        }
    }

    private suspend fun search(query: String, hint: QueryHint, market: String, bearer: String): Hits {
        val url = apiUrl.newBuilder()
            .addPathSegments("v1/search")
            .addQueryParameter("q", query)
            .addQueryParameter("type", typeFor(hint))
            .addQueryParameter("limit", "$LIMIT")
            .addQueryParameter("market", market)
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $bearer")
            .build()
        val body = call(request)
        val parsed = try {
            JSON.decodeFromString(SearchJson.serializer(), body)
        } catch (e: IllegalArgumentException) {
            throw SearchFailure(SearchError.FAILED, e)
        }
        return parsed.toHits()
    }

    /**
     * The token POST (`spotify.specs.md` §1).
     *
     * Note what is absent: no refresh token, because there is nothing to refresh. When it
     * expires, this same request runs again.
     */
    private suspend fun fetchToken(credentials: SpotifyCredentials): SpotifyTokens.Grant {
        val request = Request.Builder()
            .url(accountsUrl.newBuilder().addPathSegments("api/token").build())
            .header("Authorization", Credentials.basic(credentials.clientId, credentials.clientSecret))
            .post(FormBody.Builder().add("grant_type", "client_credentials").build())
            .build()
        val body = try {
            call(request)
        } catch (unauthorized: Unauthorized) {
            // Bad credentials, not a stale token: there is no cached token to invalidate and
            // retrying would ask the same question twice.
            throw SearchFailure(SearchError.NO_TOKEN, unauthorized)
        }
        val grant = try {
            JSON.decodeFromString(TokenJson.serializer(), body)
        } catch (e: IllegalArgumentException) {
            throw SearchFailure(SearchError.NO_TOKEN, e)
        }
        return SpotifyTokens.Grant(grant.accessToken, grant.expiresIn)
    }

    /** One round trip, off the calling thread, with every HTTP condition mapped to German copy. */
    private suspend fun call(request: Request): String = withContext(io) {
        val response: Response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw SearchFailure(SearchError.OFFLINE, e)
        }
        response.use {
            when {
                it.isSuccessful -> it.body.string()
                it.code == UNAUTHORIZED -> throw Unauthorized()
                it.code == TOO_MANY_REQUESTS -> throw SearchFailure(SearchError.RATE_LIMITED)
                else -> throw SearchFailure(SearchError.FAILED)
            }
        }
    }

    /** Internal control flow, never seen by the Sock: a 401 is the one thing worth retrying. */
    private class Unauthorized : Exception()

    private companion object {
        const val ACCOUNTS_URL = "https://accounts.spotify.com/"
        const val API_URL = "https://api.spotify.com/"

        /** Five is more than the heuristic reads and enough to see a tie (`§3`). */
        const val LIMIT = 5

        const val UNAUTHORIZED = 401
        const val TOO_MANY_REQUESTS = 429

        /**
         * Five seconds each, under the Sock's own `withTimeout` (`spotify.specs.md` §3).
         *
         * The hard budget is the Sock's, not OkHttp's: these are the per-socket backstop for a
         * connection that hangs, and the 2.5 s the user actually feels is enforced one layer up
         * where token-plus-search is one number.
         */
        const val SOCKET_TIMEOUT_S = 5L

        val JSON = Json { ignoreUnknownKeys = true }

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(SOCKET_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(SOCKET_TIMEOUT_S, TimeUnit.SECONDS)
            .build()

        /**
         * Which buckets to ask for (`spotify.specs.md` §3).
         *
         * An explicit "X von Y" wants a track and nothing else — they named both halves, so an
         * artist result would be a worse answer to a question that was not ambiguous.
         */
        fun typeFor(hint: QueryHint): String = when (hint) {
            QueryHint.TITLE_AND_ARTIST -> "track"
            QueryHint.ARTIST_ONLY -> "artist,track"
            QueryHint.UNKNOWN -> "track,artist,playlist"
        }
    }

    // --- Wire types. Only the fields the heuristic reads; everything else is ignored. ---

    @Serializable
    private data class SearchJson(
        val tracks: TrackPage? = null,
        val artists: ArtistPage? = null,
        val playlists: PlaylistPage? = null,
    ) {
        fun toHits(): Hits = Hits(
            tracks = tracks?.items?.filterNotNull()?.map { it.toHit() }.orEmpty(),
            artists = artists?.items?.filterNotNull()?.map { it.toHit() }.orEmpty(),
            playlists = playlists?.items?.filterNotNull()?.map { it.toHit() }.orEmpty(),
        )
    }

    /**
     * Three page types rather than one generic one, and the nullable elements are the reason.
     *
     * The playlist bucket really does come back with null entries — a long-standing Web API
     * quirk — and a non-null list would make the whole response fail to parse over a playlist
     * nobody asked for. The other two carry it for symmetry and cost nothing.
     */
    @Serializable
    private data class TrackPage(val items: List<TrackJson?> = emptyList())

    @Serializable
    private data class ArtistPage(val items: List<ArtistJson?> = emptyList())

    @Serializable
    private data class PlaylistPage(val items: List<PlaylistJson?> = emptyList())

    @Serializable
    private data class TrackJson(
        val uri: String = "",
        val name: String = "",
        val popularity: Int = 0,
        val artists: List<ArtistJson> = emptyList(),
    ) {
        fun toHit(): Hit = Hit(
            uri = uri,
            kind = Hit.Kind.TRACK,
            name = name,
            artist = artists.firstOrNull()?.name,
            popularity = popularity,
        )
    }

    @Serializable
    private data class ArtistJson(
        val uri: String = "",
        val name: String = "",
        val popularity: Int = 0,
    ) {
        fun toHit(): Hit =
            Hit(uri = uri, kind = Hit.Kind.ARTIST, name = name, artist = name, popularity = popularity)
    }

    @Serializable
    private data class PlaylistJson(
        val uri: String = "",
        val name: String = "",
        val owner: OwnerJson? = null,
    ) {
        // Playlists carry no popularity at all, so they sort below everything by construction —
        // which matches the heuristic: a playlist is the answer only when nothing else is.
        fun toHit(): Hit = Hit(
            uri = uri,
            kind = Hit.Kind.PLAYLIST,
            name = name,
            artist = owner?.displayName,
            popularity = 0,
        )
    }

    @Serializable
    private data class OwnerJson(@SerialName("display_name") val displayName: String? = null)

    @Serializable
    private data class TokenJson(
        @SerialName("access_token") val accessToken: String,
        @SerialName("expires_in") val expiresIn: Long = 0,
    )
}
