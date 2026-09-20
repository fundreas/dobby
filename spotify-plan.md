# Spotify — the Sock plan

## Context

Spotify is the last unbuilt piece of M1 (`dobby-plan.md` §8) and the one the whole product was
described around: *"Spiele Blinding Lights von The Weeknd" works from a button press, and the
Spotify Sock contains every Spotify-specific line of code in the project.*

It was deferred, and everything it was blocked on has since been built: `PlaybackCoordinator`
and audio focus (M2b), the chain dispatcher and `SharedCommands` (M3 work landed early),
follow-up questions (`SockResult.Asked`, M2b), filler skipping (M6c) and the Tier 2 LLM
fallback (M6). There is nothing left to wait for.

Two decisions are settled before this plan starts, and the rest of it follows from them:

1. **Playback goes through the App Remote SDK**, not through `MEDIA_PLAY_FROM_SEARCH`. The
   intent is a one-shot that takes free text and answers nothing: it starts an *activity*, so
   Spotify covers the panel, and it reports neither what it played nor whether it worked. Every
   other command in this Sock — pause, skip, previous, restart, `shared.stop` — needs a live
   connection anyway, and `activityFor` needs a cached `PlayerState` (§6 of the spec). Once the
   App Remote is there, the intent buys nothing. It stays in the plan as a degraded fallback
   (D5).
2. **Search goes through the Web API with a client-credentials token.** App Remote has no
   search at all — `playerApi.play(uri)` takes a Spotify URI and nothing else — so a URI has to
   come from somewhere. `/v1/search` is not user-scoped, so it needs an *app* token, not a user
   login: one POST, no redirect URI, no refresh token, no settings screen. See B.

The spec file `socks.specs/spotify.specs.md` was written before any of this existed. It is
mostly right and stays the source of truth for German copy; the deltas this plan makes to it
are collected in §J.

---

## A. Module layout

### A1. Two modules, the `ClockHardware` shape

```
socks/spotify/          kotlin-jvm   the Sock, the search client, the selection logic
android/spotify/        android-lib  App Remote behind the interfaces the Sock sees
```

The Clock Sock is the precedent and the argument: `ClockSock` sees `TimerAlarm` and
`ChimePlayer`, never `AlarmManager` or `SoundPool`, which is why its entire timer lifecycle runs
on a plain JVM. The same split here means the query parsing, the selection heuristic, the
German result copy and the whole command surface are reachable without a device or a Spotify
account — and the part that genuinely needs both is one small implementation class.

The Sock sees exactly two interfaces, declared in `:socks:spotify`:

```kotlin
/** Everything the Sock does to the Spotify app. Implemented by App Remote on the device. */
interface SpotifyPlayer {
    /** Cached, never a Binder round-trip on read — `activityFor` is a pure state read. */
    val state: StateFlow<PlayerSnapshot?>

    suspend fun connect(): Boolean
    suspend fun play(uri: String): Boolean
    suspend fun resumeHome(): String?      // empty query: the user's own home feed (C6)
    suspend fun pause(): Boolean
    suspend fun resume(): Boolean
    suspend fun skipNext(): Boolean
    suspend fun skipPrevious(): Boolean
    suspend fun seekToStart(): Boolean
    fun disconnect()

    companion object { val NONE: SpotifyPlayer }   // off-device default, everything false
}

/** The catalogue lookup. Implemented on OkHttp in the same module — no Android types. */
interface MusicSearch {
    suspend fun find(query: String, hint: QueryHint): List<Hit>

    companion object { val NONE: MusicSearch }
}

data class PlayerSnapshot(
    val uri: String,
    val title: String,
    val artist: String,
    val isPaused: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val artUri: String?,
)

data class Hit(val uri: String, val kind: Kind, val name: String, val artist: String?, val popularity: Int) {
    enum class Kind { TRACK, ARTIST, PLAYLIST, ALBUM }
}
```

`PlayerSnapshot` is deliberately not Spotify's `PlayerState`: the Sock module must not see an
SDK type, and the dashboard wants a flat thing it can draw.

### A2. Wiring

`SpotifyHardware(context, credentials)` in `:android:app`, exactly parallel to `ClockHardware`:
constructs the App Remote player, is created by `DobbyService`, released in `onDestroy`.
`DobbySocks.create()` gains one parameter and one line, and `DobbySocks.Wiring` gains a
`spotify: SpotifySock` field for the dashboard, the way it already keeps `clock`.

