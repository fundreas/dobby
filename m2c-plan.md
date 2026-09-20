# M2c — Dobby's voice: Piper's Thorsten and Cori, through sherpa-onnx

## Context

Dobby speaks with whatever the phone's `TextToSpeech` engine offers. On the test device that is
Google TTS (`settings get secure tts_default_synth` → `com.google.android.tts`), which is a
network-trained, cloud-updated voice the panel does not own: it changes when Google updates it,
it is missing when a device has no Google services, and it is the one part of the audio path
whose quality Dobby cannot choose. [Speaker.kt](android/pipeline/src/main/kotlin/io/dobby/pipeline/tts/Speaker.kt#L21)
picks `de-AT`, then `de-DE`, and goes mute if neither exists.

[Piper](https://github.com/rhasspy/piper) voices are single ONNX files, run offline, and two of
them are clearly better than what the panel says today (listen at
<https://rhasspy.github.io/piper-samples/#de_DE-thorsten-high>):

| Voice | Language | Quality | Sample rate | Speakers | Dataset / licence |
|---|---|---|---|---|---|
| **Thorsten** (`de_DE-thorsten-high`) | German (Germany) | high — 22.05 kHz, 28–32 M params | 22 050 Hz | 1 | [Thorsten-Voice](https://github.com/thorstenMueller/Thorsten-Voice), **CC0**. Fine-tuned from the US-English `lessac` high voice. |
| **Cori** (`en_GB-cori-high`) | English (Great Britain), female | high | 22 050 Hz | 1 | LibriVox recordings, **public domain**; ~24 h, trained from scratch for 500 epochs by Bryce Beattie. |

Both are permissively licensed — unlike the wake-word heads (CC BY-NC-SA), there is nothing here
that stops a release build.

**This plan makes Thorsten the default voice, Cori the second, keeps the Android voice as the
fallback, and makes the choice a setting.** It does not change *what* Dobby says: every Sock
answers in German ([dobby-plan.md:18](dobby-plan.md#L18)), so Cori reads German sentences with
English phonemes until the Socks can answer in English. What "English output" needs beyond the
voice is in Part E, and deliberately out of scope here.

**The runtime is already on the phone.** sherpa-onnx, vendored for Parakeet, ships Piper
support in the same JNI library: the `libsherpa-onnx-jni.so` that `:android:sherpa` downloads
today exports all eleven `Java_com_k2fsa_sherpa_onnx_OfflineTts_*` entry points and carries
espeak-ng inside it. What is missing is one Kotlin file, the voice files, the phoneme data, and
an `AudioTrack` — nothing native changes.

---

## What was checked, and the numbers

Everything below was done on 2026-09-19 with sherpa-onnx **1.13.8**, the version pinned in
[libs.versions.toml](gradle/libs.versions.toml#L15).

**The files.** Two sources publish the same converted voices; pins below are what this plan
uses. The plain `rhasspy/piper-voices` files do **not** load in sherpa-onnx (its conversion
adds the metadata sherpa reads from the graph), so the `csukuangfj/*` mirrors are the ones.

| File | Bytes | SHA-256 | Source |
|---|---|---|---|
| `de_DE-thorsten-high.onnx` | 113 895 328 | `d3d0f8fc180fd28b64a286452572e4ea0716e45ac068ee6ee678f89777034e39` | HF `csukuangfj/vits-piper-de_DE-thorsten-high` @ `d0d70c92994440adabb10804fdbe9f075f066c7b` |
| `tokens.txt` (Thorsten) | 921 | `87c8ef66eae5473ed0cc0366b3964c736ca6c5f676c979522ea31234e47430b9` | same revision |
| `en_GB-cori-high.onnx` | 114 219 480 | `006bb4db48e066f7f1be91d218db3b76617a707196271694ca6455d7bbd13842` | HF `csukuangfj/vits-piper-en_GB-cori-high` @ `37f6efb503f5dc22de01bad04c9a118a99111096` |
| `tokens.txt` (Cori) | 940 | `ef3a7e4a8d1af0c9d4dc45aaae1a6242ebe24a7ed6f3d025a49eb29682784c6d` | same revision |
| `espeak-ng-data.tar.bz2` | 7 252 012 | `4135ccf82e1f40613491c0874d4945ae9e9c7840933d8e25a6f9e003d9ebf533` | [sherpa-onnx `tts-models` release](https://github.com/k2-fsa/sherpa-onnx/releases/tag/tts-models) |

- `espeak-ng-data` unpacks to **355 files, 17 991 651 bytes**, and is byte-identical in the
  Thorsten tarball, the Cori tarball and the standalone archive. One copy serves every voice.
- The `.onnx.json` and `MODEL_CARD` are not needed at runtime; sherpa reads the metadata from
  the graph. They are not downloaded.
- The GitHub tarball `vits-piper-de_DE-thorsten-high.tar.bz2` carries an **older Thorsten export**
  (113 847 300 bytes, `415d684c…`). Both synthesise the same sentences identically on the host;
  the Hugging Face one is pinned because it is a revision, not a moving release asset — the same
  argument [SpeechModels.kt:24](android/pipeline/src/main/kotlin/io/dobby/pipeline/stt/SpeechModels.kt#L24)
  makes for Parakeet. Cori is the same file in both places.

**Synthesis works with the pinned runtime** (Python `sherpa-onnx==1.13.8`, i7-12700H, 2 threads):

| | Load | Real-time factor | Notes |
|---|---|---|---|
| Thorsten high | 0.6 s | **0.28–0.33** | "Es ist halb drei." → 1.07 s of audio in 0.31 s |
| Cori high | 0.9 s | **0.31–0.34** | English titles inside a sentence come out right |

Thorsten peaks at ≈ 0.5 full scale on typical answers, Cori at ≈ 0.28 — Cori is roughly 5 dB
quieter, which is why the catalogue carries a per-voice gain (C3).

**The device is not the one the README names.** What answers on `adb` today is a **vivo IV2201**
on a MediaTek **MT6877 (Dimensity 900: 2× Cortex-A78 + 6× Cortex-A55)** with 7.5 GB of RAM, not
a OnePlus Nord CE. The A78 is a slower core than a laptop's; a real-time factor of 0.3 on the
host is plausibly **0.8–1.5 on the phone**, which is exactly the number that decides whether
`high` is usable (Sequence, step 0). Piper's own guidance is that `medium` (63 MB, ~half the
compute) is the Raspberry Pi tier; `high` is for machines with headroom.

**Local copies.** Both voices, the older tarball export, the phoneme data and the test WAVs are
under [`android/pipeline/build/piper-voices/`](android/pipeline/build/piper-voices/) (gitignored,
next to the wake-word models the contract test fetches): `vits-piper-de_DE-thorsten-high/`,
`vits-piper-en_GB-cori-high/`, `espeak-ng-data.tar.bz2`, and `samples/*.wav` — Thorsten
reading Clock, Calculator and Help answers, Cori reading the English equivalents.

---

## What is true today that this plan relies on

- **One seam.** [VoiceIo.say](android/pipeline/src/main/kotlin/io/dobby/pipeline/VoiceIo.kt#L87)
  is the whole of what the controller asks for, and
  [VoicePipeline.kt:95](android/pipeline/src/main/kotlin/io/dobby/pipeline/VoicePipeline.kt#L95)
  is the single place a `Speaker` is constructed. `say()` suspends until the sentence has
  finished *playing* — [DobbyController.kt:485](android/app/src/main/kotlin/io/dobby/android/DobbyController.kt#L485)
  relies on that to keep the microphone shut while Dobby talks. Whatever replaces the platform
  engine must keep that contract exactly.
- **Nothing plays PCM yet.** The only playback paths are `ToneGenerator`
  ([Earcon.kt](android/pipeline/src/main/kotlin/io/dobby/pipeline/tts/Earcon.kt#L42)) and
  `SoundPool` for the chime. There is no `AudioTrack` anywhere; this plan introduces the first.
- **The download pattern exists three times** and is the same each time:
  [`RemoteFile`](android/pipeline/src/main/kotlin/io/dobby/pipeline/stt/SpeechModels.kt#L10)
  pinned by size and SHA-256, a store with a `StateFlow` of
  Absent/Downloading/Verifying/Ready/Failed, [`Downloader`](android/pipeline/src/main/kotlin/io/dobby/pipeline/download/Downloader.kt)
  hashing on the way in, and a [`Verified`](android/pipeline/src/main/kotlin/io/dobby/pipeline/download/Verified.kt)
  stamp so the hash is read once per download rather than once per start.
  [WakeWordModelStore](android/pipeline/src/main/kotlin/io/dobby/pipeline/wakeword/WakeWordModelStore.kt)
  is the closest shape — a catalogue of options, fetched the first time each is chosen, plus
  sideloaded files that appear on their own.
- **Build-time fetching exists once**: [`fetchSherpaJni`](android/sherpa/build.gradle.kts#L54)
  downloads and checksum-verifies an archive into a gitignored source directory before
  `preBuild`. The phoneme data follows it.
- **The vendoring rule.** [android/sherpa/README.md](android/sherpa/README.md) — Kotlin API files
  are copied verbatim from the pinned tag and never edited, because the JNI reads their fields by
  name. `Tts.kt` is one more of those files.
- **Settings are flat and enum-by-name.** [Settings.kt](android/app/src/main/kotlin/io/dobby/android/Settings.kt#L42)
  stores `ListenCue` by name with a default for anything unreadable;
  [SettingsScreen.kt](android/app/src/main/kotlin/io/dobby/android/ui/SettingsScreen.kt#L125)
  is one `LazyColumn` of sections with a `ChoiceRow` per option; a change flows
  `MainActivity → controller.setX → pipeline.x + settings.x`
  ([DobbyController.kt:312](android/app/src/main/kotlin/io/dobby/android/DobbyController.kt#L312)).
- **M2b is in flight in this working tree** (`m2b-plan.md`, `AndroidTurnAudio.kt`,
  `core/audio/`, uncommitted). Its turn duck takes focus with `USAGE_ASSISTANT` /
  `CONTENT_TYPE_SPEECH` for the length of a turn, and `say()` inside a turn runs under it. This
  plan **does not touch the turn**, and the speaker takes no focus of its own (C2).
- **The model root is `filesDir`**, not external storage:
  [VoicePipeline.kt:88](android/pipeline/src/main/kotlin/io/dobby/pipeline/VoicePipeline.kt#L88),
  and on the device `run-as io.dobby.android ls files` shows Parakeet, the VAD and `wakeword/`
  there. (The `adb push … /sdcard/Android/data/…` line in the README and in
  `WakeWordModelStore`'s doc comment points at a directory that does not exist; a one-line fix,
  noted under *Not in this plan*.)

---

## Part A — The runtime (`:android:sherpa`, `:android:pipeline` build)

### A1. Vendor `Tts.kt`

Copy `sherpa-onnx/kotlin-api/Tts.kt` from tag `v1.13.8` into
`android/sherpa/src/main/kotlin/com/k2fsa/sherpa/onnx/Tts.kt`, verbatim, and add it to the
file list in the module README's update recipe. It brings `OfflineTts`, `OfflineTtsConfig`,
`OfflineTtsVitsModelConfig`, `GeneratedAudio` and the `generateWithCallback` variant. The other
model families in the file (Matcha, Kokoro, Kitten, …) are the "unused demo catalogues" the
README already says not to delete.

Nothing else in the module changes: the archive, its checksum and the static-link choice stay.

### A2. Phoneme data as a build-time asset

espeak-ng needs its data directory on a **real filesystem path** — it opens files with `fopen`,
so an APK asset cannot be handed to it directly (sherpa's own Android TTS demo copies it out of
assets first). Two steps:

1. **Gradle, in `:android:pipeline`** — a `fetchEspeakData` task mirroring `fetchSherpaJni`:
   download `espeak-ng-data.tar.bz2` (7.3 MB, SHA-256 pinned above) into the Gradle cache,
   verify, and unpack it with Gradle's built-in `tarTree(resources.bzip2(...))` into
   `build/generated/espeak/assets/espeak-ng-data/`, registered as an extra assets directory.
   Hooked on `preBuild`, so a clean checkout builds without a manual step; a checksum mismatch
   fails the build with the same message shape the sherpa task uses.
2. **At runtime** — `EspeakData.ensure(context, root)`: copy `assets/espeak-ng-data/**` to
   `filesDir/espeak-ng-data/` once, and write a `VERSION` stamp containing the archive's
   SHA-256. A stamp that does not match the build's constant means a bump happened and the
   directory is replaced. ~18 MB of small files, a second or two on first run, on
   `Dispatchers.IO` inside `prepare()`.

APK cost: about **+7 MB** compressed. Alternatives considered and rejected: downloading 355
files individually at runtime (355 round trips for 18 MB), or a bzip2 decoder on the phone for
one archive (a dependency for one file). Pruning the directory to `de_dict`, `en_dict` and the
shared tables would save ~14 MB uncompressed; worth doing only if the APK size ever matters
more than the risk of breaking a voice that needs a file we cut.

---

## Part B — The voices (`:android:pipeline`, `io.dobby.pipeline.tts`)

### B1. The catalogue

```kotlin
/** One voice the panel can speak with. Ids are persisted in settings; never change one. */
data class VoiceOption(
    val id: String,              // "thorsten", "cori", "system"
    val name: String,            // "Thorsten", "Cori", "Android-Stimme"
    val language: String,        // BCP-47: "de-DE", "en-GB"; "" for the system voice
    val description: String,     // one line for the settings row
    val directory: String,       // under files/voices/, "" for the system voice
    val files: List<RemoteFile>, // model + tokens; empty for the system voice
    val gain: Float = 1f,        // C3
)

object VoiceCatalogue {
    val THORSTEN: VoiceOption   // the default
    val CORI: VoiceOption
    val SYSTEM: VoiceOption     // Android TextToSpeech, exactly what plays today
    val ALL = listOf(THORSTEN, CORI, SYSTEM)
    val DEFAULT = THORSTEN
    fun of(id: String?): VoiceOption = ALL.firstOrNull { it.id == id } ?: DEFAULT
}
```

The system voice is a catalogue entry rather than a special case in the UI, for two reasons: it
is the fallback when a download fails or a graph will not load, and it is the way back if a
Piper voice turns out to mispronounce something the platform got right. Keeping the old code
path alive with a name is cheaper than keeping it alive as dead code.

### B2. The store

`VoiceStore(root)` mirrors `WakeWordModelStore`:

- `options()` — the catalogue plus **sideloaded** voices: any directory under `files/voices/`
  that holds one `*.onnx` and a `tokens.txt` and is not a catalogue entry, named from the
  directory (`vits-piper-de_DE-thorsten-medium` → "Thorsten Medium"). That is how a `medium`
  export, `thorsten_emotional`, or any other Piper voice gets tried without a rebuild:
  ```sh
  adb push vits-piper-de_DE-thorsten-medium /data/local/tmp/ && adb shell run-as io.dobby.android \
    sh -c 'mkdir -p files/voices && cp -r /data/local/tmp/vits-piper-de_DE-thorsten-medium files/voices/'
  ```
- `ensureAvailable(option)` — per-file download into `files/voices/<directory>/` with
  `Verified` stamps, one size-weighted `DownloadProgress`, and a `StateFlow<VoiceState>`:
  `Absent`, `Downloading(name, percent)`, `Verifying`, `Ready(files)`, `Failed(reason)`. Returns
  null on failure; the reason is in the state.
- `isPresent(option)` — for the settings row, without downloading.
- `delete(option)` — frees 114 MB, for a settings screen that may later offer it.

**First run grows from 670 MB to 784 MB.** Thorsten is fetched in `prepare()` after the speech
models and the wake word, with its own status line ("Lade Stimme… 40 %"). Until it lands, the
system voice speaks — the panel is never mute on first run, which it is today if no German
platform voice exists. Cori is fetched the first time she is chosen, like a wake-word head.

---

## Part C — Speaking (`:android:pipeline`, `io.dobby.pipeline.tts`)

### C1. One interface, two speakers

```kotlin
interface Speaker {
    suspend fun awaitReady(): Boolean
    suspend fun say(text: String)   // returns when the audio has finished playing
    fun stop()
    fun shutdown()
}
```

- `PlatformSpeaker` — today's [Speaker.kt](android/pipeline/src/main/kotlin/io/dobby/pipeline/tts/Speaker.kt),
  renamed, unchanged otherwise.
- `PiperSpeaker(files, dataDir, gain)` — below.

`VoicePipeline` holds `@Volatile var speaker: Speaker = PlatformSpeaker(...)` from construction,
and a `speaking = Mutex()` that `say()` takes. Swapping voices happens under that mutex, so a
sentence in flight finishes in the voice it started in and the next one starts in the new one.

### C2. `PiperSpeaker`: synthesise per sentence, play as a stream

```kotlin
private val tts = OfflineTts(
    config = OfflineTtsConfig(
        model = OfflineTtsModelConfig(
            vits = OfflineTtsVitsModelConfig(model = onnx.path, tokens = tokens.path, dataDir = espeak.path),
            numThreads = 2,          // the two big cores; measured in step 0
        ),
        maxNumSentences = 1,         // one callback per sentence — this is what makes streaming work
    ),
)
```

`say(text)` runs on a **dedicated single thread** (`newSingleThreadContext("dobby-tts")`):
synthesis is CPU-bound and blocks, the JNI callback arrives on the calling thread, and a
blocking `AudioTrack.write` on a `Dispatchers.Default` worker would starve the wake word's
inference. One thread also serialises sentences for free.

1. Build an `AudioTrack`: `ENCODING_PCM_FLOAT` (no conversion — sherpa hands back floats in
   −1..1), mono, the rate from `tts.sampleRate()` (asserted 22 050 in the device test, never
   hard-coded), `MODE_STREAM`, buffer ≥ 1 s so the first write never underruns. Attributes
   `USAGE_ASSISTANT` + `CONTENT_TYPE_SPEECH` — the same words M2b's turn duck uses to describe
   itself, and a usage that maps to the media volume, which is the lesson
   [Earcon.kt:30](android/pipeline/src/main/kotlin/io/dobby/pipeline/tts/Earcon.kt#L30) paid for.
2. `track.play()`, then `tts.generateWithCallback(text) { samples -> … }`: each callback is one
   sentence; multiply by `gain`, `write(samples, 0, n, WRITE_BLOCKING)`, count frames, return
   `1` to continue or `0` if `stop()` was called. **Playback starts after the first sentence
   is synthesised**, not after the whole answer — for a Help answer of three sentences that is
   the difference between one second of silence and three.
3. Drain: `setNotificationMarkerPosition(framesWritten)` and suspend on `onMarkerReached`,
   with a timeout of the audio length plus one second so a lost marker cannot hold the
   microphone shut forever. Then `stop()`, `release()`.
4. `stop()` flips a flag the callback reads, then `pause()`/`flush()` on the track — this is
   what cancellation of `say()` calls, exactly as `tts.stop()` is today.

**No audio focus here.** Inside a turn, M2b's duck already holds it. Outside a turn, the only
caller is `announce()` from a Sock — the timer firing — and Clock already holds transient focus
while it rings ([TimerEngine.kt:162](socks/clock/src/main/kotlin/io/dobby/socks/clock/TimerEngine.kt#L162)).
A third focus request from the same process, over the top of those two, is the open question
m2b-plan §Open risks already lists; this plan does not add to it.

### C3. Gain, and what is not normalised

The catalogue carries a per-voice linear gain, default 1.0, applied to the float samples before
the write and clipped to ±1. Cori measures ~5 dB below Thorsten on comparable sentences; the
value is set from an on-device RMS measurement across the answer set (Sequence, step 6), not
from the host peaks above. Nothing else is done to the text: espeak-ng reads German digits,
times and units on its own, and the Socks already write for the ear ("halb 3", "8 mal 2 ist
16"). If an answer reads wrongly the fix is in the Sock's string, where the platform engine's
mispronunciations were also fixed — sherpa's `ruleFsts` normalisation hook exists if a class of
strings ever needs it.

### C4. Memory, and the order of retreat

ONNX Runtime loads the 114 MB graph into memory; expect **~130–160 MB resident** for the loaded
voice, plus espeak-ng's tables. Only one voice is ever loaded — choosing another frees the
first (`tts.free()`). `dobby-plan.md` §9's order under pressure gains one line: Tier 2's KV
cache goes first, **then the voice** (freed on `onTrimMemory(RUNNING_CRITICAL)` and reloaded
lazily on the next `say()`, ~1 s, with the system voice covering that one sentence), and STT is
never touched. Measured in step 0 with `dumpsys meminfo`; the numbers go in *Measured*.

---

## Part D — The setting (`:android:app`)

### D1. Persistence

`Settings.voiceId: String?` under key `voice.id`, read through `VoiceCatalogue.of()` so a
stale or unknown id — a sideloaded voice that was deleted, a value from an older build — falls
back to Thorsten instead of throwing. Same shape and same reasoning as `listenCue`.

### D2. Through the seam

`VoiceIo` gains three members, in the shape `selectWakeWord` / `wakeWordOptions` /
`selectedWakeWordId` already have:

```kotlin
fun voiceOptions(): List<VoiceOption>
val selectedVoiceId: String
val voiceState: StateFlow<VoiceState>   // so the settings row can show a download
suspend fun selectVoice(id: String)
```

`VoicePipeline.selectVoice`: resolve; if `SYSTEM`, swap to the `PlatformSpeaker`; else
`store.ensureAvailable` (the row shows the percentage), load a `PiperSpeaker` on IO, swap under
the mutex, free the old one. On any failure the previous speaker stays and `voiceState` says
why. Then Dobby **says one sentence in the new voice** — "Ich bin Thorsten." / "Hello, I'm
Cori." / "Ich bin die Android-Stimme." — because a voice is chosen by ear, and a radio button
that changes nothing you can hear until the next timer fires is a setting nobody trusts.

`DobbyController.selectVoice(id)` persists and forwards, like `setListenCue`; `DobbyUiState`
gains `voices`, `voiceId` and `voiceState`. `DobbyService` passes `settings.voiceId` into the
pipeline constructor beside `wakeWordId` and `listenCue`.

### D3. The screen

A fourth section in `SettingsScreen`, `SectionLabel("Stimme")`, between the wake-word list and
the listen cue, one `ChoiceRow` per option:

| Row | Subtitle |
|---|---|
| **Thorsten** | "Deutsch · 114 MB" — or "Wird geladen… 37 %" while fetching, or the failure reason |
| **Cori** | "Englisch (britisch) · 114 MB · Liest deutsche Antworten mit englischer Aussprache." |
| **Android-Stimme** | "Die Stimme des Telefons, wie bisher." |
| *sideloaded* | "Eigene Stimme · <directory>" |

The Cori subtitle says the awkward thing where it is chosen, the way the wake-word footnote does
for English-trained heads: until Part E exists, picking her is picking pronunciation, not
language.

---

## Part E — What "English output" needs, and why it is not here

The request behind Cori is a panel that can *answer* in English. The voice is the smaller half:

- Every spoken string lives in a Sock — Clock's times, Calculator's read-backs, Help's
  discovery sentences, `DobbyEngine.NOT_UNDERSTOOD` — and they are German literals. Roughly
  forty strings across the three product Socks today; Spotify, Radio, System and Departures
  will add more. Answering in English means a `Locale` on `SockContext` and a per-Sock strings
  table, which is a contract change every Sock has to implement and every spec's utterance
  table has to cover.
- Understanding stays German either way: the Tier 1 palette and the Tier 2 few-shots are
  German, and Parakeet's multilingual decoding is what makes an English *title* inside a German
  command survive, not what makes an English command match.
- `GermanTime`, `GermanNumbers` and the normaliser are language-specific by name.

So the honest shape is: **a language setting (M2d or part of M3) selects the answer language,
and the voice follows it** — `VoiceCatalogue.defaultFor(language)` — with the voice setting
kept as an override. This plan lays the id and the language tag on each `VoiceOption` so that
follow-up does not have to change what is stored, and stops there.

---

## Locked decisions

- **sherpa-onnx's `OfflineTts`, not a second runtime.** The JNI library already exports it;
  `piper-phonemize`, ONNX Runtime and espeak-ng are all inside the 24 MB that ships today.
- **Voices are downloaded, never bundled** (`dobby-plan.md` §4): 114 MB each, per-file
  SHA-256, revision-pinned Hugging Face URLs. Phoneme data is the exception — 7 MB, shared by
  every voice, fetched at build time and shipped as an asset.
- **Thorsten `high` is the default, as asked** — and Sequence step 0 measures it on the phone
  before anything else is built. If its real-time factor on the A78 is above ~1.0 the catalogue
  default becomes `de_DE-thorsten-medium` (63 MB) and `high` stays an option; the user chose
  quality, the device decides whether it can afford it, and the plan does not pretend to know.
- **The Android voice stays as a catalogue entry**, not as dead code: it is the first-run
  voice, the failure fallback, and the way back.
- **Streaming by sentence, on one dedicated thread.** `maxNumSentences = 1`,
  `generateWithCallback`, `AudioTrack` in float. `say()` keeps its contract: it returns when the
  audio has finished playing.
- **The speaker takes no audio focus.** The turn's duck (M2b) and the Sock's own focus already
  cover both callers.
- **`USAGE_ASSISTANT` / `CONTENT_TYPE_SPEECH`**, media volume. Never a notification stream.
- **Sample rate comes from the model**, never from a constant.
- **Sideloaded voices are first-class**, for the same reason sideloaded wake words are.
- **No change to what is said.** Part E is a separate decision.

---

## Verification

JVM, in `./gradlew build`:

- **`VoiceCatalogueTest`** — every remote file pinned by size and lowercase SHA-256; every
  Hugging Face URL pinned to a revision, none to `main`; ids unique; `DEFAULT` is Thorsten;
  `of()` falls back for null, unknown and a sideloaded-style id; `SYSTEM` has no files and no
  directory; Thorsten's language is `de-DE`, Cori's `en-GB`.
- **`EspeakDataTest`** — the stamp logic as a pure function: missing stamp, matching stamp,
  stale stamp, unreadable stamp; and the manifest count (355) so a truncated asset copy is a
  failing test rather than a voice that loads and says nothing.
- **`DobbyControllerTest`** additions — `selectVoice` persists to `Settings` and reaches the
  pipeline; the UI state carries the options, the selection and the download state; a
  `FakeVoice` with a `Failed` state leaves the previous selection in place.
- Existing `SpeechModelsTest` progress tests cover the voice download's weighting: it is the
  same `DownloadProgress`.

Instrumented, like `SttTemplateTest` (skips itself when the voice is absent):

- **`PiperVoiceDeviceTest`** (`:android:app` androidTest) — for each catalogue voice present on
  the device: loads, `sampleRate() == 22_050`, `numSpeakers() == 1`; synthesises a fixed set of
  real answers (Clock's "Es ist halb 3.", Calculator's "8 mal 2 ist 16.", Help's three-sentence
  discovery answer, a Departures-style sentence with "U4" and a stop name, and one with an
  English title) and asserts each is non-silent with peak ≤ 1.0; logs **load time, first-chunk
  latency, real-time factor per sentence, and RSS before/after** — the instrument for step 0
  and the numbers that go into *Measured*.
- Gradle: a deliberately wrong `espeakDataSha256` fails `preBuild` with the checksum message
  (checked once by hand, like the sherpa task).

---

## Sequence

0. **Measure before building.** Vendor `Tts.kt` (A1). Write `PiperVoiceDeviceTest`. Push the
   local copies from `android/pipeline/build/piper-voices/` into `files/voices/` with the
   `run-as` recipe in B2 and the phoneme data alongside. Run the test; read RTF, first-chunk
   latency and RSS off logcat. **Decide `high` or `medium` for the default here**, and the
   thread count. Half a day. — *Done except the measurement itself: `Tts.kt` is vendored, the
   test is written, the voices are on the device. The install is blocked on a signing mismatch;
   see* Measured.
1. Phoneme data: `fetchEspeakData` in Gradle, `EspeakData.ensure` at runtime, `EspeakDataTest`.
2. `VoiceOption`, `VoiceCatalogue`, `VoiceStore`, `VoiceCatalogueTest`.
3. `Speaker` interface, `PlatformSpeaker` rename, `PiperSpeaker`; `VoicePipeline` wiring —
   speaker swap under the mutex, Thorsten fetched in `prepare()` after the wake word, status
   lines. First run on the phone end to end.
4. `Settings.voiceId`, the three `VoiceIo` members, `DobbyController.selectVoice`, `DobbyUiState`
   fields, the "Stimme" section, the spoken confirmation. `DobbyControllerTest` additions.
5. Docs: README module table ([line 36](README.md#L36): "Android TTS" → "Piper voices through
   sherpa-onnx, Android TTS as fallback"), `dobby-plan.md` [§4 line 241](dobby-plan.md#L241),
   `android/sherpa/README.md` file list, the test list in the README. Commit as `M2c: …`.
6. On the wall for a few days. Set Cori's gain from an RMS measurement across the answer set,
   note any sentence Thorsten reads worse than Google did and fix it in the Sock, confirm the
   memory numbers under a running Tier 2.

Step 0 is the only step that can send the plan back to the drawing board, which is why it is
step 0 — and why *Measured* says exactly what it is still allowed to change now that it has run
last instead.

---

## Measured

*(filled in as the sequence runs; host numbers from the check above go here for comparison)*

| | Host (i7-12700H, 2 thr) | Device (MT6877, 2 thr) |
|---|---|---|
| Thorsten high — load | 0.6 s | — |
| Thorsten high — RTF | 0.28–0.33 | — |
| Thorsten high — first chunk, 1 s sentence | 0.31 s | — |
| Cori high — RTF | 0.31–0.34 | — |
| Resident memory, voice loaded | — | — |
| Thorsten peak / Cori peak (same sentences) | 0.50 / 0.28 | — |

**The device column is outstanding, and step 0 ran out of order because of it.** The
`io.dobby.android` installed on the phone was signed with a different debug key, so the
instrumented APK cannot be installed over it (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`) and the
only way through is an uninstall — which takes Parakeet's 670 MB, the wake-word heads and the
settings with it. Not worth spending somebody's connection on before the code was written, so
the sequence was run 1 → 5 first and the measurement is the next thing to do:

```sh
# voices onto the device (they are already in /data/local/tmp from the first attempt)
adb shell 'run-as io.dobby.android sh -c "mkdir -p files/voices/vits-piper-de_DE-thorsten-high \
  && cp /data/local/tmp/de_DE-thorsten-high.onnx files/voices/vits-piper-de_DE-thorsten-high/ \
  && cp /data/local/tmp/thorsten-tokens.txt files/voices/vits-piper-de_DE-thorsten-high/tokens.txt"'

./gradlew :android:app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=io.dobby.android.tts.PiperVoiceDeviceTest
adb logcat -d -s DobbyVoice
```

**What that measurement is still allowed to change.** The catalogue ships `high` as the default,
as asked. If the real-time factor on the A78 comes back above ~1.0, the fallback decided in
advance applies — `de_DE-thorsten-medium` (63 MB) becomes `VoiceCatalogue.DEFAULT` and `high`
stays an option. That is a two-line change to `VoiceCatalogue` plus the pinned checksums, and
`VoiceCatalogueTest` is what says the ids did not move underneath somebody's setting.

---

## Open risks

- **`high` may be too slow on the phone.** The whole reason for step 0. The fallback is
  decided in advance (medium as default, high as an option), so it is a number, not a debate.
- **A gap between sentences** if synthesis of sentence *n+1* takes longer than playback of
  sentence *n* — RTF above 1.0 shows up as a pause at every full stop, which is more tolerable
  than a delay before the first word, but is still audible. Same measurement, same fallback.
- **Memory.** ~150 MB on top of Parakeet resident and Tier 2's gigabyte, on a device the
  README does not describe. C4's trim hook is the mitigation; the device test's RSS column is
  the evidence.
- **espeak-ng reads some German differently from Google TTS.** Abbreviations ("U4", "Hbf"),
  times as digits, and English titles inside German sentences are the likely places. The fix
  is in the Sock's strings, and the device test's fixed sentence set is where a regression
  shows up first.
- **Third focus request from one process** when a timer announces during a turn — inherited
  from M2b's open list, not created here, and not made worse: the speaker requests nothing.
- **Upstream.** The Piper project moved to OHF-Voice in 2025; the `rhasspy/piper-voices`
  repository and the sherpa mirrors are still where these files live, and the revision pins
  mean a move upstream breaks the *download*, loudly, rather than the voice quietly.
- **The README's device.** Whatever the panel finally hangs on, the numbers in *Measured* are
  from the phone on `adb` today, and the README should name it.

---

## Not in this plan

- **English answers** — Part E. A language setting and per-Sock strings; the voice follows.
- **A speaking-rate setting** (`speed` / `length_scale`) — one float already plumbed through
  `generateWithCallback`; add it when somebody asks for it.
- **Two voices resident at once**, or switching voice per sentence (an English title in a
  German sentence read by Cori). Costs 150 MB and solves a problem nobody has reported.
- **A JVM contract test for the voice** like the wake word's. sherpa-onnx publishes no JVM
  artifact this project uses; the instrumented test carries that weight, as `SttTemplateTest`
  does for Parakeet.
- **The chime and the pip.** Unchanged; they are not speech.
- **The sideload path in the README and `WakeWordModelStore`'s comment** (`/sdcard/Android/data/…`
  does not exist; the models live in `filesDir`). Worth a one-line commit of its own.
