# Dobby

A voice-controlled, wall-mounted smart panel for a repurposed OnePlus Nord CE (Android 13 / OxygenOS 13). Wake word, speech-to-text and intent parsing all run on-device.

Dobby core owns no commands. Everything the user can *do* lives in a **Sock** — a self-contained module that registers its commands and executes them.

- [`dobby-plan.md`](dobby-plan.md) — the build spec for core
- [`socks.specs/`](socks.specs/) — one spec per Sock, plus [the contract](socks.specs/README.md) and the [shared-command catalog](socks.specs/shared-commands.specs.md)

## Build & Install
```sh
# Gradle Build
$ ./gradlew :android:app:assembleDebug

# Install Debug Apk
$ adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```


## Status: hands-free (M2)

Phase A was "Dobby in a terminal": everything below the microphone and above the Socks, as pure JVM code. Phase B put it on the phone. M2 makes it hands-free — say the wake phrase and the panel answers, screen off, nothing touched.

**Built and tested, not yet measured on the device.** `dobby-plan.md` §8 calls M2 done when false rejects and false accepts have been counted in the actual room and the service has survived 24 hours. Neither has happened; see [Before you trust it](#before-you-trust-it).

| | |
|---|---|
| `:core` | Sock API, template engine, normalizer, registry, dispatcher + chain. **Pure Kotlin/JVM** — the module boundary is what enforces the plan's "parsing is pure" rule. Publishes test fixtures (`FakeSockContext`) used by the Socks *and* by the Android app. |
| `:socks:clock` | **Clock** — the first product Sock, now complete: kitchen timers with an `AlarmManager` backstop and a `SoundPool` chime, the time of day in Austrian German, and the panel's clock card. Subscribes to `shared.stop`, which it wins only while the chime is ringing. |
| `:socks:help` | **Help** — spoken discovery: "Was kannst du?", "Was kann die Uhr?" |
| `:socks:devi` | Devi, the development Sock. Not a product Sock, and not in a release APK. |
| `:cli` | The terminal harness. Still the fastest way to work on a template. |
| `:android:sherpa` | [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx), packaged: the Kotlin API vendored verbatim, the 24 MB native library downloaded and checksum-verified at build time. Upstream publishes no Maven artifact, so this module is the artifact. |
| `:android:pipeline` | Microphone in, German text out; German text in, sound out. `AudioRecord` owner + frame router, openWakeWord, Silero VAD + Parakeet STT, Android TTS. Knows nothing about Socks. |
| `:android:app` | The foreground service, the Android `SockContext`, the chat view, and the one place that knows which Socks exist. |

Not yet built: the LLM tier, the remaining Socks (Spotify, Radio, System, Departures), and the rest of the dashboard — Clock brought the first card with it. See [`dobby-plan.md`](dobby-plan.md) §8.

## Run it

```sh
./gradlew build                      # 163 tests, all JVM, no emulator
./gradlew :cli:run -q                # the terminal harness
./gradlew :android:app:installDebug  # the phone
```

The Android build needs an SDK with platform 36 and `ANDROID_HOME` set (or `sdk.dir` in `local.properties`).

## On the phone

```
┌──────────────────────────────────┐
│ Dobby              wartet   [⏻] │
│ Sag "Hey Jarvis"                 │
├──────────────────────────────────┤
│                                  │
│           ┌─────────────────────┐│
│           │ wie spät ist es     ││
│           └─────────────────────┘│
│ ┌──────────────────┐             │
│ │ Es ist halb 3.   │             │
│ └──────────────────┘             │
│ clock.whats_the_time ·           │
│ clock CONSUMED                   │
│                                  │
├──────────────────────────────────┤
│               ( 🎤 )              │
└──────────────────────────────────┘
```

Say the wake phrase — a beep, the screen wakes, and it listens. Or tap the microphone, which is the only control on the screen and sized to be hit from across a room. Either way the bubble opens empty and the status line says whether it can hear you, then that it is working out what you said; a second later the transcript lands in the bubble, and Dobby's answer appears and is spoken at the same time.

The switch in the header disarms the wake word. An always-open microphone on a wall needs an off switch you can see from across the room, not a setting three screens deep.

**The line under each answer is the point.** It is the terminal harness's `/trace` output — the resolved command, its params, and every Sock the chain offered it to with the activity each reported. Speech leaves nothing behind, so when "Stopp" silences the wrong thing — the failure [`dobby-plan.md`](dobby-plan.md) §9 calls invisible in review and obvious in daily use — this is the only place you can see it happen.

### The wake word

Three ONNX graphs run on every 80 ms frame: a melspectrogram, Google's frozen speech-embedding backbone, and a small classifier head trained for one phrase. Only the head is per-wake-word, so a second phrase later is another ~1.3 MB file, not another pipeline.

**"Hey Dobby" does not exist yet.** Custom phrases are trained from synthetic speech in [openWakeWord's Colab notebook](https://colab.research.google.com/drive/1q1oe2zOyZp7UsB3jJiQ1IFn8z5YfjwEb) — under an hour on a free GPU, which is not something an app can do at startup. So the panel ships answering to *Hey Jarvis*, and picks up a model you supply the moment you drop one in:

```sh
adb push hey_dobby.onnx /sdcard/Android/data/io.dobby.android/files/wakeword/
```

Restart; no rebuild, no code change. The filename becomes the phrase on screen, because the graph carries no metadata and the file is the only thing that knows.

### Before you trust it

A wake word trained on speech nobody ever spoke has accuracy you cannot predict from the code, only measure. Before relying on it, do the two counts `dobby-plan.md` §5.1 asks for: say the phrase 50 times and count the misses; leave it armed through an evening of television and conversation and count the spurious wakes. Then set `WakeWordDetector`'s threshold and patience from those numbers.

Two more things to know: the pre-trained models are **CC BY-NC-SA** (fine for your own wall, a hard stop for shipping), and ONNX Runtime adds ~32 MB of native library, which took the release APK from 53 MB to 85 MB.

### What Phase B added, and why it looks like this

- **A foreground service, not an Activity.** The panel's screen is off most of the time and the Activity is not; the registry and the Socks' state have to outlive it. The Activity's only privileges are starting the service and showing the chat.
- **The mic rule is load-bearing.** On Android 12+ a service keeps microphone access only if it was started while an Activity was in the foreground (§7.1), so the order is permission → `startForegroundService` → bind, every time. Getting it backwards makes Dobby deaf with no error anywhere, which is why the call lives in `DobbyService.startFrom` with that written on it. One tap per reboot is the accepted trade-off (§9).
- **One `AudioRecord`, many sinks.** `AudioSource` is the only code that touches the microphone; everything downstream is a `FrameSink` fed 1280-sample frames at 16 kHz — 80 ms, openWakeWord's shape, because the wake word needs multiples of 80 ms and nothing downstream cares. Callers add and remove sinks and never start or stop the recorder: with two consumers whose lifetimes overlap, any explicit stop is the bug that takes the wake word down the moment an utterance ends.
- **The wake word sits out the turn.** The moment it fires it comes off the stream, and it goes back on only when the turn is finished — dispatched, answered and spoken. Command audio is not wake-word audio, and a detector left running through your sentence and through Dobby's reply is one threshold away from a panel that wakes itself. The capture sink joins before the detector leaves, so the microphone never closes in the gap.
- **One way through, two ways in.** The wake word does not run its own turn — it emits a signal, and the same `listen()` the button calls picks it up. A second path is how the two drift apart.
- **Speech recognition is batch, and the VAD decides when you stopped.** Parakeet sees a finished utterance and answers once, so there is no running guess to stream into the bubble. Silero VAD watches the same frames the capture buffer gets and ends the utterance on ~800 ms of silence, with a 10 s cap for the times that silence never comes. What the panel shows instead of a live transcript is the honest thing: whether it can hear a voice, and then that it is working.
- **The models are downloaded, not bundled.** 670 MB in the APK is 670 MB in git, in every build and every install, to save one round trip per device — and the same goes for sherpa-onnx's 24 MB native library, which `:android:sherpa` fetches at build time. Everything downloaded is verified against a pinned SHA-256, because a truncated encoder is otherwise a native load failure with no Kotlin stack behind it. The cost is that Dobby is deaf on first run until it finishes, so the download reports one size-weighted percentage into the same status line everything else uses.
- **Nothing in a Sock changed.** `SockContext` got its Android implementations — audio focus, the screen wake lock, SharedPreferences, logcat — and Clock and Help were rebuilt against them untouched. That was the whole bet of the Phase A interfaces, and it is the first place it could have failed.
- **Devi cannot ship.** The app's Sock list pulls development Socks from `DevSocks`, which exists twice: the debug source set returns Devi, the release source set returns nothing and does not even have `:socks:devi` on the classpath.

## Discovery

Dobby can be asked what it can do, two ways.

**By voice**, through the Help Sock — answers are written for the ear, so they name areas and offer at most three things to say:

```
> Was kannst du?
  🔊 Ich habe 3 Bereiche: Devi, Hilfe und Uhr. Frag zum Beispiel: Was kann Devi?

> Was kann die Uhr?
  🔊 Uhr hat 4 Befehle. Sag zum Beispiel: ‚timer zehn minuten‘, ‚timer stopp‘ und ‚wie spät ist es‘. Und 1 weitere.
```

**In the terminal**, for the exhaustive view:

| | |
|---|---|
| `/socks` | every Sock, with status and command count |
| `/commands` | every command Dobby knows |
| `/commands <sock>` | one Sock in detail: params, phrasings, templates in match order |
| `/find <text>` | commands matching a word |
| `/palette` | every template, in the order the matcher tries them |
| `/fallthrough` | utterances Tier 1 could not match |
| `/trace`, `/quit` | |

Both read the same `Introspection` API in `:core`, so the terminal, the app's header line and the generated Tier 2 prompt answer identical questions. `:` also works as a prefix.

## What the tests cover

All 163 tests are plain JVM tests. Nothing needs an emulator, including the wake word.

- `GermanNumbersTest`, `NormalizerTest` — German cardinals, and why `ein` is left alone while `eins` is not.
- `TemplateParserTest`, `TemplateMatcherTest`, `SpecificityTest` — the DSL, backtracking, fuzzy tolerance, palette ordering.
- **`SpecPaletteTest`** — the utterance tables from every file in `socks.specs/`, run against fixture Socks carrying the real templates. This is the collision gate: it is what fails when a new template shadows another Sock.
- `RegistryValidationTest` — every way a Sock can be malformed.
- `IntrospectionTest`, `HelpSockTest` — discovery, including that spoken lists are ordered by display name.
- `GermanTimeTest`, `ClockSockTest`, `ClockTemplatesTest` — the Clock Sock's answers and its utterance tables, including that German "halb 3" means 14:30 and not 15:30, and that "stopp den timer" is a different command from "stopp".
- **`TimerLifecycleTest`** — the whole timer, on a virtual clock: set, replace, cancel before expiry, expiry, cancel while ringing, the chime giving up after 60 s, the range it refuses, and the `AlarmManager` backstop firing for a timer the process slept through. Sixty seconds of chime is one line of `advanceTimeBy`, because `AlarmManager` and `SoundPool` sit behind interfaces.
- **`ClockChainTest`** — the two cases the chain exists for: a ringing chime wins "stopp" and the ducked music comes back to full volume with the music Sock never invoked; a *counting* timer does not, so the music pauses and the timer still fires nine minutes later.
- **`ChainDispatcherTest`** — the chain matrix: activity ranking, priority tiebreaks, `NotForMe` passing, nobody consuming, a Sock that throws, a Sock that hangs.
- **`DobbyControllerTest`** — *(Phase B, extended in M2)* the join, end to end: an utterance goes through the real registry to the real Clock Sock, and the answer is both shown and spoken, as the same sentence. Also that a Sock reaches the `SockContext` built on the Android side, that saying nothing leaves no trace, that typing takes the identical path — and that the wake word takes *that same path*, wakes the screen first, and that losing the wake word model leaves a working push-to-talk panel rather than a broken one. And the shape of a turn: five seconds for a voice to start, whether it is the first utterance or the retry after a not-understood buzz; two buzzes instead of the spoken apology; an answer if the second try lands; a bound, so a television cannot hold the microphone open all evening; and that the wake word's acknowledgement — buzz, pip or nothing — is a setting the pipeline acts on and the screen shows. Testable at all because the hardware sits behind `VoiceIo` and the context behind a factory — the Phase A trick, one layer up.
- **`TranscriptTest`** — *(Phase B)* the chat model's awkward parts: a live bubble becoming a transcript in place, an answer replacing a bubble nobody filled, bounded scrollback for a panel that runs for weeks.
- **`CaptureBufferTest`** — *(STT)* the arithmetic between the microphone and the recogniser: 16-bit PCM landing inside −1..1, only the requested samples converted, and the 10 s cap truncating inside a frame rather than overrunning it. Neither half can fail loudly — a scale mistake is a recogniser that works and is quietly worse.
- **`SpeechModelsTest`** — *(STT)* the first-run download as the person waiting on it sees it: every file pinned by size and checksum, Hugging Face URLs pinned to a revision rather than a branch, and a percentage weighted by bytes so it never stalls and never jumps back.
- **`SttTemplateTest`** — *(STT, instrumented)* recorded German commands through the real recogniser into the real palette, one per Tier-1 template. The only test that can see a wrong `model_type` or a token table that does not belong to its encoder: none of those throw, they just return text no template matches. Skips itself when the model or the recordings are absent.
- **`OutcomeDetailTest`** — *(Phase B)* the trace line under each answer, including the three-Sock chain case it exists for.
- **`WakeWordModelContractTest`** — *(M2)* the wake word's inference chain, run against the real openWakeWord graphs on the JVM. This is the one that earns its keep. Every constant in the feature pipeline — the 480-sample overlap, 8 mel rows per frame, the 76-row window, `x/10 + 2` — was reimplemented from a Python reference, and getting any of them wrong throws nothing: the models still run, the scores just sit near zero, and the only symptom is a panel that ignores its name. So the arithmetic is asserted against the graphs themselves: 1760 samples must yield exactly 8×32, the embedding must return 96, and the score must actually *move* as the audio changes. It also checks that silence and white noise never fire. Models are fetched to `build/` on first run.
- **`FeatureBuffersTest`** — *(M2)* the sliding buffers on their own: the overlap carried between frames, the windows being the newest rows in order, the bounds that let a panel run for weeks, and that a short frame is a loud error rather than a quietly padded one.

Lint runs with `warningsAsErrors`, as the Kotlin compiler does across every module.

## Next

**Measure the wake word on the device** (above), then train "Hey Dobby" and drop it in.

After that, §8's order stands: **M3–M4** more Socks, **M5** the dashboard cards, **M6** the LLM fallback tier, **M7** hardening — boot notification, watchdog, and the week of unattended uptime that decides whether any of this actually lives on a wall.

*The plan originally specified Porcupine. Picovoice discontinued its free tier on 2026-06-30 and disabled existing `AccessKey`s, and the SDK will not initialise without one, so the choice was made for us. What replaced it is better for this project anyway: nothing to sign up for and nothing anyone can switch off.*


