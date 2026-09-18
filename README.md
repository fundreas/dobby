# Dobby

A voice-controlled, wall-mounted smart panel for a repurposed OnePlus Nord CE (Android 13 / OxygenOS 13). Wake word, speech-to-text and intent parsing all run on-device.

Dobby core owns no commands. Everything the user can *do* lives in a **Sock** — a self-contained module that registers its commands and executes them.

- [`dobby-plan.md`](dobby-plan.md) — the build spec for core
- [`socks.specs/`](socks.specs/) — one spec per Sock, plus [the contract](socks.specs/README.md) and the [shared-command catalog](socks.specs/shared-commands.specs.md)

## Status: Phase B complete

Phase A was "Dobby in a terminal": everything below the microphone and above the Socks, as pure JVM code. Phase B puts it on the phone — you speak, Dobby answers out loud, and the screen shows what it heard and what it said.

| | |
|---|---|
| `:core` | Sock API, template engine, normalizer, registry, dispatcher + chain. **Pure Kotlin/JVM** — the module boundary is what enforces the plan's "parsing is pure" rule. Publishes test fixtures (`FakeSockContext`) used by the Socks *and* by the Android app. |
| `:socks:clock` | **Clock** — the first product Sock. One command: `clock.whats_the_time`. |
| `:socks:help` | **Help** — spoken discovery: "Was kannst du?", "Was kann die Uhr?" |
| `:socks:devi` | Devi, the development Sock. Not a product Sock, and not in a release APK. |
| `:cli` | The terminal harness. Still the fastest way to work on a template. |
| `:android:pipeline` | **New.** Microphone in, German text out; German text in, sound out. `AudioRecord` owner + frame router, Vosk, Android TTS. Knows nothing about Socks. |
| `:android:app` | **New.** The foreground service, the Android `SockContext`, the chat view, and the one place that knows which Socks exist. |

Not yet built: the wake word, the dashboard cards, the LLM tier, and the remaining Socks (including Clock's own timers). See [`dobby-plan.md`](dobby-plan.md) §8.

## Run it

```sh
./gradlew build                      # 146 tests, all JVM, no emulator
./gradlew :cli:run -q                # the terminal harness
./gradlew :android:app:installDebug  # the phone
```

The Android build needs an SDK with platform 36 and `ANDROID_HOME` set (or `sdk.dir` in `local.properties`).

## On the phone

```
┌──────────────────────────────────┐
│ Dobby                    hört zu │
│ 3 Socks · 4 Befehle · 19 Vorlagen│
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

Tap the microphone and talk. The bubble fills in live with Vosk's running guess, then settles into the final transcript; Dobby's answer appears and is spoken at the same time.

**The line under each answer is the point.** It is the terminal harness's `/trace` output — the resolved command, its params, and every Sock the chain offered it to with the activity each reported. Speech leaves nothing behind, so when "Stopp" silences the wrong thing — the failure [`dobby-plan.md`](dobby-plan.md) §9 calls invisible in review and obvious in daily use — this is the only place you can see it happen.

**Typing works too**, and is not a debug affordance: it is how Dobby is usable while the 46 MB model downloads on first run, on a device with no German voice installed, and in a room too loud to talk in. It takes the identical path — normalize, match, dispatch, speak.

### What Phase B added, and why it looks like this

- **A foreground service, not an Activity.** The panel's screen is off most of the time and the Activity is not; the registry and the Socks' state have to outlive it. The Activity's only privileges are starting the service and showing the chat.
- **The mic rule is load-bearing.** On Android 12+ a service keeps microphone access only if it was started while an Activity was in the foreground (§7.1), so the order is permission → `startForegroundService` → bind, every time. Getting it backwards makes Dobby deaf with no error anywhere, which is why the call lives in `DobbyService.startFrom` with that written on it. One tap per reboot is the accepted trade-off (§9).
- **One `AudioRecord`, many sinks.** `AudioSource` is the only code that touches the microphone; everything downstream is a `FrameSink` fed 1280-sample frames at 16 kHz — 80 ms, which is openWakeWord's shape. The component that cannot choose gets to choose: the wake word needs multiples of 80 ms, Vosk is indifferent to chunk size. Adding it in M2 is `addSink`, not a rewrite.
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

All 146 tests are plain JVM tests. Nothing needs an emulator, including everything Phase B added.

- `GermanNumbersTest`, `NormalizerTest` — German cardinals, and why `ein` is left alone while `eins` is not.
- `TemplateParserTest`, `TemplateMatcherTest`, `SpecificityTest` — the DSL, backtracking, fuzzy tolerance, palette ordering.
- **`SpecPaletteTest`** — the utterance tables from every file in `socks.specs/`, run against fixture Socks carrying the real templates. This is the collision gate: it is what fails when a new template shadows another Sock.
- `RegistryValidationTest` — every way a Sock can be malformed.
- `IntrospectionTest`, `HelpSockTest` — discovery, including that spoken lists are ordered by display name.
- `GermanTimeTest`, `ClockSockTest` — the Clock Sock, including that German "halb 3" means 14:30 and not 15:30.
- **`ChainDispatcherTest`** — the chain matrix: activity ranking, priority tiebreaks, `NotForMe` passing, nobody consuming, a Sock that throws, a Sock that hangs.
- **`DobbyControllerTest`** — *(Phase B)* the join, end to end: an utterance goes through the real registry to the real Clock Sock, and the answer is both shown and spoken, as the same sentence. Also that a Sock reaches the `SockContext` built on the Android side, that saying nothing leaves no trace, and that typing takes the identical path. Testable at all because the hardware sits behind `VoiceIo` and the context behind a factory — the Phase A trick, one layer up.
- **`TranscriptTest`** — *(Phase B)* the chat model's awkward parts: a partial transcript becoming a final one in place, a late frame arriving after the bubble is gone, bounded scrollback for a panel that runs for weeks.
- **`VoskJsonTest`** — *(Phase B)* the recogniser's result parsing, written by hand precisely so it is not `org.json` and can be tested off-device.
- **`OutcomeDetailTest`** — *(Phase B)* the trace line under each answer, including the three-Sock chain case it exists for.

Lint runs with `warningsAsErrors`, as the Kotlin compiler does across every module.

## Next

**M2 — hands-free.** [openWakeWord](https://github.com/dscripka/openWakeWord) on the shared `AudioSource`, an earcon, and screen-off listening. The last piece between Dobby and being usable without touching it, and the plumbing is already in place: one more `FrameSink` and a wake-word branch in `VoicePipeline`.

"Hey Dobby" is ~200 KB on top of a shared frozen feature extractor, trained from synthetic Piper TTS audio in a Colab notebook — no recordings, no account, no key. A Raspberry Pi 3 core runs 15–20 of these in real time, so one on a Nord CE costs nothing worth measuring.

The plan originally specified Porcupine; Picovoice discontinued its free tier on 2026-06-30 and disabled existing `AccessKey`s, and the SDK will not initialise without one. What replaced it is better for this project anyway: nothing to sign up for and nothing anyone can switch off. Two things to know going in — the pre-trained models are CC BY-NC-SA (fine for a panel on your own wall, a hard stop for shipping it), and a wake word trained on speech nobody ever spoke has to be *measured* in the actual room before its threshold is set. See `dobby-plan.md` §5.1 and §9.

After that, §8's order stands: more Socks (M3–M4), the dashboard (M5), the LLM fallback tier (M6), hardening (M7).