Off-device — the CLI harness, any unit test — the Sock is constructed with
`SpotifyPlayer.NONE` and `MusicSearch.NONE`, reports `Unavailable`, and fails every command
with the spoken line from §H. That keeps `cli/Main.kt` compiling and keeps the registry
complete (the palette must contain every command whether or not a device is present).

### A3. Dependencies

New entries in `gradle/libs.versions.toml`:

```toml
okhttp = "5.3.0"
serialization = "1.9.0"          # kotlinx-serialization-json, and the plugin version = kotlin

okhttp = { module = "com.squareup.okhttp3:okhttp", version.ref = "okhttp" }
kotlinx-serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "serialization" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
```

The App Remote SDK is **not** on Maven Central: Spotify ships it as a zip containing
`spotify-app-remote-release-x.y.z.aar`, which goes in `android/spotify/libs/` and is picked up
with `implementation(files("libs/spotify-app-remote-release-x.y.z.aar"))`. *Verify at
implementation time whether a Maven coordinate now exists* — if it does, use it and delete the
`libs/` folder. The AAR also needs `com.google.code.gson:gson` at runtime, which it does not
declare.

`com.spotify.android:auth` is **not** needed for this plan. It is the library for the user
OAuth flow (B4), which we are not building yet. App Remote does its own authorization.

`:android:app` needs `buildFeatures { buildConfig = true }` for B1.

---

## B. Authentication

### B1. Client credentials, and why there is no login screen

`/v1/search` reads the public catalogue. It needs a token, but not a *user's* token:

```
POST https://accounts.spotify.com/api/token
Authorization: Basic base64(client_id:client_secret)
Content-Type: application/x-www-form-urlencoded

grant_type=client_credentials
```

→ `{"access_token": "...", "token_type": "Bearer", "expires_in": 3600}`

Note what is absent: **no refresh token**, because there is nothing to refresh. When it expires
you make the same request again. That deletes `spotify.auth_token` / `spotify.refresh_token`
from the spec's §9 config table, deletes the encrypted-storage requirement, deletes the
redirect URI from the *search* path, and deletes the settings screen the spec's failure table
points at ("Bitte melde dich in den Einstellungen an").

```kotlin
internal class SpotifyTokens(
    private val http: OkHttpClient,
    private val credentials: SpotifyCredentials,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val mutex = Mutex()
    private var token: String? = null
    private var expiresAt = 0L

    suspend fun bearer(): String = mutex.withLock {
        token?.takeIf { clock.millis() < expiresAt - SKEW_MS } ?: fetch()
    }

    /** Called on a 401 — the token was revoked or the clock lied. One retry, then fail. */
    suspend fun invalidate() = mutex.withLock { token = null }
}
```

The mutex is not decoration: `handle()` is called once per utterance, but the token fetch is an
I/O suspend, and two music commands in one turn (`"spiel was von queen"` … `"nächster song"`)
must not each open their own token request.

Where the secret lives: `local.properties` (already git-ignored, already where the plan puts
device-local config), read in `android/app/build.gradle.kts` into `BuildConfig`, passed to the
Sock as a `SpotifyCredentials` data class from `SpotifyHardware`. **The JVM module never reads
`BuildConfig`** — it takes credentials as a constructor parameter, which is also what makes it
testable with a fake.

```kotlin
val local = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}
buildConfigField("String", "SPOTIFY_CLIENT_ID", "\"${local["spotify.client.id"] ?: ""}\"")
buildConfigField("String", "SPOTIFY_CLIENT_SECRET", "\"${local["spotify.client.secret"] ?: ""}\"")
```

Empty strings rather than a build failure: a fresh clone must build. Missing credentials make
the Sock `Unavailable` at `onStart` with a reason that says so, which is the same path as a
missing Spotify app.

**The secret is in the APK and extractable.** For a wall panel in one flat the blast radius is
somebody burning our search rate limit, and the fix is rotating the secret in the dashboard.
This is the same class of accepted risk as the non-commercial wake-word models
(`dobby-plan.md` §9): recorded here so it is not rediscovered as a surprise. If Dobby is ever
handed to anyone else, this becomes PKCE (B4) or a token proxy, and it is the *first* thing
that has to change.

### B2. What an app token cannot see

Client credentials reach the public catalogue and nothing else:

| Works | Does not work |
|---|---|
| tracks, artists, albums | the user's Liked Songs |
| **public** playlists | the user's *private* playlists |
| popularity, market filtering | "recently played", personalisation |

