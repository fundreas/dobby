# Shared commands — the catalog

Not a Sock. This file specifies the commands that **no single Sock owns**, because the right target depends on what is happening at the moment the user speaks. They are dispatched down a chain of subscribing Socks ranked by activity — see [`../dobby-plan.md`](../dobby-plan.md) §3.4 for the mechanism.

In code this catalog is `core/sock/SharedCommands.kt` — a plain data catalog, not dispatcher logic. Core still contains no behavior for any command.

---

## 1. When to make a command shared

Make it shared when **all** of these hold:

1. The same German phrase can sensibly address more than one Sock.
2. Which Sock is correct depends on runtime state, not on the wording.
3. Rewording one Sock's templates to dodge the other would make the user say something unnatural.

If only (1) holds, qualify the templates instead: "stopp die musik" is unambiguous and stays an exclusive `spotify.pause`. A command earns its place here by being genuinely context-dependent.

The anti-pattern this replaces: "bare *stopp* belongs to Spotify, everyone else must say a qualifier." That is a coin flip encoded as a rule, and it is wrong roughly half the time.

## 2. Catalog

| Shared command | Params | Chain mode | Subscribers |
|---|---|---|---|
| `shared.stop` | — | `FIRST_CONSUMER` | Clock ✅ (100), Radio ✅ (50), Spotify ✅ (50) |
| `shared.resume` | — | `FIRST_CONSUMER` | Spotify ✅ (50), Radio ✅ (40) |
| `shared.whats_the_song` | — | `FIRST_CONSUMER` | Spotify ✅ (50), Radio ✅ (50) |
| `shared.whats_the_artist` | — | `FIRST_CONSUMER` | Spotify ✅ (50), Radio ✅ (50) |

✅ = built. All four chains have every subscriber they were designed around.

The two now-playing chains were `spotify.whats_the_song` and `spotify.whats_the_artist` until
the Radio turned out to carry the same information — the ICY `StreamTitle` is an artist and a
title for three of the five stations — and they are the clearest case §1 has yet produced: the
same sentence, the same answer, and *which source* decided by what is playing rather than by a
word in the utterance. "Wie heißt das Lied im Radio" is not a thing anybody says at a wall
panel, so re-wording either side was never an option.

Numbers in brackets are `priority` — used **only** to break ties between Socks reporting the same `SockActivity`.

---

## 3. `shared.stop`

### Definition

| | |
|---|---|
| **id** | `shared.stop` |
| **params** | none |
| **description** (Tier 2, German) | Beendet oder pausiert das, was gerade läuft. |
| **chainMode** | `FIRST_CONSUMER` |
| **unconsumedResponse** | `Spoken("Es läuft gerade nichts.")` |

### Tier 1 templates (catalog-level, shared by all subscribers)

```
(stopp|stop|stoppe|halt|anhalten|aufhören|hör auf)
(pause|pausiere|pausier)
(mach|leg) (eine )?pause
(mach|schalt|schalte) (es |das )?aus
aus
```

Plus per-Sock `extraTemplates`, listed in §3.3.

> `aus` alone is deliberately included and deliberately last. It is a common one-word command at a wall panel and it is maximally context-dependent — exactly what the chain is for.

### Tier 2 examples

| Utterance | Invocation |
|---|---|
| stopp | `shared.stop()` |
| hör bitte auf damit | `shared.stop()` |
| mach das mal aus | `shared.stop()` |
| jetzt ist aber gut | `shared.stop()` |

### Subscribers, ranking and behavior

| Sock | Priority | `ACTIVE` when | `IDLE` when | Consumes by | Extra templates |
|---|---|---|---|---|---|
| **Clock** ✅ | **100** | the timer chime is **ringing** | never | silencing the chime, releasing transient audio focus → `Silent` | `(ich hab's gehört\|ich habs gehört\|ja ja\|ist gut)` |
| **Radio** ✅ | 50 | ExoPlayer is playing or buffering | never | stopping + releasing the player and its focus → `Silent` | — |
| **Spotify** ✅ | 50 | the cached `PlayerState` has a track and `isPaused == false` | a track is loaded but paused | `playerApi.pause()` → `Silent` | — |

Rules that make this behave the way a person expects:

- **Clock is `ACTIVE` only while the chime is actually ringing** — never merely because a timer is counting down. "Stopp" during a running timer with music playing must pause the music, not cancel the timer. Cancelling a *running* timer requires the explicit `clock.cancel_timer` ("timer stopp").
- **Clock's priority 100** wins the Clock-ringing-and-radio-playing tie. A ringing alarm is the most salient thing in the room; silencing it is what "stopp" means then.
- **Spotify reports `IDLE`, never `ACTIVE`, when its track is already paused**, and then returns `NotForMe` — pausing an already-paused player is not "consuming" anything, and it would swallow the command from a Sock further down.
- Every subscriber returns `NotForMe` when `INACTIVE`, **without I/O** (plan §3.4).
- **Spotify's activity is a read of a cached subscription, never a Binder call.** A silently
  dead App Remote therefore leaves a stale cache, and the Sock can claim a command it cannot
  execute; it then fails with its normal message rather than passing down the chain
  ([spotify.specs.md](spotify.specs.md) §6). Accepted — the alternative is a round trip on
  every "stopp".

### Worked scenarios

| State | Consumer | Outcome |
|---|---|---|
| Spotify playing, nothing else | Spotify (ACTIVE) | music pauses |
| Radio playing, nothing else | Radio (ACTIVE) | stream stops |
| Timer ringing, Spotify playing (ducked) | Clock (ACTIVE, prio 100) | chime stops, **music resumes at full volume** |
| Timer ringing, nothing else | Clock | chime stops |
| Timer counting down, Spotify playing | Spotify (Clock is INACTIVE) | music pauses, timer keeps running |
| Spotify paused, nothing else | nobody (Spotify IDLE → `NotForMe`) | "Es läuft gerade nichts." |
| Nothing at all | nobody | "Es läuft gerade nichts." |

That third row is the one that justifies the whole design.

---

## 4. `shared.resume`

### Definition

| | |
|---|---|
| **id** | `shared.resume` |
| **params** | none |
| **description** (Tier 2, German) | Setzt fort, was zuletzt pausiert wurde. |
| **chainMode** | `FIRST_CONSUMER` |
| **unconsumedResponse** | `Spoken("Es ist gerade nichts pausiert.")` |

### Tier 1 templates

```
(weiter|weitermachen|weiterspielen|fortsetzen|fortfahren)
(mach|spiel) weiter
(weiter gehts|weiter geht's)
```

> ⚠️ **Ordering hazard:** `weiter zum nächsten` (→ `spotify.skip_next`) and `nächster song` must be registered **before** bare `weiter`. The registry orders specific → generic, but this pair is asserted explicitly in the collision test — it is the single most likely regression when someone adds a template later.

### Tier 2 examples

| Utterance | Invocation |
|---|---|
| weiter | `shared.resume()` |
| mach weiter | `shared.resume()` |
| spiel bitte weiter | `shared.resume()` |

### Subscribers, ranking and behavior

| Sock | Priority | `ACTIVE` when | `IDLE` when | Consumes by |
|---|---|---|---|---|
| **Spotify** ✅ | 50 | never (resuming something already playing is a no-op) | a track is loaded and paused | `playerApi.resume()` → `Silent` |
| **Radio** ✅ | 40 | never | a station was stopped **in this session** | restarting that station → `Spoken("{displayName}.")` |

- Neither Sock is ever `ACTIVE` for this command, so ranking falls through to priority: **Spotify is asked first.** This matches expectation — "weiter" after pausing a song means that song.
- Radio's `IDLE` is session-scoped: after a service restart, "weiter" with no history is `NotForMe`, not a surprise burst of FM4.
- A Sock currently *playing* must report `INACTIVE` and return `NotForMe`.

---

## 5. `shared.whats_the_song`

### Definition

| | |
|---|---|
| **id** | `shared.whats_the_song` |
| **params** | none |
| **description** (Tier 2, German) | Sagt, welcher Titel gerade läuft — mit Künstler. |
| **chainMode** | `FIRST_CONSUMER` |
| **unconsumedResponse** | `Spoken("Es läuft gerade nichts.")` |

### 5.1 Tier 1 templates

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

**The articles are written out although `das`, `der` and `die` are fillers.** Skipping drops
*extra* words from the utterance; it does not make a template's own literals optional. "Wie
heißt das Lied" needs its `das`, and what the filler list buys is "wie heißt denn gerade das
Lied" for free — the particles, not the articles.

**Both spellings of `heißt`.** The phonetic tier folds ß to ss and would reach `heisst` anyway,
but the commonest phrasing in this catalog belongs on the strict first pass rather than on the
fallback ([README.md](README.md) §6).

**`wie heißt das` is the loosest wording here and resolves to the song, not the artist.**
Somebody pointing at a speaker with three words is asking what the thing is called, and that
answer names the artist as well.

### 5.2 Tier 2 examples

