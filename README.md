# Dobby

A voice-controlled, wall-mounted smart panel for a repurposed OnePlus Nord CE (Android 13 / OxygenOS 13). Wake word, speech-to-text and intent parsing all run on-device.

Dobby core owns no commands. Everything the user can *do* lives in a **Sock** — a self-contained module that registers its commands and executes them.

- [`dobby-plan.md`](dobby-plan.md) — the build spec for core
- [`socks.specs/`](socks.specs/) — one spec per Sock, plus [the contract](socks.specs/README.md) and the [shared-command catalog](socks.specs/shared-commands.specs.md)

## Status: hands-free (M2)

Phase A was "Dobby in a terminal": everything below the microphone and above the Socks, as pure JVM code. Phase B put it on the phone. M2 makes it hands-free — say the wake phrase and the panel answers, screen off, nothing touched.

**Built and tested, not yet measured on the device.** `dobby-plan.md` §8 calls M2 done when false rejects and false accepts have been counted in the actual room and the service has survived 24 hours. Neither has happened; see [Before you trust it](#before-you-trust-it).

| | |
|---|---|
| `:core` | Sock API, template engine, normalizer, registry, dispatcher + chain. **Pure Kotlin/JVM** — the module boundary is what enforces the plan's "parsing is pure" rule. Publishes test fixtures (`FakeSockContext`) used by the Socks *and* by the Android app. |
| `:socks:clock` | **Clock** — the first product Sock. One command: `clock.whats_the_time`. |
| `:socks:help` | **Help** — spoken discovery: "Was kannst du?", "Was kann die Uhr?" |
| `:socks:devi` | Devi, the development Sock. Not a product Sock, and not in a release APK. |
| `:cli` | The terminal harness. Still the fastest way to work on a template. |
| `:android:pipeline` | Microphone in, German text out; German text in, sound out. `AudioRecord` owner + frame router, openWakeWord, Vosk, Android TTS. Knows nothing about Socks. |
| `:android:app` | The foreground service, the Android `SockContext`, the chat view, and the one place that knows which Socks exist. |

Not yet built: the dashboard cards, the LLM tier, and the remaining Socks (including Clock's own timers). See [`dobby-plan.md`](dobby-plan.md) §8.

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
│ [ Tippen statt sprechen ]    🎤  │
└──────────────────────────────────┘
```

Say the wake phrase — a beep, the screen wakes, and it listens. Or tap the microphone. Either way the bubble fills in live with Vosk's running guess, then settles into the final transcript; Dobby's answer appears and is spoken at the same time.

The switch in the header disarms the wake word. An always-open microphone on a wall needs an off switch you can see from across the room, not a setting three screens deep.

**The line under each answer is the point.** It is the terminal harness's `/trace` output — the resolved command, its params, and every Sock the chain offered it to with the activity each reported. Speech leaves nothing behind, so when "Stopp" silences the wrong thing — the failure [`dobby-plan.md`](dobby-plan.md) §9 calls invisible in review and obvious in daily use — this is the only place you can see it happen.

**Typing works too**, and is not a debug affordance: it is how Dobby is usable while the 46 MB model downloads on first run, on a device with no German voice installed, and in a room too loud to talk in. It takes the identical path — normalize, match, dispatch, speak.

### The wake word

Three ONNX graphs run on every 80 ms frame: a melspectrogram, Google's frozen speech-embedding backbone, and a small classifier head trained for one phrase. Only the head is per-wake-word, so a second phrase later is another ~1.3 MB file, not another pipeline.

**"Hey Dobby" does not exist yet.** Custom phrases are trained from synthetic Piper speech in [openWakeWord's Colab notebook](https://github.com/dscripka/openWakeWord) — an hour and a GPU, which is not something an app can do at startup. So the panel ships answering to *Hey Jarvis*, and picks up a model you supply the moment you drop one in:

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
- **One `AudioRecord`, many sinks.** `AudioSource` is the only code that touches the microphone; everything downstream is a `FrameSink` fed 1280-sample frames at 16 kHz — 80 ms, openWakeWord's shape, because the wake word needs multiples of 80 ms and Vosk is indifferent. Callers add and remove sinks and never start or stop the recorder: with two consumers whose lifetimes overlap, any explicit stop is the bug that takes the wake word down the moment an utterance ends.
- **One way through, two ways in.** The wake word does not run its own turn — it emits a signal, and the same `listen()` the button calls picks it up. A second path is how the two drift apart.
- **The model is downloaded, not bundled.** 46 MB in the APK is 46 MB in git, in every build and every install, to save one round trip per device. The cost is that Dobby is deaf on first run until it finishes, so the download reports progress into the same status line everything else uses.
- **Nothing in a Sock changed.** `SockContext` got its Android implementations — audio focus, the screen wake lock, SharedPreferences, logcat — and Clock and Help were rebuilt against them untouched. That was the whole bet of the Phase A interfaces, and it is the first place it could have failed.
- **Devi cannot ship.** The app's Sock list pulls development Socks from `DevSocks`, which exists twice: the debug source set returns Devi, the release source set returns nothing and does not even have `:socks:devi` on the classpath.

## Discovery

Dobby can be asked what it can do, two ways.

**By voice**, through the Help Sock — answers are written for the ear, so they name areas and offer at most three things to say:

```
> Was kannst du?
  🔊 Ich habe 3 Bereiche: Devi, Hilfe und Uhr. Frag zum Beispiel: Was kann Devi?

> Was kann die Uhr?
  🔊 Uhr hat einen Befehl. Sag zum Beispiel: 'wie spät ist es'.
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
- `GermanTimeTest`, `ClockSockTest` — the Clock Sock, including that German "halb 3" means 14:30 and not 15:30.
- **`ChainDispatcherTest`** — the chain matrix: activity ranking, priority tiebreaks, `NotForMe` passing, nobody consuming, a Sock that throws, a Sock that hangs.
- **`DobbyControllerTest`** — *(Phase B, extended in M2)* the join, end to end: an utterance goes through the real registry to the real Clock Sock, and the answer is both shown and spoken, as the same sentence. Also that a Sock reaches the `SockContext` built on the Android side, that saying nothing leaves no trace, that typing takes the identical path — and that the wake word takes *that same path*, wakes the screen first, and that losing the wake word model leaves a working push-to-talk panel rather than a broken one. Testable at all because the hardware sits behind `VoiceIo` and the context behind a factory — the Phase A trick, one layer up.
- **`TranscriptTest`** — *(Phase B)* the chat model's awkward parts: a partial transcript becoming a final one in place, a late frame arriving after the bubble is gone, bounded scrollback for a panel that runs for weeks.
- **`VoskJsonTest`** — *(Phase B)* the recogniser's result parsing, written by hand precisely so it is not `org.json` and can be tested off-device.
- **`OutcomeDetailTest`** — *(Phase B)* the trace line under each answer, including the three-Sock chain case it exists for.
- **`WakeWordModelContractTest`** — *(M2)* the wake word's inference chain, run against the real openWakeWord graphs on the JVM. This is the one that earns its keep. Every constant in the feature pipeline — the 480-sample overlap, 8 mel rows per frame, the 76-row window, `x/10 + 2` — was reimplemented from a Python reference, and getting any of them wrong throws nothing: the models still run, the scores just sit near zero, and the only symptom is a panel that ignores its name. So the arithmetic is asserted against the graphs themselves: 1760 samples must yield exactly 8×32, the embedding must return 96, and the score must actually *move* as the audio changes. It also checks that silence and white noise never fire. Models are fetched to `build/` on first run.
- **`FeatureBuffersTest`** — *(M2)* the sliding buffers on their own: the overlap carried between frames, the windows being the newest rows in order, the bounds that let a panel run for weeks, and that a short frame is a loud error rather than a quietly padded one.

Lint runs with `warningsAsErrors`, as the Kotlin compiler does across every module.

## Next

**Measure the wake word on the device** (above), then train "Hey Dobby" and drop it in.

After that, §8's order stands: **M3–M4** more Socks, **M5** the dashboard cards, **M6** the LLM fallback tier, **M7** hardening — boot notification, watchdog, and the week of unattended uptime that decides whether any of this actually lives on a wall.

*The plan originally specified Porcupine. Picovoice discontinued its free tier on 2026-06-30 and disabled existing `AccessKey`s, and the SDK will not initialise without one, so the choice was made for us. What replaced it is better for this project anyway: nothing to sign up for and nothing anyone can switch off.*


