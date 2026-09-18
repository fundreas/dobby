# Dobby

A voice-controlled, wall-mounted smart panel for a repurposed OnePlus Nord CE (Android 13 / OxygenOS 13). Wake word, speech-to-text and intent parsing all run on-device.

Dobby core owns no commands. Everything the user can *do* lives in a **Sock** — a self-contained module that registers its commands and executes them.

- [`dobby-plan.md`](dobby-plan.md) — the build spec for core
- [`socks.specs/`](socks.specs/) — one spec per Sock, plus [the contract](socks.specs/README.md) and the [shared-command catalog](socks.specs/shared-commands.specs.md)

## Status: Phase A complete

Phase A is "Dobby in a terminal": everything below the microphone and above the Socks, as pure JVM code with no Android, no hardware and no network.

| | |
|---|---|
| `:core` | Sock API, template engine, normalizer, registry, dispatcher + chain. **Pure Kotlin/JVM** — the module boundary is what enforces the plan's "parsing is pure" rule. |
| `:socks:devi` | Devi, the development Sock. One command, `devi.hello`. Not a product Sock. |
| `:cli` | The terminal harness, and the one place that knows which Socks exist. |

Not yet built: audio, wake word, STT, TTS, the Android app, and every product Sock. See [`dobby-plan.md`](dobby-plan.md) §8 for what Phase B adds.

## Run it

```sh
./gradlew test            # 80 tests, all JVM, ~1s
./gradlew :cli:run -q     # the terminal harness
```

```
> hello
hello Devi
  → devi.hello
      handled by devi CONSUMED
```

Harness commands: `:socks`, `:palette` (every template in match order), `:fallthrough` (what Tier 1 could not match), `:trace`, `:quit`.

## What the tests cover

- `GermanNumbersTest`, `NormalizerTest` — German cardinals, and why `ein` is left alone while `eins` is not.
- `TemplateParserTest`, `TemplateMatcherTest`, `SpecificityTest` — the DSL, backtracking, fuzzy tolerance, palette ordering.
- **`SpecPaletteTest`** — the utterance tables from every file in `socks.specs/`, run against fixture Socks carrying the real templates. This is the collision gate: it is what fails when a new template shadows another Sock. It already asserts "spiele radio fm4" → Radio, "stopp die musik" → Spotify, "stopp" → the chain, and the whole `… aus` family.
- `RegistryValidationTest` — every way a Sock can be malformed.
- **`ChainDispatcherTest`** — the chain matrix: activity ranking, priority tiebreaks, `NotForMe` passing, nobody consuming, a Sock that throws, a Sock that hangs, and the two scenarios the design exists for (ringing timer over playing music; timer merely counting down).

Fixture Socks under `core/src/test` carry the real spec templates with stub handlers, so the engine is validated against the specs long before those Socks exist.

## Next

Phase B — the Android shell: app skeleton, foreground service, `AudioRecord` owner, Vosk, TTS, and the Android `SockContext`. Still no product Socks; the debug dashboard drives fake ones so the chain can be validated on a real device with a real microphone.
