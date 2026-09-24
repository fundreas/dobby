# Sock: Memo

> **Implementation status.** Built and shipping in `:socks:memo`.

## 1. Identity

| | |
|---|---|
| **id** | `memo` |
| **displayName** | Memos |
| **Purpose** | Takes down what somebody says out loud, and reads it back one memo at a time until they have dealt with it. |
| **Milestone** | M5 — the first Sock whose product *is* the dashboard card as much as the voice |
| **Dependencies** | none — no network, no account, no SDK |
| **Permissions** | none |
| **Audio** | none. It neither plays nor claims the channel, and subscribes to no chain |

**Why it is a Sock and not a notepad.** Dictating is the easy half: "erstelle Memo Rechnung bezahlen" with both hands in the sink is worth the panel on its own. The hard half is getting it back out. A list is only worth dictating to if it reads itself back one item at a time, in an order somebody can follow by ear, saying how much is left — and then lets them say "erledigt" to the one they have just done, without naming it again. That last sentence is the whole design: it needs a piece of state that outlives a turn and dies well before the day does, which is the **memo session** (§4).

---

## 2. Commands

### Exclusive

| Command id | Params | Description (German, feeds Tier 2) |
|---|---|---|
| `memo.create_memo` | `text` | Speichert ein Memo mit dem gesagten Text. |
| `memo.latest_memo` | — | Liest das neueste Memo vor und sagt, wie viele weitere offen sind. |
| `memo.oldest_memo` | — | Liest das älteste Memo vor und geht von dort aufwärts weiter. |
| `memo.next_memo` | — | Liest das nächste Memo in der begonnenen Richtung vor. |
| `memo.previous_memo` | — | Liest das vorherige Memo vor, also eines zurück. |
| `memo.close_memo` | — | Erledigt das zuletzt vorgelesene Memo. |

### Shared subscriptions

**None**, and one of them was considered seriously.

`shared.resume` — "weiter" — would be a natural way to ask for the next memo while a session is open, and it is rejected. Music is what this panel is mostly doing (`dobby-plan.md` §1), "weiter" is what somebody says to a paused song, and a Sock that outranks Spotify for that word because a memo was read out four minutes ago is a Sock that has taken a word it does not own. The memo commands all name a memo, which costs one word and is never ambiguous. `activityFor` therefore always returns `INACTIVE` and is never consulted.

`shared.stop` was not considered: there is nothing running to stop.

---

## 3. `memo.create_memo`

### Params

| Name | Type | Required | Default | Constraints |
|---|---|---|---|---|
| `text` | Text | yes | — | Normalizer output, cleaned by `MemoText` (below). Filler-only is a failure, not a memo. |

### Tier 1 templates

```
(erstelle|erstell|erzeuge|speicher|speichere|schreib|schreibe) (ein|neues)? (memo|notiz) {text}
(neues|neue) (memo|notiz) {text}
(memo|notiz) {text}
(merk|merke) dir {text}
(notier|notiere) {text}
```

Every one of these has a keyword in front of the slot, because a trailing `{text}` is greedy for the rest of the utterance. They also sort *after* every closed template in the palette (`Specificity.ORDER`), which is what keeps "memo weiter" a navigation command instead of a memo called "Weiter" — the ordering is computed, so this holds against templates other Socks add later, too.

### Utterances → invocation

| Utterance | Invocation |
|---|---|
| erstelle memo milch kaufen | `create_memo(text="milch kaufen")` |
| erstell ein memo rechnung bezahlen | `create_memo(text="rechnung bezahlen")` |
| neues memo fenster schließen | `create_memo(text="fenster schließen")` |
| memo mama anrufen | `create_memo(text="mama anrufen")` |
| notiz brot holen | `create_memo(text="brot holen")` |
| schreibe ein memo müll rausbringen | `create_memo(text="müll rausbringen")` |
| merk dir brot kaufen | `create_memo(text="brot kaufen")` |
| notiere reifen wechseln lassen | `create_memo(text="reifen wechseln lassen")` |

Tier 2 few-shots (`matchedByTemplates = false`): "kannst du dir merken dass ich die rechnung bezahlen muss", "schreib mir auf dass morgen der müll rausmuss". Held out: "ich darf nicht vergessen die pakete abzuholen", "erinnere mich später an die wäsche".

### Behavior

