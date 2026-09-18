# Sock: System

## 1. Identity

| | |
|---|---|
| **id** | `system` |
| **displayName** | System |
| **Purpose** | Device-level controls: volume, mute, screen. |
| **Milestone** | M3 |
| **Dependencies** | `AudioManager`, core's `ScreenController`, `DevicePolicyManager` (for `turn_off_screen` — see §6) |
| **Permissions** | None at manifest level. `turn_off_screen` needs Dobby to be an **active device admin**, enabled once by the user. |
| **Audio** | None. Adjusts the stream, never plays on it. |

This Sock is the thinnest possible wrapper over the platform — but it is still a Sock, because core must not know that "lauter" exists.

## 2. Commands

### Exclusive

| Command id | Params | Description (German, feeds Tier 2) |
|---|---|---|
| `system.volume` | `direction: enum`, `steps: int?` | Ändert die Lautstärke schrittweise. |
| `system.mute` | `state: enum?` | Schaltet den Ton stumm oder wieder an. |
| `system.turn_on_screen` | — | Schaltet den Bildschirm ein. |
| `system.turn_off_screen` | — | Schaltet den Bildschirm aus. |

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

### Tier 1 templates

```
(viel|deutlich|wesentlich) (lauter|leiser)
(mach|dreh|stell) (es |die musik |den ton |die lautstärke )?(lauter|leiser)
(lauter|leiser)
lautstärke (hoch|runter|rauf|runter drehen)
(ein bisschen|etwas) (lauter|leiser)
```

`viel/deutlich/wesentlich …` → `steps = 5`. `ein bisschen/etwas …` → `steps = 1`. Everything else → default `2`. The modifier is folded into `steps` by the template's static param binding, not by the handler.

### Utterances → invocation

| Utterance | Invocation |
|---|---|
| lauter | `volume(direction=lauter, steps=2)` |
| leiser | `volume(direction=leiser, steps=2)` |
| mach lauter | `volume(direction=lauter, steps=2)` |
| dreh die musik leiser | `volume(direction=leiser, steps=2)` |
| viel lauter | `volume(direction=lauter, steps=5)` |
| ein bisschen leiser | `volume(direction=leiser, steps=1)` |
| lautstärke hoch | `volume(direction=lauter, steps=2)` |

### Behavior

`audioManager.adjustStreamVolume(STREAM_MUSIC, ADJUST_RAISE|ADJUST_LOWER, 0)` called `steps` times — **flag `0`, not `FLAG_SHOW_UI`**: this is a wall panel, a system volume overlay covering the dashboard is wrong.

If the stream is muted and `direction = lauter`, unmute first, then raise.

Clamping at 0 or max is not an error.

### Result

`Silent` — the user hears the change immediately; TTS would fight the thing being adjusted.

Exception: already at max/min and the command would be a no-op → `Spoken("Schon ganz {laut|leise}.")`, so a broken volume path is distinguishable from a working one at the extremes.

---

## 4. `system.mute`

### Params

| Name | Type | Required | Default | Constraints |
|---|---|---|---|---|
| `state` | enum | no | `an` | `an` (muted) \| `aus` (unmuted) |

> The param is the *mute* state, not the *sound* state: `state=an` means muted. Templates below bind it explicitly so the German never has to be reasoned about at runtime.

### Tier 1 templates

```
(stumm|stummschalten|stumm schalten|mach stumm|sei still|ruhe)                       → state=an
(ton|lautstärke) aus                                                                 → state=an
(ton|lautstärke) (an|wieder an|wieder ein|ein)                                       → state=aus
(nicht mehr stumm|stumm aus|entstummen|laut stellen|wieder laut)                     → state=aus
```

### Utterances → invocation

| Utterance | Invocation |
|---|---|
| stumm | `mute(state=an)` |
| stummschalten | `mute(state=an)` |
| ton aus | `mute(state=an)` |
| sei still | `mute(state=an)` |
| ton an | `mute(state=aus)` |
| ton wieder an | `mute(state=aus)` |
| nicht mehr stumm | `mute(state=aus)` |

