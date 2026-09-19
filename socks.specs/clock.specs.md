# Sock: Clock

> **Implementation status.** Built in full: `set_timer`, `cancel_timer`, `whats_the_time` and
> the `shared.stop` subscription all ship in `:socks:clock`, with `AlarmManager` behind
> [`TimerAlarm`](../socks/clock/src/main/kotlin/io/dobby/socks/clock/TimerAlarm.kt) and
> `SoundPool` behind [`ChimePlayer`](../socks/clock/src/main/kotlin/io/dobby/socks/clock/Chime.kt),
> so the whole timer lifecycle is tested on a plain JVM against a virtual clock. The dashboard
> card (§7) renders the exposed state on the panel. `set_timer` also asks for a missing unit,
> which is the first user of the follow-up contract ([README §5](README.md#5-follow-up-questions)).
> Still open: everything in §12.

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
| `clock.set_timer` | `amount: int`, `unit: enum` *(optional — asked for when missing, §3)* | Stellt einen Timer für eine bestimmte Dauer. |
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
| `unit` | enum | **no** | — | `sekunden` \| `minuten` \| `stunden` |

`unit` is optional because "Stell einen Timer auf zehn" is a sentence German people really say. A missing unit is not a parse failure — the Sock asks for it (§3, *Follow-up*).

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

Plus the unit-less forms, which sit **last** — they are strictly less specific, and a palette that reached them first would ask "10 was?" about "timer 10 minuten":

```
(stell|stelle|setz|setze|mach) (mir )?(einen |nen )?timer( auf| für)? {amount:int}
(stell|stelle|setz|setze) (mir )?(einen |nen )?wecker( auf| für)? {amount:int}
timer( auf| für)? {amount:int}
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
| stell einen timer auf zehn *(normalized: `stell einen timer auf 10`)* | `set_timer(amount=10)` → asks |
| timer 5 | `set_timer(amount=5)` → asks |
| timer auf 90 | `set_timer(amount=90)` → asks |
| setz einen wecker auf 2 | `set_timer(amount=2)` → asks |

### Follow-up: the missing unit

An invocation with an `amount` and no `unit` cannot be executed and must not be refused either — ten *what* is the only thing missing, and asking is one short sentence. The Sock answers with `Asked` ([README §5](README.md#5-follow-up-questions)):

| | |
|---|---|
| **Question (German TTS)** | "{amount} was — Sekunden, Minuten oder Stunden?" (e.g. "10 was — Sekunden, Minuten oder Stunden?") |
| **Follow-up command** | `clock.set_timer` |
| **Follow-up templates** | `{unit:enum}` · `(in\|auf\|für) {unit:enum}` |
| **Token holds** | the spoken `amount`, and nothing else |

The answer arrives as a second `handle()` call with `answering = token`. The Sock reads the stashed amount and sets the timer exactly as if both had been said at once — including the singular rule, so "eins" then "Minute" says "Timer läuft: 1 Minute."

| Utterance | Then | Result |
|---|---|---|
| stell einen timer auf zehn | minuten | `Spoken("Timer läuft: 10 Minuten.")`, timer at 600 s |
| timer 5 | in minuten | `Spoken("Timer läuft: 5 Minuten.")`, timer at 300 s |
| stell einen timer auf eins | minute | `Spoken("Timer läuft: 1 Minute.")`, timer at 60 s |
| timer 20 | stunden | `Failed("Diese Dauer kann ich nicht stellen.")` — past the 12 h cap |
| stell einen timer auf zehn | stopp | goes to `shared.stop`; the amount is dropped and **no timer is set** |
| stell einen timer auf zehn | *(silence)* | the turn ends, the amount is dropped, no timer is set |

The turn ending, or any other command being spoken instead, reaches the Sock as `onAskCancelled(token)`; it drops the stashed amount there. An answer whose token the Sock no longer holds is `Failed("Diese Dauer kann ich nicht stellen.")` — a kitchen timer must not guess at a duration it cannot reconstruct.

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
| New timer set | `Spoken` | "Timer läuft: {amount} {unit}." (e.g. "Timer läuft: 10 Minuten.") — `{unit}` is spoken in the singular when `amount` is 1: "Timer läuft: 1 Minute." |
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
(stopp|stoppe|aus mit) (dem |den |das )?(alarm|wecker|klingeln)
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
was ist die zeit
sag (mir )?(die )?zeit
what time is it
(whats|what's|what is) the time( now)?
```

This is the broadest command in the catalog on purpose. Tier 1 is the cheap tier — more
templates enlarge a lookup table, while the expensive thing is the Tier 2 system prompt, which
grows with `description` and `examples` and not with templates ([`dobby-plan.md`](../dobby-plan.md)
§9). The constraint on breadth is never the count; it is the two rules below.

### Utterances → invocation

| Utterance | Invocation |
|---|---|
| wie spät ist es | `whats_the_time()` |
| wie spät | `whats_the_time()` |
| wie viel uhr ist es | `whats_the_time()` |
| uhrzeit | `whats_the_time()` |
| sag mir die uhrzeit | `whats_the_time()` |
| was ist die zeit | `whats_the_time()` |
| sag mir die zeit | `whats_the_time()` |
| whats the time | `whats_the_time()` |
| what's the time | `whats_the_time()` |
| whats the time now | `whats_the_time()` |
| what time is it | `whats_the_time()` |

### Why "zeit" never stands alone

Every template above that uses `zeit` carries keywords around it. A **bare** `zeit` is
deliberately absent and must stay absent: keywords are fuzzed by length, and at 4 characters
`zeit` tolerates one edit ([`Levenshtein.tolerance`](../core/src/main/kotlin/io/dobby/core/nlu/template/Levenshtein.kt)).
A bare template would therefore also answer **`seit`**, **`weit`** and **`zeig`** — three words
somebody says in a kitchen without addressing the panel at all. The same goes for a bare English
`time`.

The failure mode is asymmetric, and that asymmetry is the whole argument: a missed utterance
costs a repeat, a panel that speaks when nobody addressed it costs trust. The general form of
this rule is in [README §6](README.md#6-template-dsl-tier-1) — `lauter` is the case where a
single keyword is fine, `zeit` is the case where it is not.

### Why English is here, and only here

The recogniser is Parakeet TDT 0.6B v3, multilingual across 25 European languages with German
and English among them ([`dobby-plan.md`](../dobby-plan.md) §4). English tokens arrive intact,
so English templates work.

They are confined to commands with **no integer and no enum slot**. The normalizer is
German-only ([`GermanNumbers`](../core/src/main/kotlin/io/dobby/core/nlu/GermanNumbers.kt)): it
turns "zehn" into `10` and knows nothing of "ten", so an English template feeding an
`{x:int}` slot would match and then silently fail to coerce. `whats_the_time` takes no params
and qualifies; `set_timer` (§3) does not, and gets no English.

Apostrophes **survive** normalization — the normalizer's keep-class includes `'`, which is why
`ich hab's gehört` is spelled with one (§4). So the English templates are written `what's`, and
list `whats` beside it for the transcript that arrives without the apostrophe.

Dobby answers in **German regardless of the language it was addressed in**. That is a product
decision, not an oversight: the panel has one voice, and a device in an Austrian kitchen that
switches personality because somebody phrased a question in English is a worse device. Do not
"fix" it.

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

Exclusively claimed: `timer …`, `wecker …`, `wie spät …`, `wie viel uhr …`, `uhrzeit`, `was ist die zeit`, `sag (mir) (die) zeit`, `erinner(e) mich in …`, and `stopp/beende/brich … <timer|alarm|wecker|klingeln>`.

English, claimed on `whats_the_time` alone (§6): `whats the time`, `what's the time`, `what is the time`, `whats the time now`, `what time is it` — so the keywords `whats`, `what's`, `what`, `is`, `it`, `the`, `time` and `now` are in play, but only in those sequences. **Not** claimed: a bare `zeit` or a bare `time`, for the reason in §6.

Contributed to `shared.stop`, not owned: `ich hab's gehört`, `ja ja`, `ist gut` (plus the catalog's bare `stopp` / `pause` / `aus`).

- `timer …` now also claims an amount with no unit (`timer 10`, `stell einen timer auf zehn`), which is answered with a question rather than a template miss.
- ⚠️ `stell einen wecker auf 7 uhr` (an alarm at a wall-clock time) currently matches **nothing** — `7 uhr` is not `{amount}{unit}`, and `uhr` is not a unit the follow-up offers either. It falls through to Tier 2 and then to `none`. See §12.

## 9. Config

| Key | Type | Default | Where set |
|---|---|---|---|
| `clock.chime_sound` | enum (`glocke`, `piep`, `gong`) | `glocke` | Settings |
| `clock.chime_max_duration_s` | int | `60` | Settings (advanced) |
| `clock.chime_interval_s` | int | `3` | Settings (advanced) |
| `clock.show_seconds` | bool | `false` | Settings |

The Sock also writes two keys nobody sets by hand: `clock.pending_timer_ends_at` and
`clock.pending_timer_total_ms`. They are the running timer's deadline, written so a restarted
process can pick it up (§10), and cleared the moment the timer is cancelled or heard.

## 10. Failure & degradation

- No exact-alarm permission → `Degraded("Timer nicht garantiert genau")`; the coroutine still runs, only the backstop is weaker. Commands stay available.
- Process death while a timer runs → the `AlarmManager` backstop fires and restarts the service to announce. The deadline is in config, so the restarted Sock resumes a timer that is still running and rings one that came due while nobody was home; a deadline more than an hour old is dropped instead, because a chime an hour late is not news. **Verify this on the actual device under OxygenOS** — its background restrictions are the real risk here, not the Android API (see plan §7.4). A refused foreground-service start is logged and the service stops, rather than crash-looping a `START_STICKY` service.
- Never `Unavailable`.

## 11. Testing

- Template tables (§3, §4, §6) as parameterized unit tests, including normalizer output ("zehn" → "10") as input.
- **Routing tests:** "timer stopp" → `clock.cancel_timer` (always, regardless of chain state); "stopp" → `shared.stop`.
- **Chain tests** — the two cases the design exists for:
  - chime ringing + Spotify playing → Clock consumes, **music resumes at full volume**, Spotify's handler is never invoked;
  - timer counting down + Spotify playing → Clock returns `NotForMe`, Spotify pauses, **the timer still runs afterwards**.
- `activityFor` returns `ACTIVE` only for `isRinging`, asserted against a counting-down state.
- Duration conversion incl. clamping and rejection.
- **The follow-up, end to end, through `DobbyEngine`:** "stell einen timer auf zehn" → question → "minuten" → a timer at 600 s; the same path with "stopp" instead of an answer, asserting that the chain gets it and no timer is set; the turn ending instead of an answer; an answer with no stashed amount. Plus the ordering assertion that a phrasing naming its unit never falls through to a unit-less template.
- Time phrasing: a table covering every branch, with 14:30 → "halb 3" explicitly asserted.
- Timer lifecycle with a virtual clock: set, replace, cancel before expiry, expiry, cancel while ringing, chime auto-stop at max duration.
- Pure-JVM only; `AlarmManager` sits behind a small interface with a fake.

## 12. Open questions / out of scope (v1)

- **Multiple simultaneous timers** and named timers ("Nudeltimer") — the data model allows it, the commands do not expose it.
- **Alarms at a wall-clock time** ("Wecker auf 7 Uhr") — a genuinely different command (`set_alarm`) with a different param shape. Deferred; §7 documents the current dead end.
- Timers surviving a reboot. A timer does now survive a *process* death (§10), because it must; a reboot clears the alarm and stops the service, and nothing re-arms it.
- Stopwatch, countdown to a date, world clocks.
- Date questions ("Welcher Tag ist heute?") — trivial to add, deliberately not in v1's command list.