The captured text is cleaned first. A `{text}` slot takes whatever tokens follow its anchor, so "erstelle mal ein Memo bitte" arrives as `text = "bitte"`; `MemoText.clean` drops filler words and a leading run of scaffolding (`memo`, `notiz`, `für`, `über`, `von`, `zum`, …) and refuses what is left if it is empty. Saving that memo would put a note reading "Bitte" on the wall that nobody can act on and everybody has to close. This is `TimerNames.clean`'s argument, unchanged, and for the same reason it is not solved in the templates.

The memo is stored with `Instant.now(clock)` and the list is persisted (§9). The screen is woken for 30 s: the panel showing the memo written out is the half of the acknowledgement that survives being misheard.

A full pile is **refused, never rotated** — see §9 for the cap. Dropping the oldest memo to make room would be the one failure this Sock may not have: forgetting something nobody asked it to forget, silently, at the moment somebody was adding something else.

### Result

| Case | Result | German TTS |
|---|---|---|
| saved | `Spoken` | „Memo gespeichert: Milch kaufen.“ |
| nothing but filler in the slot | `Failed` | „Ich habe nicht verstanden, was ich mir merken soll.“ |
| at the cap | `Failed` | „Ich habe schon 50 Memos offen. Erledige erst ein paar.“ |
| before `onStart` | `Failed` | „Ich komme gerade nicht an meine Memos.“ |

**Creating does not open a memo session.** The idea file's rule is that closing follows a *select* command, and it is the right rule: "erstelle Memo X" followed by "erledigt" is somebody talking about something else, not undoing what they just said.

---

## 4. Reading them back: `latest`, `oldest`, `next`, `previous`

The four readers are one mechanism. `latest_memo` and `oldest_memo` start a **walk** from one end of the pile; `next_memo` and `previous_memo` move along it. "Next" always means *further in the direction of travel*, so it goes backwards in time after "welche Memos sind offen" and forwards in time after "ältestes Memo" — which is the idea file's rule and also the only one somebody can hold in their head while listening.

The walk is the **memo session**: a cursor (which memo), a direction, and when it was last touched. It lives across turns — the user asks, listens, walks away, comes back and says "nächstes Memo" — so it is deliberately *not* the follow-up mechanism (`README.md` §5), which dies with the turn by design. It times out after five minutes (§9), and being read an edge answer ("das war schon das letzte Memo") counts as touching it: that answer is somebody still working on their memos.

**With no live session, `next` and `previous` start a walk at the newest memo** rather than refusing. "Nächstes Memo" said into a room where nothing has been read out is not a mistake; it is somebody asking for a memo in the words they would use for the second one, and the honest thing to do with it is hand them the first. The answer then carries the "Dein neuestes Memo" lead-in, so what happened is audible.

### Tier 1 templates

```
memo.latest_memo
    (gibt es|gibts|hab ich|habe ich|haben wir) (noch)? (offene)? (memos|notizen)
    (liste|zeig|zeige|nenn|nenne|sag) (alle|meine)? (memos|notizen)
    welche (memos|notizen) (sind)? (noch)? (offen|da)
    (memos|notizen) (vorlesen|bitte)
    (letztes|neuestes|aktuelles) (memo|notiz)

memo.oldest_memo
    (ältestes|aeltestes|älteste|aelteste|erstes|erste) (memo|notiz)
    (memos|notizen) von (vorne|unten|anfang)
    (fang|starte) (beim|mit dem) (ältesten|aeltesten|ersten) (memo|notiz)? an

memo.next_memo
    (nächstes|naechstes|nächste|naechste) (memo|notiz)
    (memo|notiz) weiter
    weiter (zum|zur)? (nächsten|naechsten)? (memo|notiz)
    (noch|und)? ein (memo|notiz) weiter

memo.previous_memo
    (vorheriges|vorherige|voriges|vorige) (memo|notiz)
    (memo|notiz) (zurück|zurueck|davor)
    (ein)? (memo|notiz) zurück
    zurück zum (vorherigen|vorigen) (memo|notiz)
```

`letztes memo` belongs to **`latest_memo`**, not to `previous_memo`: in German "das letzte Memo" is the most recent one. The English reading is the trap, and the collision gate is what would have caught it — both commands claiming it is exactly the coin flip the contract's §4 is about.

### Utterances → invocation

