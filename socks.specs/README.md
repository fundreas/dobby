# Socks — the contract

A **Sock** is Dobby's unit of capability. Dobby core owns the microphone, the wake word, speech-to-text, intent parsing, the dispatcher and text-to-speech. It owns **no commands**. Every command the user can speak is contributed by a Sock.

> Dobby is freed by a sock. Each Sock hands Dobby one more thing it is allowed to do.

This file defines what a Sock is and how to write its spec. One spec file per Sock: `<sockId>.specs.md`.

---

## 1. What a Sock provides

| | |
|---|---|
| **id** | stable lowercase slug, e.g. `spotify`. Prefix for all its command ids. |
| **commands** | one or more `CommandSpec` — exclusive, owned by this Sock alone. |
| **shared** | optional `SharedSubscription`s — participation in a dispatch chain (§4) |
| **handler** | `suspend fun handle(invocation): SockResult` |
| **activity** | `fun activityFor(invocation): SockActivity` — ranks it in the chain (§4) |
| **lifecycle** | optional `onStart(ctx)` / `onStop()`, plus `onAskCancelled(token)` — a question of its own that will never be answered (§5) |
| **status** | `Ready` \| `Degraded(reason)` \| `Unavailable(reason)` — surfaced in settings UI |
| **dashboard** | optional Compose card for the wall panel |
| **config** | optional settings, stored namespaced under the Sock id |

## 2. What a Sock may NOT do

- Touch `AudioRecord` or the wake word. The mic has exactly one owner (core).
- Call `TextToSpeech` directly for a command acknowledgement — return a `SockResult` instead. Asynchronous announcements (a timer firing) go through `ctx.announce(text)`.
- Hold screen wake locks. Ask `ctx.screen` instead.
- Start sustained audio playback without requesting focus from `ctx.playback`.
- Reach into another Sock. Cross-Sock effects happen only through `PlaybackCoordinator` / `ScreenController`.
- Block. `handle()` runs under a timeout; long work belongs in `ctx.coroutineScope` with a `Deferred` result. **This includes waiting for an answer to a question**: a Sock that asks something returns `Asked` and keeps its half-built state under the token (§5). Core calls `handle()` a second time with the answer. There is no waiting instance and nothing to block on.

If a Sock needs something not on `SockContext`, that is a core change and a plan change — not a local workaround.

## 3. Command ids

`<sockId>.<command_name>`, snake_case, e.g. `spotify.play_music`. Ids are contract: they appear in the GBNF grammar, in logs, in the flywheel data. **Never rename one without a migration note in the spec.**

`none` is core's own reserved command and belongs to no Sock. `shared.*` is the reserved namespace for chain commands (§4).

## 4. Shared commands & the chain

Some utterances are ambiguous by nature. "Stopp" means pause Spotify, kill the radio, or silence a ringing timer — it depends on what is running.

Such a command is **shared**: it lives in the `shared.*` namespace, owned by nobody, and several Socks subscribe to it. At dispatch, core ranks the subscribers by what each reports through `activityFor(invocation)` and offers the command down the chain until one claims it.

| `SockActivity` | Meaning |
|---|---|
| `ACTIVE` | currently doing the thing this command would stop or change |
| `IDLE` | not doing it, but holds relevant state and could act |
| `INACTIVE` | nothing to do |

Order: `ACTIVE` → `IDLE` → `INACTIVE`, ties broken by the subscription's `priority` (higher first), then registration order. Each Sock answers `handle()` with a normal `SockResult` (**"I consume it"**) or with `SockResult.NotForMe` (**"nothing to do for me here"**). The first consumer wins; if nobody consumes, core speaks the command's `unconsumedResponse`.

Rules for a subscribing Sock:

- `activityFor` is a **pure state read**: no I/O, no network, no Binder call, under 5 ms. It runs for every subscriber on every shared invocation.
- A Sock reporting `INACTIVE` **must** return `NotForMe` without performing I/O.
- `NotForMe` from an **exclusive** command is a programming error.
- A handler that throws or times out does not break the chain — it is skipped and marked `Degraded`.