Two places in the spec collide with that, and both have an answer:

- **§3.2, empty query → Liked Songs shuffle.** Replaced by App Remote's own content API, which
  runs in the user's session and needs no Web API at all (C6). Arguably the better behaviour
  anyway.
- **"mach die playlist entspannen an" when *entspannen* is the user's own private playlist.**
  No workaround. It finds a public playlist of that name or fails with the normal "nichts
  gefunden". Accepted for v1 — this is the one feature the auth decision costs, and B4 is the
  price list for buying it back.

### B3. App Remote authorizes itself, separately

The App Remote connection is a *different* authorization from the Web API token, and it is
almost free: `ConnectionParams.Builder(clientId).setRedirectUri(redirectUri).showAuthView(true)`
makes the Spotify app show a one-time consent dialog on the first connect. After that the
connection is established without UI. No token to store on our side.

It does require, non-negotiably:

- The Spotify app installed and logged in.
- **Premium** — App Remote playback control is Premium-only.
- The app registered in the Spotify Developer Dashboard with the package name
  `io.dobby.android` and the **SHA-1 of both the debug and the release signing key**, plus the
  redirect URI. A missing debug SHA-1 is the classic "it works nowhere and the error says
  nothing"; do both up front.

### B4. The PKCE seam

If private playlists turn out to matter, the upgrade is Authorization Code + PKCE: `client_id`
only (no secret in the app — that is the point of PKCE), a redirect URI registered as an
intent-filter, a one-time login in a Custom Tab, an access token plus a refresh token renewed
with the client id alone, refresh token in `EncryptedSharedPreferences`.

The seam that makes it a contained change: **everything goes through `suspend fun bearer()`**.
`SpotifyTokens` is the only class that knows which flow produced the string, and `MusicSearch`
never asks. Do not leak a token type, a scope or a "logged in" flag past that boundary.

---

## C. Search and resolution

### C1. Send the sentence, not a query language

The spec (§3.3a) splits on `" von "` and builds `q="track:<title> artist:<artist>"`. **Invert
that.** Field filters are near-exact; the transcript is not. `q=track:bleinding leitz artist:se
weeknd` returns an empty array, while the free-text `q=bleinding leitz se weeknd` still ranks
the right track first, because Spotify's own ranker is fuzzy and the filter syntax is not. The
Sock's input is a phonetically mangled ASR transcript — that is stated as expected and tolerated
in the spec's own params table — so the robust form is the only form.

The `" von "` split survives as a **hint**, not as a query builder:

```kotlin
enum class QueryHint { TITLE_AND_ARTIST, ARTIST_ONLY, UNKNOWN }
```

- `"blinding lights von the weeknd"` → `TITLE_AND_ARTIST`, from the `von` split
- `"queen"` — matched by the `(etwas|was|irgendwas) von {query}` template, which already ate the
  `von` — → `ARTIST_ONLY`, set by the template through a **static param**
  (`socks.specs/README.md` §6), not re-derived in the handler
- everything else → `UNKNOWN`

The hint changes only which `type=` we ask for and how C3 scores. The `q` is always the whole
phrase.

### C2. The request

```
GET https://api.spotify.com/v1/search
    ?q=<whole normalized query>
    &type=<per hint>
    &limit=5
    &market=<spotify.market, default AT>
Authorization: Bearer <app token>
```

| Hint | `type=` |
|---|---|
| `TITLE_AND_ARTIST` | `track` |
| `ARTIST_ONLY` | `artist,track` |
| `UNKNOWN` | `track,artist,playlist` |

`market` matters more than it looks: without it the response contains tracks that are not
playable in Austria, which produce a successful search and a silent failure to play.

Budget: **2.5 s total** for token (only when expired) plus search, on OkHttp timeouts of 5 s
connect / 5 s read with a hard `withTimeout` around the whole resolution. A music command that
takes four seconds to start feels broken; failing with "Ich habe auf Spotify nichts gefunden"
is worse than a two-second wait but better than an eight-second one. Tier 2 already costs 2–4 s
on the paraphrase path, and stacking a slow search on top of it is the case to protect against.

### C3. Selection: trust the ranker, break ties

Spotify's ordering is already the best signal available; the heuristic's job is to pick a
*kind*, not to re-rank a list we understand worse than they do.

