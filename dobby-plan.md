# Dobby — Project Plan

A voice-controlled, wall-mounted smart panel built as a single Android app, running 24/7 on a repurposed **OnePlus Nord CE (OxygenOS 13, official ROM, permanently plugged in)**. No server, no cloud assistant: wake word detection, speech-to-text, and intent parsing all run **on-device**.

Dobby itself owns no skills. Everything the user can actually *do* lives in a **Sock** — a self-contained module that registers its commands with Dobby's command palette and executes them. Adding a capability means adding a Sock, never touching the pipeline.

This document is the build spec for **Dobby core**. Per-Sock behavior is specified in [`socks.specs/`](socks.specs/) — one file per Sock, see [`socks.specs/README.md`](socks.specs/README.md) for the contract every Sock must satisfy.

---

## 1. Product behavior

- The device hangs on the wall, screen **off by default** (OLED burn-in avoidance + power).
- It listens continuously for a wake word. On detection: screen wakes for ~30 s, a listening indicator appears, the user speaks a command.
- Commands are interpreted locally and dispatched to the owning Sock. Responses are spoken via TTS and/or shown on the dashboard.
- Dashboard (when screen is on): clock plus whatever the installed Socks contribute (departure board, timer countdown, now playing).
- Everything must survive: screen off, app backgrounded, days of uptime, OxygenOS's aggressive process killing. A reboot may require one manual tap (see §7.3 — accepted limitation).
- Interaction language is **German** (de-AT/de-DE). Every Sock's utterances, TTS strings and LLM examples are German.

---

## 2. Architecture overview

```
 ┌────────────────────────── Foreground Service (microphone type) ──────────────────────────┐
 │                                                                                          │
 │  Mic (AudioRecord, 16 kHz mono)                                                          │
 │        │                                                                                 │
 │        ▼                                                                                 │
 │  [1] Wake word — openWakeWord, on-device, always running                                 │
 │        │  (detection)                                                                    │
 │        ▼                                                                                 │
 │  [2] STT — Vosk, German small model, streaming, endpointing on silence                   │
 │        │  (final transcript: String)                                                     │
 │        ▼                                                                                 │
 │  [3] Intent parsing — tiered, driven entirely by the SockRegistry                        │
 │        ├─ Tier 1: template/slot matcher (deterministic, <10 ms)   ── 95% path            │
 │        └─ Tier 2: local LLM via llama.cpp + generated GBNF        ── paraphrase fallback │
 │        │  (CommandInvocation: commandId + params)                                        │
 │        ▼                                                                                 │
 │  [4] Dispatcher → SockRegistry.resolve(commandId) → Sock.handle(invocation)              │
 │        │                                                                                 │
 │        ▼                                                                                 │
 │  [5] Feedback — SockResult → TextToSpeech (German) + UI state (StateFlow → Compose)      │
 └──────────────────────────────────────────────────────────────────────────────────────────┘

            ┌─────────────────── SockRegistry (the only extension point) ───────────────────┐
            │  SpotifySock   RadioSock   ClockSock   SystemSock   DeparturesSock   …        │
            │  each contributes: CommandSpec[] · handler · optional dashboard card · config │
            └──────────────────────────────────────────────────────────────────────────────┘
```

**Core design rules:**

1. **Core knows no commands.** The pipeline contains zero references to `play_music`, `set_timer`, etc. The palette is assembled at startup from the registered Socks.
2. **Parsing is pure.** `fun parse(text: String, palette: Palette): CommandInvocation?` — no side effects, no Android dependencies. Table-driven unit tests, including deliberately misrecognized inputs.
3. **One dispatcher seam.** Every recognized command flows through a single `dispatch(invocation: CommandInvocation)`. This keeps the door open to later forwarding unmatched utterances to a Home Assistant instance (`POST /api/conversation/process`) as just another Sock.
4. **Single mic owner.** One `AudioRecord` instance feeds both the wake word and Vosk (the wake word consumes frames until it fires; then frames are routed to Vosk until endpoint). Never open two recorders. Socks never touch the mic.
5. **The LLM is a fallback, not the workhorse.** Tier 1 must fully cover every command every Sock declares.
6. **A Sock is replaceable and removable.** Deleting a Sock from the registry removes its commands from the palette, its GBNF branches, and its dashboard card — and nothing else breaks.
7. **Ambiguous commands are resolved at dispatch time, not at parse time.** "Stopp" means whatever is currently running. The parser produces one *shared* command; the dispatcher offers it down a chain of Socks ranked by how active they are, and the first one that claims it wins (§3.4). Core still knows nothing about what "stopp" does.

