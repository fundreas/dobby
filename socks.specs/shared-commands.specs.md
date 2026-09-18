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
| `shared.stop` | — | `FIRST_CONSUMER` | Clock (100), Radio (50), Spotify (50) |
| `shared.resume` | — | `FIRST_CONSUMER` | Spotify (50), Radio (40) |

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
| **Clock** | **100** | the timer chime is **ringing** | never | silencing the chime, releasing transient audio focus → `Silent` | `(ich hab's gehört\|ich habs gehört\|ja ja\|ist gut)` |
| **Radio** | 50 | ExoPlayer is playing or buffering | never | stopping + releasing the player and its focus → `Silent` | — |
| **Spotify** | 50 | App Remote reports a playing track | App Remote connected, track loaded but paused | `playerApi.pause()` → `Silent` | — |

Rules that make this behave the way a person expects:

- **Clock is `ACTIVE` only while the chime is actually ringing** — never merely because a timer is counting down. "Stopp" during a running timer with music playing must pause the music, not cancel the timer. Cancelling a *running* timer requires the explicit `clock.cancel_timer` ("timer stopp").
- **Clock's priority 100** wins the Clock-ringing-and-radio-playing tie. A ringing alarm is the most salient thing in the room; silencing it is what "stopp" means then.
- **Spotify reports `IDLE`, never `ACTIVE`, when its track is already paused**, and then returns `NotForMe` — pausing an already-paused player is not "consuming" anything, and it would swallow the command from a Sock further down.
- Every subscriber returns `NotForMe` when `INACTIVE`, **without I/O** (plan §3.4).

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
| **Spotify** | 50 | never (resuming something already playing is a no-op) | App Remote connected and a paused track exists | `playerApi.resume()` → `Silent` |
| **Radio** | 40 | never | a station was stopped **in this session** | restarting that station → `Spoken("{displayName}.")` |

- Neither Sock is ever `ACTIVE` for this command, so ranking falls through to priority: **Spotify is asked first.** This matches expectation — "weiter" after pausing a song means that song.
- Radio's `IDLE` is session-scoped: after a service restart, "weiter" with no history is `NotForMe`, not a surprise burst of FM4.
- A Sock currently *playing* must report `INACTIVE` and return `NotForMe`.

---

## 5. Cross-cutting testing

Owned by `core/dispatch`, not by any Sock. The suite is a **matrix**, not a list of examples:

- Axes: Spotify ∈ {playing, paused, disconnected} × Radio ∈ {playing, stopped-with-history, cold} × Clock ∈ {ringing, counting, idle}, for each of `shared.stop` and `shared.resume`. Assert the expected consumer, not just the observable side effect.
- Priority tie-breaks: two Socks both `ACTIVE`, verify the higher priority consumes and the loser's handler is **never invoked** (`FIRST_CONSUMER` must short-circuit).
- Nobody consumes → exact `unconsumedResponse` string.
- A subscriber throws, and a subscriber times out → chain continues, Sock marked `Degraded`, the next Sock consumes.
- **No-I/O contract:** a fake `SockContext` whose `http` and App Remote fakes fail the test on any call, driven through a full chain run where every Sock is `INACTIVE`.
- Registry validation: two Socks subscribing to the same shared id with structurally different params → startup failure.

## 6. Adding a Sock to a chain

1. Add the `SharedSubscription` to the Sock, with a priority justified in **its** spec.
2. Define its `ACTIVE` / `IDLE` / `INACTIVE` conditions in that spec's "Activity" section.
3. Add a row to §3.3 or §4.3 here, and the resulting scenarios to §3.4.
4. Extend the chain matrix test with the new axis.

Never resolve a new ambiguity by re-wording an existing Sock's templates.

## 7. Open questions / out of scope (v1)

- **`BROADCAST` mode** is specified but unused. Its intended first customer is `shared.stop_all` ("alles aus" — stop music *and* radio *and* chime in one word). Deliberately not in v1 so the mode ships with a real use case rather than on speculation.
- `shared.next` / `shared.previous` — a chain of one today (only Spotify can skip). Promote when a second skippable Sock exists (podcasts, playlists); until then `spotify.skip_next` stays exclusive.
- `shared.louder` / `shared.quieter` — **not** shared. Volume is a device-level concern owned by the System Sock and applies to whatever is on `STREAM_MUSIC`; there is no per-Sock volume in v1.
- **Contextual pronouns** ("mach das lauter", "spiel es nochmal") need a referent the pipeline does not track. Out of scope; the chain resolves *who acts*, not *what "das" refers to*.