| Utterance | Invocation |
|---|---|
| gibt es memos | `latest_memo()` |
| gibt es noch offene memos | `latest_memo()` |
| habe ich memos | `latest_memo()` |
| liste die memos | `latest_memo()` |
| zeig mir meine memos | `latest_memo()` |
| welche memos sind offen | `latest_memo()` |
| welche notizen sind noch da | `latest_memo()` |
| memos vorlesen | `latest_memo()` |
| letztes memo | `latest_memo()` |
| neuestes memo | `latest_memo()` |
| ältestes memo | `oldest_memo()` |
| das älteste memo | `oldest_memo()` |
| erstes memo | `oldest_memo()` |
| memos von vorne | `oldest_memo()` |
| fang beim ältesten memo an | `oldest_memo()` |
| nächstes memo | `next_memo()` |
| nächste notiz | `next_memo()` |
| memo weiter | `next_memo()` |
| weiter zum nächsten memo | `next_memo()` |
| ein memo weiter | `next_memo()` |
| vorheriges memo | `previous_memo()` |
| voriges memo | `previous_memo()` |
| memo zurück | `previous_memo()` |
| ein memo zurück | `previous_memo()` |
| zurück zum vorherigen memo | `previous_memo()` |

### Behavior

The cursor moves, the screen is woken for 30 s, and the memo is read out. Nothing is ever mutated by reading.

### Result

| Case | Result | German TTS |
|---|---|---|
| newest, others open | `Spoken` | „Dein neuestes Memo: Milch kaufen. Notiert heute um 14:30 Uhr. Es sind noch 2 weitere Memos offen.“ |
| oldest | `Spoken` | „Dein ältestes Memo: Rechnung bezahlen. Notiert gestern um 18:05 Uhr. Es ist noch ein weiteres Memo offen.“ |
| a step along the walk | `Spoken` | „Brot holen. Notiert am Montag um 9:15 Uhr.“ |
| nothing open | `Spoken` | „Es sind keine Memos offen.“ |
| no memo further on | `Spoken` | „Das war schon das letzte Memo.“ |
| no memo further back | `Spoken` | „Davor gibt es kein weiteres Memo.“ |

Three sentences at most, always in that order: the memo, when it was noted, how much else is waiting. The text is what somebody asked for, the timestamp is what tells them whether it is still worth doing, and the count is what tells them whether to ask for the next one. **A zero is left out rather than said** — "Es sind noch 0 weitere Memos offen" is a sentence only a computer would produce.

The timestamp coarsens with age, because that is how somebody refers to their own notes: `heute um 14:30 Uhr`, `gestern um 18:05 Uhr`, `am Montag um 9:15 Uhr` inside a week, and `am 12. September` past it — no clock time on the oldest ones, since nobody asks at what hour last month they thought of the recycling.

---

## 5. `memo.close_memo`

### Tier 1 templates

```
(memo|notiz) (ist)? (erledigt|fertig|abgehakt|weg)
(erledige|erledigt|schließe|schließ|schliesse|lösch|lösche|loesche|streich|streiche|entferne) (das|die)? (memo|notiz)
(hak|hake) (das|die)? (memo|notiz) ab
(memo|notiz) (löschen|loeschen|schließen|schliessen|streichen|entfernen)
(das|die)? (memo|notiz) kann weg
```

### Utterances → invocation

| Utterance | Invocation |
|---|---|
| memo erledigt | `close_memo()` |
| memo ist erledigt | `close_memo()` |
| notiz fertig | `close_memo()` |
| lösche das memo | `close_memo()` |
| schließe das memo | `close_memo()` |
| hak das memo ab | `close_memo()` |
| memo löschen | `close_memo()` |
| das memo kann weg | `close_memo()` |

### Behavior

Closes the memo the session is sitting on, and **ends the session with it**. Advancing the cursor to the next memo would make a second "erledigt" close a memo that was never read out: one word, no confirmation, and a note gone that nobody heard. Ending the session costs the user four words ("welche Memos sind offen") — and because closing has just promoted the next memo to newest, those four words land them exactly where the cursor would have been anyway.

With no live session there is nothing this can honestly act on, so it says so. "Das Memo" is a word about a conversation; five minutes after the conversation it is a word about nothing, and the idea file is explicit that a close outside a session does not fire.

A closed memo is **gone**, not archived. See §12.

### Result

| Case | Result | German TTS |
|---|---|---|
| closed, others left | `Spoken` | „Erledigt: Milch kaufen. Es sind noch 2 Memos offen.“ |
| closed, one left | `Spoken` | „Erledigt: Milch kaufen. Es ist noch ein Memo offen.“ |
| closed, none left | `Spoken` | „Erledigt: Milch kaufen. Es sind keine Memos mehr offen.“ |
| no session, or timed out | `Spoken` | „Ich weiß gerade nicht, welches Memo du meinst. Frag mich zuerst nach deinen Memos.“ |

