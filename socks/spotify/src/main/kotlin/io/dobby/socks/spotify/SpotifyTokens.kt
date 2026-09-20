package io.dobby.socks.spotify

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock

/**
 * The client id and secret, handed in from outside.
 *
 * The JVM module never reads `BuildConfig` — the credentials arrive as a constructor parameter
 * from `SpotifyHardware`, which is also what makes this testable with a fake. Empty strings are
 * the normal state of a fresh clone (`spotify.specs.md` §1): missing credentials make the Sock
 * `Unavailable` with a reason that says so, rather than failing the build.
 */
data class SpotifyCredentials(val clientId: String, val clientSecret: String, val redirectUri: String) {

    /** Enough for the Web API token. The App Remote needs only the id and the redirect URI. */
    val canSearch: Boolean get() = clientId.isNotBlank() && clientSecret.isNotBlank()

    /** Enough for the App Remote connection, which does its own authorization (§1). */
    val canConnect: Boolean get() = clientId.isNotBlank() && redirectUri.isNotBlank()

    companion object {
        val NONE: SpotifyCredentials = SpotifyCredentials("", "", "")
    }
}

/**
 * The app token behind `/v1/search`, fetched on demand and re-fetched when it expires.
 *
 * `/v1/search` reads the public catalogue, so it needs a token but not a *user's* token:
 * client credentials, one POST, no redirect, **no refresh token** — there is nothing to
 * refresh, and when it expires you make the same request again. That is the whole of Spotify
 * authentication in this project, and it is why there is no login screen (`spotify.specs.md`
 * §1).
 *
 * The mutex is not decoration. `handle()` runs once per utterance, but a token fetch is an I/O
 * suspend, and two music commands in one turn — "spiel was von queen", then "nächster song" —
 * must not each open their own token request.
 *
 * **The seam for user authorization.** If private playlists ever matter, the upgrade is
 * Authorization Code + PKCE, and it is contained precisely because everything goes through
 * [bearer]. This class is the only one that knows which flow produced the string; nothing
 * downstream may learn a token type, a scope or a "logged in" flag.
 */
class SpotifyTokens(
    private val credentials: SpotifyCredentials,
    private val clock: Clock = Clock.systemUTC(),
    /** Performs the POST. Injected so the flow is exercisable without a network. */
    private val fetcher: suspend (SpotifyCredentials) -> Grant,
) {
    /** What the token endpoint hands back. Note the absence of a refresh token. */
    data class Grant(val accessToken: String, val expiresInSeconds: Long)

    private val mutex = Mutex()
    private var token: String? = null
    private var expiresAt = 0L

    suspend fun bearer(): String = mutex.withLock {
        if (!credentials.canSearch) throw SearchFailure(SearchError.NOT_CONFIGURED)
        val cached = token
        if (cached != null && clock.millis() < expiresAt - SKEW_MS) return@withLock cached
        val grant = fetcher(credentials)
        expiresAt = clock.millis() + grant.expiresInSeconds * MILLIS_PER_SECOND
        token = grant.accessToken
        grant.accessToken
    }

    /** Called on a 401 — the token was revoked, or this clock lied. One retry, then fail. */
    suspend fun invalidate() = mutex.withLock {
        token = null
        expiresAt = 0L
    }

    private companion object {
        /**
         * Treat a token as expired this long before it is.
         *
         * An hour-long token used in its final second is a 401 and a wasted round trip on the
         * one command where latency is already the complaint.
         */
        const val SKEW_MS = 30_000L
        const val MILLIS_PER_SECOND = 1000L
    }
}
