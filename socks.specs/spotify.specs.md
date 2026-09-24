# Sock: Spotify

## 1. Identity

| | |
|---|---|
| **id** | `spotify` |
| **displayName** | Spotify |
| **Purpose** | Play and control music on the account's Spotify Premium session. |
| **Milestone** | M1 |
| **Modules** | `:socks:spotify` (JVM — the Sock, the search, the selection) and `:android:spotify` (the App Remote) |
| **Dependencies** | `spotify-app-remote` SDK, Spotify Web API (`/v1/search`) via OkHttp + kotlinx.serialization |
| **Hard requirements** | Spotify **Premium** account; Spotify app installed on the device; app registered in the Spotify Developer Dashboard with package name + release/debug SHA-1 |
| **Permissions** | `INTERNET` |
| **Secrets** | `spotify.client.id`, `spotify.client.secret`, `spotify.redirect.uri` → `local.properties`, never committed |
| **Audio** | `PlaybackCoordinator.claimExternal` — **not** `requestFocus`. See §3, step 1. |

The Spotify Auth Library (`com.spotify.android:auth`) is **not** a dependency. It is the library
for the user OAuth flow, which this Sock does not do (§1.2); App Remote authorizes itself.

The App Remote SDK is not on Maven Central either — Spotify attaches a bare `.aar` to a GitHub
release. `:android:spotify` fetches it pinned by SHA-256 into a git-ignored local Maven
repository; see [`android/spotify/README.md`](../android/spotify/README.md) for why that is a
repository and not a file dependency, and why the fetch runs at configuration time.

### 1.1 Authentication: an app token, and no login screen

`/v1/search` reads the public catalogue. It needs a token, but not a *user's* token:

```
POST https://accounts.spotify.com/api/token
Authorization: Basic base64(client_id:client_secret)
Content-Type: application/x-www-form-urlencoded

grant_type=client_credentials
```

→ `{"access_token": "...", "token_type": "Bearer", "expires_in": 3600}`

