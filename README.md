# Dobby

A voice-controlled, wall-mounted smart panel for a repurposed OnePlus Nord CE (Android 13 / OxygenOS 13). Wake word, speech-to-text and intent parsing all run on-device.

Dobby core owns no commands. Everything the user can *do* lives in a **Sock** — a self-contained module that registers its commands and executes them.

- [`dobby-plan.md`](dobby-plan.md) — the build spec for core
- [`socks.specs/`](socks.specs/) — one spec per Sock, plus [the contract](socks.specs/README.md) and the [shared-command catalog](socks.specs/shared-commands.specs.md)

## Status: Phase A complete

Phase A is "Dobby in a terminal": everything below the microphone and above the Socks, as pure JVM code with no Android, no hardware and no network.

| | |
|---|---|
| `:core` | Sock API, template engine, normalizer, registry, dispatcher + chain. **Pure Kotlin/JVM** — the module boundary is what enforces the plan's "parsing is pure" rule. Publishes test fixtures (`FakeSockContext`) for Sock modules. |
| `:socks:clock` | **Clock** — the first product Sock. One command: `clock.whats_the_time`. |
| `:socks:help` | **Help** — spoken discovery: "Was kannst du?", "Was kann die Uhr?" |
| `:socks:devi` | Devi, the development Sock. One command, `devi.hello`. Not a product Sock. |
| `:cli` | The terminal harness, and the one place that knows which Socks exist. |

Not yet built: audio, wake word, STT, TTS, the Android app, and the remaining Socks (including Clock's own timers). See [`dobby-plan.md`](dobby-plan.md) §8 for what Phase B adds.

## Run it

```sh
./gradlew test            # 121 tests, all JVM, ~3s
./gradlew :cli:run -q     # the terminal harness
```

```
> wie spät ist es
  → clock.whats_the_time
      handled by clock CONSUMED
  🔊 Es ist halb 3.

> hello
hello Devi
  → devi.hello
      handled by devi CONSUMED
```

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

Both read the same `Introspection` API in `:core`, so the Phase B settings screen and the generated Tier 2 prompt will answer identical questions. `:` also works as a prefix.

## What the tests cover

- `GermanNumbersTest`, `NormalizerTest` — German cardinals, and why `ein` is left alone while `eins` is not.
- `TemplateParserTest`, `TemplateMatcherTest`, `SpecificityTest` — the DSL, backtracking, fuzzy tolerance, palette ordering.
- **`SpecPaletteTest`** — the utterance tables from every file in `socks.specs/`, run against fixture Socks carrying the real templates. This is the collision gate: it is what fails when a new template shadows another Sock. It already asserts "spiele radio fm4" → Radio, "stopp die musik" → Spotify, "stopp" → the chain, and the whole `… aus` family.
- `RegistryValidationTest` — every way a Sock can be malformed.
- `IntrospectionTest`, `HelpSockTest` — discovery, including that spoken lists are ordered by display name and that `was kannst du` and `was kann die Uhr` do not shadow each other.
- `GermanTimeTest`, `ClockSockTest` — the Clock Sock, including that German "halb 3" means 14:30 and not 15:30.
- **`ChainDispatcherTest`** — the chain matrix: activity ranking, priority tiebreaks, `NotForMe` passing, nobody consuming, a Sock that throws, a Sock that hangs, and the two scenarios the design exists for (ringing timer over playing music; timer merely counting down).

Fixture Socks under `core/src/test` carry the real spec templates with stub handlers, so the engine is validated against the specs long before those Socks exist.

## Next

Phase B — the Android shell: app skeleton, foreground service, `AudioRecord` owner, Vosk, TTS, and the Android `SockContext`. Clock already gives it something real to say out loud on day one; fake Socks driven from the debug dashboard cover the chain, which no real Sock exercises yet.
