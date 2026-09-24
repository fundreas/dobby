# Sock: Radio

## 1. Identity

| | |
|---|---|
| **id** | `radio` |
| **displayName** | Radio |
| **Purpose** | Play Austrian internet radio streams. |
| **Milestone** | M4 |
| **Dependencies** | Media3 ExoPlayer (`androidx.media3:media3-exoplayer`) |
| **Hard requirements** | Network, over **HTTPS**. Every stream in §3.4 is reachable over TLS, so there is no cleartext exception and no `network_security_config.xml`. A future station that is HTTP-only is left out rather than let in. No account, no API key. |
| **Permissions** | `INTERNET` |
| **Audio** | Requests `PlaybackCoordinator` focus **with an `onLost` callback** — starting radio pauses Spotify and, since M4, vice versa. Registers its player with `InProcessPlayers` so a turn can duck it (§6) |

> **`media3-session` is deliberately absent**, although the first draft listed it. Nothing in
> this spec uses it. A `MediaSession` publishes transport controls to the lock screen,
> Bluetooth buttons and Android Auto — none of which a kitchen wall panel has, and all of which
> would be a second, unarbitrated way to start audio that `PlaybackCoordinator` knows nothing
> about.

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
| `shared.whats_the_song` | 50 | "Wie heißt das Lied" — the ICY title is an answer to it |
| `shared.whats_the_artist` | 50 | "Wer spielt das" — the same title, left half only |

See [shared-commands.specs.md](shared-commands.specs.md).

---

## 3. `radio.play_radio`

### Params

| Name | Type | Required | Default | Notes |
|---|---|---|---|---|
| `station` | string | no | `""` → `config.radio.default_station` | Spoken station name, resolved fuzzily against the station table (§3.4). |

### Tier 1 templates

```
(spiele|spiel|mach|schalt|schalte) (den)? (sender|radiosender) {station} (an|ein)
(spiele|spiel|mach|schalt|schalte) (das|den)? radio {station} (an|ein)
(spiele|spiel|mach|schalt|schalte) (den)? (sender|radiosender) {station}
(spiele|spiel|mach|schalt|schalte) (das|den)? radio {station}
(spiele|spiel|mach|schalt|schalte) (das|den)? radio (an|ein)?
radio {station}
radio (an|ein)?
```

**Registration order is critical:** every one of these contains the literal `radio` (or `sender`), which makes them more specific than Spotify's greedy `spiele {query}`. Asserted in the collision test.

**The trailing `an`/`ein` is spelled out as its own template rather than left optional**, and that is the one place the first draft was wrong about how the registry orders things. `Specificity.ORDER` sorts *closed* templates ahead of open ones **before** it counts keywords, so `… radio {station}( an| ein)?` — which may end on its slot — loses to Spotify's closed `(mach|leg|spiel) {query} (an|auf)`, and "mach radio wien an" resolves to `spotify.play_music(query="radio wien")`. Written out, the closed form carries three keywords against Spotify's two and wins on its own merits; the open form stays one rung below it for "spiele radio fm4".

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

1. Normalize with **`StationKey.of`**, and put the **table's aliases through the same function**. This is not tidiness. `Normalizer.tokenize` runs `GermanNumbers` over every token before a Sock ever sees the slot, so "ef em vier" arrives as `ef em 4` and "ö drei" as `ö 3` — the first draft's alias list could never have matched anything. `StationKey.of` is `Normalizer.normalize` (lowercase, punctuation out, **number words → digits**) then `ö→oe`, `ä→ae`, `ü→ue`, `ß→ss`, then spaces and hyphens out. Aliases are therefore written the way a person says them and never pre-normalized.
2. Exact match against the station's `id` or any `alias`.
3. Levenshtein against ids and aliases, best match wins, with **a budget of `min(2, key.length / 3)`**. The cap is load-bearing: a flat 2 lets a two-character key like `o1` accept most two-letter strings, and §11's `bayern 3` negative case is not safe without it. **A tie is a failure, not a coin flip** — two stations at the same distance means the Sock does not know which.
4. **Kölner Phonetik** (`Phonetics.koelner`, the same tool the M6 keyword matcher uses), exact on the code and only when exactly one station matches. Rescues a misspelling that sounds identical and cannot widen the net any further than that.
5. No match → `Failed` (see below). **Never silently fall back to the default station** when the user named one.

### Station table

Ships as a constant list; overridable from config later. **Verified on 2026-09-20** — every URL fetched, checked for a 2xx and an `audio/*` content type, and separately checked with `Icy-MetaData: 1` for a real `StreamTitle`. ORF rotates its endpoints, so this is a date and not a guarantee: `scripts/verify-streams` re-runs the whole check against this table and is meant to be run before a release.

