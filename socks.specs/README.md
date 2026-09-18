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
| **lifecycle** | optional `onStart(ctx)` / `onStop()` |
| **status** | `Ready` \| `Degraded(reason)` \| `Unavailable(reason)` — surfaced in settings UI |
| **dashboard** | optional Compose card for the wall panel |
| **config** | optional settings, stored namespaced under the Sock id |

## 2. What a Sock may NOT do

- Touch `AudioRecord` or the wake word. The mic has exactly one owner (core).
- Call `TextToSpeech` directly for a command acknowledgement — return a `SockResult` instead. Asynchronous announcements (a timer firing) go through `ctx.announce(text)`.
- Hold screen wake locks. Ask `ctx.screen` instead.
- Start sustained audio playback without requesting focus from `ctx.playback`.
- Reach into another Sock. Cross-Sock effects happen only through `PlaybackCoordinator` / `ScreenController`.
- Block. `handle()` runs under a timeout; long work belongs in `ctx.coroutineScope` with a `Deferred` result.

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

## 5. Template DSL (Tier 1)

Templates are German, lowercase, written against the **normalized** transcript (lowercased, punctuation stripped, whitespace collapsed, German number words → digits).

| Syntax | Meaning |
|---|---|
| `wort` | literal keyword — matched with Levenshtein ≤ 1 (≤ 2 for words ≥ 8 chars) |
| `(a\|b)` | alternation |
| `( ab)?` | optional group |
| `{name}` | string slot, captured verbatim, non-greedy |
| `{name:int}` | integer slot (post-normalization digits) |
| `{name:enum}` | slot constrained to the `ParamSpec` enum values |

Rules:
- Order templates **specific → generic** within a Sock.
- Slot content is never fuzzy-matched — only keywords are.
- A template with a trailing open `{query}` slot is greedy for the rest of the utterance; put such templates last.
- **Exhaustiveness matters.** The registry test asserts no two Socks match the same utterance *for different commands*, so a spec that under-lists its utterances hides a collision until runtime. Templates contributed to the same `shared.*` id are exempt — that overlap is the design.

## 6. Writing a spec file

Every `<sockId>.specs.md` has these sections, in this order:

1. **Identity** — id, display name, one-line purpose, status, dependencies, permissions.
2. **Commands** — two summary tables: exclusive commands, and shared-command subscriptions with their priority.
3. **Per command** — a subsection each with:
   - params (name, type, required, default, constraints)
   - Tier 1 templates
   - example utterances → expected invocation (this table *is* the unit test)
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

## 7. Current Socks

| Sock | Spec | Exclusive commands | Chains | Milestone |
|---|---|---|---|---|
| Spotify | [spotify.specs.md](spotify.specs.md) | `play_music`, `pause`, `resume`, `skip_next` | `shared.stop`, `shared.resume` | M1 |
| Clock | [clock.specs.md](clock.specs.md) | `set_timer`, `cancel_timer`, `whats_the_time` | `shared.stop` | M3 |
| System | [system.specs.md](system.specs.md) | `volume`, `mute`, `turn_on_screen`, `turn_off_screen` | — | M3 |
| Radio | [radio.specs.md](radio.specs.md) | `play_radio`, `stop_radio` | `shared.stop`, `shared.resume` | M4 |
| Departures | [departures.specs.md](departures.specs.md) | `departures` | — | M4 |

Plus the shared-command catalog, which is not a Sock: [shared-commands.specs.md](shared-commands.specs.md).

See [`../dobby-plan.md`](../dobby-plan.md) §3 for the core-side Sock API.
