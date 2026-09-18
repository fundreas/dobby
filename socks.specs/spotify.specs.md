# Sock: Spotify

## 1. Identity

| | |
|---|---|
| **id** | `spotify` |
| **displayName** | Spotify |
| **Purpose** | Play and control music on the account's Spotify Premium session. |
| **Milestone** | M1 |
| **Dependencies** | `spotify-app-remote` SDK, Spotify Auth Library, Spotify Web API (`/v1/search`) via OkHttp + kotlinx.serialization |
| **Hard requirements** | Spotify **Premium** account; Spotify app installed on the device; app registered in the Spotify Developer Dashboard with package name + release/debug SHA-1 |
| **Permissions** | `INTERNET` |
| **Secrets** | `SPOTIFY_CLIENT_ID`, redirect URI → `local.properties`, never committed |
| **Audio** | Requests `PlaybackCoordinator` focus, class `AUDIO_SUSTAINED` |

## 2. Commands

### Exclusive

| Command id | Params | Description (German, feeds Tier 2) |
|---|---|---|
| `spotify.play_music` | `query: string?` | Spielt Musik auf Spotify ab, optional nach Titel, Künstler oder Playlist. |
| `spotify.pause` | — | Pausiert Spotify — **explizit adressiert** ("stopp die Musik"). |
| `spotify.resume` | — | Setzt Spotify fort — **explizit adressiert** ("spiel die Musik weiter"). |
| `spotify.skip_next` | — | Springt zum nächsten Titel. |

### Shared subscriptions

| Shared command | Priority | Why |
|---|---|---|
| `shared.stop` | 50 | Bare "stopp" / "pause" / "aus" — target depends on what is running |
| `shared.resume` | 50 | Bare "weiter" — highest priority, since resuming a paused song is the common case |

Bare stop/pause/resume phrasings are **not** owned by this Sock any more; they live in [shared-commands.specs.md](shared-commands.specs.md). The exclusive `pause`/`resume` commands remain for utterances that name Spotify or "die Musik" explicitly, which are unambiguous and should bypass the chain.

---

## 3. `spotify.play_music`

### Params

| Name | Type | Required | Default | Notes |
|---|---|---|---|---|
| `query` | string | no | `""` | Free text, taken verbatim from the transcript. May be phonetically mangled — that is expected and tolerated. |

### Tier 1 templates

```
(spiele|spiel|spiele mir|spiel mir) (den song|das lied|den titel|die playlist) {query}
(spiele|spiel|spiele mir|spiel mir) (etwas|was|irgendwas) von {query}
(mach|leg|spiel) {query} (an|auf)
(spiele|spiel|spiele mir|spiel mir) {query}( ab)?
(musik|spotify) (an|einschalten)
```

Order matters: the bare `{query}` template is greedy and must stay second-to-last; `(musik|spotify) an` is last so it cannot swallow "spiele musik von …".

### Utterances → invocation (this table is the unit test)

| Utterance | Invocation |
|---|---|
| spiele blinding lights von the weeknd | `play_music(query="blinding lights von the weeknd")` |
| spiel was von queen | `play_music(query="queen")` |
| spiele den song bohemian rhapsody | `play_music(query="bohemian rhapsody")` |
| mach die playlist entspannen an | `play_music(query="die playlist entspannen")` |
| spiele die playlist entspannen | `play_music(query="entspannen")` |
| schbiele bleinding leitz | `play_music(query="bleinding leitz")` — fuzzy keyword hit on "spiele" |
| musik an | `play_music(query="")` |
| spotify einschalten | `play_music(query="")` |

### Behavior

1. Request playback focus from `ctx.playback`. Denied focus → `Failed`.
2. **Empty query:** if the App Remote reports a paused track, resume it; otherwise play the user's Liked Songs in shuffle. No Web API call.
3. **Non-empty query:**
   a. If the query contains ` von `, split into `title` / `artist`; search `q="track:<title> artist:<artist>"`, `type=track`, `limit=5`, `market=<config.market>`.
   b. Otherwise search `q=<query>`, `type=track,artist,playlist`, `limit=5`.
   c. Pick: exact-ish track match first (highest popularity among top 3); else artist → play the artist's top tracks context; else playlist.
   d. `playerApi.play(uri)` via App Remote.