| id | Display | Aliases (spoken) | `streamUrl` | `fallbackUrl` |
|---|---|---|---|---|
| `fm4` | FM4 | `fm vier`, `fm 4`, `f m 4`, `ef em vier`, `efemvier` | `https://orf-live.ors-shoutcast.at/fm4-q2a` | `…/fm4-q1a` |
| `oe3` | Hitradio Ö Drei | `ö drei`, `oe drei`, `o3`, `hitradio`, `hitradio ö3`, `hitradio oe3` | `https://orf-live.ors-shoutcast.at/oe3-q2a` | `…/oe3-q1a` |
| `oe1` | Ö1 | `ö eins`, `oe eins`, `o1`, `österreich eins` | `https://orf-live.ors-shoutcast.at/oe1-q2a` | `…/oe1-q1a` |
| `wien` | Radio Wien | `radio wien`, `wien`, `orf wien` | `https://orf-live.ors-shoutcast.at/wie-q2a` | `…/wie-q1a` |
| `kronehit` | Kronehit | `krone hit`, `kronen hit`, `krone`, `kronehit 105 8` | `https://secureonair.krone.at/kronehit1058.mp3` | `…/kronehit.mp3` |

Each entry: `id`, `displayName`, `aliases: List<String>`, `streamUrl`, `fallbackUrl: String?`, `isDefault: Boolean`, `source` — provenance (radio-browser station UUID, homepage, codec, bitrate, `verifiedOn`), read by nothing at runtime and everything to whoever reads this in a year.

Four decisions inside that table:

1. **ORF is one uniform host**, `orf-live.ors-shoutcast.at/{code}-q{1,2}a` with `code ∈ {fm4, oe3, oe1, wie}` — not four scraped URLs. The directory lists `oe3-q1a` on a different host and `oe1`/`wie` over plain `http`; all eight combinations answer `206 audio/mpeg` on the uniform host over HTTPS, and the odd one out is what an ORS rotation looks like caught mid-rotation.
2. **q2a primary (MP3 192), q1a fallback (128).** The fallback is not a quality setting a user picks — it is §10's reconnect ladder's last rung.
3. **Kronehit over HTTPS**, on a host radio-browser does not list. The alternative was a cleartext exception in the manifest for one station, which is a permanent widening of the app's network posture.
4. **No HLS.** The `…mdn.ors.at/out/u/{code}/q4a/manifest.m3u8` variants (AAC ~290k) need an extra Media3 dependency *and* carry no ICY metadata — so the dashboard's now-playing line would go dark for exactly the stations that supply the best of it.

`krone` as an alias is safe in a way a bare template would not be: an alias is only ever matched against the content of the `{station}` slot, which by definition sits inside an utterance that already said "radio" or "sender". The README's single-keyword rule is about templates that match a whole utterance.

### Help (`CommandHelp`)

| Field | |
|---|---|
| `title` | Radio hören |
| `detail` | Spielt einen der eingebauten Sender als Internet-Stream. Ohne Sendernamen läuft der Standardsender aus den Einstellungen. |
| `hints` | „Ich kenne FM4, Hitradio Ö Drei, Ö1, Radio Wien und Kronehit.“ · „„Weiter“ holt den zuletzt gestoppten Sender zurück.“ |
| `aliases` | radio, sender, radio anmachen, radiosender |

The usage line is not written here — `Syntax` derives it from the templates (README §6a).

### Behavior

1. Resolve the station (§3.4).
2. Request playback focus from `ctx.playback`, **with an `onLost` callback**; granting it pauses Spotify. The callback is what makes the arbitration two-way: a Sock playing in Dobby's own process is not reachable by the OS's focus revocation once Dobby's request has been abandoned, so `PlaybackCoordinator` invokes it directly when another Sock or another app takes the channel. Losing the channel is **not** `stop_radio` — it is not a user decision, so `last_station` survives and "weiter" brings the station back.
3. `ExoPlayer.setMediaItem(MediaItem.fromUri(streamUrl))`, `prepare()`, `play()`.
4. The player instance is created lazily and **released on `stop_radio`, on focus loss, and in `onStop()`** — a live HTTP stream left open is both battery and bandwidth.
5. Buffering timeout: 10 s. Exceeded → stop, release, `Failed`.

### Result

