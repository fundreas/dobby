# M6 — the fallback tier: phonetic keyword matching, then the local LLM

## Context

The panel's real failure mode today is not "I asked for something Dobby cannot do". It is
**one word misheard and the whole command dies**. Tier 1 matches token-by-token with a
Levenshtein tolerance of 0/1/2 by keyword length, and that tolerance cannot reach the plan's
own motivating example: "schbiele" is 3 edits from "spiele" and no tolerance that catches it
is safe for a six-letter word. When the keyword misses, the template misses, and the whole
utterance falls through to two buzzes.

There are two different fixes for that, at wildly different costs, and the plan already names
both:

1. **Phonetic keyword matching** (Kölner Phonetik, `dobby-plan.md` §5.3/§9) — pure Kotlin, no
   model, no latency, no RAM. "spiele" and "schbiele" both reduce to the code `815`. This is
   the one that actually targets the stated symptom.
2. **Tier 2, the local LLM** (§5.4, M6) — llama.cpp + Qwen3 1.7B with a generated GBNF
   grammar, invoked only when Tier 1 finds no match. This catches paraphrases Tier 1 was never
   meant to reach ("kannst du bitte irgendwas Ruhiges von Queen anmachen"), and it catches
   garbled sentences too, at ~2–4 s and ~300 MB of anonymous memory.

So phonetics ships **first and alone**, then Tier 2. Phonetics is likely to remove the majority
of the single-word failures at zero runtime cost, and it makes Tier 2's job smaller. Both are
in scope; the ordering is the point.

Outcome: a misheard keyword no longer kills a command; an utterance no template can reach gets
one bounded attempt at a local model before the panel buzzes; and nothing on this list ever
leaves the phone.

---

## Part A — Phonetic keyword matching (ships alone, before any Tier 2 code)

### A1. The insertion point