| Utterance | Invocation |
|---|---|
| was ist das für ein lied | `whats_the_song()` |
| wie heißt der song | `whats_the_song()` |
| wie heißt das | `whats_the_song()` |
| welches lied ist das | `whats_the_song()` |
| was läuft gerade | `whats_the_song()` |
| welche musik läuft | `whats_the_song()` |

### 5.3 Subscribers, ranking and behavior

| Sock | Priority | `ACTIVE` when | `IDLE` when | Consumes by |
|---|---|---|---|---|
| **Spotify** ✅ | 50 | the cached `PlayerState` has a title or an artist and `isPaused == false` | a track is loaded but paused | `Spoken("{title} von {artist}.")` |
| **Radio** ✅ | 50 | a stream is `Playing` | never | `Spoken` from the ICY title, split on `" - "` |

- **`IDLE` for a paused Spotify is what makes this chain correct, and it is the one row that
  differs from §3.3's reasoning.** Pausing a paused player is a no-op, which is why `shared.stop`
  treats `IDLE` as "pass"; answering about a paused track is not, so here `IDLE` still consumes
  — it just does it *after* anything audible. The scenario that needs it is ordinary: music
  paused an hour ago, FM4 on now, "was läuft gerade". Both Socks can answer, and the room can
  only hear one of them.
- **The tie at 50 is therefore between an `ACTIVE` and an `IDLE`, and activity outranks
  priority** — which is the whole ranking rule, not a special case. Two Socks both `ACTIVE`
  cannot happen: `PlaybackCoordinator` hands the channel to one at a time. The number is
  written down anyway so the order is decided here rather than by registration order if that
  invariant ever breaks.
- **Neither Sock ever speaks its own "nothing is playing".** Both return `NotForMe`, and the
  sentence comes from `unconsumedResponse`. This is the one behavioural change the promotion
  made: as an exclusive command, Spotify answered "Auf Spotify läuft gerade nichts" from the
  top of the chain and would now swallow a question the Radio can answer. Spotify not being
  installed or not configured passes for the same reason — the standing `Failed` reason belongs
  to a command that was *addressed* to Spotify.
- **Buffering is not `ACTIVE` for the Radio.** There is no sound yet, so there is nothing to
  ask about; `shared.stop` treats the same state as `ACTIVE` because a stop two seconds after
  "radio fm4" means the thing that is connecting.
- **Both read a cache and neither does I/O.** Spotify reads the App Remote subscription, the
  Radio reads its own `RadioState` — the same fields `activityFor` ranks on and the same ones
  the wall panel draws. A question that opens a connection is a question that can hang for two
  seconds and then put a consent dialog over its own answer.

### 5.4 Answers

| Case | Consumer | German TTS |
|---|---|---|
| Spotify, title and artist | Spotify | "Blinding Lights von The Weeknd." |
| Spotify, no artist | Spotify | "Blinding Lights." |
| Radio, ICY `Artist - Title` | Radio | "Voyage Voyage von Desireless." |
| Radio, programme name only | Radio | "Fivas Ponyhof." |
| Radio playing, no ICY title yet | Radio | "Der Sender sagt gerade nicht, was läuft." |
| Spotify paused, radio playing | Radio | the stream — `ACTIVE` outranks Spotify's `IDLE` |
| Spotify paused, radio stopped | Spotify | the paused track, answered from `IDLE` |
| Nothing playing anywhere | nobody | "Es läuft gerade nichts." |

`whats_the_song` names the artist too, because "Blinding Lights" on its own is half an answer
and nobody asks the follow-up out loud.

**The Radio's split is a hyphen with spaces around it, first occurrence wins.** Three of the
five stations follow ICY's `Artist - Title` convention; FM4 and Ö1 broadcast a programme name
instead, which has no artist, and Ö1's "Im Zeit-Raum: Judith Mangelsdorf" is why the hyphen
must be surrounded by spaces to count. A missing separator is *no artist*, never a guess.

---

## 6. `shared.whats_the_artist`

### Definition

| | |
|---|---|
| **id** | `shared.whats_the_artist` |
| **params** | none |
| **description** (Tier 2, German) | Sagt, wer den Titel spielt, der gerade läuft. |
| **chainMode** | `FIRST_CONSUMER` |
| **unconsumedResponse** | `Spoken("Es läuft gerade nichts.")` |

### 6.1 Tier 1 templates

```
was ist das für ein (künstler|interpret|sänger|artist)
was ist das für eine (band|sängerin|gruppe)
wie (heißt|heisst) (der künstler|der interpret|der sänger|der artist)
wie (heißt|heisst) (die band|die sängerin|die gruppe)
wer (spielt|singt) (das|den song|das lied|hier)?
von wem ist (das|der song|das lied|das stück)
wer ist (der|die) (künstler|interpret|sänger|band|sängerin)
```

