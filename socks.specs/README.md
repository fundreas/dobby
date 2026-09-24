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
- Call `TextToSpeech` directly for a command acknowledgement — return a `SockResult` instead. Asynchronous announcements (a timer firing) go through `ctx.announce(phrase)`.
- Hold screen wake locks. Ask `ctx.screen` instead.
- Start sustained audio playback without requesting focus from `ctx.playback`. A Sock that plays **in this process** also passes an `onLost` callback to `requestFocus` and stops when it fires — the OS cannot revoke a request Dobby has already abandoned, so eviction is the coordinator's job (`radio.specs.md` §3, M4).
- Reach into another Sock. Cross-Sock effects happen only through `PlaybackCoordinator` / `ScreenController`.
- Block. `handle()` runs under a timeout; long work belongs in `ctx.coroutineScope` with a `Deferred` result. **This includes waiting for an answer to a question**: a Sock that asks something returns `Asked` and keeps its half-built state under the token (§5). Core calls `handle()` a second time with the answer. There is no waiting instance and nothing to block on.

If a Sock needs something not on `SockContext`, that is a core change and a plan change — not a local workaround.

---

## 2a. What a Sock says, and in which language

**Nothing sayable is a `String`.** `SockResult.Spoken`, `Asked`, `Failed`, `Ended` and `ctx.announce` all take a `Phrase` — `(Lang) -> String`, with `Lang` being `DE` or `EN`. The Sock is handed the language and builds the sentence; core calls the function at the moment it speaks.

```kotlin
// A sentence with nothing to interpolate: the two wordings, side by side.
SockResult.Spoken("Es läuft gerade kein Timer.", "No timer is running.")

// A constant, for copy the spec names and several handlers share.
val NO_TIMER: Phrase = Phrase.of("Es läuft gerade kein Timer.", "No timer is running.")

// Anything assembled. This is the common case, and it is why there is no strings table:
// the article, the number and the word order all move together.
SockResult.Spoken { lang ->
    val subject = timer.subject(lang)          // "Der Timer" / "The timer"
    if (lang == Lang.EN) "$subject has ${left(timer, lang)} left." else "$subject läuft noch ${left(timer, lang)}."
}

// A name that is neither German nor English. Write it once.
SockResult.Spoken(Phrase.of("${station.displayName}."))
```

Three rules follow from it:

1. **Build late, never early.** The function must be cheap and must not capture a rendered string. A timer set before the voice was switched is announced after it, in the language now selected — that only works because nothing was rendered until it was said.
2. **Understanding does not follow the answer language.** Templates, examples and the Tier 2 few-shots are German whatever the voice is, so a question asked in English is still answered in German ("The first or the second?" → "die zweite"). Do not add an English answer palette to a `FollowUp`.
3. **The panel's own UI stays German.** `SockStatus` reasons, dashboard cards and `CommandHelp` are drawn rather than spoken; where a Sock needs the same copy for both, it renders the phrase with `Lang.DE` for the screen and hands the phrase itself to core for the speaker.

**The per-Sock specs quote the German wording only**, and that is deliberate rather than stale: German is the copy that was reviewed out loud in the room, the English is its translation, and duplicating every table would give the two a way to drift. A spec says `Spoken("Es läuft gerade kein Timer.")`; the code carries both wordings and the German one must match the spec verbatim.

**Which language is set is not a Sock's business, and not a setting of its own.** It is the selected voice's: Cori answers in English, Thorsten and the Android voice in German (`m2c-plan.md` Part E). A Sock never reads it outside a `Phrase`.

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

Templates are German — English only under the rule below — lowercase, and written against the **normalized** transcript (lowercased, punctuation stripped apart from the apostrophe, whitespace collapsed, German number words → digits).

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
- **A template a single keyword can satisfy is legitimate only when nothing else in spoken German falls inside that keyword's fuzzy tolerance.** The test is that sentence, not "is it one word" — and it bites hardest at 4–7 characters, where tolerance is 1 and a one-word utterance therefore only has to come *close*. `lauter` passes: six characters with no German neighbour at distance 1, so it is claimed as a bare template. A bare `zeit` is rejected: four characters, tolerance 1, which also hands it `seit`, `weit` and `zeig`. The asymmetry is the reason the rule is this strict — a miss costs a repeat, a panel that answers something nobody said to it costs trust. Write the keyword with something around it instead (`was ist die zeit`), and record the rejection in the Sock's spec so the next author does not read the gap as an oversight.
- Ordering across Socks is computed, not declared: closed before open, then more keywords, then fewer text slots, then registration order.