- `Ended()` — **nothing is said, and the turn is over.** The terse "FM4." this line used to ask for was one word too many: the audio starting *is* the feedback, and announcing the station over the first bar of it is a panel talking across the thing it was asked for. Ending the turn is the same reasoning one step on — the seconds after "radio an" are music, not a follow-up, so the microphone closes and the wake word comes straight back, exactly as `spotify.play_music` already does. Errors below still speak: a command that did *not* happen is the one case where silence is indistinguishable from a panel that did not hear.
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

### Help (`CommandHelp`)

| Field | |
|---|---|
| `title` | Radio ausschalten |
| `detail` | Beendet den Stream. Das bloße „Stopp“ steht hier nicht — das entscheidet sich im Moment des Sagens und gehört zu „Stopp“. |
| `aliases` | radio aus, radio ausschalten |

### Behavior

Stop and release the ExoPlayer, release playback focus, **cancel any pending reconnect** (§10). Idempotent: stopping when nothing is playing is not an error.

### Result

`Silent` in all cases. (The audio stopping is the feedback; if nothing was playing, saying so is more annoying than useful.)

---

## 5. Activity

| Shared command | `ACTIVE` | `IDLE` | `INACTIVE` |
|---|---|---|---|
| `shared.stop` | ExoPlayer exists and is playing or buffering | never | player released, or idle/ended |
| `shared.resume` | never | a station was stopped **in this session** and is remembered | no station history since service start |
| `shared.whats_the_song` | a stream is `Playing` — with or without an ICY title | never | anything else, including `Buffering` |
| `shared.whats_the_artist` | as above | never | as above |

Read from the Sock's own `StateFlow<RadioState>` (§7) — no player interrogation, no network. Trivially satisfies the no-I/O contract.

Consumes:

- `shared.stop` while `ACTIVE` → stop + release the player, release focus → `Silent`.
- `shared.resume` while `IDLE` → restart the remembered station → `Ended()`. Silent like every other way into `tuneTo`, and the earlier "speaks, because buffering reads as a no-op" is gone with it: the station is back within the second the sentence would have taken to say, and the card shows the spinner meanwhile.
- `shared.whats_the_song` / `shared.whats_the_artist` while `ACTIVE` → `Spoken`, from the ICY title the card already shows (§5a).
- `INACTIVE` → `NotForMe`, no player construction, no prefetch.

**Priority 50 on `shared.stop`,** tied with Spotify. The tie is theoretical: `PlaybackCoordinator` guarantees only one of them can be `ACTIVE`. It is written down anyway so the ordering is deterministic if that invariant ever breaks.

**Priority 40 on `shared.resume`,** below Spotify's 50. "Weiter" after pausing a song means the song; only if Spotify passes does the last radio station become the sensible referent.

**Priority 50 on both now-playing chains,** tied with Spotify, and unreachable for the same reason the `shared.stop` tie is.

`last_station` is deliberately **session-scoped, not persisted**: after a service restart, "weiter" with no history must be `NotForMe`, not a surprise burst of FM4 at 3 a.m.

---

## 5a. `shared.whats_the_song`, `shared.whats_the_artist`

**Specified in [shared-commands.specs.md](shared-commands.specs.md) §5 and §6, not here.** The templates and the German copy are the catalog's, and they are word for word what Spotify answers — "wie heißt das Lied" must not tell you which of the two happens to be playing. What follows is only what this Sock contributes.

### Where the answer comes from

The ICY `StreamTitle` that §7's card already draws, cleaned by `NowPlaying.clean` and then **split into an artist and a title by `NowPlaying.split`**. No new subscription, no second request, no network: the title has been arriving on its own since `onStart`, and a question about what is already true has nothing to fetch.

**The separator is a hyphen with spaces around it, and the first occurrence wins.**

```
Desireless - Voyage Voyage             → artist "Desireless", title "Voyage Voyage"
bebe rexha & faithless - new religion  → artist "bebe rexha & faithless", title "new religion"
Fivas Ponyhof                          → no artist, title "Fivas Ponyhof"
Im Zeit-Raum: Judith Mangelsdorf       → no artist, title as it stands
```

Three of the five stations follow ICY's `Artist - Title` convention; FM4 and Ö1 broadcast a programme name instead, which genuinely has no artist. Ö1's row is why the hyphen has to be surrounded by spaces to count — "Zeit-Raum" is one word. **A missing separator is *no artist*, never a guess**, and never the station's name standing in for one.

Same rule as `clean`: the halves are rendered as they come. Kronehit lower-cases everything and a title-caser gets "Ac/Dc" and "R.e.m." wrong.

### Result

