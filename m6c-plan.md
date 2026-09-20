# M6c — filler skipping: the matcher ignores words that carry no meaning

## Context

Parakeet gets the content words right and the function words wrong. "wie spät ist **das**" for
"wie spät ist **es**" is the recorded case; "denn", "jetzt", "mal", "bitte" appear and vanish
at random. Tier 1 is anchored at both ends and gives words of ≤ 3 characters a Levenshtein
tolerance of 0, so a wrong "es" kills the whole template and the utterance falls to Tier 2 — a
2–4 s LLM round trip, for a sentence whose two content words were heard perfectly.

Today the palettes paper over this by enumerating variants: `ClockSock` carries seven templates
for "what time is it", with `(mir)?` and `(die)?` optionals sprinkled through them. That does
not scale (every command needs the same cross-product) and still misses the next variant.

The fix is the one Adapt, Rhasspy and Snips all landed on: **templates name content words, the
engine tolerates filler.** A small, language-specific list of filler tokens that the matcher may
skip anywhere in the utterance. Pure Kotlin, no model, no measurable cost.

Outcome: "wie spät" matches "wie spät ist das", "wie spät ist es denn jetzt" and "ähm wie spät
ist es bitte". Half the clock templates go away. Nothing that matches today matches differently.

---

## A. Design

### A1. Two passes, not one

`Palette.match` runs the whole ordered palette **strict first** (today's behaviour, byte for
byte), and only if nothing matched runs it **again with filler skipping**. Consequences:

- Every utterance that resolves today keeps resolving to the same entry. Specificity ordering
  is untouched. There is nothing to re-verify about existing behaviour.
- Filler skipping only ever acts on utterances that would otherwise go to Tier 2 or buzz. The
  bar for it is "does it create a *new* false positive", nothing else.
- Same two passes for the scoped follow-up palette (`compileScopedPalette`), so "ja bitte"
  answers a question the same way "ja" does.

### A2. The insertion point