**Bare „erledigt“ is deliberately not claimed**, and this is the rejection the contract asks to be recorded (`README.md` §6). At eight characters the fuzzy tolerance is two, which is the loosest radius in the system, and this is the one command in the Sock that throws something away. A single-keyword template needs nothing else in the utterance to agree with it, so the protection would have to come from the word alone — and it does not. The five-minute session narrows *when* a stray "erledigt" could land; it would not make the word safe to claim. Somebody who wants the memo closed says "Memo erledigt", which is two words and cannot be produced by accident.

---

## 6. Activity

Subscribes to nothing. `activityFor` → `INACTIVE` always. See §2.

---

## 7. Utterance collision surface

Exclusively claimed: `memo`, `memos`, `notiz`, `notizen` in every template; plus `erstelle` / `erstell` / `erzeuge` / `speicher(e)` / `schreib(e)` / `neues` / `neue` / `merk(e) dir` / `notier(e)`; `gibt es` / `gibts` / `hab(e) ich` / `haben wir` / `liste` / `zeig(e)` / `nenn(e)` / `welche` / `offen` / `offene` / `vorlesen`; `letztes` / `neuestes` / `aktuelles`; `ältestes` / `älteste` / `erstes` / `erste` / `von vorne` / `von unten`; `nächstes` / `weiter` / `vorheriges` / `voriges` / `zurück` / `davor`; `erledigt` / `fertig` / `abgehakt` / `kann weg` / `abhaken` / `löschen` / `schließen` / `streichen` / `entfernen` — **all of them only ever beside `memo` or `notiz`.**

That is the rule this Sock follows throughout, and it is what keeps it out of everybody else's way:

- Bare `weiter` is `shared.resume`; `memo weiter` is this Sock. Nothing skips filler in front of a bare keyword, so the two cannot be confused (`README.md` §6).
- `nächster song` / `nächstes lied` is `spotify.skip_next`; `nächstes memo` is this Sock. Same word, different noun.
- `ein lied zurück` is `spotify.skip_previous`; `ein memo zurück` is this Sock.
- `letztes ergebnis` is `calculator.last_result`, `letztes lied` is Spotify, `letztes memo` is this Sock.
- `fang neu an` / `von vorne anfangen` is `calculator.clear`; `memos von vorne` and `fang beim ältesten memo an` are this Sock.

Deliberately **not** claimed:

- Bare `erledigt`, bare `fertig`, bare `weg`. See §5.
- Bare `memos`. Five characters at tolerance 1, with no anchor to disagree with a misfire, on a command that is otherwise free — "memos vorlesen" and "gibt es memos" cost one extra word and are what people say anyway.
- `was steht an`, `was ist heute zu tun`. Calendar and departures ground (`departures.specs.md`), and a phrasing this Sock would have to fight for later. It is a Tier 2 few-shot instead, which is exactly what that tier is for.
- `erinnere mich in 20 minuten` → `clock.set_timer`. A reminder with a time in it is a timer; a memo has no time attached, which is the whole distinction between the two Socks. "Erinnere mich später an die Wäsche" is a held-out Tier 2 case here, because *later* is not a duration.

---

## 8. State & dashboard

`MemoState` — the open memos newest first, plus `spotlight`, the id the memo session is sitting on. Both are a `StateFlow` on the Sock, read by `DobbyController.memos`.

Two surfaces, and the split is the whole of the design.

**`MemoCard`** (`android/app/.../ui/MemoCard.kt`) sits above the conversation and shows **one** memo: the one the session is sitting on — which is the one a spoken "Memo erledigt" would close — or the newest when no walk is open. Under it, the same timestamp the voice speaks and a "noch 2 weitere" count. One memo and not a list, because this card shares the top of the screen with a clock and the job here is to say *there is something*, in a line that is readable while walking past.

It is drawn **whenever anything is open**, unlike the clock's timers or the two music cards, which appear only while something is running. An open memo is open until somebody closes it, and a list that is visible only while you are asking about it is a list nobody is reminded by.

The card carries an **X**, and it closes the memo on the card and nothing else. The whole row is otherwise a button: a tap opens the list.

**`MemoScreen`** (`android/app/.../ui/MemoScreen.kt`) is that list — every open memo with its stamp, an X per row, and the spotlit one marked, so the screen and the voice never disagree about which memo "das Memo" is. It is reached by tapping the card rather than from a button in the header, because the card is what somebody is already looking at when they want more of it, and the back arrow and the system back gesture both leave it.