Note what is absent: **no refresh token**, because there is nothing to refresh. When it expires
you make the same request again. That deletes `spotify.auth_token` and `spotify.refresh_token`
from §9, deletes the encrypted-storage requirement, deletes the redirect URI from the *search*
path, and deletes the settings screen the failure table used to point at ("Bitte melde dich in
den Einstellungen an").

`SpotifyTokens` holds the token behind a mutex. That is not decoration: `handle()` runs once per
utterance, but the token fetch is an I/O suspend, and two music commands in one turn — "spiel
was von queen", then "nächster song" — must not each open their own token request.

The App Remote connection is a **different** authorization, and it is almost free:
`ConnectionParams.Builder(clientId).setRedirectUri(uri).showAuthView(true)` makes the Spotify app
show a one-time consent dialog on the first connect. After that the connection is established
without UI, and there is no token on our side at all.

**The client secret is in the APK and extractable.** For a wall panel in one flat the blast
radius is somebody burning our search rate limit, and the fix is rotating the secret in the
developer dashboard. Same class of accepted risk as the non-commercial wake-word models
(`dobby-plan.md` §9), recorded here so it is not rediscovered as a surprise. If Dobby is ever
handed to anyone else, this is the **first** thing that has to change.

### 1.2 What an app token cannot see, and the seam that buys it back

| Works | Does not work |
|---|---|
| tracks, artists, albums | the user's Liked Songs |
| **public** playlists | the user's *private* playlists |
| popularity, market filtering | "recently played", personalisation |

Two places this bites, and both have an answer:

- **Empty query → the user's own music.** Not the Web API at all: App Remote's content API runs
  inside their session and has none of these limits (§3, step 2). Arguably the better behaviour.
- **"mach die playlist entspannen an" when *entspannen* is their own private playlist.** No
  workaround. It finds a public playlist of that name or fails with the normal "nichts
  gefunden". Accepted for v1 — this is the one feature the auth decision costs.

The upgrade, if it ever matters, is Authorization Code + PKCE: `client_id` only (no secret in
the app — that is the point of PKCE), a redirect URI registered as an intent-filter, a one-time
login in a Custom Tab, and a refresh token in `EncryptedSharedPreferences`. The seam that makes
it a contained change is that **everything goes through `suspend fun bearer()`**. `SpotifyTokens`
is the only class that knows which flow produced the string, and `MusicSearch` never asks. Do not
leak a token type, a scope or a "logged in" flag past that boundary.

### 1.3 Module split

The Sock sees exactly two interfaces, both declared in `:socks:spotify`:

```kotlin
/** Everything the Sock does to the Spotify app. App Remote on the device. */
interface SpotifyPlayer {
    val state: StateFlow<PlayerSnapshot?>   // cached; activityFor is a pure read of this
    val isInstalled: Boolean
    suspend fun connect(): Connection
    suspend fun play(uri: String): Boolean
    suspend fun resumeHome(): String?       // empty query: the user's own home feed
    suspend fun pause(): Boolean
    suspend fun resume(): Boolean
    suspend fun skipNext(): Boolean
    suspend fun skipPrevious(): Boolean
    suspend fun seekToStart(): Boolean
    fun disconnect()
    companion object { val NONE: SpotifyPlayer }
}

/** The catalogue lookup. OkHttp, same module, no Android types. */
interface MusicSearch {
    suspend fun find(query: String, hint: QueryHint, market: String): Hits
    companion object { val NONE: MusicSearch }
}
```

`connect` returns a small `Connection` result rather than a boolean, and every other call is a
boolean, and the asymmetry is deliberate: "no Premium" has its own German sentence and its own
`Unavailable` status, while an overnight disconnect has neither and must not be confused with it
(§10). Everything else fails with one sentence, so a boolean carries everything the Sock needs.

`PlayerSnapshot` is deliberately not Spotify's `PlayerState`: the Sock module must not see an SDK
type, and the dashboard wants a flat thing it can draw. **Album art is not on the interface at
all** — it arrives as an Android `Bitmap` over the same connection, so it stays on the device
side and reaches the dashboard from `SpotifyHardware` directly (§7).

Off-device — the CLI harness, any unit test — the Sock is constructed with `SpotifyPlayer.NONE`
and `MusicSearch.NONE`, reports `Unavailable`, and fails every command with the spoken line from
the table in §3. That keeps `cli/Main.kt` compiling and keeps the registry complete: the palette
must contain every command whether or not a device is present.

## 2. Commands

### Exclusive

| Command id | Params | Description (German, feeds Tier 2) |
|---|---|---|
| `spotify.play_music` | `query: string?`, `hint: enum?` | Spielt Musik auf Spotify ab, optional nach Titel, Künstler oder Playlist. |
| `spotify.pause` | — | Pausiert Spotify — **explizit adressiert**. |
| `spotify.resume` | — | Setzt Spotify fort — **explizit adressiert**. |
| `spotify.skip_next` | — | Springt zum nächsten Titel. |
| `spotify.skip_previous` | — | Springt zum vorherigen Titel. |
| `spotify.restart_song` | — | Spielt den laufenden Titel von vorne. |
| `spotify.whats_the_song` | — | Sagt, welcher Titel gerade auf Spotify läuft — mit Künstler. |
| `spotify.whats_the_artist` | — | Sagt, wer den Titel spielt, der gerade auf Spotify läuft. |

`skip_previous` and `restart_song` are the two commands that were missing from a music Sock
somebody actually talks to. `whats_the_song` and `whats_the_artist` are the two that were
missing from one somebody *listens* to: every other command in this Sock changes what is
playing, and these are the only ones that ask about it.

### Shared subscriptions

| Shared command | Priority | Why |
|---|---|---|
| `shared.stop` | 50 | Bare "stopp" / "pause" / "aus" — target depends on what is running |
| `shared.resume` | 50 | Bare "weiter" — a paused song is the likeliest referent |

Bare stop/pause/resume phrasings are **not** owned by this Sock; they live in
[shared-commands.specs.md](shared-commands.specs.md). The exclusive `pause`/`resume` commands
remain for utterances that name Spotify or "die Musik" explicitly, which are unambiguous and
bypass the chain.

---

## 3. `spotify.play_music`

### Params

| Name | Type | Required | Default | Notes |
|---|---|---|---|---|
| `query` | string | no | `""` | Free text, taken verbatim from the transcript. May be phonetically mangled — that is expected and tolerated. |
| `hint` | enum | no | — | `title_and_artist` \| `artist_only` \| `unknown`. Fixed by the *wording*, through a static param; absent means the handler derives it. |

### Tier 1 templates

```
(spiele|spiel) (den song|das lied|den titel|die playlist) {query}
(spiele|spiel) (etwas|was|irgendwas) von {query}          → hint = artist_only
(spiele|spiel|starte|start) spotify                       → query = ""
spotify (abspielen|starten)                               → query = ""
(mach|schalt|schalte|leg) spotify (an|auf)                → query = ""
(spiele|spiel) (die)? musik                               → query = ""
(mach|schalt|schalte|leg) (die)? musik (an|auf)           → query = ""
musik (abspielen|starten)                                 → query = ""
(spiele|spiel) (etwas|was|irgendwas)                      → query = ""
(spiele|spiel) (etwas|was|irgendwas) musik                → query = ""
(mach|leg|spiel) {query} (an|auf)
(spiele|spiel) {query}( ab)?
(musik|spotify) (an|einschalten)
```

Order matters: the bare `{query}` template is greedy and must stay second-to-last;
`(musik|spotify) an` is last so it cannot swallow "spiele musik von …". `Specificity.ORDER`
produces this ordering from the templates themselves — it is not declared.

**The eight empty-query templates are closed on purpose, and that is the whole point of them.**
A slot takes whatever token is sitting there, so before they existed "spiele Spotify" bound the
query `spotify` and searched the catalogue for the word Spotify; "spiele Musik" searched for
`musik`, "mach die Musik an" for `die musik`, and "spiele was" for `was`. None of those is a
thing anybody wanted played — they are sentences *about* playing. Being closed, they sort ahead
of both slot templates by specificity and bind nothing, which is what makes the empty-query
behaviour below reachable by the words people actually use for it.

`spiel mir` and `spiele mir` are gone from every branch: "mir" is filler and the matcher skips it
(M6c). What is **not** gone is any optional in front of a `{query}` slot — skipping happens
before a keyword, never before a slot ([README §6](README.md#6-template-dsl-tier-1)).

### Utterances → invocation (this table is the unit test)

| Utterance | Invocation |
|---|---|
| spiele blinding lights von the weeknd | `play_music(query="blinding lights von the weeknd")` |
| spiel was von queen | `play_music(query="queen", hint=artist_only)` |
| spiele den song bohemian rhapsody | `play_music(query="bohemian rhapsody")` |
| mach die playlist entspannen an | `play_music(query="die playlist entspannen")` |
| spiele die playlist entspannen | `play_music(query="entspannen")` |
| schbiele bleinding leitz | `play_music(query="bleinding leitz")` — phonetic hit on "spiele" |
| musik an | `play_music(query="")` |
| spotify einschalten | `play_music(query="")` |
| spiele spotify | `play_music(query="")` |
| spotify starten | `play_music(query="")` |
| mach spotify an | `play_music(query="")` |
| spiele musik | `play_music(query="")` |
| mach die musik an | `play_music(query="")` |
| spiele was | `play_music(query="")` |

> `spiele musik von queen` still binds `query="musik von queen"` and searches, because the closed
> `(spiele|spiel) (die)? musik` cannot match an utterance with three more content words in it.
> Anchoring at both ends is what keeps the two apart.

### Behavior

1. **Claim the audio channel with `ctx.playback.claimExternal(id)`, not `requestFocus`.** This
   is the trap in this Sock, and it is why the core interface grew a method. The audio is
   produced by **the Spotify app's process**, which holds its own audio focus. Requesting
   `AUDIOFOCUS_GAIN` on Dobby's behalf sends `AUDIOFOCUS_LOSS` to Spotify — pausing the music one
   instant before asking it to play, or producing a stuttering start. `claimExternal` is
   bookkeeping only: no Android focus is requested, and the coordinator still knows who holds the
   channel, which is what Radio-vs-Spotify arbitration and the dashboard read. The eviction
   `requestFocus` would have done happens anyway, one layer down: Spotify's own focus request
   reaches Dobby's ExoPlayer through the OS. Turn ducking (M2b) is untouched —
   `requestTransientFocus` → `GAIN_TRANSIENT_MAY_DUCK` is cross-process, so Spotify ducks while
   Dobby speaks and comes back up by itself, which is what makes barge-in over music work at all.
2. **Empty query — "spiele Spotify":** no Web API call at all. Three cases, in order:
   - Cached snapshot has a track and it is **paused** → `resume()`. This is the one people mean:
     Spotify is sitting in the background on a track somebody paused an hour ago, possibly from
     Spotify's own UI and with Dobby never having connected, and "spiele Spotify" picks it up
     where it was left. It works from cold because the snapshot is read *after* the lazy connect,
     and `connect()` waits (up to `FIRST_STATE_MS`, 1 s) for the subscription's first event
     before returning — so the track the app is paused on is already in the cache by then.
   - Cached snapshot has a track and it is **playing** → `Spoken("Läuft.")`, and nothing else.
     Falling through to the home feed here would *change the track*: "mach mal Musik an" said
     into a room that already has music is a remark, not an instruction to play something else.
     Same sentence `spotify.resume` says for the same state.
   - **No snapshot** → `resumeHome()`, App Remote's `contentApi.getRecommendedContentItems`,
     which is the user's own home feed (recently played, their playlists, made-for-you) running
     inside their session, with none of §1.2's limits. In practice that is the same track again:
     recently-played is what the feed leads with. Nothing playable at all → `Failed`.

   There is deliberately **no separate `spotify.play_spotify` command.** "Continue whatever
   Spotify was doing" is exactly what `play_music` with no query already meant; a second command
   would be a second name for one behaviour, and the palette would then have two commands one
   sentence could plausibly land on.
3. **Non-empty query:** resolve, then `playerApi.play(uri)`. See §3.1–§3.3.
4. Connect the App Remote **lazily** on the first music command; on a dead cached connection
   reconnect exactly once before failing. Long-lived connections die overnight, and that is
   **normal** (§10).

### 3.1 Send the sentence, not a query language

The first draft of this spec split on `" von "` and built `q="track:<title> artist:<artist>"`.
**That is inverted.** Field filters are near-exact and the transcript is not:
`q=track:bleinding leitz artist:se weeknd` returns an empty array, while the free-text
`q=bleinding leitz se weeknd` still ranks the right track first, because Spotify's own ranker is
fuzzy and the filter syntax is not. The Sock's input is a phonetically mangled ASR transcript —
stated as expected and tolerated in the params table above — so the robust form is the only form.

The `" von "` split survives as a **hint**, never as a query builder:

- `"blinding lights von the weeknd"` → `title_and_artist`, derived from the split
- `"queen"` — matched by the `(etwas|was|irgendwas) von {query}` template, which already ate the
  `von` — → `artist_only`, set by the template through a **static param**
  ([README §6](README.md#6-template-dsl-tier-1)), not re-derived in the handler
- everything else → `unknown`

The split spells `" von "` with its spaces. "Bon Iver" and "Von Wegen Lisbeth" are bands, and a
bare `contains("von")` cuts both in half.

**The normalizer has already touched the query**, and it is invisible unless written down: the
`{query}` slot receives the *normalized* transcript, which has had German number words turned
into digits. "Sieben Leben" arrives as `7 leben`, "Vier Jahreszeiten" as `4 jahreszeiten`. Free
text handles this better than field filters would — another point for the paragraph above — and
Spotify generally finds `4 jahreszeiten`. **Accepted, not fixed.** If the fallthrough log shows
number-word titles failing, the fix is to search the de-digitized variant as a second attempt,
not to change the normalizer, which `{amount:int}` depends on.

Filler words *inside* the slot are kept: `{query}` captures verbatim, which is why "spiele es
muss liebe sein" binds the whole title.

### 3.2 The request

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
| `title_and_artist` | `track` |
| `artist_only` | `artist,track` |
| `unknown` | `track,artist,playlist` |

`market` matters more than it looks: without it the response contains tracks that are not
playable in Austria, which produce a successful search and a silent failure to play.

**Budget: 2.5 s total** for the token (only when expired) plus the search, enforced as one
`withTimeout` around the whole resolution, over OkHttp socket timeouts of 5 s. One number,
because one number is what a person feels. A music command that takes four seconds to start feels
broken; failing with "Die Suche hat zu lange gedauert" is worse than a two-second wait and better
than an eight-second one. Tier 2 already costs 2–4 s on the paraphrase path, and stacking a slow
search on top of it is the case to protect against.

A 401 is retried exactly once, after invalidating the cached token. Nothing else is retried: a
panel that quietly retries a 500 is a panel that takes eight seconds to say it failed.

### 3.3 Selection: trust the ranker, break ties

Spotify's ordering is already the best signal available; the heuristic's job is to pick a *kind*,
not to re-rank a list we understand worse than they do.

1. `title_and_artist` → the top track whose artist loosely matches the spoken artist. None
   matches → `tracks[0]` anyway; the ASR mangled the artist, not the intent.
2. `artist_only` → `artists[0]` if its name loosely matches the query, else `tracks[0]`.
3. `unknown` → `tracks[0]`, unless `spotify.prefer_track_over_artist` is false, or the query
   loosely equals `artists[0].name` and `tracks[0]` is not a much stronger hit (popularity gap
   < 10). A playlist only if there is no track and no artist at all.
4. Nothing in any bucket → `Failed("Ich habe auf Spotify nichts gefunden.")`

Loose matching is one small pure function — normalize, drop the filler words (`Fillers.DE` is
already in `:core` and already on this module's classpath), compare token sets, ≥ 0.6 overlap of
the shorter side. It is not Levenshtein and not phonetics; the fuzziness that matters already
happened inside Spotify's ranker.

Artists and playlists are played by URI directly — `playerApi.play("spotify:artist:…")` starts
that artist's tracks, which is exactly what "spiel was von queen" means.

### 3.4 When to ask instead of guess

Deliberately **narrow**: a panel that interrogates you about every song is worse than one that
occasionally plays the wrong version. Ask only when all of these hold, and only while
`spotify.ask_when_unsure` is on (§9):

- the hint is `unknown` or `artist_only` — an explicit "X von Y" is not ambiguous, they told us
- the top two tracks have **different artists**
- their popularity gap is **< 10**
- the query did not loosely match an artist name — that is case 2 above, and it is decided

Two-way:

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
question's scoped palette gets first refusal on the next utterance, so a bare `{text}` slot would
turn "wie spät ist es" into an answer. Ordinals are closed, short, and what people actually say.
"Egal" takes the first — it is the user handing the decision back, which is what the first hit
already was. Anything else falls through to the global palette, `onAskCancelled` fires, and the
candidates are dropped: the user changed their mind, which is allowed.

The candidates live in a `ConcurrentHashMap<String, List<Hit>>` keyed by token, the same shape as
`ClockSock.asked`. A token we no longer hold → `Failed`, the same as the clock's `BAD_DURATION`
path.

### 3.5 The intent, as a fallback only

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

It brings Spotify to the foreground and tells us nothing — no URI, no confirmation, and an empty
dashboard card — but it still does what the user wanted, so the result is `Ended()` like every
other successful play, not `Failed("Ich komme gerade nicht an Spotify ran.")`. Nothing is spoken
here either: the intent starts an *activity*, so the handoff shows itself — Spotify comes up over
the panel. **Only `play_music` gets this**; there is no meaningful intent fallback for pause or
skip, and the empty query has nothing to search for.

This is also the argument for App Remote being the primary path rather than the intent: the
intent starts an *activity*, so Spotify covers the panel; it reports neither what it played nor
whether it worked; and every other command here needs a live connection anyway, as does
`activityFor` (§6).

### Result

- **Every success → `Ended()`: nothing spoken, and the turn is closed.** Not `Spoken`, and not
  the open microphone every other command leaves behind. Two reasons, and the second is the
  load-bearing one:
  - The result is *audible on its own*. "Spiele Blinding Lights." arriving on top of Blinding
    Lights starting is Dobby talking over the thing it was asked for.
  - The room has just gone loud. Holding the microphone open for a follow-up now means
    listening for a voice through the music this command started — so the play command hands
    the turn back to the wake word instead of waiting out a window it cannot hear through.
- This covers all three paths: a resolved hit, a resume or home feed on the empty query, and
  the intent fallback (§3.5).
- What was played is not lost: the dashboard card (§7) names it, and the Sock logs it. The
  spoken confirmation was always short deliberately; now it is not spoken at all.
- **Failures still speak** and still leave the microphone open — an error nobody hears is an
  error nobody can answer, and the obvious next thing is to say it again.

### Failure modes

| Condition | Status | Result | German TTS |
|---|---|---|---|
| Spotify app not installed | `Unavailable` | `Failed` | "Spotify ist auf diesem Gerät nicht installiert." |
| No credentials in the build | `Unavailable` | `Failed` | "Spotify ist nicht eingerichtet." |
| App Remote not connectable | — | fallback §3.5, else `Failed` | "Ich komme gerade nicht an Spotify ran." |
| Not Premium (App Remote refuses authorization) | `Unavailable` | `Failed` | "Dafür brauche ich Spotify Premium." |
| Token request fails (401/400) | `Degraded` | `Failed` | "Ich komme gerade nicht an die Spotify-Suche ran." |
| Search returns nothing | — | `Failed` | "Ich habe auf Spotify nichts gefunden." |
| No network | — | `Failed` | "Ich habe gerade keine Internetverbindung." |
| Rate limited (429) | `Degraded` | `Failed` | "Spotify lässt mich gerade nicht so oft suchen." |
| Search times out (§3.2) | — | `Failed` | "Die Suche hat zu lange gedauert." |

"Spotify ist nicht verbunden. Bitte melde dich in den Einstellungen an." is **deleted**: with
client credentials there is no path that reaches it (§1.1).

Never fail silently. A disconnect is not degradation (§10).

---

## 4. `spotify.pause` (explicitly addressed)

### Tier 1 templates

```
(musik|spotify|wiedergabe) (aus|stoppen|anhalten|pausieren)
(stopp|stoppe|stop|pausiere|pausier|halt) (die musik|die wiedergabe|spotify)
(mach|schalt|schalte) (die musik|spotify) aus
```

Every template names the target. Bare `stopp` / `pause` / `aus` are **not** here — they go to
`shared.stop`.

### Utterances → invocation

| Utterance | Invocation |
|---|---|
| musik aus | `pause()` |
| stopp die musik | `pause()` |
| pausiere spotify | `pause()` |
| mach die musik aus | `pause()` |
| spotify anhalten | `pause()` |

| Utterance | Goes to |
|---|---|
| stopp | `shared.stop` — chain decides |
| pause | `shared.stop` — chain decides |
| aus | `shared.stop` — chain decides |

### Behavior

`playerApi.pause()`. Because this command was explicitly addressed to Spotify it does **not**
consult the chain and does not fall through to another Sock — the user said "die Musik", they
meant this.

### Result

- Playing → `Silent` (the music stopping *is* the feedback; TTS here is noise).
- Nothing playing → `Spoken("Auf Spotify läuft gerade nichts.")` — unlike the chain case, the
  user named a target, so silence would look like a failure.
- App Remote unreachable → `Failed`, "Ich komme gerade nicht an Spotify ran."

---

## 5. `resume`, `skip_next`, `skip_previous`, `restart_song`

All four answer `Silent` on success. The music changing *is* the feedback, and a panel that says
"Okay" over the first second of every song is a panel people switch off. All four fail with
"Auf Spotify läuft gerade nichts." when nothing is loaded.

### `spotify.resume` (explicitly addressed)

`weiter mit spotify` / `weiter auf spotify` are here rather than on the chain: they name their
target, so they bypass `shared.resume` the way every other template in this section does. Bare
`weiter` stays the chain's.

```
(die musik|die wiedergabe|spotify) (weiter|fortsetzen|weiterspielen)
(spiel|spiele|mach) (die musik|spotify) weiter
```

| Utterance | Invocation |
|---|---|
| spiel die musik weiter | `resume()` |
| spotify fortsetzen | `resume()` |
| die wiedergabe weiterspielen | `resume()` |

| Utterance | Goes to |
|---|---|
| weiter | `shared.resume` — chain decides |
| mach weiter | `shared.resume` — chain decides |

Behavior: `claimExternal`, then `playerApi.resume()`. Already playing →
`Spoken("Läuft.")`. Nothing loaded → `Failed`, "Auf Spotify ist nichts pausiert."

### `spotify.skip_next`

```
(nächster|nächstes|nächste) (song|lied|titel|stück|track)
(überspringen|skip|skippen|weiter zum nächsten)
(spiel|spiele) (den|das) (nächste|nächsten|nächstes) (song|lied|titel)
```

| Utterance | Invocation |
|---|---|
| nächster song | `skip_next()` |
| nächstes lied | `skip_next()` |
| überspringen | `skip_next()` |
| weiter zum nächsten | `skip_next()` |

> ⚠️ `weiter zum nächsten` must be ordered **before** the bare `weiter` of `shared.resume`. The
> registry orders specific → generic; asserted explicitly — see
> [shared-commands.specs.md](shared-commands.specs.md) §4.

`skip`, `skippen` and `überspringen` are **bare keywords**, which
[README §6](README.md#6-template-dsl-tier-1) asks to be justified one by one. All three are
loanwords with no German neighbour inside their tolerance, and the bare-keyword rule means
leading filler is not skipped, so the whole utterance has to *be* one of them. `skip` is the
closest call — four characters, tolerance 1 — and it is kept because the words it collects
(`ski`, `slip`) are not things anybody says to a panel on their own.

Stays exclusive: no other Sock can skip anything in v1. Promote to `shared.next` when one can.

### `spotify.skip_previous`

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
| spiel den letzten song | `skip_previous()` |

**Bare `zurück` is not claimed, and that is a decision, not a gap.** Six characters is tolerance
1 in a bare template, and it is a word people say to each other in a kitchen with no panel
involved. `zurück` is also on the never-a-filler list (sequence words), so when it is said it is
in the transcript — which is what makes it safe as an *anchor* here and unsafe as a whole
template.

Behavior: `playerApi.skipPrevious()`.

### `spotify.restart_song`

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
repetition word that a later Sock — or the conversation turn itself — has a much better claim on.
`noch`, `nochmal` and `wieder` are on the never-a-filler list, which again makes them good
anchors and bad templates.

Behavior: `playerApi.seekTo(0)` — a seek, not a re-`play(uri)`, which would rebuild the queue.

---

## 5a. `whats_the_song`, `whats_the_artist`

The two commands that only read. Both take no params and both answer from the cached snapshot.

### Tier 1 templates

`spotify.whats_the_song`:

```
was ist das für ein (lied|song|titel|stück)
was für ein (lied|song|titel|stück) ist das
(wie|welches) (heißt|heisst) (das lied|das stück|die nummer)
wie (heißt|heisst) (der song|der titel)
welches (lied|stück) ist das
welcher (song|titel) ist das
was (läuft|spielt) (da|hier)?
welche musik (läuft|spielt)
wie (heißt|heisst) das
```

`spotify.whats_the_artist`:

```
was ist das für ein (künstler|interpret|sänger|artist)
was ist das für eine (band|sängerin|gruppe)
wie (heißt|heisst) (der künstler|der interpret|der sänger|der artist)
wie (heißt|heisst) (die band|die sängerin|die gruppe)
wer (spielt|singt) (das|den song|das lied|hier)?
von wem ist (das|der song|das lied|das stück)
wer ist (der|die) (künstler|interpret|sänger|band|sängerin)
```

**The articles are written out although `das`, `der` and `die` are fillers.** Skipping drops
*extra* words from the utterance; it does not make a template's own literals optional. "Wie
heißt das Lied" needs its `das`, and what the filler list buys is "wie heißt denn gerade das
Lied" for free — the particles, not the articles.

**Both spellings of `heißt`.** The phonetic tier folds ß to ss and would reach `heisst` anyway,
but the commonest phrasing in this Sock belongs on the strict first pass rather than on the
fallback (`README.md` §6).

**`wie heißt das` is the loosest wording here and resolves to the song, not the artist.**
Somebody pointing at a speaker with three words is asking what the thing is called, and that
answer names the artist as well.

**Bare `wer ist das` is deliberately not claimed** — the one wording from the original request
that is absent. It is a question about a person at least as often as about a song (at a door,
in a photo, on the radio news), and this Sock cannot tell which was meant. `wer spielt das` and
`wer singt das` name the act of playing and cannot be about anything else.

### Utterances → invocation

| Utterance | Invocation |
|---|---|
| was ist das für ein lied | `whats_the_song()` |
| wie heißt der song | `whats_the_song()` |
| wie heißt das | `whats_the_song()` |
| welches lied ist das | `whats_the_song()` |
| was läuft gerade | `whats_the_song()` |
| welche musik läuft | `whats_the_song()` |
| wer spielt das | `whats_the_artist()` |
| wer singt das | `whats_the_artist()` |
| wie heißt der künstler | `whats_the_artist()` |
| was ist das für eine band | `whats_the_artist()` |
| von wem ist der song | `whats_the_artist()` |

### Behavior

**A pure read of the cached snapshot. No `connect()`, no Binder round trip, no consent
dialog.** Every other command in this Sock connects because it is about to *change* something;
these are questions about what is already true, and the App Remote's subscription is where that
truth lives — the same cache §6 requires `activityFor` to read and the same one the wall panel
draws. A question that opens a connection is a question that can hang for two seconds and then
put a dialog over its own answer.

The consequence is the one §6 already accepts and writes down: a silently dead App Remote leaves
a stale cache, and the answer is then the last track it knew about. The subscription's error
callback clears the cache, which turns most of that window into an honest "nothing is playing".

Paused or playing makes no difference. The track is loaded, it is on the card, and "what is
this" is asked about it either way.

### Result

| Case | Result | German TTS |
|---|---|---|
| `whats_the_song`, both known | `Spoken` | "Blinding Lights von The Weeknd." |
| `whats_the_song`, no artist | `Spoken` | "Blinding Lights." |
| `whats_the_artist` | `Spoken` | "The Weeknd." |
| `whats_the_artist`, no artist | `Spoken` | "Spotify nennt dazu keinen Künstler." |
| Nothing loaded | `Spoken` | "Auf Spotify läuft gerade nichts." |
| Not installed / not configured | `Failed` | the standing reason (§3) |

`whats_the_song` names the artist too, because "Blinding Lights" on its own is half an answer
and nobody asks the follow-up out loud. `whats_the_artist` does **not** name the title, because
"wer spielt das" asked one thing and got it.

---

## 6. Activity

| Shared command | `ACTIVE` | `IDLE` | `INACTIVE` |
|---|---|---|---|
| `shared.stop` | a track is loaded and `isPaused == false` | a track is loaded but paused | no track loaded (includes "not connected") |
| `shared.resume` | never — resuming something already playing is a no-op | a track is loaded and paused | no track loaded, or currently playing |

Both read the **cached** snapshot from the App Remote subscription
(`playerApi.subscribeToPlayerState`), which the device module keeps in a `StateFlow`.
`activityFor` never calls into the Spotify app — that is a Binder round-trip and the no-I/O
contract forbids it.

Consequence, stated honestly: if the App Remote silently died, the cache is stale and this Sock
may claim a command it cannot execute. It then fails with the normal "Ich komme gerade nicht an
Spotify ran." rather than passing down the chain — accepted, because the alternative is a Binder
call on every "stopp". What the implementation does add, because it costs nothing, is that the
**subscription's error callback clears the flow to null**, which turns most of that window into a
correct `INACTIVE`.

**A cold panel therefore does not answer bare "weiter".** Dobby has just started, has never
connected, and Spotify is sitting in the background on a paused track: the cache is empty, this
Sock reports `INACTIVE`, the chain runs out and the answer is "Es ist gerade nichts pausiert."
That is the no-I/O contract doing exactly what it says, and it is not fixable here — an
`activityFor` that connects is an `activityFor` that takes a second and may put a consent dialog
up, on every "stopp" as well as every "weiter". The wordings that **name** their target sidestep
it entirely, because they are exclusive commands and never consult the chain: "spiele Spotify"
(§3), "spiel die Musik weiter" and "weiter mit Spotify" (§5) all connect first and then look. So
does the panel's play button (§7). Once anything has connected once, bare "weiter" works for the
rest of the session.

Consumes:

- `shared.stop` while `ACTIVE` → `playerApi.pause()` → `Silent`.
- `shared.stop` while `IDLE` → **`NotForMe`.** Pausing an already-paused player consumes the
  command without doing anything, and would starve a Sock further down the chain.
- `shared.resume` while `IDLE` → `playerApi.resume()` → `Silent`.
- Anything while `INACTIVE` → `NotForMe`, no I/O, no reconnect attempt.

**Priority 50 on both chains**, tied with Radio on `shared.stop` (activity ranking separates them
in practice — only one can be playing, because `PlaybackCoordinator` enforces it) and ahead of
Radio's 40 on `shared.resume` (a paused song is the far likelier referent of "weiter" than a radio
station stopped earlier).

---

## 7. State & dashboard

`val nowPlaying: StateFlow<PlayerSnapshot?>` on the Sock, mapped straight from the subscription:
title, artist, `isPaused`, `positionMs`, `durationMs`, `artUri`. `DobbySocks.Wiring` keeps the
Sock by name, `DobbyController` exposes it as `val spotify`, and `ChatScreen` draws a
`NowPlayingCard` under the existing `ClockCard`. That path already existed end to end for the
clock; this is its second user and it needed nothing new.

The card: title, artist, a progress bar, play/pause state, album art. Black background, no
animation — the panel's screen is meant to be dark (`dobby-plan.md` §7.2) — and it draws nothing
at all while no track is loaded.

**Album art comes from App Remote's `imagesApi.getImage(imageUri, Dimension.LARGE)`**, not from
the Web API: it arrives as a `Bitmap` over the existing connection, with no URL, no extra HTTP
client and no token. It reaches the UI from `SpotifyHardware` rather than through the Sock,
because `:socks:spotify` is a plain JVM module and must not see an Android type.

**Progress is interpolated, not polled.** The position in a `PlayerState` update is a snapshot,
not a ticker: the card advances from `positionMs` plus elapsed wall time while `!isPaused`, the
way `ClockSock.tick` aligns a countdown to the second boundary. The App Remote is never asked
for it.

**Transport controls on the card**: |‹ · ▶/❚❚ · ›| on their own centred row, and an X on the
right of the title row. The argument is the radio card's X (`radio.specs.md` §7) and it is the
same one: the room is playing music, which is the worst possible moment to make somebody say
"Dobby" twice over it. Their own row because three targets at the size a wall panel is pressed
at do not fit beside a cover and two lines of text; the X apart from them because closing the
card is not a thing you do to the music, and a finger reaching for "next" must not land on it.
The play/pause button replaces the non-pressable paused marker that was there before.

Each runs `SpotifySock.playPauseFromPanel` / `skipNextFromPanel` / `skipPreviousFromPanel` —
the identical `SpotifyPlayer` calls the spoken commands run, so a tap and an utterance are one
event. None of them opens a connection, and that is deliberate: the card is drawn only while
`nowPlaying` holds a snapshot, and a snapshot exists only while the App Remote is subscribed,
so the `open()` dance the spoken commands need has nothing to do here.

**The X is `dismissFromPanel`: pause, then disconnect.** The radio's X ends the stream and the
card goes with it; Spotify has no equivalent stop — App Remote can pause a queue but not unload
it — so the closest honest reading of "close this card" is to stop the sound and drop the
connection, which clears the snapshot the card is drawn from. The next music command reconnects
the way it does after any overnight disconnect (§10), so this is not a state somebody has to
talk their way back out of.

## 8. Utterance collision surface

Exclusively claimed by this Sock: `spiele`, `spiel`, `mach … an`, `leg … auf`, `musik an`,
`musik aus`, `spiele|starte spotify`, `<mach|schalt|leg> spotify <an|auf>`, `spotify <abspielen|starten>`, `spiele (die) musik`,
`musik <abspielen|starten>`,
`spiele <etwas|was|irgendwas> (musik)`, `weiter <mit|auf> <spotify|der musik>`, `spotify …`,
`wiedergabe …`, `nächster/nächstes/nächste <song|lied|titel|stück|track>`,
`überspringen`, `skip`, `skippen`, `weiter zum nächsten`,
`vorheriger/vorheriges/vorherige/letzter/letztes/letzte <song|lied|titel|stück|track>`,
`… zurück`, `zurück zum <vorherigen|letzten> …`, `von vorne`, `von anfang`, `nochmal von vorne`,
`<das lied|den song|den titel> <nochmal|neu> starten`.

Also claimed, all of them read-only (§5a): `was ist das für ein <lied|song|titel|stück|künstler
|interpret|sänger|artist>`, `was ist das für eine <band|sängerin|gruppe>`, `wie heißt <das lied|
der song|der künstler|die band|…>`, `welches <lied|stück> ist das`, `welcher <song|titel> ist
das`, `was <läuft|spielt>`, `welche musik <läuft|spielt>`, `wie heißt das`, `wer <spielt|singt>
das`, `von wem ist …`, `wer ist <der|die> <künstler|band|…>`.

Deliberately **not** claimed: bare `zurück` and bare `nochmal` (§5), and bare `wer ist das`
(§5a).

> ⚠️ **`was läuft gerade` while the radio is playing answers about Spotify.** These commands are
> exclusive to this Sock, so they route by id and never consult the chain — and the Radio Sock
> has an ICY now-playing title of its own that they cannot see. The answer is then the honest
> but unhelpful "Auf Spotify läuft gerade nichts." The shape of the fix is a shared command
> (`shared.whats_playing`, arbitrated by `activityFor` exactly as `shared.stop` is), not a
> second copy of these templates in `:socks:radio`. Recorded here rather than built, because it
> touches core's shared catalog and both Socks.

Contributed to chains, **not** owned: `stopp`, `stop`, `halt`, `pause`, `pausiere`, `aus`,
`weiter`, `fortsetzen`, `weiterspielen`. A new Sock that wants these does not have to fight for
them — it subscribes to the same shared command.

### Ordering hazards

Three now, all the same family: a specific phrasing must sort ahead of a generic one.

1. `spiele {query}` is greedy and would swallow "spiele radio fm4". The Radio Sock's templates
   are *more specific* (they contain the literal `radio`) and must sort ahead of it (M4). This is
   the single most important ordering assertion in the registry test suite.
2. `weiter zum nächsten` (`skip_next`) must sort ahead of bare `weiter` (`shared.resume`).
3. **New:** `spiel das lied nochmal` (`restart_song`) versus
   `(spiel|spiele) (den|das) (letzte|letzten) (song|lied)` (`skip_previous`) versus
   `(spiele|spiel) {query}` (`play_music`). All three begin `spiel …`. The first two are closed
   templates and the third has a text slot, so `Specificity.ORDER` already sorts them correctly —
   closed before open. That is a property of the ordering rule, not of how they are written, and
   it is asserted rather than assumed.

## 9. Config

| Key | Type | Default | Where set |
|---|---|---|---|
| `spotify.market` | string | `AT` | Settings |
| `spotify.prefer_track_over_artist` | bool | `true` | Settings |
| `spotify.ask_when_unsure` | bool | `true` | Settings — gates §3.4 entirely |

`spotify.auth_token` and `spotify.refresh_token` are **gone**, per §1.1.

`spotify.ask_when_unsure` exists because §3.4's trigger is a guess about human patience, and the
honest way to find out whether the guess is right is to be able to switch it off without a
rebuild — the same argument `Settings.micProfile` makes for the M2b measurement.

## 10. Failure & degradation

- `Unavailable("Spotify ist nicht eingerichtet.")` — no credentials in the build. Detected at
  `onStart`; the commands stay registered and every invocation fails fast with the spoken line.
- `Unavailable("Spotify ist auf diesem Gerät nicht installiert.")` — same shape, detected at
  `onStart` without opening a connection.
- `Unavailable("Dafür brauche ich Spotify Premium.")` — after App Remote refuses authorization.
  It clears on the next successful connect.
- `Degraded` — a token request that failed, or a 429. The connection and every transport command
  still work; it is only the catalogue lookup that is out.
- **App Remote disconnect is normal** (overnight). It is not degradation: reconnect on demand, at
  most one retry per invocation. A Sock that goes permanently amber because a Binder connection
  timed out at four in the morning is a settings screen nobody believes any more.

## 11. Testing

This project does not add unit tests for new work (`dobby-plan.md` §5). What matters is which of
the **existing** build-time assertions this Sock walks into, because those fail the build:

- `SpecPaletteTest` / `RegistryValidationTest` — every utterance table above is asserted against
  the real palette on every build, through the `Example`s on each `ExclusiveCommandSpec`. This is
  where §8's ordering hazards get caught.
- The **cross-Sock collision gate** — no two Socks may match the same utterance for different
  commands. `spiele {query}` is the greediest template in the project; this gate is the thing
  standing between it and every other Sock.
- The **filler invariants** (M6c) — no filler may satisfy a template on its own, and no two
  commands may become the same sentence once fillers are dropped. `von vorne` and
  `ein lied zurück` are both short and both near the boundary.
- `Tier2RegistryTest` — six more commands put the route prompt over its 1500-token budget, and
  the first of `PromptBudgetTest`'s documented levers was pulled for it:
  `PromptGenerator.EXAMPLES_PER_COMMAND` went from 2 to 1. The shipped prompt is 1226 tokens with
  about four commands of headroom, and the number is printed on every build.
- `IntrospectionTest` — the Help Sock's directory has to be able to describe the new commands.

Manual, on the device, in this order — each step is a real failure mode no assertion can see:

1. `adb shell am start -a android.media.action.MEDIA_PLAY_FROM_SEARCH -p com.spotify.music -e query "bohemian rhapsody queen"` — does the intent fallback (§3.5) exist on this device's Spotify build at all?
2. Token fetch: one `curl` against `/api/token` with the real credentials.
3. First App Remote connect → the consent dialog appears → "Spiele Blinding Lights von The
   Weeknd" plays the right track, Dobby says nothing, and the panel goes back to the wake word
   rather than listening into the music. **This is M1's done-when.**
4. "Stopp" with music playing and no timer ringing → music pauses. "Stopp" with a timer ringing
   *and* music playing → the chime stops, the music does not. (The chain, with a real second
   subscriber for the first time.)
5. Talk over the music: wake word → the music ducks → command → music comes back up. This is the
   one the whole product depends on, and §3 step 1 is the reason it could break.
6. Leave it overnight. Next morning, "nächster song" reconnects and works (§10).

## 12. Open questions / out of scope (v1)

- **PKCE / user authorization** (§1.2), and with it private playlists, Liked Songs by name and
  anything else user-scoped. The seam is `bearer()`; the trigger is a real complaint, not a
  hypothetical one.
- **Spotify Connect** — playing to another device. The panel plays on the panel.
- **Seek, shuffle, repeat, volume, liking a track, playlist management.** Volume belongs to the
  System Sock, and the rest are v2. `restart_song` is in because "nochmal von vorne" is a thing
  people say to a music player; a general seek is not.
- **Caching search results.** The same query twice in one evening is not a pattern worth a cache
  and its invalidation.
- **The liveness flag for a silently dead App Remote**, beyond the error-callback clear in §6.
  Worth doing if it shows up in practice, not worth a Binder call per utterance.
- *(Resolved: choosing between multiple hits by asking back. It is §3.4 — the candidates are held
  under the follow-up token and the German copy is written down.)*
- *(Resolved: bare `stopp` routing. It is `shared.stop`, decided per-invocation by activity
  ranking — see [shared-commands.specs.md](shared-commands.specs.md).)*
