# Sock: Calculator

> **Implementation status.** Built in full: `calculate`, `continue_with`, `last_result` and
> `clear` all ship in `:socks:calculator`. No network, no permissions, no audio — the whole Sock
> is a pure function ([`Arithmetic`](../socks/calculator/src/main/kotlin/io/dobby/socks/calculator/Arithmetic.kt))
> plus one remembered number ([`ResultMemory`](../socks/calculator/src/main/kotlin/io/dobby/socks/calculator/ResultMemory.kt)),
> so it is tested end to end on a plain JVM against a clock the test moves by hand.
> Still open: everything in §10.

## 1. Identity

| | |
|---|---|
| **id** | `calculator` |
| **displayName** | Rechner |
| **Purpose** | Arithmetic out loud, spoken back with the question in front of it, and a result that stays put long enough to be the start of the next sum. |
| **Milestone** | M3 |
| **Dependencies** | None. Not even the platform — `java.time.Clock` for the memory's expiry and nothing else. |
| **Permissions** | None |
| **Audio** | None. Never requests focus, never plays anything. |

### Why this is a Sock and not a phone

Two things, and both are about a panel on a wall rather than a screen in a hand.

**The answer always reads the question back** — "8 mal 2 ist 16", never "16". An answer
without its question is a number you have to trust, and something across the kitchen that you
addressed with your hands full has not earned that. Reading it back makes every answer
checkable by ear, including the ones where the recogniser heard a different number than you
said.

**A result is kept**, so the next utterance can be the next step. That is how somebody scaling
a recipe or dividing a board actually talks — one operand at a time, revising as they go:

> "Wie viel ist 250 mal 4?" — *250 mal 4 ist 1000.*
> "Und davon die Hälfte." — *1000 geteilt durch 2 ist 500.*
> "Plus 125." — *500 plus 125 ist 625.*

Nothing in that exchange would survive a calculator that forgets between utterances, and
nothing in it needs a keyboard.

## 2. Commands

### Exclusive

| Command id | Params | Description (German, feeds Tier 2) |
|---|---|---|
| `calculator.calculate` | `a: int`, `op: enum`, `b: int` *(optional — asked for when missing, §3)* | Rechnet eine Aufgabe aus und sagt Aufgabe und Ergebnis. |
| `calculator.continue_with` | `op: enum`, `b: int`, `a: int` *(follow-up only, §4)* | Rechnet mit dem letzten Ergebnis weiter. |
| `calculator.last_result` | — | Sagt das zuletzt gerechnete Ergebnis noch einmal. |
| `calculator.clear` | — | Vergisst das letzte Ergebnis. |

### Shared subscriptions

None. This Sock is never *doing* anything, so there is never a thing a bare "stopp" could
mean. A calculation is over before the sentence that asked for it is.

---

## 3. `calculator.calculate`

### Params

| Name | Type | Required | Default | Constraints |
|---|---|---|---|---|
| `a` | int | yes | — | 0 … 1 000 000 000 |
| `op` | enum | yes | — | `plus` \| `minus` \| `mal` \| `geteilt` \| `hoch` \| `modulo` \| `ganzzahlig` |
| `b` | int | **no** | — | 0 … 1 000 000 000; `hoch` additionally caps it at 64 |

`b` is optional for the same reason `clock.set_timer`'s unit is: "wie viel ist 3 plus" is a
sentence people really produce, especially when the recogniser clips the tail. A missing
operand is not a parse failure — the Sock asks for it (*Follow-up*, below).

`op` is always fixed by the wording of the template that matched, never captured by a slot.
Its values are single tokens all the same, because a value spelled `geteilt durch` could never
be matched by an `{op:enum}` slot — the slot matcher compares one token at a time — and a value
that is unmatchable by construction is a trap laid for the next author.

### The operators

| `op` | Said as | Computes | Answer |
|---|---|---|---|
| `plus` | plus, addiert mit/zu | a + b | *3 plus 5 ist 8.* |
| `minus` | minus, weniger, abzüglich | a − b | *12 minus 4 ist 8.* |
| `mal` | mal, multipliziert mit | a × b | *7 mal 8 ist 56.* |
| `geteilt` | durch, geteilt durch, dividiert durch, geteilt/dividiert mit | a ÷ b | *17 geteilt durch 2 ist 8,5.* |
| `hoch` | hoch, potenziert mit | aᵇ | *2 hoch 10 ist 1024.* |
| `modulo` | modulo, mod, der Rest von … durch …, was bleibt von … durch … übrig | a mod b | *17 modulo 5 ist 2.* |
| `ganzzahlig` | wie oft passt/geht … in …, ganzzahlig geteilt durch | ⌊a ÷ b⌋ **and** a mod b | *5 passt 3 mal in 17, Rest 2.* |

