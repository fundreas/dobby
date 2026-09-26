# Sock: System

## 1. Identity

| | |
|---|---|
| **id** | `system` |
| **displayName** | System |
| **Purpose** | Device-level controls: volume, mute, screen. |
| **Milestone** | M3 |
| **Dependencies** | `AudioManager` behind `VolumeControl`, core's `ScreenController`, `DevicePolicyManager` (for `turn_off_screen` — see §6) |
| **Permissions** | None at manifest level. `turn_off_screen` needs Dobby to be an **active device admin**, enabled once by the user. |
| **Audio** | None. Adjusts the stream, never plays on it. |

This Sock is the thinnest possible wrapper over the platform — but it is still a Sock, because core must not know that "lauter" exists.

**Built so far: the three volume commands (§3, §3a, §4).** The two screen commands (§5, §6) are specified and not implemented — they need a device-admin receiver, which is a manifest entry and a one-off setup step rather than a handler.

## 2. Commands

### Exclusive

| Command id | Params | Description (German, feeds Tier 2) | |
|---|---|---|---|
| `system.volume` | `direction: enum`, `steps: int?` | Ändert die Lautstärke schrittweise, lauter oder leiser. | ✅ |
| `system.set_volume` | `level: int` | Setzt die Lautstärke auf einen festen Wert, in Prozent. | ✅ |
| `system.mute` | `state: enum?` | Schaltet den Ton stumm oder wieder an. | ✅ |
| `system.turn_on_screen` | — | Schaltet den Bildschirm ein. | — |
| `system.turn_off_screen` | — | Schaltet den Bildschirm aus. | — |

`system.set_volume` was §11's first open question and is now answered — as a second command, not as an optional param on `system.volume`, exactly as that section recommended. The reason it moved up the list is that half the volume commands people say are absolute: "volle Lautstärke", "Lautstärke auf mittel". A `steps` model can only say *more than before*.

### Shared subscriptions

**None.** Every command here addresses the device, not a playback source, so none of them is context-dependent — "lauter" means the same thing whatever is running.

Explicitly considered and rejected: subscribing to `shared.stop` as a last-resort "mute everything". Falling back from *stop* to *mute* would leave audio running silently and playback state untouched; the user would say "stopp" and get something that is not stopping. `unconsumedResponse` ("Es läuft gerade nichts.") is the honest answer. `activityFor` therefore always returns `INACTIVE` and is never consulted.

---

## 3. `system.volume`

### Params

| Name | Type | Required | Default | Constraints |
|---|---|---|---|---|
| `direction` | enum | yes | — | `lauter` \| `leiser` |
| `steps` | int | no | `2` | 1 … 10 |

Operates on `STREAM_MUSIC` — the stream Spotify, Radio and TTS all use.

**One step is five percentage points** (`system.volume_step_percent`). So the default two steps is the 10 % a bare "mach lauter" moves, "ein bisschen lauter" is 5 % and "viel lauter" is 25 %. Percent rather than stream indices, because an index is worth 4 % on one phone and 14 % on another, and "ein bisschen" must not mean different things on different hardware. The conversion, and the rounding it needs, is `VolumeScale`.

### Tier 1 templates

```
(viel|deutlich|wesentlich|sehr) {direction}                                          → steps=5
(ein bisschen|bisschen|etwas|leicht) {direction}                                     → steps=1
(mach|mache|dreh|drehe|stell|stelle) (die musik|den ton|die lautstärke|musik|ton|lautstärke)? {direction}
lautstärke {direction}
(erhöh|erhöhe|steigere|steigre) lautstärke                                           → direction=lauter
(verringere|verringer|reduziere|reduzier|senke|senk) lautstärke                      → direction=leiser
lautstärke (hoch|rauf|höher|hochdrehen)                                              → direction=lauter
lautstärke (runter|herunter|niedriger|runterdrehen)                                  → direction=leiser
{direction}
```

`{direction}` is a `{direction:enum}` slot over `lauter | leiser`. The modifier is folded into `steps` by the template's static param binding, not by the handler.

The article branches (`die musik`, `den ton`) are spelled out although `die` and `den` are filler words the matcher already skips: "mach die Musik lauter" is common enough to deserve the strict first pass rather than the rescue pass (README §6).

