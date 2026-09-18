# Sock: Clock

> **Implementation status.** `whats_the_time` is built and shipping in `:socks:clock`.
> `set_timer`, `cancel_timer` and the `shared.stop` subscription are specified below but **not
> implemented**, and the Sock deliberately does not declare them — a command that is declared
> but unhandled is worse than one that is absent, because the palette advertises it and the
> Tier 2 prompt teaches the LLM to emit it.

## 1. Identity

| | |
|---|---|
| **id** | `clock` |
| **displayName** | Uhr |
| **Purpose** | Kitchen timer and time-of-day. No network, no account — the reference Sock for "does the registry actually compose?". |
| **Milestone** | M3 |
| **Dependencies** | None beyond the platform: `AlarmManager`, coroutines, `SoundPool` for the chime |
| **Permissions** | `SCHEDULE_EXACT_ALARM` (Android 13: granted at install for `USE_EXACT_ALARM`-class use; if denied, degrade — see §8) |
| **Audio** | Chime only. Requests `AUDIO_TRANSIENT` focus — ducks Spotify/Radio rather than stopping them. |

## 2. Commands

### Exclusive

| Command id | Params | Description (German, feeds Tier 2) |
|---|---|---|
| `clock.set_timer` | `amount: int`, `unit: enum` | Stellt einen Timer für eine bestimmte Dauer. |
| `clock.cancel_timer` | — | Bricht den laufenden Timer ab — **explizit adressiert** ("Timer stopp"). |
| `clock.whats_the_time` | — | Sagt die aktuelle Uhrzeit. |

### Shared subscriptions

| Shared command | Priority | Why |
|---|---|---|
| `shared.stop` | **100** | A ringing alarm is the most salient thing in the room — bare "stopp" means *that* first |

This Sock is `ACTIVE` on `shared.stop` **only while the chime is actually ringing** (§5). A merely counting-down timer must never consume a bare "stopp" — see [shared-commands.specs.md](shared-commands.specs.md).

> **Deviation from the original plan:** the timer param is `amount: int` + `unit: enum`, not a single `duration_s: int`. A slot the user actually speaks must be directly capturable by a Tier 1 template and directly expressible in the GBNF grammar; "duration in seconds" is neither. The handler derives `duration_s`.

---

## 3. `clock.set_timer`

### Params

| Name | Type | Required | Default | Constraints |
|---|---|---|---|---|
| `amount` | int | yes | — | 1 … 600 |
| `unit` | enum | yes | — | `sekunden` \| `minuten` \| `stunden` |

`duration_s = amount × {sekunden: 1, minuten: 60, stunden: 3600}`, clamped to **1 s … 12 h**.

The STT normalizer in core has already turned German number words into digits ("zehn" → 10) before templates run — this Sock does not do number parsing.

### Tier 1 templates

```
(stell|stelle|setz|setze|mach) (mir )?(einen |nen )?timer( auf| für)? {amount:int} {unit:enum}
timer( auf| für)? {amount:int} {unit:enum}
{amount:int} {unit:enum} timer
(stell|stelle|setz|setze) (mir )?(einen |nen )?wecker( auf| für)? {amount:int} {unit:enum}
(erinner|erinnere) mich in {amount:int} {unit:enum}
```

The `{unit:enum}` slot matches the enum values plus their singular forms (`sekunde`, `minute`, `stunde`) — singular/plural folding happens in the enum matcher, not in five extra templates.

### Utterances → invocation

| Utterance | Invocation |
|---|---|
| timer zehn minuten *(normalized: `timer 10 minuten`)* | `set_timer(amount=10, unit=minuten)` |
| stell einen timer auf 5 minuten | `set_timer(amount=5, unit=minuten)` |
| stelle mir einen timer für 90 sekunden | `set_timer(amount=90, unit=sekunden)` |
| 3 minuten timer | `set_timer(amount=3, unit=minuten)` |
| timer eine minute | `set_timer(amount=1, unit=minuten)` |
| setz einen wecker auf 2 stunden | `set_timer(amount=2, unit=stunden)` |
| erinner mich in 20 minuten | `set_timer(amount=20, unit=minuten)` |

### Behavior

