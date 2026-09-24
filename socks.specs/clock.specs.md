# Sock: Clock

> **Implementation status.** Built in full: `set_timer`, `cancel_timer`, `cancel_all_timers`,
> `timer_remaining`, `whats_the_time`, `whats_the_date` and the `shared.stop` subscription all ship in
> `:socks:clock`, with `AlarmManager` behind
> [`TimerAlarm`](../socks/clock/src/main/kotlin/io/dobby/socks/clock/TimerAlarm.kt) and
> `SoundPool` behind [`ChimePlayer`](../socks/clock/src/main/kotlin/io/dobby/socks/clock/Chime.kt),
> so the whole timer lifecycle is tested on a plain JVM against a virtual clock. The dashboard
> card (§9) renders the exposed state on the panel. `set_timer` also asks for a missing unit,
> which is the first user of the follow-up contract ([README §5](README.md#5-follow-up-questions)),
> and `cancel_timer` is the second — it asks *which* timer when several are running.
>
> **Several timers at once, named or numbered**, since M7. v1 held one timer and said so; the
> data model always allowed a list and the commands now expose it.
>
> **`whats_the_date` (§8a)** is the one command §14 called trivial and left out, now that the
> panel is asked it often enough to be worth the templates.
>
> **The calendar, §8b–§8e**: `weekday_of_date`, `date_of_weekday`, `date_in` and `time_until`
> ship too, which closes §14's "dates other than today" and turns this Sock into the one that
> owns the calendar as well as the clock. They are four reads of the injected `Clock` and no
> state at all, which is why they live here rather than in a Sock of their own — a second Sock
> speaking about dates would have to fight this one for "welcher Tag …". `GermanNumbers` learned
> to read ordinals out of an `{n:int}` slot for them, and that is the only change outside
> `:socks:clock`. Still open: everything else in §14.

## 1. Identity

| | |
|---|---|
| **id** | `clock` |
| **displayName** | Uhr |
| **Purpose** | Kitchen timer and time-of-day. No network, no account — the reference Sock for "does the registry actually compose?". |
| **Milestone** | M3 |
| **Dependencies** | None beyond the platform: `AlarmManager`, coroutines, `SoundPool` for the chime |
| **Permissions** | `SCHEDULE_EXACT_ALARM` (Android 13: granted at install for `USE_EXACT_ALARM`-class use; if denied, degrade — see §12) |
| **Audio** | Chime only. Requests `AUDIO_TRANSIENT` focus — ducks Spotify/Radio rather than stopping them. |

## 2. Commands

### Exclusive

| Command id | Params | Description (German, feeds Tier 2) |
|---|---|---|
| `clock.set_timer` | `amount: int`, `unit: enum` *(optional — asked for when missing, §3)*, `name: text` *(optional)* | Stellt einen Timer für eine bestimmte Dauer, auf Wunsch unter einem Namen. |
| `clock.cancel_timer` | `name: text` *(optional — asked for when ambiguous, §4)* | Bricht einen laufenden Timer ab, auf Wunsch den mit einem bestimmten Namen. |
| `clock.cancel_all_timers` | — | Bricht alle laufenden Timer ab. |
| `clock.timer_remaining` | `name: text` *(optional)* | Sagt, wie lange ein Timer oder alle Timer noch laufen. |
| `clock.whats_the_time` | — | Sagt die aktuelle Uhrzeit. |
| `clock.whats_the_date` | — | Sagt, welcher Tag heute ist. |
| `clock.weekday_of_date` | `day: int`, `month: enum` *(optional)* | Sagt, auf welchen Wochentag ein bestimmtes Datum fällt. |
| `clock.date_of_weekday` | `weekday: enum` | Sagt das Datum des nächsten genannten Wochentags. |
| `clock.date_in` | `amount: int`, `unit: enum` | Sagt das Datum, das eine bestimmte Zahl von Tagen, Wochen, Monaten oder Jahren in der Zukunft liegt. |
| `clock.time_until` | `unit: enum` *(optional, `tage`)*, `day: int` *(optional)*, `month: enum` *(optional)*, `weekday: enum` *(optional)* | Zählt, wie viele Tage, Wochen oder Monate es noch bis zu einem Datum oder Wochentag sind. |

### Shared subscriptions

| Shared command | Priority | Why |
|---|---|---|
| `shared.stop` | **100** | A ringing alarm is the most salient thing in the room — bare "stopp" means *that* first |

This Sock is `ACTIVE` on `shared.stop` **only while the chime is actually ringing** (§7). A merely counting-down timer must never consume a bare "stopp" — see [shared-commands.specs.md](shared-commands.specs.md).

> **Deviation from the original plan:** the timer param is `amount: int` + `unit: enum`, not a single `duration_s: int`. A slot the user actually speaks must be directly capturable by a Tier 1 template and directly expressible in the GBNF grammar; "duration in seconds" is neither. The handler derives `duration_s`.

---

## 3. `clock.set_timer`

### Params

| Name | Type | Required | Default | Constraints |
|---|---|---|---|---|
| `amount` | int | yes | — | 1 … 600 |
| `unit` | enum | **no** | — | `sekunden` \| `minuten` \| `stunden` |
| `name` | text | **no** | — | free text, cleaned (§3, *Names*) |

`unit` is optional because "Stell einen Timer auf zehn" is a sentence German people really say. A missing unit is not a parse failure — the Sock asks for it (§3, *Follow-up*).

`duration_s = amount × {sekunden: 1, minuten: 60, stunden: 3600}`, clamped to **1 s … 12 h**.

The STT normalizer in core has already turned German number words into digits ("zehn" → 10) before templates run — this Sock does not do number parsing.

### Tier 1 templates

```
(stell|stelle|setz|setze|mach|neuer|neuen|neue|erstell|erstelle|starte|start) (einen |nen )?timer( auf| für)? {amount:int} {unit:enum}
timer( auf| für)? {amount:int} {unit:enum}
{amount:int} {unit:enum} timer
(stell|stelle|setz|setze) (einen |nen )?wecker( auf| für)? {amount:int} {unit:enum}
(erinner|erinnere) mich in {amount:int} {unit:enum}
```

Named, where the preposition is **not** optional:

```
(stell|…|start) (einen |nen )?timer {name}( auf| für) {amount:int} {unit:enum}
timer {name}( auf| für) {amount:int} {unit:enum}
```

Plus the unit-less forms, which sit **last** — they are strictly less specific, and a palette that reached them first would ask "10 was?" about "timer 10 minuten":

```
(stell|…|start) (einen |nen )?timer( auf| für)? {amount:int}
(stell|stelle|setz|setze) (einen |nen )?wecker( auf| für)? {amount:int}
timer( auf| für)? {amount:int}
(stell|…|start) (einen |nen )?timer {name}( auf| für) {amount:int}
timer {name}( auf| für) {amount:int}
```

No `(mir )?` anywhere above: "mir" is filler and the matcher skips it (README §6). `(einen |nen )?` stays, because it is what keeps the commonest phrasing of all — "stell einen timer auf 5 minuten" — on the strict first pass; "stelle mir einen timer für 90 sekunden" resolves on the second.

The `{unit:enum}` slot matches the enum values plus their singular forms (`sekunde`, `minute`, `stunde`) — singular/plural folding happens in the enum matcher, not in five extra templates.

> **Why the preposition is required in the named forms, and why that is the whole safety argument.**
> A `{name}` slot is free text: it takes whatever tokens sit in it, so it needs a keyword on
> both sides or it swallows the rest of the sentence. With `( auf| für)` required, "timer auf 5
> minuten" **cannot** match a named template — "auf" would have to be the name, and then there is
> no preposition left to satisfy the template.
>
> The ordering makes the same point twice. A template with a text slot is strictly less specific
> than one without ([`Specificity.ORDER`](../core/src/main/kotlin/io/dobby/core/nlu/template/CompiledTemplate.kt)),
> so every unnamed phrasing is tried first regardless of where it is written in the list. A named
> template is only ever reached by an utterance no unnamed one fits.

### Names

A captured `{name}` is **cleaned** before it becomes a timer's name, and the same cleaning runs on
the `name` of every other command in this spec:

1. Filler words are dropped (`Fillers.DE`).
2. Leading scaffolding is dropped: `timer`, `wecker`, `für`, `auf`, `über`, `von`, `vom`, `zum`, `zur`, `bis`.
3. What is left is title-cased for speech. **Nothing left means no name was given.**

Rule 3 is load-bearing and not a nicety. "Stopp den Timer bitte" really does arrive with the name
"bitte", because the slot has no way to know that a particle is not a noun — and enumerating the
particles a name must not be is exactly the cross-product the filler list exists to avoid
(README §6). *A name made of nothing but filler is not a name*: the command falls back to meaning
"the timer", which is what the person said.

Rule 2 is what makes "Stell einen Timer **für die Nudeln** auf 10 Minuten" name its timer
"Nudeln" — the template's anchor is the *second* preposition, so the slot captures "für die
nudeln".

### How a timer is identified afterwards

| Case | Spoken as | Panel shows |
|---|---|---|
| The only timer, no name | "Timer" / "Der Timer" | the countdown alone |
| A default timer, once a second one exists | "Timer 1", "Timer 2", … | `Timer 1` |
| A named timer | "Timer Nudeln" | `Nudeln` |

The rename is the point: "Timer" only reads as a name while there is nothing to tell it apart
from. A default timer keeps the **lowest free number** at the moment it was created, so cancelling
"Timer 1" leaves "Timer 2" called "Timer 2" — a number that silently slid down would be worse than
a gap. The next default timer then takes 1 again.

A timer answers to its name, or to its number, and the match is fuzzy at the usual keyword
tolerance so "Nudel" still reaches "Nudeln". Two timers within tolerance of the same word is an
**ambiguity, not a guess** — nothing is cancelled.

### Utterances → invocation

| Utterance | Invocation |
|---|---|
| timer zehn minuten *(normalized: `timer 10 minuten`)* | `set_timer(amount=10, unit=minuten)` |
| stell einen timer auf 5 minuten | `set_timer(amount=5, unit=minuten)` |
| stelle mir einen timer für 90 sekunden | `set_timer(amount=90, unit=sekunden)` — filler pass, "mir" skipped |
| 3 minuten timer | `set_timer(amount=3, unit=minuten)` |
| timer eine minute | `set_timer(amount=1, unit=minuten)` |
| setz einen wecker auf 2 stunden | `set_timer(amount=2, unit=stunden)` |
| erinner mich in 20 minuten | `set_timer(amount=20, unit=minuten)` |
| neuer timer nudeln auf 10 minuten | `set_timer(amount=10, unit=minuten, name=nudeln)` |
| erstelle timer wäsche auf 45 minuten | `set_timer(amount=45, unit=minuten, name=wäsche)` |
| stell einen timer tee auf 3 minuten | `set_timer(amount=3, unit=minuten, name=tee)` |
| timer nudeln auf 10 minuten | `set_timer(amount=10, unit=minuten, name=nudeln)` |
| stell einen timer für die nudeln auf 10 minuten | `set_timer(amount=10, unit=minuten, name="für die nudeln")` → cleaned to **Nudeln** |
| stell einen timer auf zehn *(normalized: `stell einen timer auf 10`)* | `set_timer(amount=10)` → asks |
| timer 5 | `set_timer(amount=5)` → asks |
| timer auf 90 | `set_timer(amount=90)` → asks |
| setz einen wecker auf 2 | `set_timer(amount=2)` → asks |
| neuer timer nudeln auf 10 | `set_timer(amount=10, name=nudeln)` → asks |

### Follow-up: the missing unit

An invocation with an `amount` and no `unit` cannot be executed and must not be refused either — ten *what* is the only thing missing, and asking is one short sentence. The Sock answers with `Asked` ([README §5](README.md#5-follow-up-questions)):

| | |
|---|---|
| **Question (German TTS)** | "{amount} was — Sekunden, Minuten oder Stunden?" (e.g. "10 was — Sekunden, Minuten oder Stunden?") |
| **Follow-up command** | `clock.set_timer` |
| **Follow-up templates** | `{unit:enum}` · `(in\|auf\|für) {unit:enum}` |
| **Token holds** | the spoken `amount`, and the cleaned `name` if one was said |

The answer arrives as a second `handle()` call with `answering = token`. The Sock reads the stashed amount and sets the timer exactly as if both had been said at once — including the singular rule, so "eins" then "Minute" says "Timer läuft: 1 Minute."

| Utterance | Then | Result |
|---|---|---|
| stell einen timer auf zehn | minuten | `Spoken("Timer läuft: 10 Minuten.")`, timer at 600 s |
| timer 5 | in minuten | `Spoken("Timer läuft: 5 Minuten.")`, timer at 300 s |
| stell einen timer auf eins | minute | `Spoken("Timer läuft: 1 Minute.")`, timer at 60 s |
| neuer timer wäsche auf 90 | minuten | `Spoken("Timer Wäsche läuft: 90 Minuten.")`, timer at 5400 s |
| timer 20 | stunden | `Failed("Diese Dauer kann ich nicht stellen.")` — past the 12 h cap |
| stell einen timer auf zehn | stopp | goes to `shared.stop`; the amount is dropped and **no timer is set** |
| stell einen timer auf zehn | *(silence)* | the turn ends, the amount is dropped, no timer is set |

The turn ending, or any other command being spoken instead, reaches the Sock as `onAskCancelled(token)`; it drops the stashed amount there. An answer whose token the Sock no longer holds is `Failed("Diese Dauer kann ich nicht stellen.")` — a kitchen timer must not guess at a duration it cannot reconstruct.

### Behavior

1. **Up to 8 timers at once.** A *named* `set_timer` whose name is already running **replaces**
   that timer, and says so — saying "Timer Nudeln auf 10 Minuten" twice means one pot of noodles,
   not two. An unnamed one never replaces anything; that is the whole of what multi-timer changed
   about v1. Past 8 the command is refused (§Result): the cap is a guard against a misheard
   sentence filling the panel with timers nobody can name their way back out of, not a limit of
   the engine.
2. Start a coroutine countdown in `ctx.coroutineScope` (drives the UI, 1 s tick), one per timer.
3. Register **one** `AlarmManager.setExactAndAllowWhileIdle` as a **backstop**, for the *earliest*
   deadline still counting, re-armed whenever that changes. `AlarmManager` replaces whatever was
   pending, so there is one alarm however many timers run; when it fires, every timer that has come
   due rings, not one — a process that was asleep may well have slept past two of them.
4. On expiry:
   - `ctx.screen.wakeFor(30.seconds)`
   - `ctx.playback.requestTransientFocus()` — ducks music, does not stop it
   - `ctx.announce("Der Timer ist abgelaufen.")`, or the named form (§Result)
   - repeating chime, every 3 s, for up to **60 s** or until `cancel_timer`
   - release transient focus when **the last** ringing timer is done — a second timer still
     ringing keeps the music ducked
5. On device reboot the timers are lost. Accepted — see §12.

### Result

| Case | Result | German TTS |
|---|---|---|
| New timer set | `Spoken` | "{Timer} läuft: {amount} {unit}." — `{Timer}` is "Timer", "Timer 2" or "Timer Nudeln" (§3, *How a timer is identified*); `{unit}` is spoken in the singular when `amount` is 1: "Timer läuft: 1 Minute." |
| Replaced a timer of the same name | `Spoken` | "Alter Timer ersetzt. Timer Nudeln läuft: 10 Minuten." |
| `amount` out of range (0 or > 12 h after conversion) | `Failed` | "Diese Dauer kann ich nicht stellen." |
| 8 timers already running | `Failed` | "Ich kann nicht mehr als 8 Timer gleichzeitig stellen." |
| Exact-alarm permission missing | `Spoken` + status `Degraded` | "Timer läuft: {amount} {unit}. Achtung, er ist nicht garantiert genau." |

Expiry announcement is **not** a `SockResult` — it is asynchronous, via `ctx.announce`:

| Timer | German TTS |
|---|---|
| The only one, no name | "Der Timer ist abgelaufen." |
| A default timer among others | "Timer 2 ist abgelaufen." |
| A named timer | "Timer Nudeln ist abgelaufen." |

---

## 4. `clock.cancel_timer`

### Params

| Name | Type | Required | Default | Constraints |
|---|---|---|---|---|
| `name` | text | **no** | — | free text, cleaned exactly as in §3, *Names* |

### Tier 1 templates

```
timer (stopp|stop|aus|stoppen|abbrechen|löschen|beenden|abschalten)
(stopp|stoppe|brich|breche|lösch|lösche|beende) (den )?timer( ab)?
(stopp|stoppe|aus mit) (dem |den |das )?(alarm|wecker|klingeln)
```

Named:

```
timer {name} (stoppen|abbrechen|löschen|beenden|abschalten)
(stopp|stoppe|brich|breche|lösch|lösche|beende) (den )?timer {name}( ab)?
```

> Bare `stopp` is **not** claimed — it routes to `shared.stop`, which this Sock wins while the chime is ringing (§7). The word `timer`, `alarm`, `wecker` or `klingeln` is required here, because this command must also work while a timer is merely counting down and music is playing.
>
> **The trailing named form drops `stopp`, `stop` and `aus`** from the verb list its unnamed
> sibling carries, and that is not cosmetic. Those three are one word each, and a one-word verb
> behind a `{name}` slot means "timer doch bitte stopp" matches with the name "doch bitte" — on
> the **strict** pass, before filler skipping ever gets its say. The long verbs cannot be reached
> that way by anything anybody says.
>
> The *leading* named form has no such escape: "stopp den timer bitte" really does arrive with the
> name "bitte". That is what the cleaning in §3 is for, and it is why the cleaning is a rule of
> this Sock rather than a detail of one command.
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
| brich timer nudeln ab | `cancel_timer(name=nudeln)` |
| timer nudeln abbrechen | `cancel_timer(name=nudeln)` |
| stopp den timer nudeln | `cancel_timer(name=nudeln)` |
| brich timer 2 ab | `cancel_timer(name=2)` |

| Utterance | Goes to |
|---|---|
| stopp | `shared.stop` — consumed here only if the chime is ringing |
| ich hab's gehört | `shared.stop` — same |

### Behavior

With a **name**: the one timer that answers to it. With **no name**, in this order:

1. **A chime is sounding** → silence every ringing timer, release transient focus if none are left
   ringing. Result `Silent`. The chime is the thing demanding attention, and silencing it is
   unambiguously what the sentence meant.
2. **Exactly one timer, counting** → cancel the coroutine and re-arm the backstop for whatever is
   left. Result `Spoken("Timer abgebrochen.")`.
3. **Several timers, none ringing** → **nothing is cancelled**, and the Sock asks (§4, *Follow-up*).
4. **Nothing running** → `Spoken("Es läuft gerade kein Timer.")`.

### Follow-up: which timer

Cancelling a timer is not undoable by saying it again, so a Sock that cannot tell which one was
meant asks instead of guessing:

| | |
|---|---|
| **Question (German TTS)** | "Es laufen Timer Nudeln und Timer 2. Welchen soll ich abbrechen?" |
| **Follow-up command** | `clock.cancel_timer` |
| **Follow-up templates** | `{name:enum}` · `(den )?timer {name:enum}` |
| **Candidate set** | the names and numbers currently running, one token each |
| **Token holds** | nothing |

> **The slot is an `enum`, not the `{name}` text slot the command itself carries**, and that is the
> only shape that is safe here. A question's own palette gets first refusal on the next utterance,
> ahead of every command Dobby knows ([`DobbyEngine`](../core/src/main/kotlin/io/dobby/core/DobbyEngine.kt)) —
> so a bare text slot would match literally anything and the question would be a trap: "wie spät ist
> es", said while it is open, would become a timer called "Wie Spät Ist Es". A closed candidate set
> is what lets everything else fall through to the global palette and abandon the question, which is
> exactly why `{unit:enum}` is safe in the same position (§3).
>
> The price: a multi-word name cannot be answered on its own, because an enum slot takes one token.
> Those timers are still listed in the question and still reachable by the full sentence ("brich
> Timer grüner Tee ab"), which goes to the global palette. If **no** running timer has a
> single-token name the Sock does not hold the floor at all — it says the same sentence as a
> statement, because a question no utterance could satisfy is worse than no question.

The token holds nothing on purpose: the answer carries the whole of its own meaning, and a name is
resolved against the timers running **when it arrives**, not the ones that were running when Dobby
asked.

### Result

| Case | Result | German TTS |
|---|---|---|
| Chime silenced | `Silent` | — |
| Timer cancelled | `Spoken` | "Timer abgebrochen." / "Timer 2 abgebrochen." / "Timer Nudeln abgebrochen." |
| Name matches nothing | `Spoken` | "Es läuft kein Timer namens Nudeln." |
| Several running, no name | `Asked` | see *Follow-up* |
| Nothing running | `Spoken` | "Es läuft gerade kein Timer." |

---

## 5. `clock.cancel_all_timers`

The one command in the cancel family that needs no name and has no tie to break. Separate from
`cancel_timer` rather than a flag on it: "alle" is the difference between one destructive action
and eight, and a route label Tier 2 can pick is worth more than a parameter it has to infer.

### Tier 1 templates

```
(stopp|stoppe|brich|breche|lösch|lösche|beende) (alle|sämtliche) (timer|wecker)( ab)?
(alle|sämtliche) (timer|wecker) (stoppen|abbrechen|löschen|beenden|abschalten|stopp|stop|aus|weg)
```

`alle` is not a filler and never can be, which is what keeps these apart from §4: "brich alle timer
ab" cannot reach `(brich) (den )?timer {name}( ab)?`, because "alle" is neither "den" nor skippable.

### Utterances → invocation

| Utterance | Invocation |
|---|---|
| brich alle timer ab | `cancel_all_timers()` |
| alle timer abbrechen | `cancel_all_timers()` |
| stopp alle timer | `cancel_all_timers()` |
| alle timer löschen | `cancel_all_timers()` |
| lösch alle wecker | `cancel_all_timers()` |

### Behavior

Cancel every timer, silence anything ringing, release transient focus, cancel the backstop and
clear the persisted deadlines. Nothing is asked: "alle" is not ambiguous.

### Result

| Case | Result | German TTS |
|---|---|---|
| Several cancelled | `Spoken` | "Alle 3 Timer abgebrochen." |
| Exactly one was running | `Spoken` | "Timer Nudeln abgebrochen." — naming it is more use than counting to one |
| Nothing running | `Spoken` | "Es läuft gerade kein Timer." |

---

## 6. `clock.timer_remaining`

### Params

| Name | Type | Required | Default | Constraints |
|---|---|---|---|---|
| `name` | text | **no** | — | free text, cleaned exactly as in §3, *Names* |

### Tier 1 templates

```
wie lange (geht|läuft|dauert) (der |die )?timer noch
wie lange (gehen|laufen) (die )?timer noch
wie lange (geht|läuft|dauert) (der )?timer {name} noch
wie viel zeit (bleibt|ist) noch
wie lange noch
```

> **`noch` is the right-hand anchor for the named form**, and it is safe to lean on: repetition and
> sequence words are the one group that may never be a filler (README §6), so "noch" is in the
> transcript whenever it was said. Without it the slot would run to the end of the utterance.
>
> `zeit` appears only with keywords around it, for the reason in §8 — a bare `zeit` is four
> characters at tolerance 1 and comes with `seit`, `weit` and `zeig`.
>
> "wie lange geht der timer **denn** noch" matches the *named* template with the name "denn", on
> the strict pass. It is right anyway, because the cleaning in §3 drops it and the command falls
> back to meaning "the timer" — which is the case the cleaning rule exists for.

### Utterances → invocation

| Utterance | Invocation |
|---|---|
| wie lange geht der timer noch | `timer_remaining()` |
| wie lange läuft der timer noch | `timer_remaining()` |
| wie lange gehen die timer noch | `timer_remaining()` |
| wie lange noch | `timer_remaining()` |
| wie viel zeit bleibt noch | `timer_remaining()` |
| wie lange geht timer nudeln noch | `timer_remaining(name=nudeln)` |
| wie lange läuft timer 2 noch | `timer_remaining(name=2)` |

### Behavior

A pure state read — no context needed, no side effect beyond waking the screen for 30 s, because the
countdown ring is the visual half of this answer the way the clock face is of the time (§9). Timers
are read out **soonest first**, the order the panel draws them in.

The duration is spoken in at most two parts, and the smaller one only when it is not zero: "noch
1 Stunde und 30 Minuten", "noch 9 Minuten", "noch 45 Sekunden". "Noch 1 Stunde, 5 Minuten und
3 Sekunden" is a stopwatch reading; a person asking how long the noodles have wants the number they
can act on and one below it.

### Result

| Case | Result | German TTS |
|---|---|---|
| One timer, no name given | `Spoken` | "Der Timer läuft noch 9 Minuten." |
| A named timer | `Spoken` | "Timer Nudeln läuft noch 9 Minuten." |
| A numbered timer | `Spoken` | "Timer 2 läuft noch 20 Minuten." |
| That timer is ringing | `Spoken` | "Timer Nudeln ist gerade abgelaufen." |
| Several, no name given | `Spoken` | "Timer Nudeln: noch 9 Minuten. Timer 2: noch 20 Minuten." |
| Name matches nothing | `Spoken` | "Es läuft kein Timer namens Nudeln." |
| Nothing running | `Spoken` | "Es läuft gerade kein Timer." |

---

## 7. Activity


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

## 8. `clock.whats_the_time`

### Tier 1 templates

```
wie (spät|viel uhr)
(sag|was ist) (die )?(uhrzeit|zeit)
uhrzeit
what time is it
(whats|what's|what is) the time( now)?
```

**Three German templates, where there were seven.** The matcher skips filler words on a second
pass ([`../socks.specs/README.md`](README.md) §6), so `ist`, `es`, `jetzt`, `mir` and `denn` no
longer need a template each: `wie spät` covers "wie spät ist es", "wie spät ist es denn jetzt"
and the "wie spät ist **das**" the recogniser really produces. Enumerating those variants was
what did not scale — every command would have needed the same cross-product.

Tier 1 is still the cheap tier: more templates only enlarge a lookup table, while the expensive
thing is the Tier 2 system prompt, which grows with `description` and `examples` and not with
templates ([`dobby-plan.md`](../dobby-plan.md) §9). The constraint on breadth is never the
count; it is the two rules below.

### Utterances → invocation

| Utterance | Invocation |
|---|---|
| wie spät ist es | `whats_the_time()` — filler pass, "ist es" skipped |
| wie spät ist das | `whats_the_time()` — the recorded mishearing, filler pass |
| wie spät | `whats_the_time()` |
| wie viel uhr ist es | `whats_the_time()` — filler pass |
| uhrzeit | `whats_the_time()` |
| sag mir die uhrzeit | `whats_the_time()` — filler pass, "mir" skipped |
| was ist die zeit | `whats_the_time()` |
| sag mir die zeit | `whats_the_time()` — filler pass, "mir" skipped |
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

## 8a. `clock.whats_the_date`

The other half of "what is it right now", and the sibling of §8 in every respect: no params, no
state, one read of the injected `Clock`. Numbered `8a` rather than `9` because everything from
§9 down is referenced by number from `ClockSock`, the panel and the other specs, and renumbering
six sections to insert one is how cross-references rot.

### Tier 1 templates

```
welcher (tag|wochentag) (haben wir)? (heute)?
(wievielte|wie vielte) heute
(wievielte|wie vielte) haben wir (heute)?
welches datum (haben wir)? (heute)?
(sag|was ist) (das)? datum
was (haben wir)? heute für (ein)? (tag|datum)
was für (ein)? (tag|datum) heute
what day is it (today|now)?
(whats|what's|what is) the date (today|now)?
(whats|what's|what is) (todays|today's) date
```

**Content words only**, exactly as in §8: "welcher Tag **ist** heute" is the first template plus
one filler, and the matcher skips filler on the second pass ([README §6](README.md#6-template-dsl-tier-1)).
Two consequences worth spelling out, because both look like omissions:

- **One `welcher` covers the whole declension.** At seven characters it tolerates one edit, so
  `welchen`, `welches` and `welche` are the same keyword. Writing them out would be the
  cross-product the filler pass exists to avoid.
- **`(haben wir)?` is not filler tolerance.** Neither word is on the filler list — "welchen Tag
  **haben wir** heute" is the phrasing half of Austria uses, and it has to be in the template or
  it does not match at all.

`wievielte` is spelled both ways because the recogniser writes it both ways. At nine characters
it tolerates two edits, which takes `wievielten` and `wievielter` with it.

**`heute` is required wherever `was für ein …` is**, and that is the guard rather than a detail:
without it, "was für ein Tag" is one fuzzy step from Spotify's "was für ein Lied ist das" (§10),
and a panel that answers the date when somebody asked about the song is worse than one that
answers neither.

A **bare `datum`** is deliberately absent, for the reason §8 gives for a bare `zeit`: five
characters, tolerance 1, and nothing is skipped in front of a one-word template. It is written
`(sag|was ist) (das)? datum`, the shape `(sag|was ist) (die)? (uhrzeit|zeit)` already has.

English is admitted by the same rule as §8 — no `{x:int}` and no `{x:enum}` slot, so the
German-only normalizer cannot silently drop a value. `now` is spelled out in the English
templates: it is on `Fillers.EN`, which nothing reads, and the matcher runs with `Fillers.DE`.

### Utterances → invocation

| Utterance | Invocation |
|---|---|
| welcher tag ist heute | `whats_the_date()` — filler pass, "ist" skipped |
| welchen tag haben wir heute | `whats_the_date()` — strict |
| welcher wochentag ist heute | `whats_the_date()` — filler pass |
| der wievielte ist heute | `whats_the_date()` — filler pass, "der ist" skipped |
| der wie vielte ist heute | `whats_the_date()` — the two-token spelling |
| den wievielten haben wir heute | `whats_the_date()` — strict, fuzzy on `wievielte` |
| welches datum haben wir heute | `whats_the_date()` |
| welches datum ist heute | `whats_the_date()` — filler pass |
| sag mir das datum | `whats_the_date()` — filler pass, "mir" skipped |
| was ist heute für ein tag | `whats_the_date()` — filler pass |
| was für ein tag ist heute | `whats_the_date()` — the other word order, filler pass |
| what day is it today | `whats_the_date()` |
| what's the date | `whats_the_date()` |
| what is todays date | `whats_the_date()` |

### Behavior

Read the system clock in the device's zone, inside the phrase rather than before it — a question
asked at 23:59:59 is answered with the day it is *spoken* on. Wake the screen for 30 s: the
dashboard's date line (§9) is the visual half of this answer, exactly as the clock face is for
§8.

### Result

`Spoken`, weekday and date and nothing else:

| Date | German | English |
|---|---|---|
| 2026-07-14 | "Heute ist Samstag, der 14. Juli." | "Today is Saturday, July 14th." |
| 2026-09-24 | "Heute ist Donnerstag, der 24. September." | "Today is Thursday, September 24th." |
| 2026-03-01 | "Heute ist Sonntag, der 1. März." | "Today is Sunday, March 1st." |

**No year**, in either language: somebody asking across a kitchen wants the weekday and the
date, and "2026" on the end of every answer is the part nobody asked about. The day the panel is
asked *which year* it is, that is a different sentence and it can have its own.

The English day is an **ordinal** — "July 14th", not "July 14" — because a TTS engine handed the
bare number may read it "July fourteen", which is not a date anybody says. German needs no such
care: "der 14." is already read as "der vierzehnte".

---

## 8b. `clock.weekday_of_date`

"Welcher Tag ist der 26.?" — the first of four commands that treat the calendar as something to
*ask about* rather than merely to read off. Numbered `8b`…`8e` for the reason §8a gives: §9 and
everything below it are referenced by number from `ClockSock`, the panel and the other specs.

### Params

| Name | Type | Required | Default | Constraints |
|---|---|---|---|---|
| `day` | int | yes | — | 1 … 31 |
| `month` | enum | **no** | — | `januar` \| `jänner` \| `februar` \| `feber` \| `märz` \| … \| `dezember` |

### Which year, and why there is no year slot

**Everything in §8b–§8e resolves forwards, today included.** "Der 14." is the next 14th there
is; "der 14. Juli" is the next 14 July there is; "Samstag" is the next Saturday, and if that is
today then the answer is *today*.

No year is ever spoken (§8a) and none is ever asked for, so the only thing a partial date leaves
open is the direction — and the asymmetry decides it the way it decides everything else in this
file. Somebody standing in a kitchen asking which day the 14th is has the coming one in mind. A
panel that answers "today" on the day itself has told them something true and useful; one that
answers a date eleven months out has answered a question nobody asked.

Two consequences worth spelling out:

- **A month without a 31st is skipped, not clamped.** "Der 31." asked on 1 April is 31 May.
  Answering "30. April" would be answering a different question.
- **29 February is searched for, not computed.** The next one can be eight years out
  (2096 → 2104), so `CalendarDates` scans years rather than assuming four. A formula that
  assumed four would be wrong about once a century, which is the kind of bug nobody finds.

### Tier 1 templates

```
welcher (tag|wochentag) (ist|haben wir)? (der|den|dem|am|vom|zum) {day:int} {month:enum}
(auf)? welcher (tag|wochentag) fällt (der|den|dem|am|vom|zum) {day:int} {month:enum}
was für (ein)? (tag|wochentag) (ist)? (der|den|dem|am|vom|zum) {day:int} {month:enum}
was ist (der|den|dem|am|vom|zum) {day:int} {month:enum} für (ein)? (tag|wochentag)
```

…and the same four again without the `{month:enum}` slot.

**The article is spelled out and that is load-bearing**, not scaffolding left in by accident.
`der`, `den` and `dem` are on `Fillers.DE`, so the filler pass would happily drop them — but
filler is skipped in front of a *keyword* and never in front of a slot ([README §6](README.md#6-template-dsl-tier-1)),
because a slot takes whatever token is sitting there. Without `der` written out, "welcher Tag ist
der 14." would have to reach `{day:int}` with "der" still in the way.

**One `welcher` covers the declension**, exactly as in §8a: at seven characters it tolerates one
edit, so `welchen`, `welches` and `welche` are the same keyword. That is what makes "auf
**welchen** Tag fällt der 24. Dezember" — the phrasing a calendar question is really asked in —
one template rather than four.

**Ordinals spoken as words are read by the int slot.** "Welcher Tag ist der **vierzehnte**"
arrives with `vierzehnte` intact, because `Normalizer` only rewrites cardinals. `GermanNumbers.parseOrdinal`
is reached from `parseSlotValue` and from nowhere else, which is the whole safety argument: "der
Vierzehnte" is a number wherever an `{n:int}` slot is waiting for one, and is a word everywhere
else. The declension is trimmed rather than enumerated — `vierzehnte`, `vierzehnten`,
`vierzehnter` and `vierzehntes` are one stem and four endings.

**`jänner` and `feber` are enum values.** The panel is in an Austrian kitchen and "Jänner" is
what is said there; at three edits from `januar` the fuzzy tier would never find it. Same for
`maerz` beside `märz`, for the transcript that arrives without the umlaut.

### No English, here or in §8c–§8e

§8 and §8a carry English templates. These four do not, and the rule is the one in
[README §6](README.md#6-template-dsl-tier-1): **English is allowed only on commands with no
`{x:int}` and no `{x:enum}` slot.** The normalizer is German-only, so "fourteenth" would never
become 14, and the `month`, `weekday` and `unit` candidate sets hold German words, so
"saturday" reaches nothing. An English template here would match and then silently fail to
coerce, which is worse than not matching. Recorded so the gap is not read as an oversight.

### Utterances → invocation

| Utterance | Invocation |
|---|---|
| welcher tag ist der 26. | `weekday_of_date(day=26)` |
| welcher wochentag ist der 24. dezember | `weekday_of_date(day=24, month=dezember)` |
| auf welchen tag fällt der 1. mai | `weekday_of_date(day=1, month=mai)` |
| was für ein tag ist der 31. oktober | `weekday_of_date(day=31, month=oktober)` |
| welchen tag haben wir am 14. | `weekday_of_date(day=14)` |
| was ist der 24. dezember für ein tag | `weekday_of_date(day=24, month=dezember)` |
| welcher tag ist der vierzehnte | `weekday_of_date(day=14)` — the ordinal, via the int slot |
| auf welchen wochentag fällt der dritte oktober | `weekday_of_date(day=3, month=oktober)` |

### Behavior

Read the system clock **once**, before the phrase rather than inside it — the one place this
Sock departs from §8 and §8a. Those two answer with a single instant and can read it at the
moment they speak; this one has to resolve a date first, and an answer assembled from two
different "today"s would be worse than one that is a tenth of a second stale. The sentence is
still built late ([README §2a](README.md#2a-what-a-sock-says-and-in-which-language)). Wake the
screen for 30 s.

### Result

`Spoken`. Today and tomorrow are said as today and tomorrow, with the date in apposition:
somebody asking which day the 26th is on the 25th wants to hear "morgen" first and the weekday
second, because that is the part they can act on.

| Case | German | English |
|---|---|---|
| any other day | "Der 26. September ist ein Samstag." | "September 26th is a Saturday." |
| it is today | "Heute, der 24. September, ist ein Donnerstag." | "Today, September 24th, is a Thursday." |
| it is tomorrow | "Morgen, der 25. September, ist ein Freitag." | "Tomorrow, September 25th, is a Friday." |

A day no month has — "der 32.", "der 30. Februar" — is `Failed("Dieses Datum kenne ich nicht.")`.
One sentence for every way a calendar question can fail to name a real day, and deliberately not
four: from the room's point of view they are the same thing, and a person who said "der 30.
Februar" does not need to be told which half of it was wrong.

---

## 8c. `clock.date_of_weekday`

The other direction: "wann ist der nächste Samstag?" → the date.

### Params

| Name | Type | Required | Default | Constraints |
|---|---|---|---|---|
| `weekday` | enum | yes | — | `montag` … `sonntag`, plus `sonnabend` |

`sonnabend` is a second value rather than a fuzzy neighbour of `samstag`: seven characters
against nine with four of them different, so the tolerance-1 pass would never find it. One line
is the only way in.

### Tier 1 templates

```
wann (ist|haben wir)? (der|den|dem|am|vom|zum)? (nächste|kommende|diese)? {weekday:enum}
welches datum (ist|hat|haben wir)? (der|…|zum)? (nächste|kommende|diese)? {weekday:enum}
welcher (tag|wochentag) (ist|haben wir)? (der|…|zum)? (nächste|kommende|diese)? {weekday:enum}
(der|…|zum)? (wievielte|wie vielte) (ist|haben wir)? (der|…|zum)? (nächste|kommende|diese)? {weekday:enum}
(auf)? welches datum fällt (der|…|zum)? (nächste|kommende|diese)? {weekday:enum}
```

**`nächste`, `kommende` and `diese` are not a param.** All three mean the next one there is,
which is the only thing this command resolves, so there is nothing for a direction slot to say.
`nächste` at seven characters covers `nächsten` and `nächster` at tolerance 1; `kommende` at
eight covers its own declension at tolerance 2.

**"Welcher Tag ist der nächste Samstag" belongs here and not in §8b**, although it opens with
§8b's words. The slot is what tells them apart — a weekday is not an integer — and the enum
candidate set is closed, so there is no utterance that could satisfy both.

### Utterances → invocation

| Utterance | Invocation |
|---|---|
| wann ist der nächste samstag | `date_of_weekday(weekday=samstag)` |
| wann ist samstag | `date_of_weekday(weekday=samstag)` |
| wann ist kommenden mittwoch | `date_of_weekday(weekday=mittwoch)` |
| welches datum ist am freitag | `date_of_weekday(weekday=freitag)` |
| welches datum haben wir am donnerstag | `date_of_weekday(weekday=donnerstag)` |
| welcher tag ist der nächste samstag | `date_of_weekday(weekday=samstag)` |
| der wievielte ist am sonntag | `date_of_weekday(weekday=sonntag)` |
| auf welches datum fällt der nächste dienstag | `date_of_weekday(weekday=dienstag)` |
| wann ist sonnabend | `date_of_weekday(weekday=sonnabend)` → spoken as Samstag |

### Result

`Spoken`, the mirror of §8b down to the today/tomorrow forms — the weekday is what was asked, so
it leads the sentence whatever the date turns out to be.

| Case | German | English |
|---|---|---|
| any other day | "Der nächste Samstag ist der 26. September." | "The next Saturday is September 26th." |
| it is today | "Samstag ist heute, der 24. September." | "Saturday is today, September 24th." |
| it is tomorrow | "Samstag ist morgen, der 25. September." | "Saturday is tomorrow, September 25th." |

**"Der nächste Samstag" asked on a Saturday is answered "heute", and that is a decision.** German
famously cannot agree whether "nächsten Samstag" means today's week or the one after, so this
command does not try to: it resolves forwards with today included, which is never the misleading
answer. Somebody told "heute" has learnt that today is Saturday; somebody told "in 7 Tagen" when
they meant today has been sent to the wrong week.

---

## 8d. `clock.date_in`

"Der Wievielte ist heute in 3 Wochen?" — the calendar counted forwards.

### Params

| Name | Type | Required | Default | Constraints |
|---|---|---|---|---|
| `amount` | int | yes | — | 1 … 500 |
| `unit` | enum | yes | — | `tage` \| `wochen` \| `monate` \| `jahre` |

`SpanUnit` is deliberately **not** `TimerUnit`: a timer runs in seconds, minutes and hours and
never in months, and a calendar question is never about seconds. One shared list would mean
"erinner mich in 3 Monaten" matching `set_timer` and then failing to coerce, which is the silent
kind of wrong. As it is, the enum slot rejects it and the palette moves on.

The cap is a guard against a misheard sentence rather than a limit of the arithmetic, exactly as
`set_timer`'s twelve hours is: "in 3000 Jahren" is a transcript, not a question.

### Tier 1 templates

Static-param forms first — "morgen" and "übermorgen" are this command with the amount spoken as
a word rather than a number, which is what [README §6](README.md#6-template-dsl-tier-1)'s static
params are for:

```
welcher (tag|wochentag) (ist|haben wir)? morgen                → amount=1, unit=tage
welcher (tag|wochentag) (ist|haben wir)? übermorgen            → amount=2, unit=tage
(der|…|zum)? (wievielte|wie vielte) (ist|haben wir)? morgen     → amount=1, unit=tage
(der|…|zum)? (wievielte|wie vielte) (ist|haben wir)? übermorgen → amount=2, unit=tage
welches datum (ist|hat|haben wir)? morgen                       → amount=1, unit=tage
welches datum (ist|hat|haben wir)? übermorgen                   → amount=2, unit=tage
was für (ein)? (tag|datum) (ist)? morgen                        → amount=1, unit=tage
was (ist|haben wir)? morgen für (ein)? (tag|datum)              → amount=1, unit=tage
```

and the counted forms:

```
(der|…|zum)? (wievielte|wie vielte) (ist|haben wir)? (heute)? in {amount:int} {unit:enum}
welches datum (ist|hat|haben wir)? (heute)? in {amount:int} {unit:enum}
welcher (tag|wochentag) (ist|haben wir)? (heute)? in {amount:int} {unit:enum}
was (ist|haben wir)? (heute)? in {amount:int} {unit:enum} für (ein)? (tag|datum)
was für (ein)? (tag|datum) (ist)? (heute)? in {amount:int} {unit:enum}
```

**`in` is spelled out and the number sits right behind it.** Same rule as §8b's article: nothing
is skipped in front of a slot, so the preposition has to be a keyword or the slot would have to
swallow it.

**"in einer Woche" works without a number word.** `parseSlotValue` accepts the article forms of
"one" ("ein", "eine", "einer", "einem"), which is the same lenience that makes "timer eine
minute" resolve to 1 (§3). The unit follows in the singular and the enum matcher folds it —
"woche" reaches `wochen`, "monaten" reaches `monate`, both at tolerance 1.

### Utterances → invocation

| Utterance | Invocation |
|---|---|
| der wievielte ist heute in 3 wochen | `date_in(amount=3, unit=wochen)` |
| der wievielte ist in einer woche | `date_in(amount=1, unit=wochen)` |
| welches datum haben wir in 2 monaten | `date_in(amount=2, unit=monate)` |
| welcher tag ist in 10 tagen | `date_in(amount=10, unit=tage)` |
| was ist in 10 tagen für ein datum | `date_in(amount=10, unit=tage)` |
| welches datum ist in einem jahr | `date_in(amount=1, unit=jahre)` |
| welcher tag ist morgen | `date_in(amount=1, unit=tage)` |
| welcher tag ist übermorgen | `date_in(amount=2, unit=tage)` |
| welches datum haben wir morgen | `date_in(amount=1, unit=tage)` |
| der wievielte ist morgen | `date_in(amount=1, unit=tage)` |

### Result

`Spoken`, weekday and date — the answer to "which day" and "the how-manieth" is the same
sentence, so there is one.

| Case | German | English |
|---|---|---|
| counted | "In 3 Wochen ist Donnerstag, der 15. Oktober." | "In 3 weeks it's Thursday, October 15th." |
| one day out | "Morgen ist Freitag, der 25. September." | "Tomorrow is Friday, September 25th." |
| two days out | "Übermorgen ist Samstag, der 26. September." | "The day after tomorrow is Saturday, September 26th." |

One and two days out are spoken as "morgen" and "übermorgen" whichever way they were asked for,
because those are the words — and "welcher Tag ist morgen" reaches this command through a
template that fixes the amount, so the answer has to sound like the question.

**"In 3 Tagen", not "in 3 Tage".** German "in" takes the dative and the dative plural takes an
`-n`, which `Wochen` already has and `Tage`, `Monate` and `Jahre` do not. `SpanUnit.nounDative`
is the whole of it; `TimerUnit` never needed one because a timer is announced as "Timer läuft:
10 Minuten", with no preposition in front of the number.

---

## 8e. `clock.time_until`

"Wie viele Tage sind es noch bis zum 24. Dezember?" — the same arithmetic read backwards.

### Params

| Name | Type | Required | Default | Constraints |
|---|---|---|---|---|
| `unit` | enum | **no** | `tage` | `tage` \| `wochen` \| `monate` \| `jahre` |
| `day` | int | **no** | — | 1 … 31 |
| `month` | enum | **no** | — | as §8b |
| `weekday` | enum | **no** | — | as §8c |

**One command for both kinds of target.** "Bis Samstag" and "bis zum 24. Dezember" are the same
question with a different way of naming the day, and splitting them would mean two commands whose
answers are word for word identical. Which slot was filled decides the German case around the
answer, and that is the only thing the two branches disagree about. Every template fills one or
the other, so the all-optional param list is never empty in practice.

`unit` is defaulted rather than required because "wie lange noch bis Samstag" names no unit and
means days — which is the only unit a two-word answer could be in.

### Tier 1 templates

```
wie (viele|viel) {unit:enum} (sind|ist)? (es)? (noch)? bis (zum|zur|auf)? (der|…|zum)? {day:int} {month:enum}
wie (viele|viel) {unit:enum} (sind|ist)? (es)? (noch)? bis (zum|zur|auf)? (der|…|zum)? {day:int}
wie (viele|viel) {unit:enum} (sind|ist)? (es)? (noch)? bis (zum|zur|auf)? (nächste|kommende|diese)? {weekday:enum}
wie lange (ist|dauert|geht)? (es)? (noch)? bis (zum|zur|auf)? (der|…|zum)? {day:int} {month:enum}
wie lange (ist|dauert|geht)? (es)? (noch)? bis (zum|zur|auf)? (der|…|zum)? {day:int}
wie lange (ist|dauert|geht)? (es)? (noch)? bis (zum|zur|auf)? (nächste|kommende|diese)? {weekday:enum}
```

**`bis` is what keeps this off `timer_remaining`.** §6 claims the bare "wie lange noch", and it
is anchored at both ends: an utterance that stops there has nothing left over, and one that goes
on to "bis Samstag" has two tokens the end anchor will not accept as filler. The templates here
are also strictly more specific, so the palette reaches them first anyway — but the anchor is the
argument, and the ordering is the belt to its braces.

`(sind|ist)? (es)?` is spelled out although both words are filler, for the reason
[README §6](README.md#6-template-dsl-tier-1) gives: "wie viele Tage sind es noch bis …" is the
commonest phrasing of all, and the commonest phrasing belongs on the strict first pass rather
than on the rescue.

### Utterances → invocation

| Utterance | Invocation |
|---|---|
| wie viele tage sind es noch bis zum 24. dezember | `time_until(unit=tage, day=24, month=dezember)` |
| wie viele wochen bis zum 1. mai | `time_until(unit=wochen, day=1, month=mai)` |
| wie viele tage sind es bis samstag | `time_until(unit=tage, weekday=samstag)` |
| wie viele tage noch bis zum 31. | `time_until(unit=tage, day=31)` |
| wie viele monate sind es bis zum 1. januar | `time_until(unit=monate, day=1, month=januar)` |
| wie lange ist es noch bis zum 24. dezember | `time_until(unit=tage, day=24, month=dezember)` — the default unit |
| wie lange noch bis freitag | `time_until(unit=tage, weekday=freitag)` |
| wie lange dauert es bis zum 1. mai | `time_until(unit=tage, day=1, month=mai)` |

### Result

`Spoken`. **Two parts at most and the smaller one only when it is not zero** — the rule §6's
`remaining` already follows for a running timer, and for the same reason: somebody counting the
days to Christmas wants the number they can act on and one below it, not a stopwatch reading. A
whole part of zero is dropped entirely, because "0 Monate und 5 Tage" is not an answer anybody
gives.

| Case | German | English |
|---|---|---|
| days | "Noch 91 Tage bis zum 24. Dezember." | "91 days until December 24th." |
| weeks, with a remainder | "Noch 31 Wochen und 2 Tage bis zum 1. Mai." | "31 weeks and 2 days until May 1st." |
| months | "Noch 3 Monate und 8 Tage bis zum 1. Jänner." | "3 months and 8 days until January 1st." |
| a weekday | "Noch 2 Tage bis Samstag." | "2 days until Saturday." |
| the target is today | "Der 24. September ist heute." / "Samstag ist heute." | "September 24th is today." / "Saturday is today." |

The German sentence is built as "Noch … bis …" and not as "Es sind noch …" on purpose: the
verbless form needs no agreement, so "Noch 1 Tag bis Freitag" and "Noch 91 Tage bis zum 24.
Dezember" are one template rather than two.

---

## 9. State & dashboard

Always-present `DashboardCard` (this Sock owns the panel's clock):

- Large clock, HH:MM, seconds omitted (no per-second redraw on an OLED panel).
- Date line, German long format: "Donnerstag, 18. September".
- Timer countdowns when timers are active: MM:SS each, with a progress ring, **soonest first**.
  A label — `Nudeln`, `Timer 2` — is drawn only once there is something to tell apart; the sole
  default timer is a countdown and nothing else, exactly as before multi-timer. Past three, the
  rest are a count ("+2 weitere"): a panel listing eight timers in six-point type is a list nobody
  reads across a kitchen.
- Chime state: visibly flashing while ringing, so a muted device still shows it.
- An **X on each timer row**, running `ClockSock.cancelFromPanel(id)`. Per row is the point:
  "brich Timer 2 ab" is a sentence somebody has to compose while three countdowns are on the
  wall in front of them, and pointing at the one they mean skips both the naming and the
  tie-break `clock.cancel_timer` needs (§4). By **id**, so none of that resolution applies —
  a finger is pointing, not describing. The X sits outside the flashing alpha: a control that
  fades to a fifth of its opacity twice a second is one somebody stabs at, and a ringing timer
  is the row you most want to be able to switch off. Silencing a chime comes free, because
  `TimerEngine.forget` already owns it.

Exposed state: `StateFlow<ClockState>` = `now: LocalDateTime`, `timers: List<TimerState>` sorted by
deadline, each (`id`, `endsAt`, `totalMs`, `remainingMs`, `isRinging`, `name`, `ordinal`,
`numbered`). `ClockState.timer` remains as the one the panel leads with — the ringing one, else the
one going off next — for every caller that means "the timer", which most of the time is all there is.

## 10. Utterance collision surface

Exclusively claimed: `timer …`, `wecker …`, `wie spät …`, `wie viel uhr …`, `uhrzeit`, `was ist die zeit`, `sag (mir) (die) zeit`, `welcher (tag|wochentag) …`, `welches datum …`, `(der) wievielte …`, `sag (mir) (das) datum`, `was (für ein) tag/datum … heute`, `erinner(e) mich in …`, `stopp/beende/brich … <timer|alarm|wecker|klingeln>`, `alle/sämtliche timer …`, `wie lange … timer … noch`, `wie lange noch` and `wie viel zeit bleibt/ist noch`.

Claimed by the calendar commands (§8b–§8e), all of them behind a slot rather than as a bare
phrase: `welcher (tag|wochentag) … <tag>`, `auf welchen tag fällt …`, `was (ist|für ein) tag …
<tag>`, `wann ist (der nächste) <wochentag>`, `welches datum … <wochentag>`, `auf welches datum
fällt …`, `(der) wievielte … <wochentag>`, `… in <n> <tage|wochen|monate|jahre>`, `… morgen`,
`… übermorgen`, `wie viele <einheit> … bis …` and `wie lange … bis …`.

**The three overlaps inside this Sock, and what separates them.** All three are decided by the
slot, never by a word, which is why none of them can drift:

| Utterance shape | Goes to | Because |
|---|---|---|
| `welcher tag ist heute` | §8a `whats_the_date` | nothing after "ist" but `heute` |
| `welcher tag ist der 26.` | §8b `weekday_of_date` | an `{int}` where §8a wants nothing |
| `welcher tag ist der nächste samstag` | §8c `date_of_weekday` | a weekday `{enum}`, which is not an integer |
| `welcher tag ist morgen` | §8d `date_in` | `morgen` is a keyword, and it fixes `amount=1` |

**`wie lange noch` still belongs to §6**, and `wie lange noch bis …` to §8e. The `bis` anchor is
the whole of it: §6's template is anchored at both ends, so an utterance that continues past
"noch" leaves tokens the end anchor will not take as filler.

English, claimed on `whats_the_time` (§8) and `whats_the_date` (§8a): `whats the time`, `what's the time`, `what is the time`, `whats the time now`, `what time is it`, `what day is it (today|now)`, `whats/what's/what is the date (today|now)` and `what is todays date` — so the keywords `whats`, `what's`, `what`, `is`, `it`, `the`, `time`, `day`, `date`, `today`, `todays`, `today's` and `now` are in play, but only in those sequences. **Not** claimed: a bare `zeit`, a bare `time`, a bare `datum` or a bare `date`, for the reason in §8.

Contributed to `shared.stop`, not owned: `ich hab's gehört`, `ja ja`, `ist gut` (plus the catalog's bare `stopp` / `pause` / `aus`).

- `timer …` also claims an amount with no unit (`timer 10`, `stell einen timer auf zehn`), which is answered with a question rather than a template miss.
- **The `{name}` slots claim nothing extra on their own.** Every one of them sits between two
  keywords (§3, §4, §6), and a template with a text slot is strictly less specific than one
  without, so an unnamed phrasing is always tried first. What they *do* claim is the token
  between those keywords, whatever it is — which is why every name is cleaned before use (§3,
  *Names*) rather than trusted.
- **`was für ein …` is shared with the now-playing chain, and `heute` is what separates them.** "Was für ein Lied ist das" is `shared.whats_the_song`; "was für ein Tag ist heute" is `whats_the_date` (§8a). The nouns differ and the date forms require `heute`, so neither reaches the other — and "was für ein schöner Tag", which is somebody talking about the weather, reaches neither.
- ⚠️ `stell einen wecker auf 7 uhr` (an alarm at a wall-clock time) currently matches **nothing** — `7 uhr` is not `{amount}{unit}`, and `uhr` is not a unit the follow-up offers either. It falls through to Tier 2 and then to `none`. See §14.

## 11. Config

| Key | Type | Default | Where set |
|---|---|---|---|
| `clock.chime_sound` | enum (`glocke`, `piep`, `gong`) | `glocke` | Settings |
| `clock.chime_max_duration_s` | int | `60` | Settings (advanced) |
| `clock.chime_interval_s` | int | `3` | Settings (advanced) |
| `clock.show_seconds` | bool | `false` | Settings |

The Sock also writes one key nobody sets by hand: `clock.pending_timers`, one line per running
timer as `endsAt|totalMs|ordinal|name`. It is the running timers' deadlines, written so a restarted
process can pick them up (§12), and rewritten on every structural change — a countdown tick is not
one. A name is Normalizer output with its first letters title-cased, so it holds letters, digits,
apostrophes and spaces and nothing else: the separator cannot appear inside one. A line that does
not parse is dropped rather than fatal.

> **Migration note.** This replaces `clock.pending_timer_ends_at` and `clock.pending_timer_total_ms`,
> which held the single v1 timer. Nothing reads them any more; a timer running across the upgrade is
> lost, which is the same outcome as a reboot and is already accepted below.

## 12. Failure & degradation

- No exact-alarm permission → `Degraded("Timer nicht garantiert genau")`; the coroutines still run, only the backstop is weaker. Commands stay available.
- Process death while timers run → the `AlarmManager` backstop fires and restarts the service to announce. The deadlines are in config, so the restarted Sock resumes every timer that is still running and rings **each one** that came due while nobody was home; a deadline more than an hour old is dropped instead, because a chime an hour late is not news. **Verify this on the actual device under OxygenOS** — its background restrictions are the real risk here, not the Android API (see plan §7.4). A refused foreground-service start is logged and the service stops, rather than crash-looping a `START_STICKY` service.
- A misheard name is a **miss, not a guess**: "Es läuft kein Timer namens …". Two timers within fuzzy tolerance of the same word is the same answer, for the same reason — cancelling the wrong timer is not recoverable by saying it again.
- Never `Unavailable`.

## 13. Testing

- Template tables (§3, §4, §5, §6, §8) as parameterized unit tests, including normalizer output ("zehn" → "10") as input.
- **Routing tests:** "timer stopp" → `clock.cancel_timer` (always, regardless of chain state); "stopp" → `shared.stop`; "brich alle timer ab" → `clock.cancel_all_timers` and **not** `cancel_timer(name=alle)`.
- **Chain tests** — the two cases the design exists for:
  - chime ringing + Spotify playing → Clock consumes, **music resumes at full volume**, Spotify's handler is never invoked;
  - timer counting down + Spotify playing → Clock returns `NotForMe`, Spotify pauses, **the timer still runs afterwards**.
- `activityFor` returns `ACTIVE` only for `isRinging`, asserted against a counting-down state.
- Duration conversion incl. clamping and rejection.
- **Name cleaning**, as a table: "bitte" → no name; "für die nudeln" → "Nudeln"; "doch bitte" → no name; "nudel" → the timer called "Nudeln", fuzzily.
- **The follow-ups, end to end, through `DobbyEngine`:**
  - "stell einen timer auf zehn" → question → "minuten" → a timer at 600 s; the same path with "stopp" instead of an answer, asserting that the chain gets it and no timer is set; the turn ending instead of an answer; an answer with no stashed amount. Plus the ordering assertion that a phrasing naming its unit never falls through to a unit-less template.
  - "neuer timer wäsche auf 90" → question → "minuten" → a timer **still called Wäsche**.
  - two timers running, "brich den timer ab" → question → "nudeln" → that one and only that one; **and "wie spät ist es" instead of an answer, which must reach `whats_the_time` rather than becoming a timer name.** That last one is the whole reason the follow-up slot is an `enum`.
- **Multi-timer lifecycle**, with a virtual clock: several set, labels renaming as the second one lands, the lowest free ordinal reused after a cancel, a named timer replacing its namesake, the 8-timer cap, cancel-all, a ringing timer silenced while another keeps counting (**and the music staying ducked until the last one is done**).
- **Process death with several timers**, named and unnamed: both resumed with their names, ordinals and remaining time; one that came due while dead rings at once; one from hours ago dropped.
- **The backstop with several timers**: one alarm armed for the earliest deadline, re-armed when that changes, and every timer that came due during a doze ringing when it fires.
- Time phrasing: a table covering every branch, with 14:30 → "halb 3" explicitly asserted.
- Date phrasing: both languages against a fixed clock, including the English ordinal at 1st, 2nd, 3rd, 11th–13th and 14th — the branch a bare `%d` would get wrong.
- **The calendar (§8b–§8e) against a fixed clock**, which is the only way any of it is testable:
  the resolution rules (a day the current month has already passed, "der 31." skipping a 30-day
  month, the next 29 February, a weekday that is today), the German ordinal in an `{n:int}` slot
  across the irregular stems (`erste`, `dritte`, `siebte`, `achte`) and the `-te`/`-ste` boundary
  at 19/20, the two-part span with and without a remainder, the dative plural behind "in", and
  the today/tomorrow branch of every one of the four result tables.
- **The §10 separation table as routing tests**: the four `welcher tag ist …` utterances, each
  asserted against the command that owns it, plus `wie lange noch` versus `wie lange noch bis
  freitag`.
- Remaining phrasing: a table, incl. 5400 s → "1 Stunde und 30 Minuten", 570 s → "9 Minuten und 30 Sekunden", 45 s → "45 Sekunden", 60 s → "1 Minute".
- Pure-JVM only; `AlarmManager` sits behind a small interface with a fake.

## 14. Open questions / out of scope (v1)

- **Alarms at a wall-clock time** ("Wecker auf 7 Uhr") — a genuinely different command (`set_alarm`) with a different param shape. Deferred; §10 documents the current dead end.
- **Renaming a running timer** ("nenn den Timer Nudeln") and **extending one** ("gib dem Timer noch 5 Minuten"). Both are natural once timers have names, and neither is in the command list.
- **A worked Tier 2 example of a named timer.** The route prompt carries the first two paraphrases per command and the fill turn the first one, so the named few-shot in `ClockSock` is third and reaches neither. Tier 2 learns `name` from the param list in the fill turn, the way every other optional param is learned. Moving that example up one line is the whole lever, the day the fallthrough log says it is needed.
- **A long name can outrun the fill decode budget.** `Tier2.MAX_FILL_TOKENS` is 25 and `clock.set_timer`'s worst-case reply — a 40-character name — is about 44, so a paraphrase with a very long name would be truncated mid-JSON. Reported, not enforced, exactly as it already is for `calculator.calculate`; the levers are a per-param length cap in `GrammarGenerator` or a measured `MAX_FILL_TOKENS`. Tier 1 handles named timers without the model at all, so the exposure is paraphrases only.
- Timers surviving a reboot. A timer does now survive a *process* death (§12), because it must; a reboot clears the alarm and stops the service, and nothing re-arms it.
- Stopwatch, countdown to a date, world clocks.
- **Backwards dates.** "Welcher Tag **war** der 1. September", "wie lange ist es **her** seit
  …". §8b–§8e all resolve forwards, today included, and the direction is not a slot. A past date
  with no year is ambiguous in a way a future one is not — "der 1. September" looking backwards
  could be three weeks or eleven months ago — so this needs a decision before it needs code.
- **Compound spans.** "In 2 Wochen und 3 Tagen" is one question and two `{amount} {unit}` pairs;
  Tier 1 takes one. It is a Tier 2 few-shot on `date_in` today, which is the right place for it
  until the fallthrough log says otherwise.
- **Named days.** "Wie lange noch bis Weihnachten", "wann ist Ostern". A fixed-date holiday is a
  lookup table and a moving one is an algorithm, and both want a `holiday: enum` that nothing
  else in this Sock has a use for.
- **A numeric month.** "Der 14.7." normalizes to two integers, and a second `{int}` slot beside
  the first is one template away — but it is also one misheard sentence away from reading "der
  14. 7 Uhr" as a date. Deferred until somebody says it.