---

## 3. The Sock model

### 3.1 Contract

```kotlin
interface Sock {
    val id: String                       // "spotify", stable, used as command prefix
    val displayName: String              // "Spotify"

    val commands: List<CommandSpec>                          // exclusive — this Sock alone owns them
    val shared: List<SharedSubscription>                     // chain participation, §3.4

    suspend fun onStart(ctx: SockContext) {}
    suspend fun onStop() {}

    /** Ranks this Sock in the chain. Must be fast (< 5 ms) and do NO I/O. */
    fun activityFor(invocation: CommandInvocation): SockActivity = SockActivity.INACTIVE

    suspend fun handle(invocation: CommandInvocation): SockResult

    val status: StateFlow<SockStatus>    // Ready | Degraded(reason) | Unavailable(reason)
    val dashboard: DashboardCard?        // optional Compose contribution
}
```

```kotlin
data class CommandSpec(
    val id: String,                      // "spotify.play_music" — always "<sockId>.<name>"
    val params: List<ParamSpec>,         // name, type (String|Int|Enum), required, enum values
    val templates: List<String>,         // Tier 1 DSL, German — see §5.3
    val description: String,             // Tier 2: one German line, "Spielt Musik ab."
    val examples: List<Example>,          // Tier 2 few-shots: utterance → expected invocation
    val requiresScreen: Boolean = false,
    val interrupts: InterruptClass? = null // e.g. AUDIO — see PlaybackCoordinator, §3.3
)
```

- `CommandInvocation` = `commandId: String` + `params: Map<String, Any>`, already type-coerced and validated against `ParamSpec` before it reaches the Sock.
- `SockResult` = `Spoken(text)` | `Silent` | `Deferred` (Sock will speak later, e.g. timer expiry) | `Failed(userMessage, cause)` | **`NotForMe`** (chain only, §3.4). Core turns it into TTS + UI state. **A Sock never calls TTS directly for command acknowledgement** — it returns a result. It may push asynchronous announcements via `ctx.announce(text)`.
- Returning `NotForMe` for an **exclusive** command is a programming error: logged loudly, treated as `Failed`.

### 3.2 SockContext — everything a Sock is allowed to touch

`appContext`, `coroutineScope` (service-lifetime), `announce(text)` (async TTS), `playback: PlaybackCoordinator`, `screen: ScreenController`, `http: OkHttpClient` (shared, configured), `config: SockConfigStore` (namespaced key-value), `log: SockLog` (feeds the Tier-2 fallthrough flywheel).

Anything not on `SockContext` is off-limits — this is what keeps Socks unit-testable without an emulator.

### 3.3 Cross-Sock coordination

- `PlaybackCoordinator` arbitrates the audio channel: any Sock that wants to produce sustained audio requests focus; granting focus pauses the current holder. Spotify and Radio can therefore never play at once, and TTS ducks both.
- `ScreenController` owns the screen-on wake locks. Socks request "wake the screen for N seconds", they do not hold locks themselves.

### 3.4 Shared commands & chain dispatch

Some utterances are inherently ambiguous about *who* should act. "Stopp" means pause Spotify, or kill the radio stream, or silence a ringing timer — depending entirely on what is happening at that moment. Resolving this in the parser is impossible (the parser is pure and stateless) and resolving it by assigning the word to one lucky Sock is what produced the collision hacks this model replaces.

Instead: such a command is declared **shared**, and dispatch walks a chain.

#### Declaration

A shared command lives in the reserved `shared.` namespace — no Sock prefix, because no Sock owns it. Socks **subscribe**:

