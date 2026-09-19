# Sock: Conversation

> **Implementation status.** Built and shipping in `:socks:conversation`.

## 1. Identity

| | |
|---|---|
| **id** | `conversation` |
| **displayName** | Gespräch |
| **Purpose** | Ends the turn on request: "OK" and Dobby goes back to waiting for its name. |
| **Milestone** | M2b — turn behaviour, landed after M6b |
| **Dependencies** | none |
| **Permissions** | none |
| **Audio** | none — it is the Sock that produces *less* sound, not more |

Being dismissed is a capability, so it is a Sock rather than a special case in core — the same argument [`help.specs.md`](help.specs.md) makes for discovery. Core still knows no commands: what it knows is [`SockResult.Ended`](../core/src/main/kotlin/io/dobby/core/sock/Types.kt), which has existed since M1 with no Sock returning it. This Sock is the first one that does.

**Why it is needed.** A turn does not end when one instruction has been carried out — the microphone stays open for a few seconds because the common thing after one instruction is a second one (`dobby-plan.md` §5.1). The cost is the other case: somebody who is finished has to stand in front of an open microphone and wait five seconds for it to notice. "OK" is what a person says there anyway, and the honest response to it is to stop listening at once.

It is the mirror of the wake word. The wake word opens a turn without a Sock; this closes one with the smallest Sock in the repo.

---

## 2. Commands

### Exclusive

| Command id | Params | Description (German, feeds Tier 2) |
|---|---|---|
| `conversation.dismiss` | — | Beendet das Gespräch, ohne sonst etwas zu tun. |

### Shared subscriptions

**None.** Nothing here is context-dependent: "OK" addresses Dobby itself, never whatever happens to be playing. `activityFor` always returns `INACTIVE` and is never consulted.

Explicitly considered and rejected: subscribing to `shared.stop`, so that "stopp" with nothing running would end the turn instead of answering "Es läuft gerade nichts." That would make this Sock the last consumer of every unclaimed stop in the house, and a panel that silently goes to sleep when you asked it to stop something is a panel that looks like it crashed. "Stopp" is about the room; "OK" is about the conversation. They stay apart.

---

## 3. `conversation.dismiss`

### Params

None.

### Tier 1 templates

```
(ok|okay|oke|okey)
(alles klar|alles gut)
(danke|danke dir|danke schön|dankeschön|vielen dank)
(danke )?(das war's|das wars|das wär's|das wärs|das ist alles)
(schon gut|passt schon|passt so|lass gut sein)
```

### Utterances → invocation

| Utterance | Invocation |
|---|---|
| ok | `dismiss()` |
| okay | `dismiss()` |
| alles klar | `dismiss()` |
| danke | `dismiss()` |
| danke dir | `dismiss()` |
| vielen dank | `dismiss()` |
| das war's | `dismiss()` |
| danke das war's | `dismiss()` |
| passt schon | `dismiss()` |
| schon gut | `dismiss()` |

### Behavior

Nothing. The handler returns immediately; the whole effect is the result type.

Core does the rest, and it already did before this Sock existed: `Ended` closes the turn, the pending question of the turn — if any — is cancelled through `onAskCancelled` like any other abandoned one (`README.md` §5), the microphone closes, and the wake word goes back on the audio stream. The panel is asleep again, one utterance after being told to be.

The screen is **not** turned off. "OK" ends the conversation, not the display; the wake word's 30 s of screen time runs out on its own, and the command for a dark panel is "gute nacht" (`system.specs.md` §6).

### Result

| Case | Result | German TTS |
|---|---|---|
| always | `Ended(null)` | — |

**Nothing is spoken, deliberately.** An answer to "OK" would be a sentence nobody wanted, played into a room somebody has just stopped addressing, and it would hold the turn open for the length of the answer. The acknowledgement is the panel going quiet: the status line returns to `Sag "Hey Dobby"` and the chat keeps the utterance, so what happened is still visible.

### Failure modes

None. There is nothing to fail.

---

## 4. Activity

Subscribes to nothing. `activityFor` → `INACTIVE` always.

---

## 5. Utterance collision surface

Exclusively claimed: `ok`, `okay`, `oke`, `okey`, `alles klar`, `alles gut`, `danke …`, `vielen dank`, `das war's` / `das wars` / `das wär's` / `das wärs`, `das ist alles`, `schon gut`, `passt schon`, `passt so`, `lass gut sein`.

Deliberately **not** claimed:

- Bare `stopp`, `aus`, `pause` (→ `shared.stop`). See §2.
- `gute nacht`, `schlaf` (→ `system.turn_off_screen`). Those turn the screen off and are a different intent that happens to end a conversation too.
- `ja ja`, `ist gut`, `ich hab's gehört` — the Clock Sock's chime-silencing phrasings on `shared.stop` (`shared-commands.specs.md` §3.3). `ist gut so` was considered here and dropped: two templates one word apart, routed to different Socks, is exactly the coin flip §4 of the contract warns about. Somebody who wants the conversation over says "ok".
- Bare `klar`, bare `passt`, bare `fertig`. Four- to five-letter words at fuzzy tolerance 1 with real German neighbours (`klar`/`kar`, `passt`/`fasst`/`hasst`/`lasst`), which is what the contract's single-keyword rule (§6) rejects. They appear here only with a second word around them.

**Single-keyword templates, and why they are legitimate here.** `ok` is two characters, so tolerance is 0 and only "ok" matches it. `okay` is four and therefore fuzzy at distance 1; German has no one-word utterance inside that radius. `danke` is five, and `denke` and `tanke` *are* inside it — but as whole utterances, alone, neither is something anybody says to a wall panel, and this is the one command in the repo where a false positive costs a wake word and nothing else: the panel stops listening a few seconds earlier than it would have anyway. The rule is written around the asymmetry between a miss and a wrong action (`README.md` §6); here there is no action. The registry still logs these as single-keyword templates, and that log line is expected.

## 6. State & dashboard

None.

## 7. Config

None. "How long before the microphone closes on its own" is `DobbyController.SPEECH_WINDOW`, which belongs to the turn, not to this Sock.

## 8. Failure & degradation

Always `Ready`. A Sock with no dependencies has nothing to degrade.

## 9. Testing

- The utterance table (§3) as template assertions, plus the negative cases: `stopp`, `ist gut`, `ja ja`, `gute nacht`, `hilfe`, `wie spät ist es` must **not** resolve to `conversation.dismiss`.
- Collision gate against the Socks it shares a palette with (Clock, Calculator, Help, Winky), exactly as `:socks:calculator` does it.
- Handler returns `SockResult.Ended(null)` — no text, because the silence is the product decision.
- Core already covers what `Ended` does to a turn (`EndedTest`, `DobbyControllerTest`); this Sock does not re-test it.

## 10. Open questions / out of scope (v1)

- **A cue on dismissal.** A single short buzz, so somebody three metres away knows the panel heard the "OK" rather than ignored it. Needs `ListenCue`'s judgement (`dobby-plan.md` §5.1) applied a second time, in the controller rather than the pipeline, and it is not obvious it is wanted — the screen is on and the status line already says the panel is asleep. Deferred until the wall says otherwise.
- **`conversation.repeat`** ("was hast du gesagt") — the obvious second command for this Sock, and it needs core to keep the last spoken sentence, which it does not today.
- **English** (`thanks`, `never mind`). Allowed by the contract (§6: no int or enum slots here) and trivially addable. Left out until somebody actually talks English at the panel.