There is exactly one place a template literal is compared to a spoken token:
[CompiledTemplate.kt:59](core/src/main/kotlin/io/dobby/core/nlu/template/CompiledTemplate.kt#L59),
`Levenshtein.fuzzyEquals(tokens[state.position], node.text)`. Everything below hangs off that
one line.

### A2. The danger, and the rule that contains it

Kölner Phonetik maps every vowel to `0` and then drops it, so short German words collapse
catastrophically: `an`/`in`/`ein` all code to `06`, and `aus`/`es`/`ist`/`hat` all code to `08`.
Unguarded phonetic matching over the current palette produces **31 colliding code groups**.

Two length guards (keyword and token both ≥ 5 characters, code ≥ 3 digits, exact code
equality) cut that to 9 groups. The rule that eliminates the rest:

> A Kölner code is **contested** if two palette keywords share it and are *not* already
> Levenshtein-equivalent. Keywords with a contested code are excluded from the phonetic tier
> entirely — both sides of the collision, not one.

Computed once from the palette's own literals at construction. It is self-maintaining: a Sock
added in M4 whose keyword collides phonetically with an existing one retires both automatically
rather than silently creating a misroute. Against today's palette that retires
`bereiche, breche, brich, kannst, kennst, kommt, stell, stelle, still, weiter, wieder`, leaves
**84 of 178 keywords** phonetically protected, and — verified against a 96-word corpus of
plausible non-command German — produces **zero** false matches, including the
`wetter`→`weiter` case that `SpecPaletteTest` already guards as a negative.

Recovered garbles include `schbiele`→`spiele`, `schtopp`→`stopp`, `teimer`→`timer`,
`urzeit`→`uhrzeit`, `musick`→`musik`.

### A3. Files

- **New** `core/src/main/kotlin/io/dobby/core/nlu/template/Phonetics.kt` — `object Phonetics {
  fun koelner(word: String): String }`. Fold `ä ö ü`→`a o u` and `ß`→`ss` *before* coding (the
  Normalizer does not strip umlauts); digits code to `""`; standard context rules for `c`,
  `d/t`, `p`, `x`; collapse adjacent repeats; drop all `0` except a leading one.
- **New** `core/src/main/kotlin/io/dobby/core/nlu/template/KeywordMatcher.kt` — layered
  `matches(token, keyword)`: exact → `Levenshtein.fuzzyEquals` → phonetic, the last gated on the
  eligible map. `KeywordMatcher.over(keywords)` builds it; `KeywordMatcher.STRICT` is the
  pre-M6 behaviour and the A/B baseline. Constants `MIN_LENGTH = 5`, `MIN_CODE_LENGTH = 3`.
- **Edit** [CompiledTemplate.kt](core/src/main/kotlin/io/dobby/core/nlu/template/CompiledTemplate.kt)
  — add `val literals: List<String>` (mirroring the existing `collectSlots`), and take a
  defaulted `keywords: KeywordMatcher = KeywordMatcher.STRICT` parameter *before* the trailing
  `enumValues` lambda so all three existing call sites keep compiling.
- **Edit** [Palette.kt](core/src/main/kotlin/io/dobby/core/registry/Palette.kt) — build the
  matcher from `entries.flatMap { it.template.literals }`, pass it into `template.match(...)`.
  A `phonetic: Boolean = true` constructor flag gives the tests their baseline.
- **Edit** [cli/Main.kt](cli/src/main/kotlin/io/dobby/cli/Main.kt) +
  [Discovery.kt](cli/src/main/kotlin/io/dobby/cli/Discovery.kt) — `/keywords`: every literal,
  its code, and whether phonetics trusts it. This is the tool you will actually reach for when
  a new Sock stops something matching.

Nothing in `SockRegistry`, `DobbyEngine`, the Socks or Android changes.

### A4. Optional follow-up commit

`CompiledTemplate.kt:107` already does a Levenshtein-1 fallback for `{x:enum}` slots over a
closed candidate set; the same phonetic tier is safe there. Real enum values are all distinct
by code (`sekunden/minuten/stunden` → `84626/6626/82626`, `lauter/leiser` → `527/587`). Ship as
a separate, revertible commit.

---

## Part B — Tier 2, the pure half (`:core`, no Android, no model)

Package `io.dobby.core.nlu.llm`. All of it is JVM-testable without a phone, which is why it goes
first.

### B1. The generators

| File | What |
|---|---|
| `Tier2Catalog.kt` | the registry view the generators read: `registry.commands` (holds exclusive **and** shared specs, each shared one exactly once — which is the plan's "one branch per shared command", for free) |
| `GrammarGenerator.kt` | `SockRegistry` → GBNF. One branch per command, fixed JSON shape, plus `cmd-none` |
| `PromptGenerator.kt` | `SockRegistry` → the German system prompt |
| `TokenEstimate.kt` | a stand-in tokenizer (`:core` has none), deliberately pessimistic |
| `Tier2Json.kt` | hand-rolled decoder/encoder for the one shape the grammar admits |
| `Tier2.kt` | `Tier2Resolver` interface, `Tier2Outcome`, `Tier2Program`, and the gating wrapper |

**Grammar decisions that are not obvious:**

- **Rule names use hyphens, not underscores.** llama.cpp's GBNF parser identifies rule names
  with `is_word_char`, which accepts `[a-zA-Z0-9-]` and not `_`. `dobby-plan.md` §5.4's
  `cmd_play_music` sketch is pseudocode. **Verify this in the native spike before the golden
  file is written** (Part C, step 1) — it is the single highest-risk assumption here.
- **Optional params get a `null` alternative** (`p-system-volume-steps ::= int | "null"`)
  rather than grammar-level optionality. The shape stays fixed; the decoder drops nulls; and
  `ParamCoercion` then applies `ParamSpec.default` — reusing the existing machinery instead of
  duplicating it. A required param that cannot be filled must produce `cmd-none`.
- **`int ::= [0-9] | [1-9] [0-9] | [1-9] [0-9] [0-9]`**, not `[0-9]+` — an unbounded repetition
  invites `99999999999` and an overflow in the decoder.
- **`char ::= [^"\\]`** — text params carry Normalizer output, which has no punctuation to
  escape. The decoder still handles escapes anyway, because `:core` cannot verify the native
  side actually applied the grammar.
- **Deterministic ordering** (exclusive-then-shared, each sorted by id; params and enum values
  in declaration order; nothing iterates a map) so the golden test means something.

**Prompt decisions:**

- Generated from `registry.commands` for *structure* — a grammar needs `ParamType.Enumeration.
  values` as a list, and `Introspection.describe(param)` stringifies it to `"enum[a|b|c]"`.
  Re-parsing a display string to build a grammar is exactly the coupling that breaks silently.
- Generated from a **new** `Introspection.promptExamples(commandId)` for *examples*, because
  which examples an audience may see is genuinely Introspection's policy question.
  [Introspection.kt:83](core/src/main/kotlin/io/dobby/core/registry/Introspection.kt#L83)
  filters to `matchedByTemplates` and drops `Example.params` — correct for spoken help, exactly
  inverted for few-shots. The new accessor returns the Tier 2 paraphrases first, then
  `SharedSubscription.extraExamples` (read by nothing today), then the Tier 1 examples.
- **At most two example lines per command**, preferring `matchedByTemplates = false`, plus one
  `none` line. Fixed, not budget-adaptive — an adaptive selector would churn the golden file
  every time an unrelated Sock lands.
- Every few-shot is rendered through `Tier2Json.encode`, so it is grammar-legal by construction.
- **`require(Normalizer.normalize(utterance) == utterance)`** on every emitted line. Tier 2 sees
  the *normalized* string ("zwanzig" is already "20"), so a few-shot written in raw German
  teaches the model a surface form it will never be shown. This is the subtlest bug available in
  this milestone; make it a build failure.

### B2. The budget tripwire

`PromptBudgetTest` fails the build when `TokenEstimate.of(prompt) > PromptGenerator.MAX_TOKENS`
(1500, `dobby-plan.md`:418). Measured: **~790 estimated tokens at today's 7 commands**.
Projected, the tripwire fires at **13–14 commands** — i.e. partway through M4, *before* the full
~18-command registry lands. The failure message carries the three levers in order (one example
line per command; drop the `"params"` wrapper; shard by Sock / two-step), so the contingency is
written down once and nobody has to remember it. Do not build sharding now.

### B3. Decoder and the second gate

`Tier2Json.decode` is hardened against the model emitting something the grammar should have
prevented: bounded at 2 KB, scans to the first balanced `{` (tolerating prose around it), depth
cap 3, rejects floats/arrays/nested objects/duplicate keys, drops `null` values, never throws.

Then the gate, which is what makes "malformed or unknown-command output is impossible" true
rather than hoped for:

```kotlin
if (reply.commandId == CommandInvocation.NONE) return Tier2Outcome.NoCommand
val spec = registry.commands[reply.commandId] ?: return Rejected("unknown command")
val params = ParamCoercion.coerce(spec, reply.params.mapValues { it.value.toString() })
    ?: return Rejected("params do not satisfy ${spec.id}")
```

`SockRegistry.build` already rejects any Sock declaring the id `"none"`, so `registry.commands
["none"]` is always absent and the `NONE` branch is the sole meaning of that grammar branch —
closing the loop the reserved id was opened for. Routing LLM output back through
[ParamCoercion](core/src/main/kotlin/io/dobby/core/registry/Palette.kt#L70) costs one
`mapValues` and buys the entire Tier 1 validation path: the LLM gets no route into a Sock that
a template does not also have.

### B4. Engine wiring

[DobbyEngine.kt:47-58](core/src/main/kotlin/io/dobby/core/DobbyEngine.kt#L47-L58) is the seam,
already commented as such.

```kotlin
class DobbyEngine(
    val registry: SockRegistry,
    private val dispatcher: Dispatcher = Dispatcher(registry),
    private val onFallthrough: (Fallthrough) -> Unit = {},   // was (String) -> Unit
    private val tier2: Tier2? = null,                        // null = today's behaviour exactly
) {
    suspend fun handle(raw: String, useTier2: Boolean = true): EngineOutcome
}
```

`EngineOutcome` gains `tier: Tier` (`TEMPLATE` / `MODEL` / `NONE`) and `tier2: Tier2Trace?`,
both defaulted, so existing tests compile untouched. `Fallthrough(utterance, outcome, latency)`
replaces the bare string — that is the flywheel's raw material, and its `toString()` is
`weck mich in 20 minuten → clock.set_timer{amount=20, unit=minuten} 1842ms`, which is enough
to promote a phrasing into a template by hand.

Four outcomes: **resolved** → dispatched normally, no buzz, turn ends; **none** / **timeout** /
**unavailable** → identical to today's not-understood path. With `tier2 = null` the engine is
byte-for-byte what it is now.

Call sites: [cli/Main.kt:59,79](cli/src/main/kotlin/io/dobby/cli/Main.kt#L59) and
[DobbyController.kt:120](android/app/src/main/kotlin/io/dobby/android/DobbyController.kt#L120)
change; `DeviSockTest` changes its list type; `ClockSockTest`, `ClockChainTest` and
`HelpSockTest` need no change (all new params defaulted).

### B5. Example hygiene (small, do it early)

- Fill params on the two `set_timer` few-shots and **rewrite `"weck mich in zwanzig minuten"`
  to `"weck mich in 20 minuten"`** ([ClockSock.kt:101-102](socks/clock/src/main/kotlin/io/dobby/socks/clock/ClockSock.kt#L101)).
  The other five `matchedByTemplates = false` examples point at zero-param commands, so
  `emptyMap()` is already correct.
- Extend `SockRegistry.checkExamples()` with two gates it currently has no opinion on: a Tier 2
  few-shot that Tier 1 *can* match is a bug (the flag is wrong or the template is), and
  `extraExamples` are checked by nothing at all today.

---

## Part C — Tier 2, the native half (phone only)

New Gradle module `:android:llama`. This is the first NDK/CMake build in the repo.

### C1. Build from pinned source, not a prebuilt

The `:android:sherpa` fetch-a-prebuilt pattern does not transfer: no upstream artifact exposes
grammar-constrained sampling plus KV control, we need our own JNI shim compiled against
`llama.h` either way (so the NDK is required regardless), and upstream prebuilts are
`BUILD_SHARED_LIBS=ON` — four more `.so` files in an APK whose whole discipline is one of each.

Follow sherpa's *verification* pattern exactly (`inputs.property("url"/"sha256")`,
`outputs.dir`, re-hash, `check(actual == expected)` with a three-line diagnostic, wired to
`preBuild`), applied to the source tarball instead of a binary:

- llama.cpp **`v0.4.1`** (confirmed as the current non-prerelease), commit `b29c606e…`, tarball
  sha256 `ef3d5b1907a391500ae11b5e61a8e2022e0deaac9790899cad9c4e02f03bfb9a`, 37 422 659 bytes.
- `CMakeLists.txt` does `add_subdirectory` on it with `BUILD_SHARED_LIBS=OFF`, `GGML_NATIVE=OFF`,
  `GGML_OPENMP=OFF`, `GGML_BACKEND_DL=OFF`, everything else (tools, examples, server, curl) off.
- `ANDROID_STL=c++_static` and `CMAKE_BUILD_TYPE=Release` for **both** variants — AGP hands the
  debug variant `-O0`, and the debug APK is the one that gets sideloaded.
- One output, `libdobby-llama.so`, with a version script exporting only
  `Java_io_dobby_llama_*` plus `-Wl,--exclude-libs,ALL` and `--gc-sections`. Result is the same
  ELF shape as `libsherpa-onnx-jni.so`: `DT_NEEDED` = libandroid/liblog/libm/libdl/libc and
  nothing else. No `packaging { jniLibs { } }` rule is needed and none is added.

**Blocking unknown:** `GGML_CPU_ARM_ARCH`. The Snapdragon 750G is 2×A77 + 6×A55; `+dotprod` is
optional on A55 and the A55 cluster is what binds. Run `adb shell grep -m1 Features
/proc/cpuinfo` and look for `asimddp` **before** choosing the flag — guessing wrong is a SIGILL
on a `START_STICKY` service, i.e. a boot loop. `+i8mm` is definitively out (needs armv8.6-a).

### C2. JNI surface

Eight functions, all `jlong` handles and `jstring` — no C++ object crosses the boundary, which
is what makes `c++_static` safe: `nativeInit`, `nativeLoadModel`, `nativeFreeModel`,
`nativeNewContext`, `nativeFreeContext`, `nativePrefillSystem`, `nativeGenerate`,
`nativeCancel`, plus `nativeTokenCount` (for the estimator calibration) and `nativeTimings`.

Verify against the pinned header rather than against tutorials — the API has drifted:
`llama_model_params` no longer has `use_mmap`; it is `load_mode = LLAMA_LOAD_MODE_MMAP`.
Sampler chain is `llama_sampler_init_grammar(vocab, gbnf, "root")` **first** (it must see the
full candidate set), then `llama_sampler_init_greedy()`. Greedy, not `dist`: the grammar has
already removed every malformed continuation, and we want the same JSON for the same sentence
every time.

### C3. The prompt cache is the KV cache — no snapshot, no file

The plan says persist the KV cache; do neither of the obvious things.

- **No file.** The prefill happens once per *process*, not per request, on a service that runs
  for days. A session file buys a 100+ MB write per start, a second invalidation key and a new
  corruption mode.
- **No in-memory snapshot either.** Qwen3-1.7B is 28 layers × 8 KV heads × 128 head_dim = 112
  KiB/token at f16. A ~1100-token system prefix snapshots to **~120 MiB**, held for the life of
  the service, on top of the 224 MiB KV cache it is a copy of.
- **Instead:** `llama_memory_seq_rm(mem, 0, n_system, -1)` at the *start* of each generate. One
  call, microseconds, no allocation — "restore the snapshot" implemented as a pointer move.
  Doing it at the start rather than the end means a cancelled, timed-out or crashed request
  cannot leave the cache dirty for the next one.

`Tier2Program.fingerprint` (hash of prompt + grammar) is still recorded and still asserted
before each generate — a mismatch re-prefills rather than answering. It also goes in the
fallthrough log line, so a logged resolution says which palette produced it.

### C4. Numbers and lifecycle

`n_ctx = 2048`, `n_batch/n_ubatch = 256/128`, `type_k/type_v = F16` (→ ~224 MiB KV; `Q8_0`
halves it if measurement demands), `max_tokens = 64`, **`n_threads = 4`** — the same number and
the same reason as `ParakeetRecognizer.THREADS`, declared next to it so the two read as one
decision. Anonymous memory added: ~270–305 MiB. The 1.03 GiB of weights are clean, file-backed
mmap pages the kernel can drop for free — `LLAMA_LOAD_MODE_MMAP`, never `MLOCK`.

Loaded eagerly at service start but in **its own `scope.launch`** after `controller.start()`,
so the ~15–30 s prefill never delays the microphone. Never unload on an idle timer — that
reintroduces the prefill into the slowest path. Instead implement §9's "order of retreat" as a
real trigger: `DobbyService.onTrimMemory(TRIM_MEMORY_RUNNING_CRITICAL)` → `llama_free(ctx)`,
giving back ~280 MiB; the model mmap stays and the context re-prefills in the background. STT
is never touched.

Inference runs on a dedicated single-thread dispatcher at `NORM_PRIORITY - 1`, so load, prefill
and generate can never overlap and the context needs no lock.

### C5. The GGUF

`unsloth/Qwen3-1.7B-GGUF` (repo existence confirmed), revision
`d7f544eead698dbd1f15126ef60b45a1e1933222`, `Qwen3-1.7B-Q4_K_M.gguf`, **1 107 409 472 bytes**,
sha256 `b139949c5bd74937ad8ed8c8cf3d9ffb1e99c866c823204dc42c0d91fa181897` (from HF's `lfs.sha256`
and confirmed against `x-linked-etag`). Qwen's own `Qwen/Qwen3-1.7B-GGUF` has **no** Q4_K_M —
only Q8_0 at 1.83 GB, which the memory budget cannot take. `ggml-org/Qwen3-1.7B-GGUF` has one
at 1.28 GB and better provenance; it is the fallback if unsloth measures worse on the German
paraphrase suite.

`LlmModels` / `LlmModelStore` in `:android:pipeline` mirror `SpeechModels` / `SpeechModelStore`
exactly (`Downloader` is `internal` to that module, so the store lives there regardless).

**Fix a real regression while you are here:** `SpeechModelStore.isIntact` re-hashes 670 MB on
*every* service start; adding 1.1 GB makes that ~8–15 s of cold start. Add
`download/Verified.kt` — a `<file>.verified` stamp recording `sha256 + length + lastModified`,
with a full re-hash whenever any of the three moved or the stamp is missing/unreadable — and
retrofit it to `SpeechModelStore` in the same commit. It cannot answer "intact" about a file
that is not.

---

## Part D — Android wiring and the UX of a 2–4 s think

### D1. Buzz only after Tier 2 gives up

Buzzing on the Tier 1 miss would tell someone to repeat themselves while the command they
actually gave is still being resolved — they repeat it, Tier 2 finishes the first one and sets a
timer, Tier 1 matches the repeat and sets a second. A false "I didn't get it" is strictly worse
than a slow "I got it", because the false one causes an action nobody asked for.

**This needs no change in `DobbyController`.** `respondTo` already buzzes on
`!outcome.wasUnderstood`, and with Tier 2 *inside* `handle()`, `wasUnderstood` is already the
post-Tier-2 answer. That the existing seam is correct as written is the strongest argument for
putting Tier 2 in the engine rather than beside it.

### D2. Cap, and which rounds get it

`Tier2.DEFAULT_TIMEOUT = 5.seconds` — deliberately the same as
[`SPEECH_WINDOW`](android/app/src/main/kotlin/io/dobby/android/DobbyController.kt#L396). A panel
whose two waits are the same length reads as deliberate; one whose waits differ reads as broken.
The plan's ≤4 s done-when is the *measured* target the tier is tuned against; 5 s is the safety
net above it.

**Tier 2 runs on the first utterance of a turn only** — `respondTo(heard, useTier2 = attempts == 0)`.
After a buzz the person is deliberately rephrasing toward a command they believe exists, which
is Tier 1's best case, not Tier 2's; a second and third 5 s wait is what turns "it's thinking"
into "it's stuck". `MAX_CLARIFY_ROUNDS` stays 3, and a full turn stays bounded at ~15 s.

### D3. Text under the spinner

`detailOf` returns `""` for `Phase.THINKING` today, so a 4 s think is a spinner over nothing.
Replace the `thinking: MutableStateFlow<Boolean>` with a three-state `Thinking { NO, DISPATCH,
MODEL }`; `MODEL` renders **"Ich denke nach…"**. The controller cannot see when `handle()`
crosses from Tier 1 to Tier 2, so rather than adding a callback seam for a cosmetic need, flip
to `MODEL` from a sibling `launch` after 600 ms — a Tier 1 turn never lasts that long, and if a
Sock genuinely does, the text is still true.

### D4. Surfacing and the flywheel

`OutcomeDetail.detailLine()` gains a prefix, on the left because that is where the eye lands:

```
tier2 1.8s · clock.set_timer (amount=15, unit=minuten) · clock CONSUMED
tier2 4.9s · none
tier2 5.0s · timeout
```

A miss now gets a detail line *when Tier 2 was consulted* — "the model looked and said no" is
what tells you the tier is even running. Keyed on `tier2 != null`, so with no resolver the
function still returns null and the existing test passes unchanged.

`FallthroughLog` is a bounded (200) in-memory ring exposed as a `StateFlow`, readable by the CLI
and the settings screen. **Write the constraint into its KDoc:** it is a verbatim record of
things people said in their home — it stays on the device, stays bounded, and is clearable from
settings. Persisting it across reboots is an M7 decision that needs a retention policy first.

CLI gains `/grammar`, `/prompt` (with the token estimate and headroom in commands), `/tier2
<utterance>` against a scripted resolver, and the structured `/fallthrough`.

### D5. Failure modes — all of them degrade to Tier-1-only

`LlamaTier2.prepare()` never throws, exactly like `VoicePipeline.prepare()`: model missing,
download failed, OOM at load, prompt over context, inference timeout, unparsable reply, params
that do not coerce — every one becomes a state, `tier2` stays null or returns null, and the
panel behaves exactly as it does today.

The one that cannot be caught is a **native crash**, which on a `START_STICKY` service is a boot
loop. Tripwire: set `Settings.llmLoadAttempted = true` before the first-ever `nativeLoadModel`,
clear it after a successful prefill; if it is still set at the next start, skip Tier 2
permanently, surface "Tier 2 deaktiviert (Absturz beim Laden)" and offer a re-arm toggle. This
is specifically what makes the `-march` risk in C1 survivable.

---

## Locked decisions

| | |
|---|---|
| Order | phonetics ships and is merged **first**, alone |
| Runtime | phone only — no server, no desktop helper, at any point |
| Model | Qwen3 1.7B instruct Q4_K_M, `unsloth` conversion, pinned by revision + sha256 |
| Tier 2 lives in | `:core` generates prompt + grammar + gates (pure, JVM-tested); `:android:llama` runs it |
| Tier 2 input | the **normalized** utterance, same string Tier 1 saw |
| Prompt cache | KV truncation to `n_system`, in memory, no snapshot and no file |
| Cap | 5 s, first utterance of a turn only; ≤4 s is the measured target |
| Buzz | after Tier 2 gives up, never before |
| Prompt budget | tripwire test at 1500 tokens; sharding is **not** built now |

---

## Verification

**JVM (`./gradlew build`, no device, every build):**

- `PhoneticsTest` — reference codes (`müller-lüdenscheidt` → `65752682`), vowel collapse
  documented *as a hazard*, umlaut folding, digits → `""`.
- `KeywordMatcherTest` — `schbiele`→`spiele`; a ~20-pair German near-miss negative table
  (`an`/`aus`, `an`/`in`, `ton`/`tun`, `dem`/`den`, `hör`/`uhr`…); contested-code retirement;
  the retired list pinned **by name**, so the day a new Sock retires `spiele` the test says so.
- `PaletteCollisionTest` — enumerate every literal in the palette, group by code, assert zero
  surviving pairs that Levenshtein does not already conflate. This is the structural guarantee.
- `SpecPaletteTest` (extended) — **the A/B**: every spec utterance resolves identically with
  phonetics on and off. If phonetics moves anything, it must be narrowed.
- Golden `GrammarGeneratorTest` / `PromptGeneratorTest` (+ determinism under a shuffled Sock
  list), `PromptBudgetTest`, `TokenEstimateTest`, `Tier2JsonTest` (the hostile table),
  `Tier2Test`, `DobbyEngineTier2Test` with a `FakeTier2Resolver` in `core/src/testFixtures`.
- `OutcomeDetailTest` / `DobbyControllerTest` extensions: a Tier-2-resolved utterance does not
  buzz and does not reopen the mic; rounds 2–3 pass `useTier2 = false`.

**On-device `androidTest` (the only place these can run):**

1. `Tier2GrammarContractTest` — feed the generated GBNF to llama.cpp's parser. **Run this in the
   native spike, before B1's golden file is written**: it settles the hyphen-vs-underscore rule
   and the literal escaping.
2. `TokenEstimateCalibrationTest` — real `llama_tokenize` counts; asserts the estimator never
   *under*-counts, and emits the constants the JVM test hard-codes.
3. `Tier2ModelTest` — load, warm-path latency p50/p95 over ~20 paraphrases (this is what
   confirms or refutes the 5 s cap), fingerprint invalidation, memory high-water with Parakeet
   resident, a 20-call soak for thermal throttling.
4. `Tier2AccuracyTest` — the few-shots plus ~30 held-out paraphrases with expected command ids,
   **and** non-commands ("wie wird das wetter morgen", "erzähl mir einen witz") asserted to
   return `none`. A model that resolves everything is worse than no model.
5. `NativeLibraryShapeTest` — read `/proc/self/maps`, assert exactly one each of
   `libonnxruntime.so`, `libsherpa-onnx-jni.so`, `libdobby-llama.so`, and no `libc++_shared.so`.

**Manual, into `android/llama/README.md`:** `adb shell grep -m1 Features /proc/cpuinfo` (before
choosing `-march`), `llvm-readelf -d` on the built `.so`, `unzip -l *.apk | grep '\.so$'`
(expect three), `adb shell dumpsys thermalservice` around the latency soak.

---

## Sequence

| Step | Work | Size |
|---|---|---|
| 0 | **Phonetics** — `Phonetics`, `KeywordMatcher`, palette wiring, the four tests, CLI `/keywords`. Merge and live with it before anything else starts | ~1.5 d |
| 1 | `Introspection.promptExamples`, the two `checkExamples()` gates, few-shot params + the `zwanzig`→`20` fix | ~0.5 d |
| 2 | **Native spike**: `:android:llama` build only — fetch, CMake, an empty shim, `NativeLibraryShapeTest`, and the GBNF contract question answered | ~1.5 d |
| 3 | `GrammarGenerator` + golden tests (unblocked by step 2) | ~1 d |
| 4 | `PromptGenerator`, `TokenEstimate`, budget tripwire | ~1 d |
| 5 | `Tier2Json`, `Tier2`, `Tier2Outcome`, `FakeTier2Resolver` | ~1 d |
| 6 | Engine wiring, `Fallthrough`, `FallthroughLog`, the six call sites | ~1 d |
| 7 | `dobby_llama.cpp` in full, `Llama.kt`, `LlamaLoadTest` against a hand-pushed GGUF | ~2 d |
| 8 | `Verified` stamp + `SpeechModelStore` retrofit; `LlmModels` / `LlmModelStore` | ~1 d |
| 9 | `LlamaTier2`, `DobbyService` wiring, `onTrimMemory`, the crash tripwire | ~1 d |
| 10 | CLI `/grammar` `/prompt` `/tier2`; Android `Thinking`, detail lines | ~1 d |
| 11 | Device suites, then write the **measured** prefill/decode rates, thread count and memory delta back into `dobby-plan.md` §5.4 and §9 — those sections currently carry estimates | ~1 d |

Steps 0 and 1 are independently shippable and worth merging on their own.

---

## Open risks

- **`asimddp` on the Nord CE** — blocking for `-march`, answered by one `adb` command. If
  absent, use `armv8.2-a+fp16` and expect Q4_K decode to be meaningfully slower, which may force
  the retreat to a 1B model sooner than §9 plans.
- **The 2–4 s estimate itself** is inherited, not measured on a 750G. If decode lands at 8 tok/s
  a 40-token output is 5 s on its own. Levers in order: shorten the JSON shape, `Q3_K_M`, then a
  1B model. `Tier2ModelTest` is what tells you which.
- **Per-token grammar filtering over a 151 936-token vocabulary** is the cost most likely to
  surprise us; `nativeTimings` splits prefill from decode precisely so a regression is
  attributable.
- **GitHub codeload tarballs are not contractually byte-stable.** The pinned commit is the true
  content pin; a hash mismatch without a tag change means re-derive, and the module README must
  say so.
- **The budget tripwire fires mid-M4**, not after it. Sequence the remaining Sock work knowing
  that.