**Static params.** A template may fix params by its wording, for cases no slot can carry:

```kotlin
templates = listOf(
    pattern("(viel|deutlich) {direction:enum}", "steps" to 5),
    pattern("(ton|lautstärke) aus", "state" to "an"),
) + patterns("{direction:enum}")
```

A slot capture always beats a static param. Use this rather than re-parsing German in the handler.

**Breadth is cheap; two things constrain it.** Tier 1 must fully cover every command every Sock declares ([`dobby-plan.md`](../dobby-plan.md) §2, core design rule 5), and more templates only enlarge a lookup table — it is the Tier 2 system prompt that is expensive, and that grows with `description` and `examples`, not with templates (same file, §9). So add phrasings liberally. What limits them is the single-keyword rule above, and this one:

**English templates are allowed on commands with no `{x:int}` and no `{x:enum}` slot.** The recogniser is multilingual and English tokens arrive intact, so an English template really does fire. The *normalizer* is German-only: it turns "zehn" into `10` and knows nothing of "ten", so an English template feeding an integer slot would match and then fail to coerce, silently. Apostrophes survive normalization (`'` is in the keep class), so write `what's` — and list `whats` beside it, the way `ich hab's gehört` lists `ich habs gehört`. The answer language is not the language it was addressed in — it is the voice's (§2a) — so an English template is answered in whatever the voice speaks. A spec that adds English templates says so.

**Do not enumerate filler variants.** The matcher runs the palette twice: once strictly, and — only if nothing matched — once more ignoring a list of German filler words. So `wie spät` already covers "wie spät ist es", "wie spät ist es denn jetzt", "ähm wie spät ist es bitte" and the "wie spät ist **das**" the recogniser keeps producing. Write the content words; do not write the cross-product.

The list is `Fillers.DE` in `:core`, and it holds roughly: the pronouns the recogniser swaps freely (`es`, `das`, `dies`, `mir`, `mich`, `uns`, `du`), the articles except `ein` (`der`, `die`, `den`, `dem`, `eine`, `einen`, `einem`, `einer`, `nen`, `ne`), the copula (`ist`, `sind`, `bin`, `bist`), the modal particles (`denn`, `mal`, `doch`, `eben`, `eigentlich`, `gerade`, `grad`, `jetzt`, `so`, `auch`, `dann`, `also`), `bitte`, and hesitation (`ähm`, `äh`, `hm`, `hey`, `he`, `dobby`).

What must **never** be on it: polarity and direction (`an`, `aus`, `ein`, `nicht`, `kein`, `mehr`, `hoch`, `runter`), dialogue answers (`ja`, `nein`, `ok`, `okay`, `stopp`, `halt`, `danke` — "OK" ends a turn, it is a command), and repetition or sequence (`noch`, `nochmal`, `wieder`, `weiter`, `zurück`). Two of these are in the list above for a reason: `ein` is an article *and* "mach das Licht ein", and `halt` is a particle *and* `shared.stop` in one word. Both are checked at build time, against the palette that was actually assembled: no filler may satisfy a template on its own, and no two commands may become the same sentence once fillers are dropped. A new Sock that breaks either fails the build with both templates named.

Two consequences for how you write a template:

- **Drop optionals whose only purpose was filler tolerance** — `(mir)?`, `(mal)?`, `(denn)?` — *unless the next thing is a slot.* Skipping happens in front of a keyword and at the end of an utterance, never in front of a `{slot}`, because a slot takes whatever token is there. `(rechne|berechne) (mir)? {a:int}` keeps its `(mir)?`, or "rechne mir 7 mal 8" tries to read "mir" as a number.
- **Nothing is skipped in front of a one-word template.** A template a single keyword satisfies has no anchor — the rule two bullets up — so a sentence built from filler plus that word would take it whole: a bare `aus` would answer "es ist jetzt aus", which is somebody talking about the oven. Trailing filler is still skipped, because "stopp bitte" is politeness. If a leading word matters, spell it out: the clock writes `(uhrzeit|die uhrzeit)`, not `uhrzeit`.
- **A phrasing you want on the fast path still deserves a template.** Skipping is a second pass over the whole palette; it is cheap, but it is also logged as a rescue in `/fallthrough`, and a phrasing that shows up there constantly is one worth spelling out.

