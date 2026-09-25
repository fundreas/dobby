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
| `:socks:calculator` | **Calculator** — arithmetic out loud. Every answer reads its question back ("8 mal 2 ist 16"), and the result is kept for ten minutes so the next utterance can be the next step: "250 mal 4" → "und davon die Hälfte" → "wie oft passt 150 rein". |
| `:socks:help` | **Help** — spoken discovery: "Was kannst du?", "Was kann die Uhr?" |
| `:socks:conversation` | **Conversation** — "OK", and the turn is over: the microphone closes and the wake word comes back, instead of the person waiting out the five-second window in front of an open mic. One command, no dependencies, and the first Sock to return `SockResult.Ended`. |
| `:socks:winky` | Winky, the development Sock. Not a product Sock, and not in a release APK. |
| `:cli` | The terminal harness. Still the fastest way to work on a template. |
| `:android:sherpa` | [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx), packaged: the Kotlin API vendored verbatim, the 24 MB native library downloaded and checksum-verified at build time. One library, two jobs — Parakeet's recogniser and Piper's `OfflineTts`, with espeak-ng inside it. Upstream publishes no Maven artifact, so this module is the artifact. |
| `:android:pipeline` | Microphone in, German text out; German text in, sound out. `AudioRecord` owner + frame router, openWakeWord, Silero VAD + Parakeet STT, Piper voices through sherpa-onnx with Android TTS as the fallback. Knows nothing about Socks. |
| `:android:app` | The foreground service, the Android `SockContext`, the chat view, and the one place that knows which Socks exist. |

Not yet built: the LLM tier, the remaining Socks (Spotify, Radio, System), and the rest of the dashboard — Clock brought the first card with it, Calculator the second. See [`dobby-plan.md`](dobby-plan.md) §8.

## Run it