```kotlin
data class SharedSubscription(
    val command: SharedCommandSpec,          // from the shared catalog, see below
    val priority: Int = 0,                   // tiebreak within one activity level, higher first
    val extraTemplates: List<String> = emptyList(),   // utterances only this Sock adds
    val extraExamples: List<Example> = emptyList(),
)

data class SharedCommandSpec(
    /* …CommandSpec fields… */
    val chainMode: ChainMode = ChainMode.FIRST_CONSUMER,  // or BROADCAST
    val unconsumedResponse: SockResult,      // what Dobby says when nobody claims it
)
```

**Two or more Socks declaring the same `shared.*` id is not an error — it is the point.** The registry merges the declarations: templates and examples are unioned; `params` must be structurally identical across declarations (hard validation failure otherwise); description, `chainMode` and `unconsumedResponse` come from the shared catalog (`core/sock/SharedCommands.kt`), which is a plain data catalog contributed alongside the Socks, not dispatcher logic. Core still contains no behavior for any command.

#### Activity ranking

Every candidate answers `activityFor(invocation)`:

| | Meaning | Example |
|---|---|---|
| `ACTIVE` | currently doing the thing this command would stop/change | Radio streaming; timer chime ringing; Spotify playing |
| `IDLE` | not doing it, but holds relevant state and could act | Spotify connected with a paused track |
| `INACTIVE` | nothing to do | ExoPlayer released; no timer |

`activityFor` is a **pure state read** — no I/O, no network, no App Remote round-trip, under 5 ms. It runs for every candidate on every shared invocation.

#### Algorithm

```
dispatch(invocation):
  exclusive command → owner.handle(invocation)                       // unchanged

  shared command:
    candidates ← registry.subscribers(invocation.commandId)
    rank by (activityFor DESC: ACTIVE > IDLE > INACTIVE,
             then priority DESC,
             then registration order)
    for sock in ranked:
        r ← withTimeout(TIMEOUT) { sock.handle(invocation) }
        on throw/timeout → mark sock Degraded, log, continue     // a broken Sock cannot block the chain
        if r is NotForMe → continue
        return r                                                 // FIRST_CONSUMER stops here
    return command.unconsumedResponse
```

- `BROADCAST` mode continues past the first consumer and offers to every candidate — for a future "alles aus". `FIRST_CONSUMER` is the default and the only mode used in v1.
- **A Sock reporting `INACTIVE` must return `NotForMe` without performing I/O.** Otherwise a bare "stopp" would trigger a Spotify reconnect on every utterance. This is a contract requirement, tested with a fake context that fails the test on any network call.
- The chain is offered to `INACTIVE` Socks anyway (they are cheap, and a Sock may legitimately act from a cold state — e.g. `shared.resume` reaching a Spotify that has nothing loaded but a last-played context).
- Every chain run is logged: candidates, their reported activity, who consumed. This is the only way to debug "why did stopp pause Spotify instead of stopping the radio", and it feeds the Tier 2 flywheel.

#### Effect on parsing

- **Tier 1:** shared templates merge into the same regex table. A collision *within* a `shared.*` id is expected and fine; a collision between an exclusive command and anything else is still a build-time error (§5.3).
- **Tier 2:** a shared command appears in the GBNF grammar **once**, as `shared.stop`. The LLM never has to guess who is playing — that is the chain's job. This makes the grammar smaller, not larger.

The shared catalog and the per-Sock subscriptions are specified in [`socks.specs/shared-commands.specs.md`](socks.specs/shared-commands.specs.md).

### 3.5 Registry & startup

1. Socks are declared in one list (`DobbySocks.all`) — no reflection, no dynamic loading in v1.
2. At service start the registry validates: unique `id`; every **exclusive** `commandId` is prefixed with its `sockId`; no duplicate exclusive command ids; every `shared.*` subscription's params are structurally identical across subscribers; every template compiles; every param referenced by a template exists.
3. It then builds, once: the Tier 1 template table, the Tier 2 GBNF grammar and system prompt, the exclusive dispatch map, and the **chain table** (`shared.* → ordered subscriber list`). A validation failure is a **hard startup failure** in debug builds, and a logged + surfaced degraded state in release.

### 3.6 Sock specs

One markdown file per Sock in [`socks.specs/`](socks.specs/), authored against [`socks.specs/README.md`](socks.specs/README.md):