```
1. hint = TITLE_AND_ARTIST
     → top track whose artist matches the spoken artist loosely (normalized, ≥ 0.6 token
       overlap). None matches → tracks[0] anyway; the ASR mangled the artist, not the intent.
2. hint = ARTIST_ONLY
     → artists[0] if its name loosely matches the query, else tracks[0].
3. hint = UNKNOWN
     → tracks[0], unless `spotify.prefer_track_over_artist` is false, or the query loosely
       equals artists[0].name and tracks[0] is not a much stronger hit (popularity gap < 10).
       Playlist only if there is no track and no artist at all.
4. Nothing in any bucket → Failed("Ich habe auf Spotify nichts gefunden.")
```

Loose matching is one small pure function — normalize, drop the spec's filler words (`Fillers.DE`
is already in `:core` and already on this module's classpath), compare token sets. It is not
Levenshtein and not phonetics; the fuzziness that matters already happened inside Spotify's
ranker.

Artists and playlists are played by URI directly — `playerApi.play("spotify:artist:...")`
starts that artist's tracks, which is exactly what "spiel was von queen" means.

### C4. The normalizer already touched the query

A trap worth writing down because it is invisible: the `{query}` slot receives the **normalized**
transcript, which has had German number words turned into digits. "Sieben Leben" arrives as
`7 leben`, "Vier Jahreszeiten" as `4 jahreszeiten`. Free-text search handles this better than
field filters would (another point for C1), and Spotify generally finds `4 jahreszeiten`.

Accepted, not fixed. If the fallthrough log shows number-word titles failing, the fix is to
search the de-digitized variant as a second attempt — not to change the normalizer, which
`{amount:int}` depends on.

Filler words inside the slot are *kept*: `{query}` captures verbatim, which is why "spiele es
muss liebe sein" binds the whole title (`m6c-plan.md` A2 uses this exact case).

### C5. When to ask instead of guess

The spec's §12 leaves this open and names what is missing: holding the candidate hits under the
follow-up token, and the German copy. Both are in scope here, with a deliberately **narrow**
trigger — a panel that interrogates you about every song is worse than one that occasionally
plays the wrong version.

Ask only when all of these hold:

- hint is `UNKNOWN` or `ARTIST_ONLY` (an explicit "X von Y" is not ambiguous — they told us), and
- the top two tracks have **different artists**, and
- their popularity gap is **< 10**, and
- the query did not loosely match an artist name (that is case 2 in C3, and it is decided).

Two-way copy:

> "Ich habe zwei Versionen: einmal von {a}, einmal von {b}. Die erste oder die zweite?"

Three-way:

> "Ich habe drei: von {a}, von {b} und von {c}. Die erste, die zweite oder die dritte?"

The follow-up palette is an **enum over ordinals**, never a text slot:

```kotlin
FollowUp(
    commandId = PLAY_MUSIC,
    templates = patterns("{choice:enum}", "(die|das|den) {choice:enum}"),
    params = listOf(ParamSpec("choice", ParamType.Enumeration(
        listOf("erste", "ersten", "zweite", "zweiten", "dritte", "dritten", "egal"),
    ))),
    token = token,
)
```

An enum slot consumes exactly one token, which rules out answering with a song title — and that
is the *right* constraint here, for the reason `ClockSock.askWhichTimer` documents at length: a
question's scoped palette gets first refusal on the next utterance, so a bare `{text}` slot
would turn "wie spät ist es" into an answer. Ordinals are closed, short, and what people
actually say. "Egal" takes the first. Anything else falls through to the global palette,
`onAskCancelled` fires, and the candidates are dropped — the user changed their mind, which is
allowed.

The candidates live in a `ConcurrentHashMap<String, List<Hit>>` keyed by token, same shape as
`ClockSock.asked`. A token we no longer hold → `Failed`, same as the clock's `BAD_DURATION`
path.

### C6. Empty query

`play_music(query="")` — "musik an", "spotify einschalten" — makes **no Web API call at all**:

1. If the cached `PlayerSnapshot` has a track and `isPaused`, `resume()`.
2. Otherwise `resumeHome()`: App Remote's `contentApi.getRecommendedContentItems(DEFAULT)` is
   the user's own home feed — recently played, their playlists, made-for-you — running inside
   their session, with none of B2's limits. Play the first playable item and return its name.
3. Nothing at all comes back → `Failed`.

This is the spec's §3.2 with Liked Songs replaced by the home feed, and it is the one place
where the auth decision made the behaviour *better* rather than merely cheaper.

---

## D. Playback

### D1. Connect lazily, reconnect once