### Utterances → invocation

| Utterance | Invocation |
|---|---|
| lauter | `volume(direction=lauter, steps=2)` |
| leiser | `volume(direction=leiser, steps=2)` |
| mach lauter | `volume(direction=lauter, steps=2)` |
| mach leiser | `volume(direction=leiser, steps=2)` |
| dreh die musik leiser | `volume(direction=leiser, steps=2)` |
| viel lauter | `volume(direction=lauter, steps=5)` |
| ein bisschen leiser | `volume(direction=leiser, steps=1)` |
| lautstärke hoch | `volume(direction=lauter, steps=2)` |
| lautstärke runter | `volume(direction=leiser, steps=2)` |
| erhöhe die lautstärke | `volume(direction=lauter, steps=2)` |
| das ist mir zu laut | Tier 2 only (`matchedByTemplates = false`) |

### Behavior

`steps × volume_step_percent` percentage points up or down, clamped to `0 … system.max_volume_percent`, written through `VolumeControl.setIndex` — **flag `0`, not `FLAG_SHOW_UI`**: this is a wall panel, a system volume overlay covering the dashboard is wrong.

**A relative change always moves at least one index.** Ten percent of a 7-step stream is 0.7 of an index, and naive rounding makes "mach lauter" do nothing on a device that is working perfectly. `VolumeScale.shift` moves one index whenever the target is not already the limit.

If the stream is muted and `direction = lauter`, unmute first (restoring the level from §4), then raise. Muted and `direction = leiser` is already as quiet as it gets and says so.

Clamping at 0 or the ceiling is not an error.

### Result

`Silent` — the user hears the change immediately; TTS would fight the thing being adjusted.

Exception: already at the ceiling or at zero and the command would be a no-op → `Spoken("Schon ganz laut.")` / `Spoken("Schon ganz leise.")`, so a broken volume path is distinguishable from a working one at the extremes.

---

## 3a. `system.set_volume`

### Params

| Name | Type | Required | Default | Constraints |
|---|---|---|---|---|
| `level` | int | yes | — | 0 … 100, in percent; clamped to `system.max_volume_percent` |

### Tier 1 templates

```
($VERB)? lautstärke (auf)? (voll|maximal|maximum|anschlag)                           → level=100
(volle|maximale|höchste) lautstärke                                                  → level=100
($VERB)? lautstärke (auf)? laut                                                      → level=80
($VERB)? lautstärke (auf)? (mittel|mitte|halb|normal)                                → level=50
(mittlere|halbe|normale) lautstärke                                                  → level=50
($VERB)? lautstärke (auf)? leise                                                     → level=20
($VERB)? lautstärke (auf)? {level:int}( prozent)?
```

`$VERB` is `mach|mache|dreh|drehe|stell|stelle|setz|setze`, optional in every template so "Lautstärke auf mittel" and "stell die Lautstärke auf mittel" are one template rather than two.

The four named levels are static params — the words are in the templates, the numbers are in `SystemSock`. A `{level:enum}` slot plus a lookup in the handler would be the same table one layer further from the words it belongs to (README §6).

The percent template is written **last**. An integer slot must not get first refusal at "laut", and specificity ordering (more keywords first) already guarantees it would not — the ordering is belt, the position is braces.

### Utterances → invocation

| Utterance | Invocation |
|---|---|
| volle lautstärke | `set_volume(level=100)` |
| maximale lautstärke | `set_volume(level=100)` |
| mach die lautstärke voll | `set_volume(level=100)` |
| lautstärke auf laut | `set_volume(level=80)` |
| lautstärke auf mittel | `set_volume(level=50)` |
| stell die lautstärke auf mittel | `set_volume(level=50)` |
| lautstärke auf leise | `set_volume(level=20)` |
| lautstärke auf 35 prozent | `set_volume(level=35)` |
| lautstärke auf 50 | `set_volume(level=50)` |

### Behavior

Unmute if muted, then set the stream to `min(level, max_volume_percent)` percent — **at least index 1**, because a level somebody named out loud must never round to silence.

`level = 0` is delegated to `system.mute(state=an)` rather than duplicating it. Zero volume *is* mute, and the delegation is what makes "Lautstärke aus" → "Lautstärke wieder an" a round trip: the remembered level is written by one code path and read by the other. This is also why "Lautstärke aus" is a `mute` invocation and not a `set_volume(0)` one in the table in §4.

