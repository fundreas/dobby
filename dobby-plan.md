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
 │  [2] STT — Silero VAD finds the end of the utterance, Parakeet TDT transcribes the buffer │
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
4. **Single mic owner.** One `AudioRecord` instance feeds both the wake word and the STT capture. The wake word consumes frames until it fires; then it comes **off** the stream and frames are routed to the capture buffer and the VAD until the utterance ends, and it goes back on only once the turn is finished — dispatched, answered and spoken. Never open two recorders, and never score command audio or Dobby's own voice against the wake phrase. Socks never touch the mic.
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

    /** A question this Sock asked will not be answered. Free what Asked reserved. §3.4a */
    suspend fun onAskCancelled(token: String) {}

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

- `CommandInvocation` = `commandId: String` + `params: Map<String, Any>` + `answering: String?` (the follow-up token this utterance answers, §3.4a), already type-coerced and validated against `ParamSpec` before it reaches the Sock.
- `SockResult` = `Spoken(text)` | `Silent` | `Deferred` (Sock will speak later, e.g. timer expiry) | `Failed(userMessage, cause)` | **`Asked(text, follow)`** (a question that holds the floor, §3.4a) | **`NotForMe`** (chain only, §3.4). Core turns it into TTS + UI state. **A Sock never calls TTS directly for command acknowledgement** — it returns a result. It may push asynchronous announcements via `ctx.announce(text)`.
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

### 3.4a Follow-up questions — one Sock holding the floor

§3.4 resolves "who should act". This resolves "what did they actually say": "Stell einen Timer auf zehn" — ten what? A Sock that is missing one thing returns `SockResult.Asked(text, FollowUp(commandId, templates, params, token))` instead of a statement.

Core speaks the question like any other answer, keeps the microphone open, and compiles the follow-up templates into a **scoped palette** that exists for the rest of the turn and is never registered. The next utterance is matched against that palette first; a hit is delivered to the **asking Sock directly** — past `ownerOf`, past the chain — as a second `handle()` call carrying `answering = token`.

- The asking Sock **never blocks**. There is no waiting instance: it keeps its half-built state under the token and is called again.
- A miss on the scoped palette falls through to the global one, which abandons the question (`onAskCancelled(token)`) and runs the command. Without that escape hatch a person stuck inside "Meinst du …?" could not say "stopp".
- A miss on both leaves the question standing and answers not-understood as usual.
- The pending ask lives in `DobbyEngine`, not in the Android controller, so the terminal harness and the typed path get a dialogue turn for free — a whole conversation is testable with no device.
- **It is scoped to the turn, never to wall-clock time.** `endTurn()` cancels it. A question surviving into the next turn would let the wake word plus "ja" three minutes later fire whatever was half-built.
- Two bounds: `MAX_CLARIFY_ROUNDS` still counts unmatched utterances, and `MAX_ASK_DEPTH` (2) bounds questions that follow answers, so a buggy Sock cannot interrogate the room.

Contract and copy rules: [`socks.specs/README.md` §5](socks.specs/README.md).

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
| Clock | [`clock.specs.md`](socks.specs/clock.specs.md) | `set_timer`, `cancel_timer`, `cancel_all_timers`, `timer_remaining`, `whats_the_time` | `shared.stop` |
| System | [`system.specs.md`](socks.specs/system.specs.md) | `volume`, `mute`, `turn_on_screen`, `turn_off_screen` | — |
| Departures | [`departures.specs.md`](socks.specs/departures.specs.md) | `departures` | — |
| *(catalog)* | [`shared-commands.specs.md`](socks.specs/shared-commands.specs.md) | — | `shared.stop`, `shared.resume` |

---

## 4. Tech stack