| Sock | Spec | Exclusive commands | Shared chains |
|---|---|---|---|
| Spotify | [`spotify.specs.md`](socks.specs/spotify.specs.md) | `play_music`, `pause`, `resume`, `skip_next` | `shared.stop`, `shared.resume` |
| Radio | [`radio.specs.md`](socks.specs/radio.specs.md) | `play_radio`, `stop_radio` | `shared.stop`, `shared.resume` |
| Clock | [`clock.specs.md`](socks.specs/clock.specs.md) | `set_timer`, `cancel_timer`, `whats_the_time` | `shared.stop` |
| System | [`system.specs.md`](socks.specs/system.specs.md) | `volume`, `mute`, `turn_on_screen`, `turn_off_screen` | — |
| Departures | [`departures.specs.md`](socks.specs/departures.specs.md) | `departures` | — |
| *(catalog)* | [`shared-commands.specs.md`](socks.specs/shared-commands.specs.md) | — | `shared.stop`, `shared.resume` |

---

## 4. Tech stack

- **Language/UI:** Kotlin, Jetpack Compose, single-activity. Coroutines + `StateFlow` for pipeline → UI state.
- **minSdk 33** (device is Android 13), targetSdk = latest stable. `arm64-v8a` only.
- **Wake word:** [openWakeWord](https://github.com/dscripka/openWakeWord) via ONNX Runtime. ~200 KB per wake word on top of a shared, frozen feature extractor; a single Raspberry Pi 3 core runs 15–20 of them in real time, so one on a Nord CE is free. Custom "Hey Dobby" is trained from **synthetic** Piper TTS audio (German voices available) in a Colab notebook — no recording sessions, no console, no account, no key, nothing that can be switched off from outside.
  - *Licence:* code is Apache-2.0; the **pre-trained models are CC BY-NC-SA 4.0** because of their training data, and a custom model sits on top of that frozen extractor. Non-commercial is exactly what this project is, but it is a hard constraint on ever shipping Dobby.
  - *(Was Porcupine. Picovoice discontinued its free tier on 2026-06-30 and disabled existing `AccessKey`s — and the SDK refuses to initialise without a valid one, so the plan's original choice stopped being viable rather than merely getting more expensive.)*
- **STT:** Vosk Android (`com.alphacephei:vosk-android`), model `vosk-model-small-de-0.15` (~45 MB), bundled in assets or downloaded on first run.
- **LLM tier:** `llama.cpp` built for arm64 Android via CMake/NDK (JNI wrapper; start from the official llama.cpp Android example). Model: **Qwen3 1.7B instruct, Q4_K_M GGUF** (~1.1 GB), downloaded on first run to app files dir (do not bundle in APK).
- **TTS:** Android built-in `TextToSpeech`, locale `de_AT`/`de_DE`.
- **Shared plumbing available to Socks:** OkHttp + kotlinx.serialization, Media3 ExoPlayer, AlarmManager, WorkManager.

Sock-specific dependencies (Spotify SDKs, etc.) are declared in the Sock's own spec and, where practical, in its own Gradle module.

---

## 5. Input pipeline — details

### 5.1 Wake word (openWakeWord)

- Runs permanently inside the foreground service as one more `FrameSink` on the shared audio stream (§2 invariant 4).
- **80 ms frames — 1280 samples at 16 kHz.** This is the number the whole audio path is cut to: openWakeWord's melspectrogram front-end wants multiples of 80 ms, longer frames trading latency for efficiency, and Vosk is indifferent to chunk size. The wake word cannot choose; Vosk can. So `AudioSource.FRAME_LENGTH` is openWakeWord's.
- Three stages, of which only the last is per-wake-word: melspectrogram → frozen Google speech-embedding backbone → a small classifier head. Adding a second wake word later is another ~200 KB head on the same backbone, not another pipeline.
- On detection: play a short earcon, wake screen (§7.2), switch pipeline state `LISTENING`.
- **Verify before trusting it.** A wake word trained purely on synthetic speech is the one component here whose quality cannot be predicted from the code. Measure two things on the real device in the real room: false rejects (say "Hey Dobby" 50 times, count misses) and false accepts (leave it running through an evening of normal conversation and music, count spurious wakes). Tune the detection threshold from those numbers, not from a feeling.

### 5.2 STT (Vosk)

- Stream frames into `Recognizer` until Vosk endpointing signals silence (~1 s) or a 10 s hard cap.
- Use **unconstrained decoding** (no grammar) — song titles and station names are open vocabulary. Accept that English titles come out phonetically mangled ("bleinding leitz"); raw slot text is forwarded to the Sock, which is expected to be tolerant.
- Normalize the final transcript: lowercase, strip punctuation, collapse whitespace, map German number words → digits ("zehn" → 10). Normalization is **core**, not per-Sock, so every Sock sees the same shape of text.

### 5.3 Tier 1 — templates + slots (build first, must stand alone)

- Each Sock ships its templates in `CommandSpec.templates`, written in a small DSL with optional parts, alternations and named slots:
  - `(spiele|spiel) {query}( ab)?`
  - `(stell einen )?timer( auf)? {n:int} (minuten|sekunden)`
- The registry compiles all templates from all Socks into one table and matches them **token-by-token with backtracking**, anchored at both ends. *(Originally specified as anchored regexes with named groups; regexes cannot express Levenshtein tolerance, so that would need a second verification pass over every keyword position — more machinery, not less.)*
- First match wins. Ordering is **specific → generic**, by: closed templates before ones ending in an open text slot; then more literal keywords; then fewer text slots; then registration order. The third key is load-bearing — it is what sends "mach das radio an" to Radio rather than to Spotify's `(mach|leg|spiel) {query} (an|auf)`.
- Templates are written against the **normalized** text, so no capitals, no punctuation and **no hyphens**: the normalizer splits "U-Bahn" into two tokens, so the template must say `u bahn`.
- **Fuzzy keyword matching:** literal keywords match within a Levenshtein tolerance of 0 (≤ 3 chars), 1 (≤ 7) or 2 (longer). Short words get zero deliberately — at distance 1 "an" also matches "aus", "am" and "in". Slot content is captured verbatim; only a constrained `{x:enum}` slot is fuzzy, at tolerance 1, which is what folds "minute" into the `minuten` enum value.
- **Edit distance does not catch phonetic slips.** The plan's own motivating example, "schbiele" for "spiele", is distance 3 — no tolerance that catches it is safe for a six-letter word. Catching that class needs phonetic matching (Kölner Phonetik) as a second comparison; see §9.
- **Static params:** a template may fix params by its wording (`viel lauter` → `steps=5`, `ton aus` → `state=an`). Without this, the handler would have to re-parse German that the template already disambiguated.
- **Ambiguity is a build-time error — unless it is shared:** the registry's test suite asserts that no two Socks claim the same utterance for *different* commands. Two Socks contributing templates to the same `shared.*` id is expected and exempt. Adding a Sock that shadows another's exclusive command fails CI.
- The resolution for a genuinely ambiguous word is therefore always the same: **promote it to a shared command** (§3.4), never re-word one Sock's templates to dodge the other.
- ~150 lines of pure Kotlin for the matcher itself. HA's `hassil` proves the mechanism at scale.

### 5.4 Tier 2 — local LLM with grammar-constrained decoding (later milestone)

- Only invoked when Tier 1 returns null.
- The system prompt is **generated** from the registry: every `CommandSpec.description` becomes a tool definition, every `Example` a few-shot line. Adding a Sock automatically teaches the LLM its commands.
- The **GBNF grammar** is likewise generated — one branch per command, so params are constrained per command and the model cannot emit an unknown command or a malformed param set:

```
root           ::= cmd_play_music | cmd_stop | cmd_set_timer | … | cmd_none
cmd_play_music ::= "{\"command\":\"spotify.play_music\",\"params\":{\"query\":" string "}}"
cmd_stop       ::= "{\"command\":\"shared.stop\",\"params\":{}}"
cmd_set_timer  ::= "{\"command\":\"clock.set_timer\",\"params\":{\"amount\":" int ",\"unit\":" unit "}}"
cmd_none       ::= "{\"command\":\"none\",\"params\":{}}"
```

- `none` is core's own command, always present, and means "no Sock claims this".
- A shared command emits **one** branch no matter how many Socks subscribe — the chain, not the LLM, decides who acts.
- **Prompt caching:** persist the KV cache of the static system prompt at model load; per request, only the utterance is prefilled. Expected end-to-end on Nord CE CPU: **~2–4 s** (vs. 3–6 s uncached). Generation ~8–12 tok/s at 1.7B Q4; output is ~30 tokens. The cache must be **invalidated whenever the registry changes** (Sock added/removed/updated) — key it on a hash of the generated prompt.
- Load the model **once** at service start, keep resident (mmap). If memory pressure proves fatal, make Tier 2 lazy-load + idle-unload — measure first.
- **Flywheel:** log every utterance that fell through to Tier 2 together with the resolved command. Periodically promote frequent phrasings into new Tier 1 templates **in the owning Sock's spec**.
- **Examples serve two jobs and must be marked as such.** Most `Example`s are Tier 1 regression cases — the spec's utterance tables, asserted on every registry build. Some are Tier 2 few-shots: paraphrases Tier 1 is *supposed* to miss, which is the entire reason this tier exists. The latter carry `matchedByTemplates = false` so they reach the system prompt without failing the collision gate.

---

## 6. Module structure

```
app/
  core/
    sock/          Sock, CommandSpec, ParamSpec, SockContext, SockResult, SockActivity,
                   SharedSubscription, SharedCommands (catalog), SockRegistry
    dispatch/      Dispatcher, ChainDispatcher (activity ranking), param validation/coercion
    playback/      PlaybackCoordinator
    screen/        ScreenController
  pipeline/
    audio/         AudioRecord owner, frame router
    wakeword/      openWakeWord wrapper (ONNX Runtime)
    stt/           Vosk wrapper, normalizer (numbers, lowercase)
    nlu/
      templates/   Tier 1: template DSL, compiler, fuzzy matcher  ← pure Kotlin, heavily unit-tested
      llm/         Tier 2: llama.cpp JNI, GBNF + prompt generator, KV cache
  socks/
    spotify/       ← one package (later: one Gradle module) per Sock, spec'd in socks.specs/
    radio/
    clock/
    system/
    departures/
  service/         ForegroundService, wake locks, boot receiver, watchdog
  ui/              Compose dashboard shell + Sock dashboard cards, settings
  native/          llama.cpp CMake build (arm64-v8a only)
```

**Testing focus:**
- `pipeline/nlu/templates` — table-driven suite (`utterance → expected CommandInvocation`) over the *combined* palette, including misrecognitions collected from logs.
- Each Sock — its own table-driven suite from its spec's utterance table, plus handler tests against fixtures (recorded JSON, fake `SockContext`).
- `core/sock` — registry validation: duplicate ids, prefix mismatch, template collisions across Socks, mismatched shared-command params.
- `core/dispatch` — **chain scenarios**, expressed as a matrix of (who is ACTIVE) × (utterance) → (expected consumer). This is the suite that catches "stopp stopped the wrong thing", plus: nobody consumes → `unconsumedResponse`; a Sock throws → chain continues and the Sock is marked `Degraded`; an `INACTIVE` Sock performs I/O → test fails.
- Everything Android-flavored stays thin.

---

## 7. Always-on Android integration (the fiddly 20%)

### 7.1 Service skeleton

- One `ForegroundService`, `android:foregroundServiceType="microphone|mediaPlayback|dataSync"`, persistent notification. It hosts the pipeline and the SockRegistry.
- **Mic rule (Android 12+):** the service must be **started while an activity is in the foreground** to retain mic access after screen-off/backgrounding. Flow: launch app → tap Start → service starts → screen may turn off, listening continues.
- `PARTIAL_WAKE_LOCK` held for the service's lifetime (CPU on, screen off). Legit here: wall-mounted, permanently powered.

### 7.2 Screen control

- Default: screen off. On wake word / Sock request / active playback change: wake screen for 30 s via `SCREEN_BRIGHT_WAKE_LOCK | ACQUIRE_CAUSES_WAKEUP` (deprecated but pragmatic) or activity `setTurnScreenOn(true)` + `setShowWhenLocked(true)`. Owned by `ScreenController`, exposed to Socks via `SockContext`.
- Disable the lock screen on the device (Settings → Security → None) so no keyguard sits between wake and UI.
- Dashboard uses a pure-black dark theme (OLED).

### 7.3 Boot & death resilience

- `BOOT_COMPLETED` receiver → post a high-priority notification "Tap to start listening" (full mic auto-start after reboot is framework-blocked; one tap after the rare reboot is the accepted tradeoff — do not fight this).
- Service `START_STICKY`; on crash, Android restarts it, but mic may be dead → detect and surface the same "tap to start" notification.
- Watchdog: a `WorkManager` periodic job (15 min) checks service health and re-posts the notification if the pipeline is down.
- **A failing Sock must not take down the pipeline.** `handle()` runs inside a supervisor scope with a timeout; an exception or timeout becomes `Failed(...)` → spoken error, and the Sock is marked `Degraded`.

### 7.4 OxygenOS 13 device checklist (manual, document in README)

1. App info → Battery → **Unrestricted**.
2. Battery → Advanced settings → **Sleep standby optimization: off**.
3. Recents → long-press app card → **Lock**.
4. Disable auto-launch restrictions for the app (app management), if present.
5. Lock screen: **None**; Display timeout short (screen control is app-driven).
6. Charging: device is permanently plugged in → battery swelling risk. If the ROM offers a charge limit (~80 %), enable it; otherwise put the charger on a smart plug later and cycle 40–80 %. Expect the device to run slightly warm (continuous wake-word CPU load) — normal.

---

## 8. Milestones

Each milestone ends in a runnable, demoable state. Build in this order.

**M1 — Core + first Sock (the dopamine milestone).**
Sock API (incl. `SockActivity` / `SharedSubscription` / `NotForMe` — declared now, exercised in M3), SockRegistry, Dispatcher, Tier 1 template compiler/matcher. Activity with push-to-talk button → Vosk STT → Tier 1 → dispatch → **Spotify Sock** + TTS feedback. No wake word, no service.
*Done when:* "Spiele Blinding Lights von The Weeknd" works from a button press, and the Spotify Sock contains every Spotify-specific line of code in the project.

**M2 — Hands-free.**
openWakeWord as a second `FrameSink` on the shared `AudioSource` + earcon. Screen-off listening with `PARTIAL_WAKE_LOCK`. The foreground service, the mic owner and the frame router already exist (Phase B), so this milestone is the wake word itself and the `LISTENING` branch in `VoicePipeline` — plus training "Hey Dobby" and measuring it (§5.1).
*Done when:* wake word → command works with screen off, false rejects and false accepts have been measured in the actual room, and 24 h passes without the service dying (after §7.4 checklist).

**M3 — Clock + System Socks, and the chain.**
Two more Socks, both dependency-free, to prove the registry composes: timers with TTS/chime, time-of-day answer, volume/mute/screen. German number-word normalizer in core. **`ChainDispatcher` goes live** with the first real chain: `shared.stop` across Spotify and Clock.
*Done when:* "Timer zehn Minuten", "Wie spät ist es", "Lauter" all work; "Stopp" silences a ringing timer while music keeps playing, and pauses the music when no timer is ringing; and adding a Sock required touching only `DobbySocks.all`.

**M4 — Radio + Departures Socks.**
`PlaybackCoordinator` (Radio vs. Spotify arbitration), ExoPlayer, Wiener Linien client with fair-use polling discipline. Radio joins the `shared.stop` / `shared.resume` chains — the three-way case.
*Done when:* radio and Spotify never overlap; "Wann fährt der nächste Bus" answers correctly; and "Stopp" resolves correctly in all six active/idle combinations of Spotify, Radio and Clock.

**M5 — Dashboard.**
Compose shell + per-Sock dashboard cards (clock, departure board with attribution, timer countdown, now playing). Screen wake/sleep choreography (§7.2).
*Done when:* wall-panel UX is complete without the LLM.

**M6 — LLM fallback tier.**
llama.cpp JNI build, Qwen3 1.7B Q4_K_M download-on-first-run, **generated** GBNF + few-shot German system prompt from the registry, KV-cache persistence + invalidation, Tier 2 wiring + fallthrough logging.
*Done when:* "Kannst du bitte irgendwas Ruhiges von Queen anmachen" resolves to `spotify.play_music(query="queen ruhig")` in ≤ 4 s; malformed or unknown-command output is impossible by construction.

**M7 — Hardening.**
Boot notification flow, watchdog, per-Sock failure isolation and reconnect logic, thermal observation, log-based template promotion, README with the OxygenOS checklist.
*Done when:* one week of unattended uptime.

---

## 9. Risks & accepted tradeoffs

- **Mic after reboot:** framework-blocked without a foreground activity start → one manual tap per reboot. Accepted.
- **English song titles through German STT:** phonetic garbage forwarded to Spotify search; works surprisingly often, not always. Upgrade path (not now): Whisper for the query slot only.
- **The wake word is trained on speech nobody ever spoke.** openWakeWord's custom models are built from Piper TTS output plus augmentation, which is what makes "Hey Dobby" free to create — and also means its real-world accuracy is unknown until measured. A wake word that misses is a panel that ignores you; one that fires too easily is a panel that listens to the television. Mitigation is measurement, not design: §5.1 fixes what to count before the detection threshold is chosen. Fallback if synthetic training proves inadequate for a German-accented phrase: sherpa-onnx KWS (open-vocabulary, no training at all, larger model), or pick a wake word whose phonetics the synthetic voices handle well.
- **The wake-word models are non-commercial.** openWakeWord's pre-trained feature extractor is CC BY-NC-SA 4.0, and a custom head inherits that. Irrelevant to a wall panel in one flat; a hard stop if Dobby ever becomes something you hand to other people. Accepted knowingly, recorded here so it is not rediscovered late.
- **Phonetic STT slips on *keywords* are not covered.** Levenshtein handles a dropped or doubled letter; it cannot reach "schbiele" from "spiele" (distance 3). If the Vosk spike shows this failure mode is common, add a Kölner-Phonetik comparison alongside the edit-distance one in the keyword matcher — a contained change in `nlu/template`, and the reason that matcher is isolated and pure. Measure before building it.
- **LLM latency (2–4 s) and RAM (~1.1 GB resident):** acceptable because Tier 2 is rare; if OxygenOS memory pressure kills the service, demote Tier 2 to lazy-load or drop to a 1B model.
- **Palette growth:** every new Sock enlarges the Tier 1 regex table (cheap) *and* the Tier 2 system prompt (not cheap — prefill time and KV cache size grow with it). Budget: keep the generated system prompt under ~1500 tokens; past that, shard the prompt by Sock or route Tier 2 through a two-step (pick Sock → pick command).
- **Template collisions between Socks:** mitigated structurally by shared commands (§3.4) plus the registry collision test (§5.3), but it still means Sock specs must list their utterances exhaustively.
- **The chain picks the wrong Sock.** The failure is invisible in code review and obvious in daily use ("stopp" killed the radio instead of the alarm). Mitigations: activity ranking is declarative and unit-tested as a matrix; every chain run logs its candidates and their reported activity; `activityFor` is a pure state read, so a mis-ranking is always reproducible from a log line. Accepted residual risk: a Sock that reports its own activity wrongly will misbehave and only integration use will reveal it.
- **`activityFor` on the hot path:** it runs for every subscriber on every shared invocation, before anything happens. An implementation that blocks (a Binder call into the Spotify app, a lock) adds latency to the most latency-sensitive command in the product. Hence the no-I/O contract and the test that enforces it.
- **Wiener Linien fair use:** violation risks IP blocking → polling discipline is a functional requirement, not a nicety.
- **Hardware wear:** OLED burn-in (mitigated: screen off + black theme), battery swelling (mitigated: charge limiting), thermal throttling under sustained LLM load (measured in M6/M7).

## 10. Explicit non-goals (v1)

No Home Assistant / device control (the Sock seam keeps this open as a future Sock), no dynamic/third-party Sock loading or a Sock marketplace, no multi-room audio, no wake word other than "Hey Dobby" (the architecture allows more heads on the same backbone; the product does not need them), no cloud NLU, no multiple simultaneous timers, no iOS/tablet variants.