4. Connect the App Remote **lazily** on first music command; if the cached connection is dead, reconnect once before failing (long-lived connections die overnight).

### Result

- Success with a resolved name → `Spoken("Spiele {name}.")` — `{name}` is the track title, or `Musik von {artist}`, or `Playlist {name}`.
- Success on empty query → `Spoken("Läuft.")`
- Success is also reflected in the dashboard card (§6) — the spoken line stays short deliberately.

### Failure modes

| Condition | Result | German TTS |
|---|---|---|
| Web API returns no match | `Failed` | "Ich habe auf Spotify nichts gefunden." |
| App Remote not connectable / Spotify app missing | `Failed` | "Ich komme gerade nicht an Spotify ran." |
| Not authenticated / token refresh failed | `Failed` | "Spotify ist nicht verbunden. Bitte melde dich in den Einstellungen an." |
| Account is not Premium (403 from App Remote) | `Failed`, status → `Unavailable` | "Dafür brauche ich Spotify Premium." |
| No network | `Failed` | "Ich habe gerade keine Internetverbindung." |

Never fail silently.

---

## 4. `spotify.pause` (explicitly addressed)

### Tier 1 templates

```
(musik|spotify|wiedergabe) (aus|stoppen|anhalten|pausieren)
(stopp|stoppe|stop|pausiere|pausier|halt) (die musik|die wiedergabe|spotify)
(mach|schalt|schalte) (die musik|spotify) aus
```

Every template names the target. Bare `stopp` / `pause` / `aus` are **not** here — they go to `shared.stop`.

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

`playerApi.pause()`. If nothing is playing, do nothing.

Because this command was explicitly addressed to Spotify, it does **not** consult the chain and does not fall through to another Sock — the user said "die Musik", they meant this.

### Result

- Playing → `Silent` (the music stopping *is* the feedback; TTS here is noise).
- Nothing playing → `Spoken("Auf Spotify läuft gerade nichts.")` — unlike the chain case, the user named a target, so silence would look like a failure.
- App Remote unreachable → `Failed`, "Ich komme gerade nicht an Spotify ran."

---

## 5. `spotify.resume` / `spotify.skip_next`

### `spotify.resume` (explicitly addressed)

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

Behavior: request playback focus, then `playerApi.resume()`. Result `Silent`. Nothing to resume → `Failed`, "Auf Spotify ist nichts pausiert."

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

> ⚠️ `weiter zum nächsten` must be ordered **before** the bare `weiter` of `shared.resume`. The registry orders specific → generic; assert this explicitly in tests — see [shared-commands.specs.md](shared-commands.specs.md) §4.

Behavior: `playerApi.skipNext()`. Result `Silent`. Nothing playing → `Failed`, "Es läuft gerade nichts."

Stays exclusive: no other Sock can skip anything in v1. Promote to `shared.next` when one can.

---

## 6. Activity

| Shared command | `ACTIVE` | `IDLE` | `INACTIVE` |
|---|---|---|---|
| `shared.stop` | App Remote connected **and** `PlayerState.isPaused == false` | connected, a track is loaded but paused | not connected, or no track loaded |
| `shared.resume` | never — resuming something already playing is a no-op | connected and a paused track exists | not connected, or currently playing |

Both read the **cached** `PlayerState` from the App Remote subscription (`playerApi.subscribeToPlayerState`), which the Sock keeps in a `StateFlow`. `activityFor` never calls into the Spotify app — that is a Binder round-trip and the no-I/O contract forbids it. Consequence: if the App Remote silently died, the cache is stale and this Sock may claim a command it cannot execute. It then fails with the normal "Ich komme gerade nicht an Spotify ran." rather than passing down the chain — accepted, because the alternative is a Binder call on every "stopp".

Consumes:

- `shared.stop` while `ACTIVE` → `playerApi.pause()` → `Silent`.
- `shared.stop` while `IDLE` → **`NotForMe`.** Pausing an already-paused player consumes the command without doing anything, and would starve a Sock further down the chain.
- `shared.resume` while `IDLE` → `playerApi.resume()` → `Silent`.
- Anything while `INACTIVE` → `NotForMe`, no I/O, no reconnect attempt.