Per the spec §3.4, and unchanged: no connection at `onStart` (a panel that has not been asked
for music should not be holding a Binder connection into another app), connect on the first
music command, and on a dead cached connection reconnect exactly once before failing. Overnight
disconnects are **normal** and must not set `Degraded` — that is stated in the spec §10 and is
the kind of thing that otherwise ends up permanently amber in the settings screen.

`connect()` is a callback API; wrap it in a `suspendCancellableCoroutine` inside
`:android:spotify` so the Sock only ever sees a suspend function returning a boolean.

### D2. One subscription, two consumers

`playerApi.subscribeToPlayerState` is established on connect and written into a
`MutableStateFlow<PlayerSnapshot?>`. It feeds:

- `activityFor` — a pure `.value` read, no Binder call, as the contract requires and as the
  spec's §11 asserts.
- the dashboard card (F).

The spec's §6 already documents the consequence honestly: a silently dead App Remote leaves a
stale cache, the Sock may claim a `shared.stop` it cannot execute, and it then fails with the
normal message rather than passing down the chain. Keep that. Add one cheap improvement the
spec lists as optional and that costs nothing here: the subscription's **error callback clears
the flow to null**, which turns most of that window into a correct `INACTIVE`.

### D3. Audio focus — the one thing App Remote changes

**This is the trap in this plan, and it invalidates step 1 of the spec's §3.**