### Result

`Silent`, for the same reason as §3: the change is its own confirmation, and it is audible at every level this command can produce.

---

## 4. `system.mute`

### Params

| Name | Type | Required | Default | Constraints |
|---|---|---|---|---|
| `state` | enum | no | `an` | `an` (muted) \| `aus` (unmuted) |

> The param is the *mute* state, not the *sound* state: `state=an` means muted. Templates below bind it explicitly so the German never has to be reasoned about at runtime.

### Tier 1 templates

```
(stumm|stummschalten|stumm schalten|sei still|ruhe)                                  → state=an
(mach|mache|dreh|drehe|stell|stelle|schalt|schalte) stumm                            → state=an
(ton|lautstärke) aus                                                                 → state=an
(ton|lautstärke) (wieder)? (an|ein)                                                  → state=aus
(nicht mehr stumm|stumm aus|entstummen|wieder laut|laut stellen)                     → state=aus
```

`(wieder)?` is written out rather than left to the matcher: `wieder` is repetition, which README §6 keeps off the filler list on purpose.

Two of these are single-keyword templates the registry lists at every build — `stumm` (5 characters, tolerance 1) and `ruhe` (4 characters, tolerance 1, which also answers to `rufe`). Kept, and the judgement is recorded here rather than left as a silent warning: both are muting a panel, which is the one action in this Sock a person undoes by saying three more words. `ruhe` is the weaker of the two and is the first thing to drop if the fallthrough log ever shows it firing on a remark.

### Utterances → invocation

| Utterance | Invocation |
|---|---|
| stumm | `mute(state=an)` |
| stummschalten | `mute(state=an)` |
| ton aus | `mute(state=an)` |
| lautstärke aus | `mute(state=an)` |
| sei still | `mute(state=an)` |
| ton an | `mute(state=aus)` |
| lautstärke wieder an | `mute(state=aus)` |
| nicht mehr stumm | `mute(state=aus)` |

> ⚠️ `ton aus` vs. Spotify's `musik aus` are deliberately different commands. `ton aus` mutes the stream (playback continues, silently); `musik aus` pauses Spotify. Both are what the respective words mean. Documented here because it *will* look like a bug in a log.

### Behavior

`audioManager.adjustStreamVolume(STREAM_MUSIC, ADJUST_MUTE | ADJUST_UNMUTE, 0)`, behind `VolumeControl.setMuted`.

**Unmuting restores the level from before the mute.** The platform does this by itself, and the Sock remembers the index separately anyway, because a stream that comes back at zero is indistinguishable from one that is still muted — and that is the one state a voice panel cannot talk its way out of. If nothing was remembered (a fresh process, or somebody turned the hardware keys to zero), unmuting lands at `system.unmute_percent`, 30 %.

Muting does **not** stop playback and does **not** release playback focus.

**Announcements still speak while muted?** No — TTS shares `STREAM_MUSIC`, so a muted device is silent, including timer alarms. Mitigation: while muted, timer expiry additionally flashes the dashboard (see `clock.specs.md` §6) and posts a notification. Accept that "stumm" means stumm.

### Dashboard

`val muted: StateFlow<Boolean>` on the Sock, and a speaker button in `MainScreen`'s dock beside
the microphone — `RadioSock.state` and its X are the precedent for both halves. The
button runs `SystemSock.toggleMuteFromPanel()`, which is `mute()` with the state flipped: the
same call `system.mute` makes, from a finger instead of a sentence.

**Drawn only while something is playing** — Spotify unpaused, or the radio `Playing`/`Buffering`.
Mute is a control for sound that is happening, and a speaker button on a silent panel is one
more thing to read past. `Buffering` counts: a stream connecting is about to be loud, and the
moment before it is, is exactly when somebody reaches for the button; `Error` does not, because
that card is a card about silence and it has its own X. It is in the control row
rather than the header for the same reason the help button is — that row is where a hand
already is.

The X on the cards above it stops the sound; this only makes the device silent. That difference
is the whole reason both exist.