**When something collides, promote it to a shared command.** Do not re-word one Sock's templates to dodge another's; that just moves the coin flip somewhere less visible. Qualified phrasings ("stopp die musik") stay exclusive — they are not ambiguous.

The catalog and every subscription is specified in [shared-commands.specs.md](shared-commands.specs.md).

## 5. Follow-up questions

Some utterances are not ambiguous about *who* should act (§4) but about *what was said*. "Stell einen Timer auf zehn" — ten what? "Spiele Stairway to Heaven" — the song or the live version? A Sock in that position has one thing it needs and no way to guess it.

Such a Sock answers with a **question** instead of a statement:

```kotlin
SockResult.Asked(
    text = "10 was — Sekunden, Minuten oder Stunden?",
    follow = FollowUp(
        commandId = "clock.set_timer",          // must be a command of the asking Sock
        templates = patterns("{unit:enum}", "(in|auf|für) {unit:enum}"),
        params = listOf(ParamSpec("unit", ParamType.Enumeration(TimerUnit.SPOKEN))),
        token = "unit-7",                        // opaque handle on the half-built state
    ),
)
```

`Asked` is spoken exactly like `Spoken`. What is different is what core does afterwards: it keeps the microphone open and compiles `templates` into a **scoped palette** that exists for the rest of the turn and is never registered.

### The answer is a second `handle()` call

The asking Sock does **not** block waiting for input (§2). It returns, keeping whatever it had half-built under `token`, and core invokes it again:

```kotlin
CommandInvocation(commandId = "clock.set_timer", params = {unit: "minuten"}, answering = "unit-7")
```

`answering` is the token, or null for a fresh command. The Sock looks up what it stashed and finishes the job exactly as if everything had been said at once. An answer may itself be an `Asked` — a second question — bounded by `MAX_ASK_DEPTH` (2).

The answer is routed **straight back to the Sock that asked**, past the owner lookup and past the chain. An answer belongs to whoever asked it, not to whoever the command id would ordinarily resolve to.

### How the next utterance is resolved

1. **Scoped palette first.** A hit becomes the answer.
2. **Global palette second.** A hit is an ordinary command: the question is abandoned, `onAskCancelled(token)` fires, and the command runs. This is not optional — without it somebody stuck inside "Meinst du …?" could say nothing, not "stopp" and not "lauter", that got them out of it, and the panel would be a trap.
3. **Neither.** The question stands, the utterance is not-understood as usual, and the retry loop gives them another go at it.

### Scoped, so the rules are different

Inside a scoped palette a bare `{text}` slot is legal, although §6 rejects it everywhere else. That rule exists because a *global* template matching every utterance swallows the whole palette; these templates are tried only while a question is open, against the one utterance that answers it. "Wie soll der Timer heißen?" wants to hear whatever comes back.

Nothing is registered, so a follow-up template can never collide with a global one and is never part of the Tier 1 collision gate (§6).

### The lifetime is the turn, never the wall clock

A pending question dies when the turn ends — on silence, on a Sock ending the conversation, or on the clarify bound. It must never survive into the next turn: the wake word plus "ja" three minutes later would otherwise fire whatever was half-built when the conversation was abandoned, which is exactly the class of surprise a wall panel in a kitchen must not have.

Whenever a question will not be answered, its Sock is told: `onAskCancelled(token)`, exactly once, so it can free what it reserved. It must not speak and must not block.

A question that cannot hold the floor — templates that do not compile, a `commandId` belonging to another Sock, `MAX_ASK_DEPTH` exceeded — is logged, cancelled, and **still spoken**, as a plain `Spoken`. The person hears the question and can say the whole thing again; what they do not get is the open microphone.

## 6. Template DSL (Tier 1)

Templates are German, lowercase, written against the **normalized** transcript (lowercased, punctuation stripped, whitespace collapsed, German number words → digits).

| Syntax | Meaning |
|---|---|
| `wort` | literal keyword — fuzzy, tolerance 0 (≤ 3 chars), 1 (≤ 7), 2 (longer) |
| `(a\|b)` | alternation; a branch may be several words |
| `( ab)?` | optional group — whitespace inside it is not significant |
| `{name}` | text slot, captured verbatim, non-greedy |
| `{name:int}` | integer slot: digits, number words, or the article forms of "one" |
| `{name:enum}` | slot constrained to the `ParamSpec` enum values, fuzzy at tolerance 1 |