The spec says: *"Request playback focus from `ctx.playback`. Denied focus → Failed."* With an
in-process player (Radio's ExoPlayer, M4) that is right. With App Remote it is actively wrong:
the audio is produced by **the Spotify app's process**, which holds its own audio focus.
`AndroidPlayback.requestFocus` asks for `AUDIOFOCUS_GAIN` on *Dobby's* behalf, which sends
`AUDIOFOCUS_LOSS` to Spotify — so the Sock would pause the music one instant before asking it
to play, or produce a stuttering start.

The fix is a small, deliberate core change (`socks.specs/README.md` §2: a Sock that needs
something not on `SockContext` is a plan change, not a local workaround). `PlaybackCoordinator`
gains one method:

```kotlin
/**
 * Claim the audio channel for a Sock whose sound is produced by another app.
 *
 * Bookkeeping only: no Android audio focus is requested, because the playing process
 * requests its own and taking GAIN here would stop it. The coordinator still knows who
 * holds the channel, which is what Radio-vs-Spotify arbitration reads.
 */
suspend fun claimExternal(sockId: String): Boolean
```

What still works, and why this is safe:

- **Radio vs Spotify (M4).** Spotify's own focus request evicts Dobby's ExoPlayer through the
  OS; the radio stops itself on `AUDIOFOCUS_LOSS`. `claimExternal` keeps `holder` truthful so
  the coordinator and the dashboard agree with reality.
- **Turn ducking (M2b).** Unaffected and already correct: `requestTransientFocus` →
  `GAIN_TRANSIENT_MAY_DUCK` is cross-process, so Spotify ducks while Dobby speaks and comes back
  up by itself. This is what makes barge-in over music work at all, and nothing here touches it.
- **`releaseFocus`** clears the holder without calling `abandonAudioFocusRequest` when the claim
  was external.

`AndroidPlayback` needs a second field remembering whether the current claim was external, so
`releaseFocus` does the right one of two things.

### D4. Command → App Remote

| Command | Call | Result |
|---|---|---|
| `play_music` (resolved) | `play(uri)` | `Spoken` with the name |
| `play_music` (empty) | `resume()` or `resumeHome()` | `Spoken("Läuft.")` |
| `pause` | `pause()` | `Silent` |
| `resume` | `resume()` | `Silent` |
| `skip_next` | `skipNext()` | `Silent` |
| `skip_previous` | `skipPrevious()` | `Silent` |
| `restart_song` | `seekToStart()` | `Silent` |
| `shared.stop` while `ACTIVE` | `pause()` | `Silent` |
| `shared.resume` while `IDLE` | `resume()` | `Silent` |

`Silent` throughout for the transport commands: the music changing *is* the feedback, and a
panel that says "Okay" over the first second of every song is a panel people switch off.

### D5. The intent, as a fallback only

When the App Remote will not connect but the Spotify app is installed, `play_music` fires
`MEDIA_PLAY_FROM_SEARCH` at `com.spotify.music` with the raw query instead of failing:

```kotlin
Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
    setPackage("com.spotify.music")
    putExtra(SearchManager.QUERY, query)
    putExtra(MediaStore.EXTRA_MEDIA_FOCUS, focusFor(hint))
    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
```

It brings Spotify to the foreground and tells us nothing, so the result is
`Spoken("Ich versuche es über die Spotify-App.")` and the dashboard card stays empty. Strictly
better than "Ich komme gerade nicht an Spotify ran." for the one command where a blind attempt
still does what the user wanted. Only `play_music` gets this; there is no meaningful intent
fallback for pause or skip.

Verify on the vivo before building it — one line, no build:

```
adb shell am start -a android.media.action.MEDIA_PLAY_FROM_SEARCH \
  -p com.spotify.music -e query "bohemian rhapsody queen"
```

---

## E. Commands

Four in the spec, six here. `skip_previous` and `restart_song` are new and are the two commands
that were missing from a music Sock somebody actually talks to.

| Command id | Params | German description (feeds Tier 2) |
|---|---|---|
| `spotify.play_music` | `query: string?`, `hint: enum?` | Spielt Musik auf Spotify ab, optional nach Titel, Künstler oder Playlist. |
| `spotify.pause` | — | Pausiert Spotify — explizit adressiert. |
| `spotify.resume` | — | Setzt Spotify fort — explizit adressiert. |
| `spotify.skip_next` | — | Springt zum nächsten Titel. |
| `spotify.skip_previous` | — | Springt zum vorherigen Titel. |
| `spotify.restart_song` | — | Spielt den laufenden Titel von vorne. |

`play_music`, `pause`, `resume` and `skip_next` keep the spec's templates verbatim, minus the
filler optionals M6c made redundant. The two new ones:

### E1. `spotify.skip_previous`

```
(vorheriger|vorheriges|vorherige|letzter|letztes|letzte) (song|lied|titel|stück|track)
(ein|einen)? (song|lied|titel) zurück
zurück zum (vorherigen|letzten) (song|lied|titel)
(spiel|spiele) (den|das) (vorherige|vorherigen|letzte|letzten) (song|lied|titel)
```

| Utterance | Invocation |
|---|---|
| vorheriger song | `skip_previous()` |
| letztes lied | `skip_previous()` |
| ein lied zurück | `skip_previous()` |
| zurück zum letzten titel | `skip_previous()` |

**Bare `zurück` is not claimed.** Four characters would be tolerance 1 in a bare template, and
it is a word people say to each other in a kitchen. `socks.specs/README.md` §6 asks for the
rejection to be recorded in the spec so the next author does not read the gap as an oversight —
record it.

### E2. `spotify.restart_song`

```
(nochmal|noch mal|neu) von (vorne|vorn|anfang)
(von|zum) (vorne|anfang)
(spiel|spiele|mach) (das lied|den song|das stück) (nochmal|noch mal|neu)
(das lied|den song|den titel) (nochmal|neu) starten
```

| Utterance | Invocation |
|---|---|
| nochmal von vorne | `restart_song()` |
| von vorne | `restart_song()` |
| spiel das lied nochmal | `restart_song()` |
| den song neu starten | `restart_song()` |

**Bare `nochmal` is not claimed** either, for a different reason: it is a general-purpose
repetition word that a later Sock (or the conversation turn) has a much better claim on.
`noch`, `nochmal` and `wieder` are on the never-a-filler list (`socks.specs/README.md` §6),
so when they are said they are in the transcript — which is exactly what makes them safe as
*anchors* here and unsafe as a whole template.

### E3. The ordering hazard, extended

The spec's §8 names one ordering assertion. There are now three, all in the same family, all
of them "a specific phrasing must sort ahead of a generic one":

1. `spiele {query}` is greedy and must sort **after** Radio's `radio`-bearing templates (M4).
2. `weiter zum nächsten` (`skip_next`) must sort ahead of bare `weiter` (`shared.resume`).
3. **New:** `spiel das lied nochmal` (`restart_song`) versus
   `(spiel|spiele) (den|das) (letzte|letzten) (song|lied)` (`skip_previous`) versus
   `(spiele|spiel) {query}` (`play_music`). All three begin `spiel …`. The first two are closed
   templates and the third has a text slot, so `Specificity.ORDER` already sorts them correctly
   — closed before open. This is a property of the ordering rule, not of how they are written,
   and it should be asserted rather than assumed.

Registration order within the Sock does not decide this; the registry computes it. Write the
templates in the order that reads well and let the assertion catch a regression.

---

## F. Dashboard and state

`val nowPlaying: StateFlow<PlayerSnapshot?>` on the Sock, mapped straight from D2's
subscription. `DobbySocks.Wiring` keeps the Sock by name, `DobbyController` exposes it as
`val spotify: StateFlow<PlayerSnapshot?>`, `MainActivity` draws a `NowPlayingCard` next to the
existing `ClockCard`. That path already exists end to end for the clock; this is the second
user of it and should not need to change it.

The card: title, artist, a progress bar, play/pause state, album art. Black background, no
animation — the panel's screen is meant to be dark (`dobby-plan.md` §7.2).

Album art comes from App Remote's `imagesApi.getImage(imageUri, Dimension.LARGE)` and **not**
from the Web API: it arrives as a `Bitmap` over the existing connection, with no URL, no extra
HTTP client and no token. Load it in `:android:spotify` and hand the Sock a `Bitmap?` through a
separate small flow, so the JVM module never sees an Android type.

Progress: the position in a `PlayerState` update is a snapshot, not a ticker. Interpolate in the
card from `positionMs` plus elapsed wall time while `!isPaused`, the way `ClockSock.tick`
already aligns to the second boundary. Do not poll App Remote for it.

---

## G. Config

| Key | Type | Default | Where set |
|---|---|---|---|
| `spotify.market` | string | `AT` | Settings |
| `spotify.prefer_track_over_artist` | bool | `true` | Settings (advanced) |
| `spotify.ask_when_unsure` | bool | `true` | Settings (advanced) — gates C5 entirely |

Gone from the spec's §9: `spotify.auth_token` and `spotify.refresh_token`, per B1.

`spotify.ask_when_unsure` exists because C5's trigger is a guess about human patience, and the
honest way to find out whether it is right is to be able to switch it off without a rebuild —
the same argument `Settings.micProfile` makes for the M2b measurement.

---

## H. Failure and degradation

German copy, verbatim, mostly unchanged from the spec's §3:

| Condition | Status | Result | German TTS |
|---|---|---|---|
| Spotify app not installed | `Unavailable` | `Failed` | "Spotify ist auf diesem Gerät nicht installiert." |
| No credentials in the build | `Unavailable` | `Failed` | "Spotify ist nicht eingerichtet." |
| App Remote not connectable | — | fallback D5, else `Failed` | "Ich komme gerade nicht an Spotify ran." |
| Not Premium (403 from App Remote) | `Unavailable` | `Failed` | "Dafür brauche ich Spotify Premium." |
| Token request fails (401/400) | `Degraded` | `Failed` | "Ich komme gerade nicht an die Spotify-Suche ran." |
| Search returns nothing | — | `Failed` | "Ich habe auf Spotify nichts gefunden." |
| No network | — | `Failed` | "Ich habe gerade keine Internetverbindung." |
| Rate limited (429) | `Degraded` | `Failed` | "Spotify lässt mich gerade nicht so oft suchen." |
| Search times out (C2) | — | `Failed` | "Die Suche hat zu lange gedauert." |

Two lines are deleted from the spec's table: "Spotify ist nicht verbunden. Bitte melde dich in
den Einstellungen an." has no path that reaches it any more (B1), and the old "App Remote not
connectable" now has the fallback in front of it.

Never fail silently. A disconnect is not degradation (D1).

---

## I. Verification

This project does not add unit tests for new work. What matters is which of the **existing**
build-time assertions this Sock walks into for free, because those are the ones that will fail
the build if the plan is implemented carelessly:

- `SpecPaletteTest` / `RegistryValidationTest` — every utterance table in §E and in the spec is
  asserted against the real palette on every build, as soon as the examples are written into the
  `ExclusiveCommandSpec`s. This is where the E3 ordering hazards get caught.
- The **cross-Sock collision gate** — no two Socks may match the same utterance for different
  commands. `spiele {query}` is the greediest template in the project; this gate is the thing
  standing between it and every other Sock.
- The **filler invariants** (M6c) — no filler may satisfy a template on its own, and no two
  commands may become the same sentence once fillers are dropped. `von vorne` and
  `ein lied zurück` are both short and both near the boundary; if either trips this, the build
  says so with both templates named.
- `IntrospectionTest` — the Help Sock's directory has to be able to describe the new commands.

Manual, on the vivo, in this order — each step is a real failure mode that no assertion can see:

1. `adb shell am start -a android.media.action.MEDIA_PLAY_FROM_SEARCH -p com.spotify.music -e query "bohemian rhapsody queen"` — does the intent fallback (D5) exist on this device's Spotify build at all?
2. Token fetch: one `curl` against `/api/token` with the real credentials before any Kotlin is written.
3. First App Remote connect → the consent dialog appears → "Spiele Blinding Lights von The Weeknd" plays the right track. **This is M1's done-when.**
4. "Stopp" with music playing and no timer ringing → music pauses. "Stopp" with a timer ringing
   and music playing → the chime stops, the music does not. (The chain, with a real second
   subscriber for the first time.)
5. Talk over the music: wake word → the music ducks → command → music comes back up. This is the
   one that the whole product depends on, and D3 is the reason it could break.
6. Leave it overnight. Next morning, "nächster song" reconnects and works (D1).

---

## J. Spec deltas

`socks.specs/spotify.specs.md` needs these edits, and they are part of the work, not follow-up:

- §1 — drop the Spotify Auth Library from dependencies; add the App Remote AAR note (A3); change
  Secrets to `SPOTIFY_CLIENT_ID` + `SPOTIFY_CLIENT_SECRET`, both in `local.properties`, with the
  extractability note from B1.
- §2 — add `skip_previous` and `restart_song` (E).
- §3 — rewrite step 1 (audio focus → `claimExternal`, D3), step 3a/3b (free text, not field
  filters, C1–C3), step 2 (home feed, not Liked Songs, C6). Add the timeout budget (C2) and the
  normalizer note (C4).
- §3 failure table — as in H.
- §5 — the two new commands, with the bare-`zurück` and bare-`nochmal` rejections recorded
  (E1, E2).
- §8 — extend the collision surface with `vorheriger/letzter <song|lied|titel>`, `… zurück`,
  `von vorne`, `nochmal von vorne`; add the third ordering hazard (E3).
- §9 — the config table from G.
- §12 — move the ambiguity question out of "open" and into C5; leave private playlists there
  as newly open, pointing at B4.
- `socks.specs/README.md` §8 — mark Spotify ✅ when it lands.
- `socks.specs/shared-commands.specs.md` — Spotify is now a real subscriber on both chains
  rather than a documented intention.

---

## K. Work order

Each step ends somewhere runnable. The order is chosen so the two riskiest unknowns — the
developer-dashboard setup and D3 — are hit early, before there is code depending on them.

1. **Dashboard + credentials.** Register the app, both SHA-1s, the redirect URI. `curl` the
   token endpoint and a search. Put the credentials in `local.properties`. No Kotlin.
2. **`:socks:spotify` skeleton.** Module, interfaces (A1), the six `ExclusiveCommandSpec`s with
   full templates and examples, both `SharedSubscription`s, `NONE` implementations. Wire into
   `DobbySocks` and `cli/Main.kt`. The build now asserts every utterance table (I) and the CLI
   can route "spiele blinding lights" to a Sock that politely fails.
3. **Search.** `SpotifyTokens`, `OkHttpMusicSearch`, the selection heuristic (C3). Still no
   Android. Drive it from the CLI against the real API — the output is a URI printed to a
   terminal, which is the cheapest possible feedback loop for the part with the most judgement
   in it.
4. **`claimExternal`.** The core change (D3), `AndroidPlayback`, the spec note. Small, and
   everything after it assumes it.
5. **`:android:spotify`.** App Remote behind `SpotifyPlayer`, the connect/reconnect lifecycle,
   the `PlayerState` subscription. `SpotifyHardware` in the app, released by `DobbyService`.
   **M1's done-when is reachable at the end of this step** — run checks 3–6 from §I.
6. **The follow-up question** (C5) and the empty-query home feed (C6).
7. **Dashboard card** (F), settings entries (G), spec deltas (J).

---

## Out of scope

- **PKCE / user authorization** (B4), and with it private playlists, Liked Songs by name and
  anything else user-scoped. The seam is `bearer()`; the trigger is a real complaint, not a
  hypothetical one.
- **Spotify Connect** — playing to another device. The panel plays on the panel.
- **Seek, shuffle, repeat, volume, liking a track, playlist management.** Volume belongs to the
  System Sock, and the rest are v2 (spec §12). `restart_song` is in because "nochmal von vorne"
  is a thing people say to a music player; a general seek is not.
- **Caching search results.** The same query twice in one evening is not a pattern worth a cache
  and its invalidation.
- **The liveness flag for a silently dead App Remote** beyond D2's error-callback clear. The
  spec's §12 judgement stands: worth doing if it shows up in practice, not worth a Binder call
  per utterance.