[CompiledTemplate.kt:73-78](core/src/main/kotlin/io/dobby/core/nlu/template/CompiledTemplate.kt#L73-L78),
the `Node.Word` case, plus the end anchor at
[line 59](core/src/main/kotlin/io/dobby/core/nlu/template/CompiledTemplate.kt#L59).
`match()` takes a `fillers: Fillers` parameter (default `Fillers.NONE`).

- **Before a `Word`**: in addition to comparing at `position`, also try after skipping 1..n
  consecutive filler tokens. Emit the no-skip state first so exact paths stay preferred.
- **At the end**: the template also matches if every remaining token is a filler.
- **Slots are untouched.** A `{query}` captures its tokens verbatim, fillers included:
  "spiele es muss liebe sein" must still bind `query = "es muss liebe sein"`. Because TEXT is
  non-greedy and tries shortest first, a `{query}` followed by nothing will still end up
  swallowing trailing fillers rather than leaving them for the end anchor — that is fine, a song
  title ending in "bitte" is rarer than a user saying it. INT and ENUM slots consume exactly one
  token as today; a filler directly before a slot is skipped by the *preceding* Word/anchor
  logic, so "stell mal einen timer auf 5 minuten" works via `(einen|nen)?` as before and
  "timer auf mal 5 minuten" is not a case worth supporting.
- **Fillers are compared exactly.** Never Levenshtein, never phonetic. "bitter" is not "bitte".
- Skipping adds match paths and removes none, so a filler that is *also* a template literal
  (`es`, `ist`, `die`, `mir`) still matches on the literal path first.

### A3. Where the list lives

New file `core/src/main/kotlin/io/dobby/core/nlu/Fillers.kt`:

```kotlin
class Fillers(val language: String, private val words: Set<String>) {
    operator fun contains(token: String) = token in words
    companion object {
        val NONE = Fillers("none", emptySet())
        val DE: Fillers
        val EN: Fillers   // M-later: wired when the engine gets a language setting
    }
}
```

`SockRegistry` builds the palette with `Fillers.DE` today. When English lands, the language
setting chooses `Fillers`, `Normalizer` (currently `Locale.GERMAN` + `GermanNumbers`) and
`Phonetics` (Kölner Phonetik is German-only; English needs Metaphone or no phonetic tier)
together. This plan only adds the seam, not the switch.

### A4. What must never be a filler

A word is a filler only if **no command's meaning changes when it is dropped**. Excluded on
purpose, and the test in B2 pins them:

- Polarity and direction: `an`, `aus`, `ein`, `nicht`, `kein`, `mehr`, `weniger`, `hoch`,
  `runter`; EN `on`, `off`, `not`, `no`, `up`, `down`, `more`, `less`.
- Dialogue answers: `ja`, `nein`, `ok`, `okay`, `stopp`; EN `yes`, `no`, `ok`, `okay`, `stop`.
  "OK" ends a conversation turn — it is a command, not noise.
- Repetition and sequence: `noch`, `nochmal`, `wieder`, `weiter`, `zurück`; EN `again`, `next`,
  `back`. ("mal" alone is fine: "noch mal" still matches its literal path, and a lone "mal" is
  noise.)
- Anything that is the only literal in a bare single-word template, checked at palette build
  time (B2).

---

## B. Word lists

### B1. German (`Fillers.DE`)

Grouped for review; shipped as one flat set.

| Group | Tokens |
|---|---|
| Pronouns the ASR swaps freely | `es`, `das`, `dies`, `mir`, `mich`, `uns`, `du` |
| Articles | `der`, `die`, `den`, `dem`, `ein`, `eine`, `einen`, `einem`, `einer`, `nen`, `ne` |
| Copula / auxiliaries | `ist`, `sind`, `bin`, `bist` |
| Modal particles | `denn`, `mal`, `doch`, `halt`, `eben`, `eigentlich`, `gerade`, `grad`, `jetzt`, `so`, `auch`, `dann`, `also` |
| Politeness | `bitte`, `danke` |
| Hesitation / address | `ähm`, `äh`, `hm`, `hey`, `he`, `du`, `dobby` |

Notes:
- `ein`/`einen` are in the table but `ein` is also excluded in A4 as polarity ("mach das
  Licht ein"). Resolve at build time: **drop `ein` from the list**, keep `eine`/`einen`/`einem`/
  `einer`. Recorded here so the next reader does not re-add it.
- `dobby` is speculative. Recording starts after the wake word fires, but the tail of the wake
  word may be in the buffer. Check the fallthrough log on device; keep it only if it appears.
- `jetzt` is a filler in "wie spät ist es jetzt" but content in a hypothetical "spiele jetzt
  {x}" vs "spiele später {x}". No such command exists; if one lands, B2 fails the build and the
  author decides.

### B2. English (`Fillers.EN`) — for the later language step

| Group | Tokens |
|---|---|
| Pronouns | `it`, `that`, `this`, `me`, `us`, `you` |
| Articles | `the`, `a`, `an`, `some` |
| Copula / auxiliaries | `is`, `are`, `am`, `do`, `does` |
| Particles | `just`, `now`, `then`, `well`, `so`, `actually`, `really`, `right`, `like`, `kinda`, `please`, `thanks` |
| Hesitation / address | `um`, `uh`, `erm`, `hmm`, `hey`, `dobby` |

Notes:
- `now` is the English `jetzt`: harmless today, re-check when a scheduling command appears.
- `do`/`does`/`can`/`could`: `do` and `does` are pure question scaffolding ("what time does the
  train leave"). `can`/`could`/`would` are *not* listed — "can you" is a paraphrase Tier 2 is
  for, and dropping them would make "could you stop" and "stop" identical, which is fine, but
  "can you hear me" and "hear me" is not.
- English templates will also want `'s` handling in `Normalizer` ("what's the time" →
  `what s the time` or `whats`). Out of scope here; noted so the EN list is not blamed for it.

---

## C. Tests

1. **`TemplateTest`** — matcher unit tests: leading filler, trailing fillers, filler between
   two literals, filler *not* skipped inside a TEXT slot, filler that is also a literal takes
   the literal path, `Fillers.NONE` reproduces today's results exactly.
2. **`FillersTest`** (new) — build-time invariants, run against the real registry:
   - No filler is the sole literal of a bare single-literal template.
   - No filler is in the A4 exclusion set (a literal list in the test, so someone adding `an`
     gets a named failure).
   - **Distinguishing-word check**: for every pair of templates from *different* commands,
     stripping fillers from both must not make their literal sequences equal. This is the
     mechanical form of "no command's meaning changes when the word is dropped".
3. **`SpecPaletteTest`** — every spec utterance resolves to the same command with
   `Fillers.DE` as with `Fillers.NONE`. Same A/B pattern as the `phonetic` flag.
4. **Negative sentences** — new `core/src/test/resources/de-sentences-negative.txt`, ~50
   everyday German sentences dense in fillers ("das ist mir jetzt eigentlich egal", "ist das
   denn so schwer"), none of which may match any template with skipping on. The existing
   `de-frequent.txt` is single words and does not exercise this.
5. **`SttTemplateTest`** (on device) — add the real "wie spät ist das" recording and two more
   filler-heavy takes. This is the test that motivated the plan; it should go from red to green.

---

## D. Follow-up, same PR

- Prune `ClockSock` templates: `wie (spät|viel uhr)`, `(sag|was ist) (die)? (uhrzeit|zeit)`,
  `uhrzeit` — the rest are now redundant. Other Socks: remove `(mir)?`/`(die)?`/`(mal)?`
  optionals whose only purpose was filler tolerance. `minWords` never counted optionals, so
  ordering does not move.
- `FallthroughLog`: record when pass 2 rescued an utterance (`tokens`, matched command) so the
  on-device number is visible in `/fallthrough` and the settings screen. That number is how
  we know whether this plan worked, and whether Tier 2 traffic dropped.
- `socks.specs/README.md` §6: one paragraph — "do not enumerate filler variants, the matcher
  skips them; here is the list; here is what must not be on it."

## Out of scope

- English wiring (language setting, `Normalizer.EN`, phonetic tier for English).
- ASR-side changes (hotwords, beam search): they do not fix "es"/"das" and cost time on the
  MT6877.
- Scoring / partial matching instead of first-match-wins. Two-pass skipping covers the observed
  failures without a threshold to tune; revisit only if the fallthrough log shows a class of
  misses this cannot reach.
