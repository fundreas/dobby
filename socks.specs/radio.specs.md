# Sock: Radio

## 1. Identity

| | |
|---|---|
| **id** | `radio` |
| **displayName** | Radio |
| **Purpose** | Play Austrian internet radio streams. |
| **Milestone** | M4 |
| **Dependencies** | Media3 ExoPlayer (`androidx.media3:media3-exoplayer`, `media3-session`) |
| **Hard requirements** | Network. No account, no API key. |
| **Permissions** | `INTERNET` |
| **Audio** | Requests `PlaybackCoordinator` focus, class `AUDIO_SUSTAINED` — starting radio pauses Spotify and vice versa. Registers its player with `InProcessPlayers` so a turn can duck it (§6) |

## 2. Commands

### Exclusive

| Command id | Params | Description (German, feeds Tier 2) |
|---|---|---|
| `radio.play_radio` | `station: string?` | Spielt einen Radiosender ab. Ohne Sendername den Standardsender. |
| `radio.stop_radio` | — | Beendet die Radiowiedergabe — **explizit adressiert** ("Radio aus"). |

### Shared subscriptions

| Shared command | Priority | Why |
|---|---|---|
| `shared.stop` | 50 | Bare "stopp" / "aus" while the stream is running |
| `shared.resume` | 40 | Bare "weiter" — below Spotify, because a paused song is the likelier referent |

See [shared-commands.specs.md](shared-commands.specs.md).

---

## 3. `radio.play_radio`

### Params

| Name | Type | Required | Default | Notes |
|---|---|---|---|---|
| `station` | string | no | `""` → `config.radio.default_station` | Spoken station name, resolved fuzzily against the station table (§3.4). |

### Tier 1 templates

```
(spiele|spiel|mach|schalt|schalte) (das |den )?radio {station}( an| ein)?
(spiele|spiel|mach|schalt|schalte) (den )?(sender|radiosender) {station}( an| ein)?
radio {station}
(spiele|spiel|mach|schalt|schalte) (das |den )?radio( an| ein)?
radio( an| ein)?
```

**Registration order is critical:** every one of these contains the literal `radio` (or `sender`), which makes them more specific than Spotify's greedy `spiele {query}`. The registry must order them ahead of it. Asserted in the collision test.

### Utterances → invocation

| Utterance | Invocation |
|---|---|
| spiele radio fm4 | `play_radio(station="fm4")` |
| spiel radio ö3 | `play_radio(station="ö3")` |
| mach radio wien an | `play_radio(station="wien")` |
| schalt den sender ö1 ein | `play_radio(station="ö1")` |
| radio kronehit | `play_radio(station="kronehit")` |
| radio an | `play_radio(station="")` |
| radio | `play_radio(station="")` |
| spiele radio | `play_radio(station="")` |

### Station resolution

STT will mangle station names (`FM4` → "ef em vier", `Ö3` → "oe drei"): letter-and-digit names are what any acoustic model is worst at, and Parakeet is better at this than Vosk was without being reliable. Resolution is therefore alias-first, fuzzy-second:

1. Normalize: lowercase, strip spaces and hyphens, `ö→oe`, `ä→ae`, `ü→ue`.
2. Exact match against the station's `id` or any `alias`.
3. Levenshtein ≤ 2 against ids and aliases; best match wins.
4. No match → `Failed` (see below). **Never silently fall back to the default station** when the user named one.

### Station table

Ships as a constant map; overridable from config later. **Stream URLs must be verified against the broadcaster's current public stream list at implementation time — do not trust the values below without checking; ORF in particular rotates its endpoints.**

| id | Display | Aliases | Stream URL |
|---|---|---|---|
| `fm4` | FM4 | `ef em vier`, `fm vier`, `efemvier`, `f m 4` | *ORF live stream (verify)* |
| `oe3` | Ö3 | `oe drei`, `ö drei`, `o3`, `hitradio ö3`, `hitradio oe3` | *ORF live stream (verify)* |
| `oe1` | Ö1 | `oe eins`, `ö eins`, `o1` | *ORF live stream (verify)* |
| `wien` | Radio Wien | `radio wien`, `wien` | *ORF live stream (verify)* |
| `kronehit` | Kronehit | `krone hit`, `kronehit` | *verify* |