**Bare `wer ist das` is deliberately not claimed.** It is a question about a person at least as
often as about a song — at a door, in a photo, on the radio news — and nothing in the chain can
tell which was meant. `wer spielt das` and `wer singt das` name the act of playing and cannot
be about anything else.

### 6.2 Tier 2 examples

| Utterance | Invocation |
|---|---|
| wer spielt das | `whats_the_artist()` |
| wer singt das | `whats_the_artist()` |
| wie heißt der künstler | `whats_the_artist()` |
| was ist das für eine band | `whats_the_artist()` |
| von wem ist der song | `whats_the_artist()` |

### 6.3 Subscribers, ranking and behavior

Identical to §5.3 — same subscribers, same priorities, same `ACTIVE` conditions, same reasons.
A Sock that can answer "what is this" can answer "who is this", and one that cannot answer
either passes.

### 6.4 Answers

| Case | Consumer | German TTS |
|---|---|---|
| Spotify, artist known | Spotify | "The Weeknd." |
| Spotify, no artist | Spotify | "Spotify nennt dazu keinen Künstler." |
| Radio, ICY `Artist - Title` | Radio | "Desireless." |
| Radio, programme name only | Radio | "Der Sender nennt dazu keinen Künstler." |
| Radio playing, no ICY title yet | Radio | "Der Sender sagt gerade nicht, was läuft." |
| Spotify paused, radio playing | Radio | the stream — `ACTIVE` outranks Spotify's `IDLE` |
| Nothing playing anywhere | nobody | "Es läuft gerade nichts." |

`whats_the_artist` does **not** name the title, because "wer spielt das" asked one thing and
got it. The two "keinen Künstler" sentences differ by one word — *Spotify* against *der Sender*
— and that is deliberate: the answer says where it looked.

---

## 7. Cross-cutting testing

Owned by `core/dispatch`, not by any Sock. The suite is a **matrix**, not a list of examples:

- Axes: Spotify ∈ {playing, paused, disconnected} × Radio ∈ {playing, stopped-with-history, cold} × Clock ∈ {ringing, counting, idle}, for each of `shared.stop` and `shared.resume`. Assert the expected consumer, not just the observable side effect.

> With Spotify built, both chains have a **real second subscriber** for the first time rather
> than a documented intention. The rows worth running by hand on the device are the ones no fake
> can reach: a timer ringing over playing music, and "weiter" after a song was paused by "stopp"
> ([spotify.specs.md](spotify.specs.md) §11).
- Priority tie-breaks: two Socks both `ACTIVE`, verify the higher priority consumes and the loser's handler is **never invoked** (`FIRST_CONSUMER` must short-circuit).
- Nobody consumes → exact `unconsumedResponse` string.
- A subscriber throws, and a subscriber times out → chain continues, Sock marked `Degraded`, the next Sock consumes.
- **No-I/O contract:** a fake `SockContext` whose `http` and App Remote fakes fail the test on any call, driven through a full chain run where every Sock is `INACTIVE`.
- Registry validation: two Socks subscribing to the same shared id with structurally different params → startup failure.

## 8. Adding a Sock to a chain

1. Add the `SharedSubscription` to the Sock, with a priority justified in **its** spec.
2. Define its `ACTIVE` / `IDLE` / `INACTIVE` conditions in that spec's "Activity" section.
3. Add a row to §3.3, §4.3, §5.3 or §6.3 here, and the resulting scenarios to §3.4.
4. Extend the chain matrix test with the new axis.

Never resolve a new ambiguity by re-wording an existing Sock's templates.

## 9. Open questions / out of scope (v1)

- **`BROADCAST` mode** is specified but unused. Its intended first customer is `shared.stop_all` ("alles aus" — stop music *and* radio *and* chime in one word). Deliberately not in v1 so the mode ships with a real use case rather than on speculation.
- `shared.next` / `shared.previous` — a chain of one today (only Spotify can skip). Promote when a second skippable Sock exists (podcasts, playlists); until then `spotify.skip_next` stays exclusive.
- `shared.louder` / `shared.quieter` — **not** shared. Volume is a device-level concern owned by the System Sock and applies to whatever is on `STREAM_MUSIC`; there is no per-Sock volume in v1.
- **Contextual pronouns** ("mach das lauter", "spiel es nochmal") need a referent the pipeline does not track. Out of scope; the chain resolves *who acts*, not *what "das" refers to*.