1. **One timer at a time (v1).** A `set_timer` while a timer is running **replaces** it, and says so (§Result). The internal model is already a list so multi-timer is a later change, not a rewrite.
2. Start a coroutine countdown in `ctx.coroutineScope` (drives the UI, 1 s tick).
3. Register an `AlarmManager.setExactAndAllowWhileIdle` for the same instant as a **backstop** — the coroutine is the primary mechanism, the alarm exists so a doze or process death does not swallow the timer.
4. On expiry:
   - `ctx.screen.wakeFor(30.seconds)`
   - `ctx.playback.requestTransientFocus()` — ducks music, does not stop it
   - `ctx.announce("Der Timer ist abgelaufen.")`
   - repeating chime, every 3 s, for up to **60 s** or until `cancel_timer`
   - release transient focus when the chime ends
5. On device reboot the timer is lost. Accepted — see §10.

### Result

| Case | Result | German TTS |
|---|---|---|
| New timer set | `Spoken` | "Timer läuft: {amount} {unit}." (e.g. "Timer läuft: 10 Minuten.") |
| Replaced a running timer | `Spoken` | "Alter Timer ersetzt. Timer läuft: {amount} {unit}." |
| `amount` out of range (0 or > 12 h after conversion) | `Failed` | "Diese Dauer kann ich nicht stellen." |
| Exact-alarm permission missing | `Spoken` + status `Degraded` | "Timer läuft: {amount} {unit}. Achtung, er ist nicht garantiert genau." |

Expiry announcement is **not** a `SockResult` — it is asynchronous, via `ctx.announce`.

---

## 4. `clock.cancel_timer`

### Tier 1 templates

```
timer (stopp|stop|stoppen|abbrechen|aus|löschen|beenden|abschalten)
(stopp|stoppe|brich|breche|lösch|lösche|beende) (den )?timer( ab)?
(stopp|stoppe|aus mit) (dem )?(alarm|wecker|klingeln)
```

> Bare `stopp` is **not** claimed — it routes to `shared.stop`, which this Sock wins while the chime is ringing (§5). The word `timer`, `alarm`, `wecker` or `klingeln` is required here, because this command must also work while a timer is merely counting down and music is playing.
>
> `ich hab's gehört` / `ja ja` / `ist gut` are contributed as this Sock's `extraTemplates` on `shared.stop` — they only make sense while something is ringing, which is exactly what the chain already tests for.

### Utterances → invocation

| Utterance | Invocation |
|---|---|
| timer stopp | `cancel_timer()` |
| timer abbrechen | `cancel_timer()` |
| stopp den timer | `cancel_timer()` |
| brich den timer ab | `cancel_timer()` |
| stopp den alarm | `cancel_timer()` |

| Utterance | Goes to |
|---|---|
| stopp | `shared.stop` — consumed here only if the chime is ringing |
| ich hab's gehört | `shared.stop` — same |

### Behavior

Two distinct jobs behind one command — this is intentional, because it is what a user means in both situations:

1. **Chime is sounding** → silence it, release transient focus. Result `Silent`.
2. **Timer running, not yet expired** → cancel the coroutine and the backstop alarm. Result `Spoken("Timer abgebrochen.")`.
3. **Nothing running** → `Spoken("Es läuft gerade kein Timer.")`.

---

## 5. Activity

| Shared command | `ACTIVE` | `IDLE` | `INACTIVE` |
|---|---|---|---|
| `shared.stop` | the chime is **ringing right now** | never | everything else — including a timer counting down |

Read from the in-memory `TimerState.isRinging`. No I/O, trivially.

Consumes:

- `shared.stop` while `ACTIVE` → silence the chime, cancel the repeat, release transient audio focus (**ducked music returns to full volume**) → `Silent`.
- Otherwise → `NotForMe`.

Two decisions worth stating plainly, because both are easy to get wrong later:

- **Never `ACTIVE` for a counting-down timer.** "Stopp" with a 10-minute timer running and music playing means *pause the music*. Cancelling a running timer is a destructive, hard-to-undo action and must be explicitly addressed ("timer stopp").
- **Priority 100 — the highest in the catalog.** When the chime rings *and* the radio plays, both are `ACTIVE`; the alarm wins. Silencing the thing that is demanding attention is unambiguously what "stopp" means in that moment, and a person who wanted the radio off will say so again a second later.

---

## 6. `clock.whats_the_time`

### Tier 1 templates

```
wie (spät|viel uhr) ist (es|es jetzt)
wie spät
(wie viel uhr|uhrzeit|die uhrzeit)
sag (mir )?(die )?uhrzeit
was ist die uhrzeit
```

### Utterances → invocation

| Utterance | Invocation |
|---|---|
| wie spät ist es | `whats_the_time()` |
| wie spät | `whats_the_time()` |
| wie viel uhr ist es | `whats_the_time()` |
| uhrzeit | `whats_the_time()` |
| sag mir die uhrzeit | `whats_the_time()` |