**Why `modulo` and `ganzzahlig` are two operators and not one phrasing.** They answer different
questions and keep different numbers. `modulo` is the remainder, full stop — it is what you want
when the remainder is the thing you are going to carry on with. `ganzzahlig` is the question
somebody portioning dough or cutting boards actually has: *how many whole ones, and what is
left over* — one question, so it gets one answer, and what carries into the next utterance is
the **count**, because "wie oft" asked about the count.

`modulo` uses `floorMod`, not `%`: the remainder of −17 and 5 is 2, not −3. A remainder you have
to think about is not a remainder anybody wanted.

### How numbers are spoken

| | |
|---|---|
| Whole | `8`, `1024` — no decimal point at all |
| Fractional | `8,5` — German writes the separator as a comma |
| Negative | `minus 2` — a hyphen in front of a digit is not a word |
| Rounded | `ungefähr 3,3333` — four decimal places, and **it says so** |

"Ungefähr" is the difference between an answer and a claim: 10 ÷ 3 has no last digit, and
pretending otherwise is the one place a calculator can lie without anybody noticing. The hedge
and the memory are the same question asked once — `ungefähr` appears exactly when the number
Dobby keeps differs from the number it just said (§4).

### Tier 1 templates

An optional run-up, an optional trailing verb, and one template per operator — the alternation
carries every phrasing of it. `{ASK}` and `{TAIL}` below are written out once in
[`CalculatorSock`](../socks/calculator/src/main/kotlin/io/dobby/socks/calculator/CalculatorSock.kt):

```
ASK  = ((wie viel|wieviel|wie viele|was) (ist|sind|ergibt|ergeben|macht|machen) (denn)?
       |(rechne|berechne) (mir)?
       |sag mir (wie viel|was) (ist|ergibt)?)?
TAIL = (ist|sind|ergibt|ergeben|macht|machen)?
```

`ASK` is optional because "3 plus 5" on its own is the commonest thing anybody says to a
calculator. `TAIL` is the verb German strands at the end — "sag mir mal, was 6 mal 7 **ist**";
the `(mal)?` that used to sit inside `ASK` is gone, because the matcher skips filler
([README](README.md) §6). Both are optional groups, so they contribute no keywords and change
neither specificity nor match order.

`(denn)?` and `(mir)?` stay, and the reason is the one rule filler skipping does not cover:
both sit directly in front of `{a:int}`, and skipping happens in front of a *keyword*, never in
front of a slot. Without them "was ergibt denn 6 mal 7" tries to read "denn" as a number.

**`mal` is the one word that is content here and filler everywhere else.** It is on the filler
list — "stell mal einen timer" — and it is also `TIMES_OP`. That works because the palette is
matched strictly first: "6 mal 7" and "mal 2" both resolve on the first pass, where no skipping
happens at all. The build-time check in `SockRegistry.checkFillers` knows the difference and
says so.

```
ASK (die)? (hälfte|haelfte) von {a:int} TAIL            → op=geteilt, b=2
ASK (das)? doppelte von {a:int} TAIL                    → op=mal,     b=2
ASK {a:int} (zum|im)? quadrat TAIL                      → op=hoch,    b=2
wie oft (passt|geht) {b:int} in {a:int} (rein|hinein)?  → op=ganzzahlig
ASK {a:int} ganzzahlig (geteilt)? durch {b:int}         → op=ganzzahlig
ASK (der|den)? rest von {a:int} (geteilt|dividiert)? durch {b:int}  → op=modulo
was bleibt von {a:int} (geteilt)? durch {b:int} (übrig|uebrig)?     → op=modulo
ASK {a:int} (plus|addiert mit|addiert zu|addiert) {b:int} TAIL      → op=plus
ASK {a:int} (minus|weniger|abzüglich|abzueglich) {b:int} TAIL       → op=minus
ASK {a:int} (mal|multipliziert mit|multipliziert) {b:int} TAIL      → op=mal
ASK {a:int} (geteilt durch|dividiert durch|geteilt mit|dividiert mit|geteilt|dividiert|durch) {b:int} TAIL → op=geteilt
ASK {a:int} (hoch|potenziert mit) {b:int} TAIL          → op=hoch
ASK {a:int} (modulo|mod) {b:int} TAIL                   → op=modulo
```