- **Language/UI:** Kotlin, Jetpack Compose, single-activity. Coroutines + `StateFlow` for pipeline → UI state.
- **minSdk 33** (device is Android 13), targetSdk = latest stable. `arm64-v8a` only.
- **Wake word:** [openWakeWord](https://github.com/dscripka/openWakeWord) via ONNX Runtime. ~1.3 MB per wake phrase on top of a 2.4 MB shared frozen feature extractor (measured from the v0.5.1 release); a single Raspberry Pi 3 core runs 15–20 of them in real time, so one on a Nord CE is free. The runtime itself is the real cost: `onnxruntime-android` adds ~32 MB of arm64 native library to the APK, which is a lot for a sideloaded panel and nothing at all for the device holding it. Custom "Hey Dobby" is trained from **synthetic** TTS audio in a Colab notebook (<1 h, no development experience) — no recording sessions, no console, no account, no key, nothing that can be switched off from outside.
  - *Licence:* code is Apache-2.0; the **pre-trained models are CC BY-NC-SA 4.0** because of their training data, and a custom model sits on top of that frozen extractor. Non-commercial is exactly what this project is, but it is a hard constraint on ever shipping Dobby.
  - *Language:* openWakeWord officially supports **English only** — its synthetic training voices are English. This is less of a problem than it sounds for "Hey Dobby", which is an English-shaped phrase either way, and the Home Assistant community has trained working Danish, Finnish, Russian and Chinese models with the same notebook. It does mean the training data will be English voices saying the phrase, while the person saying it will be an Austrian one; §5.1's measurement is where that gets settled.
  - *(Was Porcupine. Picovoice discontinued its free tier on 2026-06-30 and disabled existing `AccessKey`s — and the SDK refuses to initialise without a valid one, so the plan's original choice stopped being viable rather than merely getting more expensive.)*
- **STT:** [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (Kotlin API, package `com.k2fsa.sherpa.onnx`), CPU inference, `arm64-v8a`. Two models on top of it:
  - **ASR:** NVIDIA **Parakeet TDT 0.6B v3**, int8, as the `sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8` conversion (~670 MB: a 622 MB encoder, a 12 MB decoder, a 6 MB joiner and a 92 KB token table). Multilingual across 25 European languages, German and English among them, which is the whole reason for the switch: one model that hears "Spiele Blinding Lights von The Weeknd" as German with an English title in it, rather than a German model spelling the title phonetically. Configured as an **offline (non-streaming) transducer** with `model_type = "nemo_transducer"` — the same files under plain `transducer` load, run, and return confident nonsense, because TDT decodes in a different loop.
  - **Endpointing:** **Silero VAD** (~640 KB), through sherpa-onnx. Parakeet is batch, not streaming, so nothing in the recognition path knows where a sentence ends; finding that is now the VAD's job and its own line in the stack.
  - *Packaging:* upstream publishes no Maven artifact. The Kotlin API is vendored verbatim into `:android:sherpa` and the 24 MB `libsherpa-onnx-jni.so` is downloaded and checksum-verified at build time. It is deliberately the **static-link-onnxruntime** build: the ordinary bundle ships its own `libonnxruntime.so`, which would land in the APK beside the one `onnxruntime-android` brings for the wake word. The static build has ONNX Runtime inside it and exports nothing but `Java_com_k2fsa_sherpa_onnx_*`, so the two runtimes cannot see each other — one `libonnxruntime.so` in the APK, and no packaging rules papering over a collision.
  - *Licence:* sherpa-onnx is Apache-2.0; Parakeet TDT 0.6B v3 is CC-BY-4.0; Silero VAD is MIT. Unlike the wake word above, none of this is non-commercial.
  - *(Was Vosk with `vosk-model-small-de-0.15`. 45 MB and streaming partial results against 670 MB and a second of latency is a real trade, and it was made on one number: English song and station names through a German-only acoustic model come out as phonetic garbage, and that garbage is what the Spotify Sock has to search with.)*
- **LLM tier:** `llama.cpp` built for arm64 Android via CMake/NDK (JNI wrapper; start from the official llama.cpp Android example). Model: **Qwen3 1.7B instruct, Q4_K_M GGUF** (~1.1 GB), downloaded on first run to app files dir (do not bundle in APK).
- **TTS:** [Piper](https://github.com/rhasspy/piper) voices through sherpa-onnx's `OfflineTts` — the same `libsherpa-onnx-jni.so` the recogniser uses, with espeak-ng and ONNX Runtime already inside it. **`de_DE-thorsten-high`** is the default voice and **`en_GB-cori-high`** the second; 114 MB each, a single VITS graph plus a token table, downloaded on first run like every other model and never bundled. espeak-ng's 355-file phoneme directory is the exception: 7 MB, shared by every voice, fetched at build time and shipped as an asset, because espeak-ng opens it with `fopen` and an APK asset has no path.
  - *Fallback:* the Android built-in `TextToSpeech` at `de_AT`/`de_DE` stays as a catalogue entry, not as dead code. It is the voice that speaks on first run while Thorsten downloads, the one that covers a sentence when the graph has been freed under memory pressure, and the way back if espeak-ng reads something worse than Google did.
  - *Licence:* Thorsten is **CC0** (Thorsten-Voice); Cori is **public domain** (LibriVox, trained by Bryce Beattie). Unlike the wake-word heads, nothing here is non-commercial.
  - *Language:* the voice does not change **what** is said — every Sock answers in German (§1), so Cori reads German sentences with English phonemes. A language setting that selects the answer language, with the voice following it, is the follow-up (`m2c-plan.md` Part E).
- **Shared plumbing available to Socks:** OkHttp + kotlinx.serialization, Media3 ExoPlayer, AlarmManager, WorkManager.

Sock-specific dependencies (Spotify SDKs, etc.) are declared in the Sock's own spec and, where practical, in its own Gradle module.

---

## 5. Input pipeline — details

### 5.1 Wake word (openWakeWord)

- Lives inside the foreground service as one more `FrameSink` on the shared audio stream (§2 invariant 4). Armed whenever a turn is not in flight, which is almost always — it steps off the stream for the duration of each one and back on when the turn is finished.
- **80 ms frames — 1280 samples at 16 kHz.** This is the number the whole audio path is cut to: openWakeWord's melspectrogram front-end wants multiples of 80 ms, longer frames trading latency for efficiency, while the STT side buffers whatever it is given and Silero VAD re-cuts it into its own 512-sample windows internally. The wake word cannot choose; nothing downstream needs to. So `AudioSource.FRAME_LENGTH` is openWakeWord's.
- Three stages, of which only the last is per-wake-word: melspectrogram → frozen Google speech-embedding backbone → a small classifier head. Adding a second wake word later is another ~200 KB head on the same backbone, not another pipeline.
- On detection: **acknowledge**, wake screen (§7.2), switch pipeline state `LISTENING` — and **take the detector off the audio stream** until the turn ends (§2 invariant 4). Command audio is not wake-word audio, and a detector left running through the utterance and through Dobby's spoken answer is one threshold away from a panel that wakes itself.
- **How it acknowledges is a setting, because it cannot be a default.** Between the wake word firing and the panel having anything to show there is a second in which the only honest question is "did it hear me?", and it has to be answered before then. *How* is not a question code can settle: a kitchen at midday wants a sound, a bedroom at half past five in the morning is exactly where a sound is the reason a thing gets unplugged. So `ListenCue` offers three: **Vibrieren** (one 80 ms pulse at full amplitude — the default, silent to the room and unmistakable through the bracket), **Ton** (an 80 ms pip; the only one that carries across a room), **Nichts** (the screen coming on is the acknowledgement). Chosen in settings, stored by name, read on the audio thread at the moment of detection.
- **Five seconds to start talking.** The microphone does not stay open for the ten-second cap waiting for a voice that is not coming: a wake word that fired at the television is the common case, not the rare one. No speech inside the window and the turn ends, the wake word goes back on the stream, and the panel is asleep again. It is a deadline on *starting*, so a slow sentence is never cut off by it.
- **One wake word, as many instructions as the conversation needs.** The window reopens after *everything Dobby handled*, not just after a question or a buzz: "wie spät ist es", then "stell einen Timer auf zehn Minuten", without saying the name again. That is the difference between talking to a panel and operating it, and it costs nothing — the turn still ends the moment the room goes quiet for five seconds. Three things end one early: **silence**, **`SockResult.Ended`** — a Sock saying that the conversation is over: because what it just answered was a finished sentence, "Gute Nacht", so holding the microphone open would be a panel that did not take the hint, or because it was told so outright, which is the whole of the Conversation Sock ("ok", "danke", "passt schon" → the wake word, now) — and **three utterances nothing could match**, which is also what ends a turn that a television rather than a person is feeding. A question asked back (`SockResult.Asked`, §3) widens the window to eight seconds until it is answered, because the pause before a reply is somebody reading the choice back to themselves.
- **Verify before trusting it.** A wake word trained purely on synthetic speech is the one component here whose quality cannot be predicted from the code. Measure two things on the real device in the real room: false rejects (say "Hey Dobby" 50 times, count misses) and false accepts (leave it running through an evening of normal conversation and music, count spurious wakes). Tune the detection threshold from those numbers, not from a feeling.

### 5.2 STT (sherpa-onnx + Parakeet, VAD-endpointed)

Parakeet is a **batch** recogniser: it sees a finished utterance and answers once. That single fact is what shapes this stage — capture first, decide the end, then transcribe.

1. **Capture.** The wake word fires (or the button is pressed); the detector comes off the stream and every 16 kHz mono frame is appended to a capture buffer instead, converted to −1..1 floats.
2. **Endpoint.** Silero VAD watches the same frames. The utterance ends on **~800 ms of trailing silence**, or on a **10 s hard cap** when that silence never comes — a VAD that never hears speech never ends a segment, and a panel in a room with a television meets that case regularly. If the VAD heard no speech at all, there is nothing to transcribe and the turn is dropped without running the recogniser.
3. **Transcribe.** The completed buffer goes to the offline recogniser in one call. Decoding is **unconstrained** (greedy, no grammar): song titles and station names are open vocabulary, so a grammar here would be worse than none.
4. **Normalize.** Unchanged, and still **core** rather than per-Sock: lowercase, strip punctuation, collapse whitespace, map German number words → digits ("zehn" → 10). Every Sock sees the same shape of text.

The interface above this stage is very nearly the one it has always been — `suspend fun listen(openFor: Duration): String?`, one utterance in, one transcript out. The one addition is a deadline on the *start* of speech (§5.1's five seconds); once a voice is heard it stops applying and the endpoint decides as usual. Nothing in the NLU layer, the dispatcher or any Sock knows which engine produced the string.

**When nothing matched: two buzzes, then five more seconds.** An utterance Tier 1 could not match used to be answered by speaking "Das habe ich nicht verstanden." It is the worst sentence Dobby says: two seconds to deliver one bit, always the same, and delivered over the top of the person already repeating themselves. So it is not spoken. Instead the panel buzzes twice (~200 ms, `Haptics` — two pulses, against the one longer pulse that means *listening*, so the two are told apart by length without looking) and leaves the microphone open for the same **5 s** as any other utterance, for the sentence to start again — no second wake word, because someone who has just been buzzed at is already talking. Silence in that window ends the turn and the wake word goes back on the stream. The chat still shows the sentence, because the chat is the log of what happened and a buzz leaves no trace on it; this is the one place where what is shown and what is said deliberately differ. Bounded at **3 attempts** per turn: a television is a speaker that never runs out of unmatched sentences, and without a bound it would hold the microphone open all evening.

**What the swap costs, stated plainly:**

- **No partial results.** There is no running guess to stream into the chat bubble, because the model has not seen the sentence yet. What the panel can honestly show instead is whether the VAD hears a voice right now, and then that the recogniser is running (`LISTENING` → `TRANSCRIBING`). From three metres away "I can hear you" is the signal that matters; a live transcript was never the one people read.
- **Latency.** Budget: **≤ ~1.5 s** from end-of-speech to transcript for a 3–4 s utterance on the Snapdragon 750G at 4 threads. Four of eight cores, not more — the wake word is scoring on the same CPU and is the component nobody may notice getting slower. Measure it; if it misses, the first lever is threads and the second is the fp16 encoder conversion, not a smaller model.
- **Memory.** Parakeet resident plus Qwen3 1.7B resident is a **~2 GB** budget (§9). The weights are mapped, not copied, so most of it is page cache the kernel can evict. If OxygenOS memory pressure becomes real, **the LLM tier goes lazy-load first**; STT is never downgraded. Tier 2 is a rare fallback and Tier 1 covers every declared command — a slow paraphrase is a worse day than a deaf panel.
- **670 MB on first run.** Downloaded, never bundled (§4), into the app files dir beside the LLM GGUF, with per-file SHA-256 verification and one size-weighted progress percentage on the setup screen. Granularity is the file: a run that dies inside the encoder keeps the four small files and re-fetches only that one.

**How this stage is verified.** Three levels, because no one of them can see what the others can:

- **Unit (off-device, every build).** The normalizer's existing table tests, unchanged — they are the contract that the engine swap was allowed to happen underneath. Plus the two pieces of new arithmetic that fail silently: the PCM conversion and the 10 s cap, and the weighting of the first-run progress percentage.
- **Integration (on-device, `androidTest`).** Recorded 16 kHz German WAVs, one per Tier-1 template, run through the real recogniser and the real palette: each must transcribe to text that matches the template it was recorded for. This is the suite that catches a wrong `model_type`, a wrong feature dimension, or a normalizer that has drifted from what the templates expect — none of which throw. It is skipped when the model is not on the device, so it is opt-in by having done the download, not by a flag.
- **Manual.** Mixed German/English music commands against Spotify search, compared against the Vosk baseline. Subjective on purpose: "the title came out as the title" is the thing being bought, and it has no unit test.

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

### 5.4 Tier 2 — local LLM with grammar-constrained decoding

*Built in M6 (single shot) and split in M6b (route, then fill). This section describes what
exists; `m6-plan.md` and `m6b-plan.md` carry the reasoning.*

- Only invoked when Tier 1 returns null, on the **first utterance of a turn only**, under one
  5 s deadline, and the panel behaves exactly as it did without it whenever anything fails.
- Both prompts and both grammars are **generated** from the registry, so adding a Sock teaches
  the LLM its commands and removing one makes them unreachable in the same breath.

**Two steps, because there are two decisions.** One prompt that asks a 1.7B model for the
command *and* its parameters reached 1426 of 1500 estimated tokens at eleven commands, with
seven Socks still to come. So:

*Step 1 — route.* One line per command, no parameters. The model answers with a bare command
id, or `none`. The grammar is a flat alternation of literals:

```
root ::= "clock.set_timer" | "clock.whats_the_time" | … | "shared.stop" | "none"
```

The label **is** the command id: it is a hint a numbered list cannot give, the gate's lookup
does not change, and there is no second vocabulary to keep in sync.

*Step 2 — fill*, and only for a command that has parameters. A user turn carrying that one
command's spec and one worked example, and a grammar for its params object with an escape:

```
root   ::= "{\"amount\":" int ",\"unit\":" p-unit "}" | "none"
p-unit ::= "\"sekunden\"" | "\"minuten\"" | "\"stunden\"" | "null"
```

The `"none"` escape is load-bearing: once the command has been chosen, the model would
otherwise be *forced* to invent a duration for "stell einen timer" said without one.

Most commands have no parameters, so for them step 1 is the whole answer — no second request,
no second prefill, no second decode.

- Rule names use **hyphens**: llama.cpp's `is_word_char` accepts `[a-zA-Z0-9-]` and not `_`.
  Verified against the pinned source both ways by `android/llama/tools/gbnf_check.cpp`.
- `none` is core's own command, always present, and `SockRegistry.build` rejects any Sock that
  declares it — so the label has exactly one meaning.
- A shared command is **one** label no matter how many Socks subscribe: the chain, not the LLM,
  decides who acts.
- **The gate, not the grammar, is the guarantee.** `:core` cannot verify that a grammar was
  applied, so every reply goes back through `registry.commands[label]` and `ParamCoercion` —
  the same function Tier 1's palette uses. The LLM gets no route into a Sock that a template
  does not also have.
- **Prompt caching:** only the route prefix is prefilled, once per *process*, and the KV cache
  is truncated back to it at the start of every request — no snapshot and no file, because a
  ~1100-token prefix snapshots to ~120 MiB. A fill turn is uncached by design; its tail replays
  the route exchange rather than continuing a warm cache, which keeps the truncate-first
  invariant that makes a cancelled request safe. The cache is keyed on a hash of both prompts
  and both grammars, so any registry change re-prefills.
- Load the model **once** at service start, mmapped, on its own coroutine after the microphone
  is up. `onTrimMemory(RUNNING_CRITICAL)` gives back the ~280 MiB context and keeps the
  weights; the context re-prefills in the background.
- **Flywheel:** every utterance that fell through is logged with what Tier 2 made of it, in a
  bounded in-memory ring. Frequent phrasings get promoted into Tier 1 templates **in the owning
  Sock's spec** — or into a held-out accuracy case, which is the same discipline pointed at the
  model instead of at the matcher.
- **Examples serve three jobs and must be marked as such.** Most are Tier 1 regression cases,
  asserted on every registry build. Some are Tier 2 few-shots (`matchedByTemplates = false`):
  paraphrases Tier 1 is *supposed* to miss, which reach the prompts without failing the
  collision gate. Some are held-out accuracy cases (`heldOut = true`): never shown to the model
  at all, because an accuracy test over the few-shots measures memorisation.
- **Measured / not yet measured.** The token budgets are measured on the JVM and enforced by
  `PromptBudgetTest`. Everything about *speed* — decode rate, prefill rate, thread count,
  mlock, end-to-end latency — is still inherited rather than measured on a 750G; the suites in
  `android/llama/src/androidTest` and `android/app/src/androidTest` are what replace those
  numbers, and `android/llama/README.md` lists which they are.

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
  sherpa/          vendored sherpa-onnx Kotlin API + fetched libsherpa-onnx-jni.so (no Maven artifact upstream)
  pipeline/
    audio/         AudioRecord owner, frame router
    wakeword/      openWakeWord wrapper (ONNX Runtime)
    stt/           model store (download + verify), Silero VAD capture, Parakeet recogniser
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
Sock API (incl. `SockActivity` / `SharedSubscription` / `NotForMe` — declared now, exercised in M3), SockRegistry, Dispatcher, Tier 1 template compiler/matcher. Activity with push-to-talk button → STT → Tier 1 → dispatch → **Spotify Sock** + TTS feedback. No wake word, no service.
*Done when:* "Spiele Blinding Lights von The Weeknd" works from a button press, and the Spotify Sock contains every Spotify-specific line of code in the project; **German command transcription is subjectively reliable at 2–3 m distance, and "Spiele Blinding Lights von The Weeknd" yields a usable English title in the query slot** — not a phonetic approximation the Spotify search has to be lucky with.

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
- **English song titles through German STT:** *retired.* This was the accepted cost of Vosk's German-only model, and it is what Parakeet TDT 0.6B v3 was chosen to remove — one multilingual model that transcribes an English title inside a German sentence as the English title (§4). The M1 done-when is the acceptance test.
- **Parakeet is 670 MB and Dobby is deaf until it has arrived.** On a wall panel that is a one-time first-run cost on a fixed wifi, which is why it is acceptable here and would not be in an app anyone installs. Mitigations are all about not paying it twice: per-file SHA-256 so a bad download is caught rather than loaded, file-level granularity so a failure re-fetches one file, and a progress percentage weighted by bytes so nobody force-quits at what looks like a hang.
- **Two ONNX runtimes in one APK.** sherpa-onnx and openWakeWord each bring one. Resolved structurally rather than by packaging rules: the static-link sherpa-onnx build has ONNX Runtime inside `libsherpa-onnx-jni.so` and exports only its own JNI entry points, so the APK holds exactly one `libonnxruntime.so` and the two cannot bind to each other's symbols (§4). Running openWakeWord's own graphs *through* sherpa-onnx would be the other way to get to one runtime, and is not available: sherpa-onnx's JNI exposes ASR, TTS, VAD and KWS classes, not a general ONNX inference API. Verify both detectors on-device after any sherpa-onnx version bump — this is the failure that a unit test cannot see.
- **The wake word is trained on speech nobody ever spoke.** openWakeWord's custom models are built from Piper TTS output plus augmentation, which is what makes "Hey Dobby" free to create — and also means its real-world accuracy is unknown until measured. A wake word that misses is a panel that ignores you; one that fires too easily is a panel that listens to the television. Mitigation is measurement, not design: §5.1 fixes what to count before the detection threshold is chosen. Fallback if synthetic training proves inadequate for a German-accented phrase: sherpa-onnx KWS (open-vocabulary, no training at all, larger model), or pick a wake word whose phonetics the synthetic voices handle well.
- **The wake-word models are non-commercial.** openWakeWord's pre-trained feature extractor is CC BY-NC-SA 4.0, and a custom head inherits that. Irrelevant to a wall panel in one flat; a hard stop if Dobby ever becomes something you hand to other people. Accepted knowingly, recorded here so it is not rediscovered late.
- **Phonetic STT slips on *keywords* are not covered.** Levenshtein handles a dropped or doubled letter; it cannot reach "schbiele" from "spiele" (distance 3). A stronger acoustic model makes this rarer, not impossible. If use shows the failure mode is still common, add a Kölner-Phonetik comparison alongside the edit-distance one in the keyword matcher — a contained change in `nlu/template`, and the reason that matcher is isolated and pure. Measure before building it.
- **LLM latency (2–4 s) and RAM (~1.1 GB resident):** acceptable because Tier 2 is rare. Combined with Parakeet resident the budget is ~2 GB, and the order of retreat under memory pressure is fixed in advance so it is not decided in a panic: **Tier 2 goes lazy-load first**, then drops to a 1B model; STT is never downgraded. Tier 1 covers every declared command without the LLM, so a slow paraphrase costs a second and a deaf panel costs the product.
- **Palette growth:** every new Sock enlarges the Tier 1 regex table (cheap) *and* the Tier 2 system prompt (not cheap — prefill time and KV cache size grow with it). Budget: keep the generated system prompt under ~1500 tokens; past that, shard the prompt by Sock or route Tier 2 through a two-step (pick Sock → pick command).
- **Template collisions between Socks:** mitigated structurally by shared commands (§3.4) plus the registry collision test (§5.3), but it still means Sock specs must list their utterances exhaustively.
- **The chain picks the wrong Sock.** The failure is invisible in code review and obvious in daily use ("stopp" killed the radio instead of the alarm). Mitigations: activity ranking is declarative and unit-tested as a matrix; every chain run logs its candidates and their reported activity; `activityFor` is a pure state read, so a mis-ranking is always reproducible from a log line. Accepted residual risk: a Sock that reports its own activity wrongly will misbehave and only integration use will reveal it.
- **`activityFor` on the hot path:** it runs for every subscriber on every shared invocation, before anything happens. An implementation that blocks (a Binder call into the Spotify app, a lock) adds latency to the most latency-sensitive command in the product. Hence the no-I/O contract and the test that enforces it.
- **Wiener Linien fair use:** violation risks IP blocking → polling discipline is a functional requirement, not a nicety.
- **Hardware wear:** OLED burn-in (mitigated: screen off + black theme), battery swelling (mitigated: charge limiting), thermal throttling under sustained LLM load (measured in M6/M7).

## 10. Explicit non-goals (v1)

No Home Assistant / device control (the Sock seam keeps this open as a future Sock), no dynamic/third-party Sock loading or a Sock marketplace, no multi-room audio, no wake word other than "Hey Dobby" (the architecture allows more heads on the same backbone; the product does not need them), no cloud NLU, no multiple simultaneous timers, no iOS/tablet variants.