**A hardware volume key is not observed.** `VolumeControl` is a pull interface with no change
callback, and giving it one would mean a `ContentObserver` in the Android half and a new seam
in the JVM half. The flow is published after every path in the Sock that can change it, and
`toggleMuteFromPanel` reads the *live* stream rather than the flow — so a rocker press can leave
the icon one tap stale, and the tap that follows still does the right thing.

### Result

| Case | Result | German TTS |
|---|---|---|
| `state=an` | `Silent` | — an acknowledgement would be Dobby talking over a request to be quiet |
| `state=an` while already silent | `Silent` | — |
| `state=aus` | `Spoken("Ton ist wieder an.")` | confirms audibly, which is the point |
| `state=aus` while not muted | `Silent` | — nothing happened, so nothing is said |

---

## 5. `system.turn_on_screen`

### Tier 1 templates

```
(bildschirm|display|schirm) (an|ein|einschalten|aufwecken)
(mach|schalt|schalte) (den )?(bildschirm|display|schirm) (an|ein)
(wach auf|aufwachen|zeig mir das dashboard|dashboard)
```

### Utterances → invocation

| Utterance | Invocation |
|---|---|
| bildschirm an | `turn_on_screen()` |
| display einschalten | `turn_on_screen()` |
| mach den bildschirm an | `turn_on_screen()` |
| wach auf | `turn_on_screen()` |
| dashboard | `turn_on_screen()` |

### Behavior

`ctx.screen.wakeFor(config.system.screen_on_duration_s)` — default 60 s, longer than the 30 s the wake word grants, because this was an explicit request to *look* at the panel.

### Result

`Silent`. The screen coming on is the confirmation.

---

## 6. `system.turn_off_screen`

### Tier 1 templates

```
(bildschirm|display|schirm) (aus|ausschalten|abschalten)
(mach|schalt|schalte) (den )?(bildschirm|display|schirm) aus
(gute nacht|schlaf|schlafen)
```

### Utterances → invocation

| Utterance | Invocation |
|---|---|
| bildschirm aus | `turn_off_screen()` |
| display ausschalten | `turn_off_screen()` |
| mach den bildschirm aus | `turn_off_screen()` |
| gute nacht | `turn_off_screen()` |

### Behavior — the awkward one

Android has **no public API for an app to turn the screen off.** Releasing the bright wake lock only lets the screen time out on its own. Two mechanisms, in order:

1. `ctx.screen.release()` — drop any bright wake lock the panel holds.
2. `DevicePolicyManager.lockNow()` — actually blanks the screen immediately. Requires Dobby to be an **active device admin**.

Because the device's lock screen is set to **None** (plan §7.2), `lockNow()` blanks the screen without putting a keyguard in front of the dashboard. This is the reason that setting is mandatory, not merely convenient.

**Setup step for the README / OxygenOS checklist:** Settings → Security → Device admin apps → enable Dobby. Without it, `turn_off_screen` degrades to (1) only — the screen dims out after the system timeout instead of instantly.

The device-admin receiver declares **no policies** beyond `force-lock`. Do not request wipe, password or camera policies — they are not needed and they make the enable dialog alarming.

### Result

| Case | Result | German TTS |
|---|---|---|
| Device admin active | `Silent` | — |
| Device admin not active | `Silent`, status `Degraded` | — (screen will time out shortly; nagging by voice every time would be worse than the degradation) |

The degraded state is surfaced **visually in Settings**, not by voice.

---

## 7. Utterance collision surface

Exclusively claimed: `lauter`, `leiser`, `lautstärke …`, `volle|maximale|höchste|mittlere|halbe|normale lautstärke`, `stumm …`, `ton an` / `ton aus`, `sei still`, `ruhe`, `bildschirm …`, `display …`, `schirm …`, `wach auf`, `dashboard`, `gute nacht`.

Every phrasing with a number in it is anchored on `lautstärke`. Nothing here claims a bare integer, so the Calculator keeps "7 mal 8" and the Clock keeps "timer auf 5".

Contributes to no chain.