```sh
./gradlew build                      # 491 tests, all JVM, no emulator
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

### Talking to it while it is playing music

The panel hears its own speaker. `VOICE_RECOGNITION` — the microphone source M2 shipped — is *defined* as the one with no AGC, no noise suppression and no echo cancellation, because in a quiet room the least-processed signal is the best thing for a recogniser. It is also why music went straight into the microphone: nothing had ever been asked to stop it. That is two failures, and they have different fixes.

**The command was polluted.** The wake word fired, but the audio captured after it had the music underneath. Silero VAD, watching that stream, never found its ~800 ms of trailing silence, so every turn ran to the ten-second hard cap and Parakeet was handed ten seconds of music with a sentence somewhere inside. **The fix is ducking, and it is most of the relief for none of the difficulty:** whatever is playing is quietened for the length of a turn and restored in the same `finally` that re-arms the wake word.

- **The turn ducks, not the Sock.** `TurnAudio` is its own interface and takes its own `AudioFocusRequest`. `PlaybackCoordinator` — what a Sock uses — is keyed by sock id and holds exactly one request, so a turn borrowing it would abandon the Radio Sock's focus and never give it back. There is a test that fails loudly if anybody ever routes it through there.
- **Turn-scoped, not utterance-scoped.** A turn is up to three utterances plus Dobby's answers, and letting the music swell back up between them is worse than not ducking at all: it happens exactly while the person is waiting to speak again.
- **Two channels, because the audio has two origins.** Audio focus for everything out of process — Spotify decodes and plays in its own app, so there is nothing else to reach it with — and a registry of in-process players for what Dobby plays itself, where setting a volume beats asking the system for permission to be quiet. Radio (M4) registers there; the requirement is written into [`radio.specs.md`](socks.specs/radio.specs.md) §6 rather than left for M4 to discover.
- **Duck or pause is a setting**, in Einstellungen, defaulting to ducking. Pausing recognises better and is heavier to live with; which one is right is a measurement, not an argument.
- The typed path does not duck. No microphone is open.

**The wake word still cannot be heard over loud music**, and nothing above helps: it has to be heard *before* there is a turn to duck. Only echo cancellation fixes that, and on this device only the platform's — Spotify's samples never enter this process, so there is no reference signal for a software canceller to subtract. So the microphone source is now a setting: `RECOGNITION` (unprocessed, what M2 shipped) or `COMMUNICATION` (the telephony chain, where the hardware echo canceller lives, plus `AcousticEchoCanceler` and `NoiseSuppressor` where the device offers them). **The default has not moved**, because moving it before measuring is how you trade a known quiet-room panel for an unknown one. Wake-word threshold and patience hang off the profile for the same reason: a number measured through one microphone means nothing through the other.

### The voice

Dobby used to speak with whatever the phone's `TextToSpeech` offered, which on this device is
Google TTS — network-trained, cloud-updated, absent on a phone without Google services, and the
one part of the audio path the panel did not own. It now speaks with **Thorsten**
(`de_DE-thorsten-high`, CC0), with **Cori** (`en_GB-cori-high`, public domain) as the second
voice and the phone's own as the third.

- **No new runtime.** [Piper](https://github.com/rhasspy/piper) voices are single ONNX graphs,
  and sherpa-onnx's `OfflineTts` is already inside the `libsherpa-onnx-jni.so` that Parakeet
  uses — espeak-ng and all. What M2c adds is one vendored Kotlin file, the voice files, the
  phoneme data, and the project's first `AudioTrack`.
- **Streaming by sentence.** `maxNumSentences = 1` means one synthesis callback per sentence,
  written straight to a playing track. A three-sentence Help answer starts speaking after the
  first sentence is synthesised rather than after the third, and `say()` still returns only when
  the audio has finished playing — which is what keeps the microphone shut while Dobby talks.
- **On one dedicated thread**, because synthesis blocks and a blocking `AudioTrack.write` on a
  shared worker would starve the wake word's inference.
- **The Android voice is a catalogue entry, not dead code.** It speaks on first run while
  Thorsten's 114 MB downloads, it covers a sentence when the graph has been freed under memory
  pressure, and it is the way back if espeak-ng reads something worse than Google did. First run
  grows from 670 MB to 784 MB and the panel is never mute while it happens.
- **espeak-ng's phoneme data ships in the APK** — 7 MB compressed, 355 files, one copy shared by
  every voice — because espeak-ng opens those files with `fopen` and an APK asset has no path.
  It is fetched and checksum-verified at build time, then copied to `filesDir` once, stamped
  with the archive's SHA-256 so the second run does no work at all.
- **The voice is a setting**, under *Stimme*, and choosing one makes the panel say a sentence in
  it immediately. A voice is chosen by ear; a radio button that changes nothing you can hear
  until the next timer fires is a setting nobody trusts.
- **Sideloaded voices are first-class**, like sideloaded wake words: any directory under
  `files/voices/` with an `.onnx` and a `tokens.txt` is offered in settings.
- **The voice is also the language switch.** Choosing Cori makes Dobby *answer* in English;
  Thorsten and the Android voice answer in German. There is no separate language setting, on
  purpose — two controls that can disagree is a state where an English sentence comes out of a
  German voice, and the voice is the one whose wrong value is audible. Each settings row says
  which language it answers in.
- **Commands stay German whichever voice is speaking.** The template palette, the normaliser
  and the Tier 2 few-shots are German, so an English-answering panel is still spoken to in
  German — and a question asked in English is answered with "die zweite". The panel's own
  screens stay German too; they are read, not heard.
- **How it works**: nothing sayable is a string. `SockResult.Spoken`, `Asked`, `Failed`,
  `Ended` and `SockContext.announce` all carry a `Phrase` — `(Lang) -> String` — so the Sock
  builds the sentence and core calls it with the language set *at the moment of speaking*. A
  timer set before the voice was switched is announced after it in the new language. A strings
  table would not have survived the ordinary cases: German "halb drei" is English "half past
  two" read from the other end, and `2,5` and `2.5` are the same number that each voice reads
  the other's separator of as a second number. See `m2c-plan.md` Part E and
  `socks.specs/README.md` §2a.

**Not yet measured on the device.** `high` is the default because it was asked for and because
the host numbers say it is affordable; whether two Cortex-A78 cores agree is what
`PiperVoiceDeviceTest` answers, and the fallback if they do not — `thorsten-medium` as the
default, `high` kept as an option — is decided in advance in `m2c-plan.md`'s *Measured*.

### Before you trust it

A wake word trained on speech nobody ever spoke has accuracy you cannot predict from the code, only measure. Two measurements are outstanding, and they answer different questions.

**In a room, with a phone — the 2×2 that also settles `dobby-plan.md` §5.1's two counts.** Each cell: say the phrase 50 times and count the misses, then leave it armed through an evening and count the spurious wakes. Plus `SttTemplateTest`'s pass rate per profile, which is the existing harness for "did the recogniser get worse".

| | silent room | music at panel volume |
|---|---|---|
| `RECOGNITION` | the baseline M2 never took | today's failure, quantified |
| `COMMUNICATION` | what the telephony chain costs in the common case | whether the platform AEC references the media mix at all |

The bottom-right cell is the one the product turns on, and it is not knowable from the API: whether the *media* mix reaches the echo reference on this Nord CE is a question about Qualcomm's audio HAL. If the answer is no, barge-in during playback is not supported on this hardware, the duck setting goes to **pause**, and the panel becomes one that stops the music to listen. Better to find that out in an evening than after building an echo canceller.

Then set the profile, its threshold and its patience from the numbers, and write them here.

**On the JVM, in minutes — the detection curve against interference.** `WakeWordSnrSweepTest` mixes recorded wake phrases with recorded music at a sweep of signal-to-noise ratios and scores them through the real graphs. It isolates the detector from the echo path, which is the point: a good curve here with a deaf panel in the room means the echo is the problem, and a bad curve means no echo canceller would have saved it. It skips itself until you supply recordings — see `android/pipeline/src/test/resources/snr/README.md` — and writes its table to `build/reports/wakeword-snr.txt`.

Two more things to know: the pre-trained models are **CC BY-NC-SA** (fine for your own wall, a hard stop for shipping), and ONNX Runtime adds ~32 MB of native library, which took the release APK from 53 MB to 85 MB.

### What Phase B added, and why it looks like this

- **A foreground service, not an Activity.** The panel's screen is off most of the time and the Activity is not; the registry and the Socks' state have to outlive it. The Activity's only privileges are starting the service and showing the chat.
- **The mic rule is load-bearing.** On Android 12+ a service keeps microphone access only if it was started while an Activity was in the foreground (§7.1), so the order is permission → `startForegroundService` → bind, every time. Getting it backwards makes Dobby deaf with no error anywhere, which is why the call lives in `DobbyService.startFrom` with that written on it. One tap per reboot is the accepted trade-off (§9).
- **One `AudioRecord`, many sinks.** `AudioSource` is the only code that touches the microphone; everything downstream is a `FrameSink` fed 1280-sample frames at 16 kHz — 80 ms, openWakeWord's shape, because the wake word needs multiples of 80 ms and nothing downstream cares. Callers add and remove sinks and never start or stop the recorder: with two consumers whose lifetimes overlap, any explicit stop is the bug that takes the wake word down the moment an utterance ends.
- **Whatever is playing ducks for the turn.** The panel's microphone hears the panel's speaker, and the duck is what puts the VAD's endpoint back within reach. It is taken in `DobbyController` — the one place both the button and the wake word pass through — and released in the same `finally` that re-arms the wake word, because a duck that outlived a thrown turn is a panel that permanently quietened the music.
- **The wake word sits out the turn.** The moment it fires it comes off the stream, and it goes back on only when the turn is finished — dispatched, answered and spoken. Command audio is not wake-word audio, and a detector left running through your sentence and through Dobby's reply is one threshold away from a panel that wakes itself. The capture sink joins before the detector leaves, so the microphone never closes in the gap.
- **One way through, two ways in.** The wake word does not run its own turn — it emits a signal, and the same `listen()` the button calls picks it up. A second path is how the two drift apart.
- **Speech recognition is batch, and the VAD decides when you stopped.** Parakeet sees a finished utterance and answers once, so there is no running guess to stream into the bubble. Silero VAD watches the same frames the capture buffer gets and ends the utterance on ~800 ms of silence, with a 10 s cap for the times that silence never comes. What the panel shows instead of a live transcript is the honest thing: whether it can hear a voice, and then that it is working.
- **The models are downloaded, not bundled.** 784 MB in the APK is 784 MB in git, in every build and every install, to save one round trip per device — and the same goes for sherpa-onnx's 24 MB native library, which `:android:sherpa` fetches at build time. Everything downloaded is verified against a pinned SHA-256, because a truncated encoder is otherwise a native load failure with no Kotlin stack behind it. The cost is that Dobby is deaf on first run until it finishes, so the download reports one size-weighted percentage into the same status line everything else uses.
- **Nothing in a Sock changed.** `SockContext` got its Android implementations — audio focus, the screen wake lock, SharedPreferences, logcat — and Clock and Help were rebuilt against them untouched. That was the whole bet of the Phase A interfaces, and it is the first place it could have failed.
- **Winky cannot ship.** The app's Sock list pulls development Socks from `DevSocks`, which exists twice: the debug source set returns Winky, the release source set returns nothing and does not even have `:socks:winky` on the classpath.

## Discovery

Dobby can be asked what it can do, two ways.

**By voice**, through the Help Sock — answers are written for the ear, so they name areas and offer at most three things to say:

```
> Was kannst du?
  🔊 Ich habe 3 Bereiche: Hilfe, Uhr und Winky. Frag zum Beispiel: Was kann Uhr?

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
| `/keywords` | every keyword, its phonetic code, and whether the phonetic tier trusts it |
| `/prompt`, `/prompt <command>` | the Tier 2 route prompt and its headroom, or one command's fill turn |
| `/grammar`, `/grammar <command>` | the route GBNF, or one command's fill GBNF |
| `/tier2 <utterance>` | run both Tier 2 steps and the gate, with the model scripted from the registry's own few-shots |
| `/fallthrough` | utterances Tier 1 could not match, plus the ones filler skipping saved |
| `/trace`, `/quit` | |