### Behavior

Read the system clock in the device's zone. Wake the screen for 30 s (the dashboard clock is the visual half of the answer).

### Result

`Spoken` in natural Austrian German, **not** digit-by-digit:

| Time | Spoken |
|---|---|
| 14:00 | "Es ist 14 Uhr." |
| 14:05 | "Es ist 14 Uhr 5." |
| 14:30 | "Es ist halb 3." |
| 09:15 | "Es ist viertel nach 9." |
| 09:45 | "Es ist viertel vor 10." |
| 23:47 | "Es ist 23 Uhr 47." |

Rule: exact hour, quarter past, half (German "halb X" = X−0:30 — get this right, it is the classic bug), quarter to; everything else as "H Uhr M". 12-hour phrasing for the quarter/half forms, 24-hour for the plain form.

---

## 7. State & dashboard

Always-present `DashboardCard` (this Sock owns the panel's clock):

- Large clock, HH:MM, seconds omitted (no per-second redraw on an OLED panel).
- Date line, German long format: "Donnerstag, 18. September".
- Timer countdown when a timer is active: MM:SS, and a progress ring.
- Chime state: visibly flashing while ringing, so a muted device still shows it.

Exposed state: `StateFlow<ClockState>` = `now: LocalDateTime`, `timer: TimerState?` (`endsAt`, `totalMs`, `remainingMs`, `isRinging`).

## 8. Utterance collision surface

Exclusively claimed: `timer …`, `wecker …`, `wie spät …`, `wie viel uhr …`, `uhrzeit`, `erinner(e) mich in …`, and `stopp/beende/brich … <timer|alarm|wecker|klingeln>`.

Contributed to `shared.stop`, not owned: `ich hab's gehört`, `ja ja`, `ist gut` (plus the catalog's bare `stopp` / `pause` / `aus`).

- ⚠️ `stell einen wecker auf 7 uhr` (an alarm at a wall-clock time) currently matches **nothing** — `7 uhr` is not `{amount}{unit}`. It falls through to Tier 2 and then to `none`. See §11.

## 9. Config

| Key | Type | Default | Where set |
|---|---|---|---|
| `clock.chime_sound` | enum (`glocke`, `piep`, `gong`) | `glocke` | Settings |
| `clock.chime_max_duration_s` | int | `60` | Settings (advanced) |
| `clock.chime_interval_s` | int | `3` | Settings (advanced) |
| `clock.show_seconds` | bool | `false` | Settings |

## 10. Failure & degradation

- No exact-alarm permission → `Degraded("Timer nicht garantiert genau")`; the coroutine still runs, only the backstop is weaker. Commands stay available.
- Process death while a timer runs → the `AlarmManager` backstop fires and restarts the service to announce. **Verify this on the actual device under OxygenOS** — its background restrictions are the real risk here, not the Android API (see plan §7.4).
- Never `Unavailable`.

## 11. Testing

- Template tables (§3, §4, §6) as parameterized unit tests, including normalizer output ("zehn" → "10") as input.
- **Routing tests:** "timer stopp" → `clock.cancel_timer` (always, regardless of chain state); "stopp" → `shared.stop`.
- **Chain tests** — the two cases the design exists for:
  - chime ringing + Spotify playing → Clock consumes, **music resumes at full volume**, Spotify's handler is never invoked;
  - timer counting down + Spotify playing → Clock returns `NotForMe`, Spotify pauses, **the timer still runs afterwards**.
- `activityFor` returns `ACTIVE` only for `isRinging`, asserted against a counting-down state.
- Duration conversion incl. clamping and rejection.
- Time phrasing: a table covering every branch, with 14:30 → "halb 3" explicitly asserted.
- Timer lifecycle with a virtual clock: set, replace, cancel before expiry, expiry, cancel while ringing, chime auto-stop at max duration.
- Pure-JVM only; `AlarmManager` sits behind a small interface with a fake.

## 12. Open questions / out of scope (v1)

- **Multiple simultaneous timers** and named timers ("Nudeltimer") — the data model allows it, the commands do not expose it.
- **Alarms at a wall-clock time** ("Wecker auf 7 Uhr") — a genuinely different command (`set_alarm`) with a different param shape. Deferred; §7 documents the current dead end.
- Timers surviving a reboot.
- Stopwatch, countdown to a date, world clocks.
- Date questions ("Welcher Tag ist heute?") — trivial to add, deliberately not in v1's command list.