- Does **not** claim bare `stopp`, `aus`, `pause`, `weiter` (→ `shared.stop` / `shared.resume`), nor `musik aus` (Spotify) or `radio aus` (Radio).
- `ruhe` is unambiguously mute. The Clock Sock's chime-silencing phrasings moved onto `shared.stop` when the chain was introduced, so the old `ruhe` / `ruhe jetzt` near-collision no longer exists — do not reintroduce a `ruhe`-prefixed template anywhere.
- ⚠️ `ton aus` (mute, playback continues silently) and `musik aus` (pause Spotify) remain deliberately distinct. Keep them apart. `lautstärke aus` is on the `ton aus` side.
- `lautstärke laut` and `lautstärke lauter` are different commands and one letter apart, which is deliberate rather than an oversight: `laut` is 2 edits from `lauter`, outside the enum slot's tolerance of 1, so the absolute template takes the first and the relative one the second. Do not add `laut` to the `direction` enum.

## 8. Config

| Key | Type | Default | Where set |
|---|---|---|---|
| `system.volume_default_steps` | int | `2` | Settings — 1 … 10 |
| `system.volume_step_percent` | int | `5` | Settings — what one step is worth |
| `system.max_volume_percent` | int | `100` | Settings — a ceiling, so "viel lauter" at 3 a.m. cannot reach full volume |
| `system.unmute_percent` | int | `30` | Settings — where "Ton wieder an" lands when nothing was remembered |
| `system.screen_on_duration_s` | int | `60` | Settings — §5, not yet built |

The ceiling applies to **"volle Lautstärke" too**. A ceiling somebody can talk their way past is decoration.

## 9. Failure & degradation

- No stream at all (`VolumeControl.NONE` — the terminal, a unit test, a headless build) → `Unavailable("Keine Lautstärkeregelung")`, and every command fails with `"Ich komme hier an die Lautstärke nicht heran."` The palette stays complete and every utterance table is still assertable with no device, which is the same bargain the Spotify Sock makes off-device.
- No device admin → `Degraded("Bildschirm ausschalten eingeschränkt")`. All other commands unaffected. (§6, not yet built.)
- `AudioManager` calls do not meaningfully fail on a device, with one exception: `setStreamVolume` throws `SecurityException` while Do Not Disturb is on and the app has no `ACCESS_NOTIFICATION_POLICY`. Caught and logged — a refused volume change is a command that did not happen, not a reason to take the panel down with it.
- If another app holds exclusive audio policy (rare), volume changes may not apply — detect by reading the stream volume back after adjusting, and log a warning. Do not speak.

## 10. Testing

- Template tables (§3–6) as parameterized unit tests, including the `steps` modifier binding and the four named levels of §3a.
- **Collision tests, one family in one test:** `ruhe` → `system.mute`; `ton aus` → `system.mute`; `lautstärke aus` → `system.mute`; `musik aus` → `spotify.pause`; `radio aus` → `radio.stop_radio`; `stopp` → `shared.stop`; `aus` → `shared.stop`. The last two matter here precisely because this Sock owns several `… aus` phrasings and must not swallow the bare form. Add `lautstärke laut` → `system.set_volume` and `lautstärke lauter` → `system.volume` beside them (§7).
- Volume handler against a fake `VolumeControl`: raise/lower, clamping at both ends, unmute-then-raise, `max_volume_percent` ceiling, and the mute → unmute round trip restoring the level.
- `VolumeScale` on its own, at 7, 15 and 25 indices: a relative change always moves, and a named level never rounds to zero. This is the arithmetic the device would otherwise hide.
- Screen handler against a fake `ScreenController` and a fake `DevicePolicyManager`, both with and without admin.
- Pure JVM.

## 11. Open questions / out of scope (v1)

- ~~**Absolute volume**~~ — **done**, as `system.set_volume` (§3a), the second-command option this section recommended.
- **The two screen commands** (§5, §6). Specified, not built: they need a device-admin receiver in the manifest and a one-off enable step, which is a setup change rather than a handler.
- **A dashboard card.** The Sock exposes no state today; the panel shows no volume. Cheap to add once there is a reason to look at it.
- Brightness control (`Settings.System.SCREEN_BRIGHTNESS` needs `WRITE_SETTINGS`) — plausible for a wall panel, deliberately out of v1.
- Wi-Fi / Bluetooth / flashlight / reboot — not a phone, not a remote control.
- Per-stream volume (alarm vs. media) — everything is `STREAM_MUSIC` in v1.