Both read the same `Introspection` API in `:core`, so the terminal, the app's header line and the generated Tier 2 prompts answer identical questions. `:` also works as a prefix.

## Understanding an utterance

Two tiers, and the panel works with only the first.

**Tier 1 — templates**, on every utterance. Token-by-token matching against the palette, with
exact, Levenshtein and phonetic comparison of each keyword. The phonetic tier is Kölner
Phonetik, and almost all of it is guards: a code ignores vowels entirely, so `spiele` and
`spüle` are indistinguishable to it. A keyword is matched by sound only if it is long enough,
if no other palette keyword shares its code, if no frequent German word does either, and if a
consonant actually moved — which is what separates "schbiele" (a misheard cluster) from "spüle"
(the kitchen sink). `/keywords` says which keywords qualify and why the others do not.

The palette runs **twice**: once strictly, and — only if nothing matched — once more ignoring
filler words. Parakeet gets the content words right and the function words wrong ("wie spät ist
**das**"), and particles like "denn", "mal" and "bitte" appear and vanish at random, so templates
name content words and the matcher tolerates the rest. `Fillers.DE` is that list; it holds no
word that can carry a command, which is checked against the assembled palette at build time —
no filler may satisfy a template on its own, and no two commands may become the same sentence
once fillers are dropped. Nothing is skipped in front of a template a single
keyword satisfies — that one has no anchor, and a sentence of particles with one command word
in it is what an ordinary remark looks like. Because skipping is a *second* pass, every
utterance that resolved before resolves to the same command by the same template; it only ever
acts on one that was otherwise headed for Tier 2. `/fallthrough` counts both: what was missed, and what skipping
saved.

**Tier 2 — a local model**, only when Tier 1 matches nothing, only on the first utterance of a
turn, under one 5 s deadline, and only on the phone. Qwen3 1.7B through llama.cpp, in two steps:

1. **Route** — one line per command, and the model answers with a bare command id or `none`.
2. **Fill** — only if that command has parameters: its spec, one worked example, and a grammar
   for its params object. Most commands skip this entirely.

Both prompts and both grammars are generated from the registry, so a Sock is reachable by the
model the moment it is registered. Whatever comes back goes through the same `ParamCoercion`
Tier 1 uses, so **the model gets no route into a Sock that a template does not also have** —
and an unmatched utterance still ends in two buzzes, exactly as it did before there was a
model. `/tier2` runs the whole path in the terminal with the model scripted from the registry's
own few-shots.

## What the tests cover

> **This is a playground project, and tests are not part of the deliverable.** New work does not
> need a test written for it, and a change that breaks one of the tests below does not need that
> test updated — say what broke and move on. The suite exists because it was cheap to write while
> the engine was being designed, not because anything here is promised to anybody. Anyone
> (human or agent) working on this repo should spend the effort on the thing being built.
>
> The inventory below is a map of what already exists, not a standard to keep up.

All 523 tests are plain JVM tests. Nothing needs an emulator, including the wake word.

- `GermanNumbersTest`, `NormalizerTest` — German cardinals, and why `ein` is left alone while `eins` is not.
- `TemplateParserTest`, `TemplateMatcherTest`, `SpecificityTest` — the DSL, backtracking, fuzzy tolerance, palette ordering.
- **`SpecPaletteTest`** — the utterance tables from every file in `socks.specs/`, run against fixture Socks carrying the real templates. This is the collision gate: it is what fails when a new template shadows another Sock.
- **`FillerSkippingTest`, `FillersTest`, `NegativeSentencesTest`, `FillerRegistryTest`** — *(M6c)* filler skipping from four sides: what one template does with a sentence full of particles; what may be on `Fillers.DE` at all, including the three words (`ein`, `halt`, `danke`) that lost an argument with a real Sock; sixty everyday German sentences that must still reach nothing with skipping on; and the same two questions asked again against the Socks that actually ship, because `:core`'s fixtures and the device's catalog barely overlap. `SpecPaletteTest` carries the A/B that the whole design rests on — an utterance the strict pass matched resolves to the same command by the same template with skipping on — and pins, by name, the three spec phrasings that pruning moved onto the second pass.
- `RescueTest` — *(M6c)* the other end of the flywheel: an utterance only the second pass reached is reported as a rescue, one the first pass reached is not, one nothing reached is a fallthrough, and the log keeps, bounds and clears both halves together.
- `RegistryValidationTest` — every way a Sock can be malformed.
- `IntrospectionTest`, `HelpSockTest` — discovery, including that spoken lists are ordered by display name.
- `GermanTimeTest`, `ClockSockTest`, `ClockTemplatesTest` — the Clock Sock's answers and its utterance tables, including that German "halb 3" means 14:30 and not 15:30, and that "stopp den timer" is a different command from "stopp".
- **`TimerLifecycleTest`** — the whole timer, on a virtual clock: set, replace, cancel before expiry, expiry, cancel while ringing, the chime giving up after 60 s, the range it refuses, and the `AlarmManager` backstop firing for a timer the process slept through. Sixty seconds of chime is one line of `advanceTimeBy`, because `AlarmManager` and `SoundPool` sit behind interfaces.
- **`ClockChainTest`** — the two cases the chain exists for: a ringing chime wins "stopp" and the ducked music comes back to full volume with the music Sock never invoked; a *counting* timer does not, so the music pauses and the timer still fires nine minutes later.
- **`ChainDispatcherTest`** — the chain matrix: activity ranking, priority tiebreaks, `NotForMe` passing, nobody consuming, a Sock that throws, a Sock that hangs.
- **`DobbyControllerTest`** — *(Phase B, extended in M2)* the join, end to end: an utterance goes through the real registry to the real Clock Sock, and the answer is both shown and spoken, as the same sentence. Also that a Sock reaches the `SockContext` built on the Android side, that saying nothing leaves no trace, that typing takes the identical path — and that the wake word takes *that same path*, wakes the screen first, and that losing the wake word model leaves a working push-to-talk panel rather than a broken one. And the shape of a turn: five seconds for a voice to start, whether it is the first utterance or the retry after a not-understood buzz; two buzzes instead of the spoken apology; an answer if the second try lands; a bound, so a television cannot hold the microphone open all evening; and that the wake word's acknowledgement — buzz, pip or nothing — is a setting the pipeline acts on and the screen shows. Testable at all because the hardware sits behind `VoiceIo` and the context behind a factory — the Phase A trick, one layer up.
- **`TranscriptTest`** — *(Phase B)* the chat model's awkward parts: a live bubble becoming a transcript in place, an answer replacing a bubble nobody filled, bounded scrollback for a panel that runs for weeks.
- **`CaptureBufferTest`** — *(STT)* the arithmetic between the microphone and the recogniser: 16-bit PCM landing inside −1..1, only the requested samples converted, and the 10 s cap truncating inside a frame rather than overrunning it. Neither half can fail loudly — a scale mistake is a recogniser that works and is quietly worse.
- **`VoiceCatalogueTest`** — *(M2c)* the voices settings offers: every file pinned by size and lowercase checksum, every Hugging Face URL pinned to a revision rather than a branch, ids unique and stable because they are what settings stores, an unknown id falling back to Thorsten instead of throwing, and Cori's row saying out loud which language she answers in. Plus the naming of a sideloaded directory, which is the only thing that decides what a pushed voice is called on screen.
- **`SynthesisCallbackTest`** — *(M2c)* one method descriptor, asserted reflectively. sherpa-onnx's TTS JNI resolves the per-sentence callback by hand — `GetMethodID(cls, "invoke", "([F)Ljava/lang/Integer;")` — and does not check for a pending exception afterwards, so an object without that exact method aborts the process instead of throwing. A Kotlin lambda compiles through `invokedynamic` and does not have it; that is what took the first cut of M2c down on the phone, as a `SIGABRT` no `catch` could see. The fix is one class, and the thing that would silently undo it is a Kotlin version bump — which is why the check is a JVM test and not a device.
- **`EspeakDataTest`** — *(M2c)* the stamp that decides whether 18 MB of phoneme data is copied again: missing, matching, stale and unreadable. And both pins asserted against literals, because the same two numbers live in the build file and a bump that changes one without the other is a device that either re-copies 355 files on every start or trusts a directory it has not seen.
- **`PiperVoiceDeviceTest`** — *(M2c, instrumented)* every voice on the device, synthesising the sentences Dobby actually says — a German time, a calculation read back, a three-sentence Help answer, a departures line, an English title in a German sentence. It asserts what cannot throw: a graph loaded with the wrong token table or a phoneme directory that copied 300 of 355 files produces *silence*, not an exception. It is also the instrument: load time, first-chunk latency, real-time factor and RSS go to logcat under `DobbyVoice` and into `m2c-plan.md`'s *Measured* table. Skips itself when no voice is on the device.
- **`SpeechModelsTest`** — *(STT)* the first-run download as the person waiting on it sees it: every file pinned by size and checksum, Hugging Face URLs pinned to a revision rather than a branch, and a percentage weighted by bytes so it never stalls and never jumps back.
- **`SttTemplateTest`** — *(STT, instrumented)* recorded German commands through the real recogniser into the real palette, one per Tier-1 template. The only test that can see a wrong `model_type` or a token table that does not belong to its encoder: none of those throw, they just return text no template matches. Skips itself when the model or the recordings are absent.
- **`OutcomeDetailTest`** — *(Phase B)* the trace line under each answer, including the three-Sock chain case it exists for.
- **`WakeWordModelContractTest`** — *(M2)* the wake word's inference chain, run against the real openWakeWord graphs on the JVM. This is the one that earns its keep. Every constant in the feature pipeline — the 480-sample overlap, 8 mel rows per frame, the 76-row window, `x/10 + 2` — was reimplemented from a Python reference, and getting any of them wrong throws nothing: the models still run, the scores just sit near zero, and the only symptom is a panel that ignores its name. So the arithmetic is asserted against the graphs themselves: 1760 samples must yield exactly 8×32, the embedding must return 96, and the score must actually *move* as the audio changes. It also checks that silence and white noise never fire. Models are fetched to `build/` on first run.
- **`TurnDuckTest`** — *(M2b)* what a turn does to whatever is playing: the duck is taken before the microphone opens and released once after it closes, held across all three utterances of a turn rather than taken per utterance, released when nothing was said, when a Sock ends the turn, when the microphone throws and when the turn is cancelled — and the button ducks exactly as the wake word does, while typing does not duck at all. Its last test is the one that matters most in a year: a turn duck leaves the Socks' own `PlaybackCoordinator` focus untouched, so routing it through there later fails loudly instead of quietly stealing the Radio Sock's focus.
- **`TurnAudioTest`**, **`MicProfileTest`** — *(M2b)* the in-process half of the duck (register, duck, restore, a player that joins mid-turn, one broken player not costing the others theirs) and the promise that the microphone source has not quietly moved off the one M2 shipped.
- **`SnrMixTest`**, **`WakeWordSnrSweepTest`** — *(M2b)* the detection curve against music. The sweep is opt-in and skips itself until somebody supplies recordings; the mixer underneath it is tested on synthetic audio always, because a mixer that is quietly wrong produces a curve that looks like a finding.
- **`FeatureBuffersTest`** — *(M2)* the sliding buffers on their own: the overlap carried between frames, the windows being the newest rows in order, the bounds that let a panel run for weeks, and that a short frame is a loud error rather than a quietly padded one.

Lint runs with `warningsAsErrors`, as the Kotlin compiler does across every module.

## Next

**Measure the wake word on the device** (above) — now a 2×2 of microphone profile against a room with and without music, which also settles whether this hardware can hear past its own speaker at all. Then train "Hey Dobby" and drop it in.

**And measure the voice**, which is the same kind of outstanding number: `PiperVoiceDeviceTest` reads Thorsten's real-time factor, first-chunk latency and resident memory off the phone, and that is what decides whether `high` stays the default (`m2c-plan.md`, *Measured*).

After that, §8's order stands: **M3–M4** more Socks, **M5** the dashboard cards, **M6** the LLM fallback tier, **M7** hardening — boot notification, watchdog, and the week of unattended uptime that decides whether any of this actually lives on a wall.

*The plan originally specified Porcupine. Picovoice discontinued its free tier on 2026-06-30 and disabled existing `AccessKey`s, and the SDK will not initialise without one, so the choice was made for us. What replaced it is better for this project anyway: nothing to sign up for and nothing anyone can switch off.*