> ⚠️ `ton aus` vs. Spotify's `musik aus` are deliberately different commands. `ton aus` mutes the stream (playback continues, silently); `musik aus` pauses Spotify. Both are what the respective words mean. Documented here because it *will* look like a bug in a log.

### Behavior

`audioManager.adjustStreamVolume(STREAM_MUSIC, ADJUST_MUTE | ADJUST_UNMUTE, 0)`.

Muting does **not** stop playback and does **not** release playback focus.

**Announcements still speak while muted?** No — TTS shares `STREAM_MUSIC`, so a muted device is silent, including timer alarms. Mitigation: while muted, timer expiry additionally flashes the dashboard (see `clock.specs.md` §6) and posts a notification. Accept that "stumm" means stumm.

### Result

| Case | Result | German TTS |
|---|---|---|
| `state=an` | `Silent` | — |
| `state=aus` | `Spoken("Ton ist wieder an.")` | confirms audibly, which is the point |
| `state=aus` while not muted | `Silent` | — |

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

Exclusively claimed: `lauter`, `leiser`, `lautstärke …`, `stumm …`, `ton an` / `ton aus`, `sei still`, `ruhe`, `bildschirm …`, `display …`, `schirm …`, `wach auf`, `dashboard`, `gute nacht`.

Contributes to no chain.

- Does **not** claim bare `stopp`, `aus`, `pause`, `weiter` (→ `shared.stop` / `shared.resume`), nor `musik aus` (Spotify) or `radio aus` (Radio).
- `ruhe` is unambiguously mute. The Clock Sock's chime-silencing phrasings moved onto `shared.stop` when the chain was introduced, so the old `ruhe` / `ruhe jetzt` near-collision no longer exists — do not reintroduce a `ruhe`-prefixed template anywhere.
- ⚠️ `ton aus` (mute, playback continues silently) and `musik aus` (pause Spotify) remain deliberately distinct. Keep them apart.

## 8. Config

| Key | Type | Default | Where set |
|---|---|---|---|
| `system.volume_default_steps` | int | `2` | Settings |
| `system.screen_on_duration_s` | int | `60` | Settings |
| `system.max_volume_percent` | int | `100` | Settings — a ceiling, so "viel lauter" at 3 a.m. cannot reach full volume |

## 9. Failure & degradation

- No device admin → `Degraded("Bildschirm ausschalten eingeschränkt")`. All other commands unaffected.
- `AudioManager` calls do not meaningfully fail; there is no `Unavailable` state for this Sock.
- If another app holds exclusive audio policy (rare), volume changes may not apply — detect by reading the stream volume back after adjusting, and log a warning. Do not speak.

## 10. Testing

- Template tables (§3–6) as parameterized unit tests, including the `steps` modifier binding.
- **Collision tests, one family in one test:** `ruhe` → `system.mute`; `ton aus` → `system.mute`; `musik aus` → `spotify.pause`; `radio aus` → `radio.stop_radio`; `stopp` → `shared.stop`; `aus` → `shared.stop`. The last two matter here precisely because this Sock owns several `… aus` phrasings and must not swallow the bare form.
- Volume handler against a fake `AudioManager`: raise/lower, clamping at both ends, unmute-then-raise, `max_volume_percent` ceiling.
- Screen handler against a fake `ScreenController` and a fake `DevicePolicyManager`, both with and without admin.
- Pure JVM.

## 11. Open questions / out of scope (v1)

- **Absolute volume** ("Lautstärke auf 50 Prozent", "Lautstärke 3") — a real gap, noted in the plan's original palette review. It needs either a second command (`system.set_volume(level: int)`) or an optional exclusive param on `system.volume`; the latter complicates the generated GBNF. Recommendation: add `system.set_volume` after M3, once the registry has proven it handles four Socks.
- Brightness control (`Settings.System.SCREEN_BRIGHTNESS` needs `WRITE_SETTINGS`) — plausible for a wall panel, deliberately out of v1.
- Wi-Fi / Bluetooth / flashlight / reboot — not a phone, not a remote control.
- Per-stream volume (alarm vs. media) — everything is `STREAM_MUSIC` in v1.