Each entry: `id`, `displayName`, `aliases: List<String>`, `streamUrl`, `isDefault: Boolean`.

### Behavior

1. Resolve the station (§3.4).
2. Request playback focus from `ctx.playback`; granting it pauses Spotify.
3. `ExoPlayer.setMediaItem(MediaItem.fromUri(streamUrl))`, `prepare()`, `play()`.
4. The player instance is created lazily and **released on `stop_radio`, on focus loss, and in `onStop()`** — a live HTTP stream left open is both battery and bandwidth.
5. Buffering timeout: 10 s. Exceeded → stop, release, `Failed`.

### Result

- `Spoken("{displayName}.")` — e.g. "FM4." Deliberately terse; the audio starting is the real feedback.
- Station named but unresolvable → `Failed`, "Den Sender kenne ich nicht."
- Stream unreachable / timeout / 404 → `Failed`, "Der Sender ist gerade nicht erreichbar."
- No network → `Failed`, "Ich habe gerade keine Internetverbindung."
- Focus denied → `Failed`, "Gerade nicht möglich."

---

## 4. `radio.stop_radio`

### Tier 1 templates

```
radio (aus|stopp|stop|ausschalten|abschalten|beenden)
(mach|schalt|schalte|stopp|stoppe) (das |den )?radio (aus|ab)
(stopp|stoppe|beende) (das |den )?radio
```

### Utterances → invocation

| Utterance | Invocation |
|---|---|
| radio aus | `stop_radio()` |
| radio stopp | `stop_radio()` |
| mach das radio aus | `stop_radio()` |
| stopp das radio | `stop_radio()` |
| radio ausschalten | `stop_radio()` |

> Bare `stopp` / `stop` / `aus` is **not** claimed here — it routes to `shared.stop`, where this Sock consumes it whenever the stream is actually running (§5). These exclusive templates exist for the case where the user names the target while something *else* is playing.

### Behavior

Stop and release the ExoPlayer, release playback focus. Idempotent: stopping when nothing is playing is not an error.

### Result

`Silent` in all cases. (The audio stopping is the feedback; if nothing was playing, saying so is more annoying than useful.)

---

## 5. Activity

| Shared command | `ACTIVE` | `IDLE` | `INACTIVE` |
|---|---|---|---|
| `shared.stop` | ExoPlayer exists and is playing or buffering | never | player released, or idle/ended |
| `shared.resume` | never | a station was stopped **in this session** and is remembered | no station history since service start |

Read from the Sock's own `StateFlow<RadioState>` (§7) — no player interrogation, no network. Trivially satisfies the no-I/O contract.

Consumes:

- `shared.stop` while `ACTIVE` → stop + release the player, release focus → `Silent`.
- `shared.resume` while `IDLE` → restart the remembered station → `Spoken("{displayName}.")`. Speaks here, unlike Spotify, because a radio stream takes a second to buffer and silence would read as a no-op.
- `INACTIVE` → `NotForMe`, no player construction, no prefetch.

**Priority 50 on `shared.stop`,** tied with Spotify. The tie is theoretical: `PlaybackCoordinator` guarantees only one of them can be `ACTIVE`. It is written down anyway so the ordering is deterministic if that invariant ever breaks.

**Priority 40 on `shared.resume`,** below Spotify's 50. "Weiter" after pausing a song means the song; only if Spotify passes does the last radio station become the sensible referent.

`last_station` is deliberately **session-scoped, not persisted**: after a service restart, "weiter" with no history must be `NotForMe`, not a surprise burst of FM4 at 3 a.m.

---

## 6. Ducking while Dobby is listening

The panel's microphone hears its own speaker. While somebody is talking to Dobby the radio has
to get out of the way, or Silero VAD never finds the trailing silence that ends the utterance
and every turn runs to the ten-second hard cap with the stream underneath it (`m2b-plan.md`).

This is **not** `PlaybackCoordinator`. That one arbitrates between Socks, is keyed by sock id
and holds exactly one request; a turn-level duck routed through it would abandon this Sock's
own focus and never give it back. The turn holds its own audio focus for everything out of
process — and for a player *inside* this process, audio focus is the wrong tool entirely.

So:

- **The Sock registers its ExoPlayer with `io.dobby.core.audio.InProcessPlayers`** when it is
  created, and unregisters it when it is released. Registration is the player's lifecycle and
  nothing else's: a Sock that forgets to unregister leaves a dead player holding a duck.