Rules:
- **Write templates against normalized text**: lowercase, no punctuation, **no hyphens**. "U-Bahn" normalizes to two tokens, so the template says `u bahn`.
- Order templates **specific → generic** within a Sock.
- Slot content is never fuzzy-matched — only keywords are, plus the closed candidate set of an enum slot.
- A template with a trailing open `{query}` slot is greedy for the rest of the utterance; put such templates last.
- A bare `{text}` slot as an entire template is rejected — it would match every utterance. A bare `{x:enum}` is fine; its candidate set is closed. That is how "lauter" works.
- Ordering across Socks is computed, not declared: closed before open, then more keywords, then fewer text slots, then registration order.

**Static params.** A template may fix params by its wording, for cases no slot can carry:

```kotlin
templates = listOf(
    pattern("(viel|deutlich) {direction:enum}", "steps" to 5),
    pattern("(ton|lautstärke) aus", "state" to "an"),
) + patterns("{direction:enum}")
```

A slot capture always beats a static param. Use this rather than re-parsing German in the handler.

**Exhaustiveness matters.** The registry test asserts no two Socks match the same utterance *for different commands*, so a spec that under-lists its utterances hides a collision until runtime. Templates contributed to the same `shared.*` id are exempt — that overlap is the design.

## 7. Writing a spec file

Every `<sockId>.specs.md` has these sections, in this order:

1. **Identity** — id, display name, one-line purpose, status, dependencies, permissions.
2. **Commands** — two summary tables: exclusive commands, and shared-command subscriptions with their priority.
3. **Per command** — a subsection each with:
   - params (name, type, required, default, constraints)
   - Tier 1 templates
   - example utterances → expected invocation (this table *is* the unit test — the registry asserts it on every build). Mark a paraphrase that Tier 1 is *meant* to miss with `matchedByTemplates = false`; it then feeds the Tier 2 prompt without failing the collision gate.
   - behavior: exactly what the handler does
   - `SockResult` on success, and the German TTS string
   - failure modes and their German TTS strings
4. **Activity** — the `ACTIVE` / `IDLE` / `INACTIVE` conditions per subscribed shared command, what it consumes and what it passes on, and a justification for its priority. Omit only if the Sock subscribes to nothing.
5. **Utterance collision surface** — every keyword this Sock claims, so the next Sock author can check against it.
6. **State & dashboard** — what it exposes to the UI, if anything.
7. **Config** — keys, types, defaults, where the user sets them.
8. **Failure & degradation** — what makes it `Degraded` / `Unavailable`, and how it recovers.
9. **Testing** — what must be covered, and with which fixtures.
10. **Open questions / out of scope** — explicitly deferred.

Keep German user-facing strings **in the spec**, verbatim. They are product copy, not implementation detail.

## 8. Current Socks

| Sock | Spec | Exclusive commands | Chains | Milestone |
|---|---|---|---|---|
| **Help** ✅ | [help.specs.md](help.specs.md) | `overview`, `sock_commands` | — | M1 |
| Spotify | [spotify.specs.md](spotify.specs.md) | `play_music`, `pause`, `resume`, `skip_next` | `shared.stop`, `shared.resume` | M1 |
| **Clock** ✅ | [clock.specs.md](clock.specs.md) | `set_timer`, `cancel_timer`, `whats_the_time` | `shared.stop` | M3 |
| System | [system.specs.md](system.specs.md) | `volume`, `mute`, `turn_on_screen`, `turn_off_screen` | — | M3 |
| Radio | [radio.specs.md](radio.specs.md) | `play_radio`, `stop_radio` | `shared.stop`, `shared.resume` | M4 |
| Departures | [departures.specs.md](departures.specs.md) | `departures` | — | M4 |

✅ built · ⬤ partly built. Plus the shared-command catalog, which is not a Sock: [shared-commands.specs.md](shared-commands.specs.md).

See [`../dobby-plan.md`](../dobby-plan.md) §3 for the core-side Sock API.