**Priority 50 on both chains**, tied with Radio on `shared.stop` (activity ranking separates them in practice — only one can be playing, because `PlaybackCoordinator` enforces it) and ahead of Radio's 40 on `shared.resume` (a paused song is the far likelier referent of "weiter" than a radio station stopped earlier).

---

## 7. State & dashboard

`DashboardCard`, shown while Spotify holds playback focus:

- Track title, artist, album art (loaded via the App Remote `ImagesApi`, not the Web API).
- Progress bar, play/pause state.
- Black background, no animation while the screen is meant to be dark.

Exposed state: `StateFlow<NowPlaying?>` (title, artist, artUri, positionMs, durationMs, isPlaying).

## 8. Utterance collision surface

Exclusively claimed by this Sock: `spiele`, `spiel`, `spiele mir`, `spiel mir`, `mach … an`, `leg … auf`, `musik an`, `musik aus`, `spotify …`, `wiedergabe …`, `nächster/nächstes/nächste <song|lied|titel|stück|track>`, `überspringen`, `skip`, `weiter zum nächsten`.

Contributed to chains, **not** owned: `stopp`, `stop`, `halt`, `pause`, `pausiere`, `aus`, `weiter`, `fortsetzen`, `weiterspielen`. A new Sock that wants these does not have to fight for them — it subscribes to the same shared command.

**Known hazard (unchanged):** `spiele {query}` is greedy and would swallow "spiele radio fm4". The Radio Sock's templates are *more specific* (they contain the literal `radio`) and must be ordered ahead of it. This is the single most important ordering assertion in the registry test suite.

## 9. Config

| Key | Type | Default | Where set |
|---|---|---|---|
| `spotify.market` | string | `AT` | Settings screen |
| `spotify.auth_token` / `refresh_token` | string | — | OAuth flow in Settings, encrypted storage |
| `spotify.prefer_track_over_artist` | bool | `true` | Settings (advanced) |

## 10. Failure & degradation

- `Unavailable("Spotify-App nicht installiert")` — detected at `onStart`, command still registered but every invocation fails fast with the spoken message.
- `Unavailable("Kein Premium")` — after a 403 from App Remote.
- `Degraded("nicht angemeldet")` — no valid token.
- App Remote disconnect is **normal** (overnight). Do not treat it as degradation; reconnect on demand, at most one retry per invocation.

## 11. Testing

- Template table (§3–5) as a parameterized unit test, plus the ordering assertions against `shared.resume` / `skip_next` and against the Radio Sock.
- **Bare-form routing:** "stopp" → `shared.stop`, "weiter" → `shared.resume`, "stopp die musik" → `spotify.pause`. A regression here is silent and user-visible.
- `activityFor` against a fake `PlayerState` cache: playing → `ACTIVE`, paused → `IDLE`, disconnected → `INACTIVE`; and an assertion that it performs **zero** App Remote calls.
- `NotForMe` on `shared.stop` while `IDLE` — the starvation case.
- `/v1/search` selection heuristic against recorded JSON fixtures: exact track, artist-only, "X von Y" split, empty result, playlist-only result.
- Handler tests with a fake `SockContext` and a fake App Remote: focus denied, disconnect + successful reconnect, disconnect + failed reconnect, 403.
- No test may require a real Spotify session.

## 12. Open questions / out of scope (v1)

- `skip_previous`, seek, shuffle/repeat toggles, volume (owned by the System Sock), playlist management, liking a track.
- Choosing between multiple search hits by asking back ("Meinst du …?") — needs a dialogue turn in core, which does not exist.
- Playing to a different Spotify Connect device.
- **Stale `PlayerState` after a silent App Remote death** (§6): the Sock may claim a chain command it cannot execute. Fix would be a cheap liveness flag updated by the subscription's error callback — worth doing if it shows up in practice, not worth a Binder call per utterance.
- *(Resolved: bare `stopp` routing. It is now `shared.stop`, decided per-invocation by activity ranking — see [shared-commands.specs.md](shared-commands.specs.md).)*