- The registry calls `duck(volume)` at the start of a turn and `restore()` at the end. The
  implementation is `player.volume = volume` and back to `1f` — nothing cleverer.
- **`TurnDuck.PAUSE` mutes rather than pauses here** (`volume = 0f`). Pausing a stream costs a
  re-buffer on every turn — §10 already budgets reconnects — and a turn is seconds. Muting
  gives the recogniser the same silence, and the music is back the instant the turn ends rather
  than after a reconnect.
- A player registered *during* a turn starts ducked, so a stream that connects mid-sentence
  does not come up at full volume under the person talking.
- The duck is transient and invisible to `RadioState`: the Sock is still `Playing`, and §5's
  `shared.stop` chain behaves exactly as it does at full volume. A turn duck is not a pause and
  must never be reported as one.

## 7. State & dashboard

`DashboardCard`, shown while Radio holds playback focus:

- Station display name, large.
- Stream metadata title (ICY `StreamMetadata`) if the stream provides one — many do, most are unreliable. Render it only when non-empty; never show a placeholder.
- Buffering / reconnecting indicator.

Exposed state: `StateFlow<RadioState>` = `Idle(lastStation: Station?)` | `Buffering(station)` | `Playing(station, nowPlaying: String?)` | `Error(station, reason)`. `lastStation` is what backs `IDLE` on `shared.resume`.

## 8. Utterance collision surface

Exclusively claimed: every phrase containing the literal `radio`, plus `sender {x}` / `radiosender {x}`.

Contributed to chains, not owned: `stopp`, `aus`, `weiter` (via `shared.stop` / `shared.resume`).

- Does **not** claim `lauter` / `leiser` / `stumm` — those are the System Sock's and apply to the whole device stream, radio included.

## 9. Config

| Key | Type | Default | Where set |
|---|---|---|---|
| `radio.default_station` | string (station id) | `fm4` | Settings screen, dropdown of the station table |
| `radio.buffer_timeout_s` | int | `10` | Settings (advanced) |
| `radio.reconnect_attempts` | int | `3` | Settings (advanced) |

## 10. Failure & degradation

- Stream drops mid-playback (common on mobile networks and after router reboots): ExoPlayer error → retry up to `reconnect_attempts` with backoff 1 s / 3 s / 9 s. All retries exhausted → release, state `Error`, `ctx.announce("Der Radiostream ist abgerissen.")`.
- Never auto-restart after a user-initiated `stop_radio`.
- Sock status is always `Ready` — there is no account or device prerequisite that can make radio permanently unavailable; failures are per-invocation.

## 11. Testing

- Template table (§3, §4) as a parameterized unit test.
- **Collision test against the Spotify Sock:** "spiele radio fm4" must resolve to `radio.play_radio`, not `spotify.play_music(query="radio fm4")`. This is a regression test, not a nice-to-have.
- **Chain tests:** "stopp" while streaming → this Sock consumes; "stopp" while Spotify plays and radio is cold → `NotForMe` with no player construction; "weiter" with a session station vs. after a restart.
- `activityFor` derives purely from `RadioState`, with an assertion of zero player/network calls.
- Station resolution: the alias table, umlaut normalization, Levenshtein edge cases, and explicit negative cases ("spiele radio bayern 3" → unresolvable, not default).
- Handler tests with a fake ExoPlayer and fake `PlaybackCoordinator`: focus denied, buffering timeout, mid-stream error + successful reconnect, mid-stream error + exhausted retries.
- **Turn ducking (§6) with a fake player:** registered on create and unregistered on release; `duck`/`restore` move the volume and put it back; a turn duck leaves `RadioState` and the Sock's own `PlaybackCoordinator` focus untouched — that last one is the regression test for routing the turn duck through the coordinator by mistake.
- No test hits a real stream URL.

## 12. Open questions / out of scope (v1)

- Station favorites / "nächster Sender" cycling.
- User-editable station list in the UI (v1 is a constant table + a default-station setting).
- Podcasts, TuneIn or radio-browser.info directory lookup — the hardcoded table is deliberate.
- Recording, sleep timer for radio (the Clock Sock's timer does not stop playback).
- Verifying and pinning the stream URLs is an **implementation task for M4**, tracked here so it is not forgotten.