**Exhaustiveness matters.** The registry test asserts no two Socks match the same utterance *for different commands*, so a spec that under-lists its utterances hides a collision until runtime. Templates contributed to the same `shared.*` id are exempt — that overlap is the design.

## 6a. Explaining a command

`description` is written for the Tier 2 prompt: one German line, rendered as a tool definition and paid for by the token. It is not an explanation for a person, and the panel's help screen and `help.explain_command` both need one. That is `CommandHelp`, declared beside the command:

```kotlin
ExclusiveCommandSpec(
    id = SET_TIMER,
    templates = patterns(…),
    description = "Stellt einen Timer für eine bestimmte Dauer, auf Wunsch unter einem Namen.",
    help = CommandHelp(
        title = "Timer stellen",
        detail = "Stellt einen Küchentimer. Mehrere gleichzeitig sind erlaubt — dann lohnt " +
            "sich ein Name, damit du den richtigen wieder abbrechen kannst.",
        hints = listOf("Ohne Einheit frage ich nach: Sekunden, Minuten oder Stunden?"),
        aliases = listOf("timer", "wecker", "timer stellen"),
    ),
)
```

| Field | For | Notes |
|---|---|---|
| `title` | heading, and the name `help.explain_command` resolves | short German noun phrase; falls back to the command id |
| `detail` | the help screen and the spoken explanation | falls back to `description` |
| `hints` | what is optional, what happens when something is missing | the screen shows all; **only the first is spoken** |
| `aliases` | resolution only | what somebody might call this command instead |

**What is not in it is the usage line.** That is derived from the templates by `Syntax` — `stell [einen] timer auf <amount> <unit>`, with `( )` an alternation, `[ ]` optional and `< >` a slot — because a usage line written by hand is a second copy of the grammar that nothing keeps honest, and the first phrasing somebody deletes turns it into a promise that does not work. An author writes the German prose; the machine writes the syntax.

`help` is optional. A command without it still appears everywhere, under a title derived from its id and with its `description` as the detail — and reads like what it is, which is the incentive to write one.

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
| **Help** ✅ | [help.specs.md](help.specs.md) | `overview`, `sock_commands`, `explain_command` | — | M1 |
| **Spotify** ✅ | [spotify.specs.md](spotify.specs.md) | `play_music`, `pause`, `resume`, `skip_next`, `skip_previous`, `restart_song` | `shared.stop`, `shared.resume` | M1 |
| **Clock** ✅ | [clock.specs.md](clock.specs.md) | `set_timer`, `cancel_timer`, `cancel_all_timers`, `timer_remaining`, `whats_the_time`, `whats_the_date`, `weekday_of_date`, `date_of_weekday`, `date_in`, `time_until` | `shared.stop` | M3 |
| **Conversation** ✅ | [conversation.specs.md](conversation.specs.md) | `dismiss` | — | M2b |
| **Calculator** ✅ | [calculator.specs.md](calculator.specs.md) | `calculate`, `continue_with`, `last_result`, `clear` | — | M3 |
| **System** ⬤ | [system.specs.md](system.specs.md) | `volume`, `set_volume`, `mute` ✅ · `turn_on_screen`, `turn_off_screen` | — | M3 |
| **Radio** ✅ | [radio.specs.md](radio.specs.md) | `play_radio`, `stop_radio` | `shared.stop`, `shared.resume` | M4 |
| **Memo** ✅ | [memo.specs.md](memo.specs.md) | `create_memo`, `latest_memo`, `oldest_memo`, `next_memo`, `previous_memo`, `close_memo` | — | M5 |
| Departures | [departures.specs.md](departures.specs.md) | `departures` | — | M4 |

✅ built · ⬤ partly built. Plus the shared-command catalog, which is not a Sock: [shared-commands.specs.md](shared-commands.specs.md).

See [`../dobby-plan.md`](../dobby-plan.md) §3 for the core-side Sock API.