**Sorted by creation date, both ways.** Newest first is the order everything else uses — the card, the state flow, and what "welche Memos sind offen" reads out. Oldest first is the other end of the same walk: `memo.oldest_memo` exists precisely for the thing that has been lying around too long, and a list that could not be turned round would leave the voice better at this than the screen. The toggle is a labelled control ("Neueste zuerst" / "Älteste zuerst") rather than a bare arrow, which on its own is a guess about which end of the list it means. There is no sort by text and no filter: fifty memos is the cap (§9), a dozen fit on screen, and a wall panel with a sort menu is a spreadsheet.

Both X buttons run the Sock's own `closeFromPanel(id)`, which closes **by id** and therefore needs neither the memo session nor the five minutes it lives for. Pointing at a memo says which one; routing a button press through the one part of the system that can misunderstand it is what `ClockSock.cancelFromPanel` already refuses to do.

The German the panel draws comes from `MemoSpeech.panel(...)`, the same renderer that builds the spoken stamp, resolved at `Lang.DE` because the panel's own UI is German whatever the voice speaks (`README.md` §2a). A card reading "24.09. 12:52" beside an answer saying "heute um 12:52 Uhr" would be two clocks in one room.

## 9. Config

| Key | Type | Default | Meaning |
|---|---|---|---|
| `memo.session_minutes` | Int | 5 | How long "das Memo" keeps meaning the one just read out. Zero or negative falls back to the default rather than making `close_memo` unreachable. |
| `memo.max_memos` | Int | 50 | The cap. Clamped to 1…500. |

`memo.entries` is **not** config: it is where the memos themselves are persisted, one line of `id|createdAt|text` per memo in the same store, exactly as `clock.pending_timers` holds the timer deadlines. The text is Normalizer output cleaned by `MemoText`, so it holds letters, digits, apostrophes and spaces and nothing else and neither separator can occur inside one — which is why this is three plain fields and not a JSON dependency in a module that has none.

Defaults are what ship. The settings screen does not expose either key yet (§12).

## 10. Failure & degradation

Always `Ready`. A Sock with no dependencies has nothing to degrade, and a Sock whose storage is the config store cannot be unavailable while the panel is running.

The two failures it can have:

- **A line that does not parse on restore is dropped**, not fatal. A memo is not worth a crash loop, and the surviving memos are worth more than the broken one.
- **The process dies.** The memos come back; the session does not, by design — a cursor that survived a restart would make "Memo erledigt" act on something decided before the panel was switched off.

## 11. Testing

Per the project's standing instruction, this Sock ships without unit tests. What would be covered if that changes, in the order it matters:

- The utterance tables (§3, §4, §5) as template assertions — these are already asserted on every build by the registry's example gate, which is how "letztes memo" was caught belonging to `latest_memo` rather than `previous_memo`.
- The collision gate against Clock, Help and Spotify, which is what `:socks:memo`'s test dependencies are for.
- The walk: `latest` → `next` → `next` → `previous` over three memos, and both edges.
- The session clock, on a `MutableClock`: `close_memo` at 4:59 closes, at 5:01 asks. This is the safety property of the Sock and the one test worth writing first.
- Persistence: encode/decode round trip, a corrupt line, and a restart that keeps the memos and loses the session.

## 12. Open questions / out of scope (v1)

- **Proactive reminding.** The idea file says Dobby should "remember the user of open memos", and this Sock does it on request and on the panel — never by speaking first. An unprompted announcement needs a rule for *when* (morning? on wake? after how long?) that nothing in the system has an opinion about yet, and a panel that starts talking about the recycling while music is playing would be the single fastest way to lose the room. It belongs in a milestone with the dashboard's screen choreography, not in this one.
- **An archive.** A closed memo is deleted. Keeping them would give the panel a "was habe ich diese Woche erledigt" and cost nothing in storage; what it needs is a command surface and a retention rule, and neither is in the idea file.
- **Memos with a time in them** ("Memo morgen früh: Müll") — that is a reminder, and the boundary with `clock.set_timer` needs drawing before either Sock claims it (§7).
- **Naming or numbering memos**, so "erledige das zweite" works without walking to it. The Clock's named timers are the model; nobody has asked for it out loud yet.
- **The settings screen** does not expose `memo.session_minutes` or `memo.max_memos`. Both are defaults that have never needed changing on this panel; the keys exist so that when one does, it is a settings row and not a release.