| Case | Result | German TTS |
|---|---|---|
| `whats_the_song`, artist and title | `Spoken` | "Voyage Voyage von Desireless." |
| `whats_the_song`, programme name | `Spoken` | "Fivas Ponyhof." |
| `whats_the_artist`, artist known | `Spoken` | "Desireless." |
| `whats_the_artist`, programme name | `Spoken` | "Der Sender nennt dazu keinen Künstler." |
| Playing, no ICY title yet | `Spoken` | "Der Sender sagt gerade nicht, was läuft." |
| Not playing | `NotForMe` | the chain's, or Spotify's |

The fifth row is not an edge case. Many titles arrive a few seconds after the sound does, and somebody who says "was läuft" in that window gets a true sentence rather than a pass down a chain to a Spotify cache from an hour ago — which is also why `ACTIVE` does not wait for a title to exist. `Buffering` is the other way round: there is no sound yet, so there is nothing to ask about.

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
- Stream metadata title (ICY `StreamTitle`). Measured on 2026-09-20: **all five stations carry one, and carry it reliably** — the first draft's "many do, most are unreliable" was wrong. What they do not carry uniformly is a *track*: FM4 and Ö1 broadcast the **programme** dressed in boilerplate (`FM4 Fivas Ponyhof | fm4.orf.at`, `Jetzt in Ö1: Im Zeit-Raum: …`), while Ö3, Radio Wien and Kronehit send artist and title. `NowPlaying.clean` strips the station prefix and the URL suffix and returns null for what is left over when it is empty or is just the station's own name. Render it only when non-empty; **never show a placeholder**, and never re-capitalise it — Kronehit lower-cases everything and a title-caser gets "Ac/Dc" wrong.
- Buffering / reconnecting indicator.
- **A stop button (X), at the trailing edge.** The one control on the card, and the counterpart to the X under the microphone: the room is playing music, which is the worst moment to make somebody say the wake word over it. It calls `RadioSock.stopFromPanel()` — the identical `release(remember = true)` that `radio.stop_radio` performs, so "weiter" still brings the station back afterwards — and the card closes because the state it draws became `Idle`. Shown in `Buffering` and `Error` too: a stream still connecting is exactly one somebody may want to give up on, and from `Error` the X is how a card nobody can act on gets dismissed.

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

- Stream drops mid-playback (common on mobile networks and after router reboots): ExoPlayer error → retry up to `reconnect_attempts` with backoff 1 s / 3 s / 9 s. **The last rung uses `fallbackUrl`**, because the commonest reason a specific ORS endpoint stops answering is that endpoint and not the network. All retries exhausted → release, state `Error`, `ctx.announce("Der Radiostream ist abgerissen.")`. `reconnect_attempts` counts the retries *after* the first failure, so the default 3 is exactly that ladder — and 0 means one attempt, no retry, and no fallback, which is the right reading of "no retries".
- The ladder runs in `ctx.scope`, never inside `handle()`, which runs under a timeout.
- Never auto-restart after a user-initiated `stop_radio`. **`stop_radio`, `shared.stop` and a focus eviction all cancel a pending reconnect before releasing the player.** A ladder that survives a "radio aus" and brings the stream back nine seconds later is the worst bug this Sock could have.
- A stream that never started is §3's ten-second buffering timeout and *not* a ladder: the user has not been told it works, and `Failed` is the honest answer.
- Telling "no network" apart from "this stream is down" needs a `ConnectivityManager`, which is an Android type `:socks:radio` must not see. v1 says `UNREACHABLE` for both rather than widening `SockContext` for one sentence. Recorded so the omission reads as a decision.
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
- Podcasts, TuneIn or radio-browser.info directory lookup at runtime — the hardcoded table is deliberate. radio-browser is where the table came from and what `scripts/verify-streams` checks against; the APK ships a `Map`.
- Recording, sleep timer for radio (the Clock Sock's timer does not stop playback).
- HLS/AAC variants of the ORF streams exist (`…mdn.ors.at/out/u/{code}/q4a/manifest.m3u8`) and are deliberately not used: an extra Media3 dependency, and no ICY metadata (§3.4).
- `MediaSession` / lock-screen and Bluetooth transport controls (§1).
- The ORF programme API (`audioapi.orf.at/{station}/json/4.0/live`) — richer than ICY for FM4 and Ö1, and a second network dependency on the dashboard's critical path for one line of text. Where to look if §7's string cleanup turns out to be unsatisfying.
- ~~Verifying and pinning the stream URLs~~ — **done 2026-09-20** (M4). The table in §3.4 carries real URLs, a `fallbackUrl` and provenance, and `scripts/verify-streams` re-checks it.