Plus the operand-less forms, which sit **last** — they are strictly less specific:

```
ASK {a:int} (plus|…)            ASK {a:int} (geteilt durch|…)
ASK {a:int} (minus|…)           ASK {a:int} (hoch|…)
ASK {a:int} (mal|…)             ASK {a:int} (modulo|mod)
```

Ordering is not actually what protects them: a template match is anchored at both ends, so
"3 plus 5" cannot be consumed by "3 plus" at all. The ordering is there so the palette reaches
the fuller phrasing first for the same reason `clock.set_timer` orders its unit-less forms last
— and `CalculatorTemplatesTest` asserts both.

The **hälfte / doppelte / quadrat** wordings fix `b` by what they mean; no slot could carry
them, because there is no number spoken to capture. Each is written with a word in front of it
so that no single keyword satisfies the template on its own — "hälfte", "quadrat" and
"doppelte" all sit in the 4–7 character band where tolerance 1 has neighbours
([README §6](README.md#6-template-dsl-tier-1)).

### Utterances → invocation

| Utterance | Invocation |
|---|---|
| wie viel ist 3 plus 5 | `calculate(a=3, op=plus, b=5)` |
| drei plus fünf *(normalized: `3 plus 5`)* | `calculate(a=3, op=plus, b=5)` |
| was ist 12 minus 4 | `calculate(a=12, op=minus, b=4)` |
| rechne 7 mal 8 | `calculate(a=7, op=mal, b=8)` |
| sag mir mal was 6 mal 7 ist | `calculate(a=6, op=mal, b=7)` |
| 20 geteilt durch 4 | `calculate(a=20, op=geteilt, b=4)` |
| 90 durch 6 | `calculate(a=90, op=geteilt, b=6)` |
| 2 hoch 10 | `calculate(a=2, op=hoch, b=10)` |
| 9 zum quadrat | `calculate(a=9, op=hoch, b=2)` |
| die hälfte von 17 | `calculate(a=17, op=geteilt, b=2)` |
| das doppelte von 21 | `calculate(a=21, op=mal, b=2)` |
| 17 modulo 5 | `calculate(a=17, op=modulo, b=5)` |
| der rest von 17 geteilt durch 5 | `calculate(a=17, op=modulo, b=5)` |
| was bleibt von 17 durch 5 übrig | `calculate(a=17, op=modulo, b=5)` |
| wie oft passt 5 in 17 | `calculate(a=17, op=ganzzahlig, b=5)` |
| wie oft geht 250 in 1000 rein | `calculate(a=1000, op=ganzzahlig, b=250)` |
| was ist 3 plus | `calculate(a=3, op=plus)` → asks |
| 1000 geteilt durch | `calculate(a=1000, op=geteilt)` → asks |

Tier 2 few-shots (`matchedByTemplates = false`, and meant to be missed here):
"kannst du mir ausrechnen was drei und fünf zusammen sind", "addiere mir mal eben zwölf und
dreißig", "teil mir tausend gramm auf vier portionen auf".

### Behavior

Evaluate, speak the equation and its result, and remember the result (§4). A refused sum
touches none of the three — see *failure modes*.

### Follow-up: the second operand

"Wie viel ist 3 plus" carries everything but the last number.

```kotlin
SockResult.Asked(
    text = "3 plus was?",
    follow = FollowUp(
        commandId = "calculator.calculate",
        templates = patterns("(durch|mit|um|plus|mal|minus|hoch)? {b:int}"),
        params = listOf(ParamSpec("b", ParamType.Integer)),
        token = "sum-1",
    ),
)
```

The template is near-bare, which is legal in a scoped palette and reckless anywhere else: it is
only ever tried against the one utterance that answers this question
([README §5](README.md#5-follow-up-questions)). The half-built sum lives under the token; the
answer runs the same handler, and the two paths converge before any arithmetic happens.

### `SockResult`

| Case | Result | German |
|---|---|---|
| Success | `Spoken` | *3 plus 5 ist 8.* |
| `b` missing | `Asked` | *3 plus was?* |
| Division by zero | `Failed` | *Durch null kann ich nicht teilen.* |
| `modulo`/`ganzzahlig` on a fraction | `Failed` | *Das geht nur mit ganzen Zahlen.* |
| Operand over 10⁹ | `Failed` | *Diese Zahl ist zu groß für mich.* |
| Result over 10¹², or exponent over 64 | `Failed` | *Das Ergebnis ist zu groß für mich.* |
| Answer to a token no longer held | `Failed` | *Ich weiß nicht mehr, was ich rechnen sollte.* |

The exponent is checked **before** the power is computed: 2^10000 is not a large number, it is
an overflow to infinity, and checking the magnitude afterwards is checking a value that no
longer exists.

---

## 4. `calculator.continue_with`

### Params

| Name | Type | Required | Default | Constraints |
|---|---|---|---|---|
| `op` | enum | yes | — | as §3 |
| `b` | int | yes | — | as §3 |
| `a` | int | **no** | — | never spoken in the same breath; it is the answer to "von welcher Zahl?" |

### Tier 1 templates

```
CONT = (und)? (jetzt|dann|weiter)? (davon|das|vom ergebnis)?

CONT die (hälfte|haelfte)                            → op=geteilt, b=2
CONT das doppelte                                    → op=mal,     b=2
CONT (zum|im) quadrat                                → op=hoch,    b=2
(und)? wie oft passt (da|das)? {b:int} (rein|hinein)? → op=ganzzahlig
CONT (plus|…) {b:int}          CONT (geteilt durch|…) {b:int}
CONT (minus|…) {b:int}         CONT (hoch|potenziert mit) {b:int}
CONT (mal|…) {b:int}           CONT (modulo|mod) {b:int}
```

Every part of `CONT` is optional, so bare "mal 2" works — which is the phrasing that makes the
memory worth having in the first place.

### Utterances → invocation

| Utterance | Invocation |
|---|---|
| mal 2 | `continue_with(op=mal, b=2)` |
| und jetzt mal 2 | `continue_with(op=mal, b=2)` |
| plus 5 | `continue_with(op=plus, b=5)` |
| und dann durch 3 | `continue_with(op=geteilt, b=3)` |
| und davon die hälfte | `continue_with(op=geteilt, b=2)` |
| das doppelte | `continue_with(op=mal, b=2)` |
| und das im quadrat | `continue_with(op=hoch, b=2)` |
| modulo 3 | `continue_with(op=modulo, b=3)` |
| und wie oft passt da 250 rein | `continue_with(op=ganzzahlig, b=250)` |

### The memory

The last result lives in [`ResultMemory`](../socks/calculator/src/main/kotlin/io/dobby/socks/calculator/ResultMemory.kt)
and is deliberately **not** the follow-up mechanism, which dies with the turn by design. The
whole point is that it survives the pause where somebody reads the next figure off a recipe.

**What is kept is what was said.** 10 ÷ 3 is spoken as "ungefähr 3,3333" and stored as
`3.3333`, not `3.3333333333333335`. Keeping the full double would mean Dobby says one number
and then multiplies a different one — defensible to a floating-point library, indefensible to
somebody standing at a worktop with a pencil.

**It expires**, and that is the part worth arguing for. A result kept forever turns a bare "mal
zwei" into a sentence whose meaning depends on something said an hour ago and since forgotten
by everyone in the room; the panel would answer confidently and be the only one who knows what
it multiplied. After the TTL (§9, default 10 minutes) the Sock asks instead. Expiry is checked
on read, not on a timer — there is nothing to schedule for, and a coroutine that exists only to
null a field is a lifecycle to get wrong.

### Follow-up: where to start

"Mal 2" with nothing to continue from is a perfectly clear instruction with one thing missing,
so it asks rather than refuses — *"Ich habe noch nichts gerechnet"* would be a dead end where
one short question finishes the job.

```kotlin
SockResult.Asked(
    text = "mal 2 — von welcher Zahl?",
    follow = FollowUp(
        commandId = "calculator.continue_with",
        templates = patterns("(von|ab)? {a:int}"),
        params = listOf(ParamSpec("a", ParamType.Integer)),
        token = "sum-1",
    ),
)
```

The answer is itself a result, so the chain carries on from there.

### `SockResult`

| Case | Result | German |
|---|---|---|
| Success | `Spoken` | *8 mal 2 ist 16.* |
| No result, or it has gone stale | `Asked` | *mal 2 — von welcher Zahl?* |
| Everything in §3 | as §3 | as §3 |

---

## 5. `calculator.last_result`

```
(was|wie viel) war das ergebnis (nochmal)?      was kam (da|dabei)? raus
wie war das ergebnis (nochmal)?                 (das|dein|mein|letztes) ergebnis (bitte|nochmal)?
(was|wie viel) war (das|es) nochmal             (sag|nenn) mir (nochmal)? das ergebnis
(was|wie viel) war (das|es)                     was (hab|habe) ich (zuletzt)? gerechnet
wie war das nochmal                             was haben wir (zuletzt)? gerechnet
```

| Case | Result | German |
|---|---|---|
| Something remembered | `Spoken` | *Das Ergebnis war 625.* |
| Nothing, or stale | `Spoken` | *Ich habe noch nichts gerechnet.* |

Reads the same memory as §4 and expires with it, so the two can never disagree about what the
last result was.

## 6. `calculator.clear`

```
(vergiss|vergesse|lösch|lösche|loesch|loesche) (das|die|den|mein|dein)? (ergebnis|zahl|rechnung)
(rechner|taschenrechner) (zurücksetzen|zuruecksetzen|leeren|reset)
(fang|fange) (neu|von vorne|noch mal) an
(neu|von vorne) anfangen
```

| Case | Result | German |
|---|---|---|
| Something was forgotten | `Spoken` | *Vergessen.* |
| There was nothing | `Spoken` | *Da war nichts zu vergessen.* |

Completes the memory model — set, read, forget — so "und jetzt mal 2" is never continuing from
something the person has decided they are done with.

---

## 7. Utterance collision surface

Keywords this Sock claims, for whoever writes the next one:

**Operators** — plus, minus, mal, durch, geteilt, dividiert, hoch, modulo, mod, weniger,
abzüglich, multipliziert, addiert, potenziert, ganzzahlig.
**Fixed-operand wordings** — hälfte, doppelte, quadrat (never alone; always behind die / das /
zum / im).
**Run-ups** — wie viel, wieviel, was ist/sind/ergibt/macht, rechne, berechne, sag mir.
**Continuations** — und, jetzt, dann, weiter, davon, vom ergebnis, wie oft passt/geht … rein.
**Memory** — ergebnis, was kam raus, gerechnet, vergiss, rechner zurücksetzen, von vorne.

Every one of these is claimed **only in the company of a number**, except the four memory
commands. That is what keeps the Sock out of everybody else's way: a template with two integer
slots in it cannot be reached by an utterance that has no integers.

Two judgements worth recording, both under
[README §6](README.md#6-template-dsl-tier-1)'s single-keyword rule:

- **`hoch` is four characters** and therefore fuzzed at tolerance 1, which also hands it
  "noch", "doch" and "koch". It is claimed anyway, because a template matches the *whole*
  utterance: the cost is somebody saying "noch 2" and nothing else, with the wake word in front
  of it. "Noch zwei Minuten" and every other real use of the word sits inside a longer sentence
  and cannot reach the template. What it does allow is bare "noch 2" producing *"hoch 2 — von
  welcher Zahl?"* — a question, not an action, and audibly not what was asked. That is pinned
  by a test rather than hidden, so whoever changes the operator templates sees it move.
- **`hälfte`, `doppelte` and `quadrat`** are all in the same band and are *not* claimed alone.
  Each is written behind an article ("die hälfte", "das doppelte", "im quadrat"), which is also
  how German says them. `CalculatorTemplatesTest` asserts the registry reports no
  single-keyword template for this Sock at all.

## 8. State & dashboard

`state: StateFlow<CalculatorState>` — the last sum as it was spoken (`equation`) and as a
number (`result`), or empty after `clear`. A successful calculation wakes the screen for 30
seconds.

The Compose card that draws it is **not built yet** — it belongs with the rest of the dashboard
(`dobby-plan.md` §8), and Clock is so far the only Sock with one. What it should show is the
sentence rather than the number: the equation is the half of the answer that survives being
misheard, and a person who suspects Dobby heard "fünfzig" for "fünfzehn" can settle it by
looking instead of by asking again.

## 9. Config

| Key | Type | Default | Where |
|---|---|---|---|
| `calculator.memory_minutes` | int | `10` | Settings → Rechner |

How long a result stays continuable (§4). Long enough to cover reading the next line of a
recipe, short enough that nobody comes back to a number they have stopped thinking about. A
non-positive value falls back to the default rather than disabling the memory — "never
continuable" is what `clear` is for.

## 10. Failure & degradation

Never `Degraded`, never `Unavailable`. There is nothing to be degraded *by*: no network, no
permission, no device. Every failure in this Sock is an answer it declines to give, and each
one has a sentence in §3.

## 11. Testing

- **`CalcNumberTest`** — the four spoken forms, and the invariant that "ungefähr" appears
  exactly when the remembered number differs from the spoken one. Includes that `3,3333` as a
  literal is not exactly representable, which is why exactness is asked of doubles and never of
  the decimal.
- **`ArithmeticTest`** — every operator, the two division questions and what each keeps, that
  `floorMod` gives a remainder anybody would recognise, and every refusal.
- **`CalculatorTemplatesTest`** — the utterance tables above, the collision gate against Clock,
  Help and Winky, that a sum naming both numbers is never demoted to the operand-less form, and
  that the registry reports no single-keyword template.
- **`CalculatorSockTest`** — the memory across calls, its expiry on a clock the test moves by
  hand, the configurable TTL, both follow-ups, an answer to a token that was already dropped,
  and that a refused sum costs nothing that was already remembered.
- **`CalculatorConversationTest`** — the sequences from §1 and §4 through the real
  `DobbyEngine`: scaling a recipe, portioning with `ganzzahlig`, a sum finished in the next
  breath, a continuation that asks where to start, a question abandoned mid-turn because
  something else was said, and a question that does not survive the turn at all.

## 12. Open questions / out of scope

- **Parentheses and more than one operator per utterance.** "3 plus 5 mal 2" is not refused, it
  is simply not matched. Neither of Dobby's two grammars is an *expression* grammar, and the
  distinction is worth stating because "grammar" already means two other things here:

  - The **template DSL** ([README §6](README.md#6-template-dsl-tier-1)) compiles to a flat tree
    — `Word | Slot | Seq | Alt | Opt`. There is no production a slot can expand into, and a
    slot captures a span of tokens, never a sub-expression. It can say *int, operator, int*; it
    cannot say *expression, operator, expression*.
  - The generated **GBNF grammar** (`dobby-plan.md` §5.4, `m6-plan.md` B1) constrains the LLM's
    output JSON, not the German input, and its shape is equally flat: one branch per command,
    params that are `int`, an enum, or a bounded string. There is no branch shape a nested
    expression could be returned in, so Tier 2 cannot rescue this either — it would pick one
    binary operator or answer `none`.

  Precedence is a property of a parse tree over the utterance, and nothing in the pipeline
  builds one. A wall panel that silently picks an association order is worse than one that says
  it did not understand, so chaining (§4) is the answer for now.

  **The way in, when it is wanted:** a `{expression}` text slot behind a *required* run-up —
  `(rechne|berechne|wie viel ist|was ist) {expression}` — with a shunting-yard parser in
  [`Arithmetic`](../socks/calculator/src/main/kotlin/io/dobby/socks/calculator/Arithmetic.kt).
  The run-up cannot be optional: `ASK {expression}` slips past the registry's bare-text-slot
  check (its root has two nodes, so `singleOrNull()` is null) and would then match every
  utterance. It sorts last, because it `endsOpen`, so it would not swallow another Sock — but
  it would swallow everything *unmatched*, taking "Das habe ich nicht verstanden" and the
  Tier 2 fallthrough log with it. The cost of the required run-up is that "rechne 3 plus 5
  mal 2" works and bare "3 plus 5 mal 2" still does not.
- **Percentages.** "20 Prozent von 250" is the obvious next wording and is deliberately not
  here yet: it needs its own operator and its own decision about what "plus 20 Prozent" means.
- **Units.** "250 Gramm mal 4" would be genuinely useful for the recipe case this Sock is aimed
  at, and would need a unit to travel with the memory.
- **Negative operands.** The normalizer strips the hyphen, so no template can capture one.
  Results may be negative and chain correctly; inputs may not.
- **A spoken result over 10¹²** is refused rather than truncated. Somebody who needs it does
  not need it from a kitchen wall.
