# Sock: Help

> **Implementation status.** Built and shipping in `:socks:help`.

## 1. Identity

| | |
|---|---|
| **id** | `help` |
| **displayName** | Hilfe |
| **Purpose** | Spoken discovery: which areas exist, and what each one can do. |
| **Milestone** | M1 |
| **Dependencies** | `Introspection` from core. No network, no account, no audio of its own. |
| **Permissions** | none |
| **Audio** | none |

Discovery is a capability, so it is a Sock rather than a special case in core. The rule survives: core still knows no commands, and deleting this Sock removes voice discovery and nothing else.

**It is the one Sock that must see the palette it is part of.** That is a genuine chicken-and-egg with the registry, which is built *from* it, so it takes an `Introspection` **provider** that the app binds immediately after the registry exists:

```kotlin
var directory: Introspection? = null
val socks = listOf(ClockSock(), DeviSock(), HelpSock { directory })
val registry = SockRegistry.buildOrThrow(socks)
directory = Introspection(registry, health)
```

An unbound Sock answers `Failed("Ich kann meine Befehle gerade nicht nachschlagen.")` rather than throwing.

## 2. Commands

### Exclusive

| Command id | Params | Description (German, feeds Tier 2) |
|---|---|---|
| `help.overview` | — | Sagt, welche Bereiche es gibt. |
| `help.sock_commands` | `sock: string` | Sagt, was ein bestimmter Bereich kann. |

### Shared subscriptions

**None.** Nothing here is context-dependent, and nothing it does can be "stopped".

> **Vocabulary:** the user-facing word is **Bereich**, not "Sock". "Sock" is an implementation term and means nothing to someone standing in front of a wall panel. Socks are only called Socks in the code, the specs and the terminal.

---

## 3. `help.overview`

### Tier 1 templates

```
was kannst du (alles|tun|machen|so)?
was kannst du denn (alles)?
(hilfe|hilf mir)
welche befehle (gibt es|hast du|kennst du|gibt's)
was gibt es für befehle
wobei kannst du (mir)? helfen
welche (bereiche|module|socks) (gibt es|hast du)
was für (bereiche|module|socks) (gibt es|hast du)
```

### Utterances → invocation

| Utterance | Invocation |
|---|---|
| was kannst du | `overview()` |
| was kannst du alles | `overview()` |
| hilfe | `overview()` |
| welche befehle gibt es | `overview()` |
| welche bereiche gibt es | `overview()` |

### Behavior & result

Names every area that has at least one command, **sorted by display name** — a list read aloud must be ordered by what the listener hears, never by internal sock id. Then offers one concrete follow-up question, preferring an area other than Hilfe itself.

| Case | German |
|---|---|
| several areas | "Ich habe 3 Bereiche: Devi, Hilfe und Uhr. Frag zum Beispiel: Was kann Devi?" |
| exactly one | "Ich habe einen Bereich: Hilfe. Frag: Was kann Hilfe?" |
| none | "Ich kann im Moment noch nichts." |

It does **not** read out every command. That is the terminal's job (`/commands`) and, from M5, the dashboard's.

---

## 4. `help.sock_commands`

### Params

| Name | Type | Required | Notes |
|---|---|---|---|
| `sock` | string | yes | Spoken area name, resolved against display names and ids |

### Tier 1 templates

```
was kann (die|der|das)? {sock} (alles)?
welche befehle hat (die|der|das)? {sock}
hilfe (zu|für) (die|der|das|den)? {sock}
was kann ich (mit|bei) (der|dem|die|das)? {sock} (machen|sagen)
```

> ⚠️ `was kann {sock}` and `was kannst du` are one letter apart in German and must not shadow each other. They don't: "kannst" is edit distance 2 from the keyword "kann", and the tolerance for a four-letter word is 1. This is asserted explicitly — it is the kind of thing that breaks silently when someone widens the fuzzy tolerance.

### Utterances → invocation

| Utterance | Invocation |
|---|---|
| was kann die uhr | `sock_commands(sock="uhr")` |
| was kann uhr | `sock_commands(sock="uhr")` |
| welche befehle hat die uhr | `sock_commands(sock="uhr")` |
| hilfe zu spotify | `sock_commands(sock="spotify")` |
| was kann ich mit der uhr machen | `sock_commands(sock="uhr")` |

### Area resolution

1. Strip a leading article (`die`, `der`, `das`, `den`, `dem`).
2. Exact match against `id` or `displayName`, case-insensitive.
3. Levenshtein fallback against both. The candidate set is closed, which makes fuzzy safe here.

### Behavior & result

Answers with **what to say**, not with command ids — "wie spät ist es" is useful over a speaker; "clock.whats_the_time" is not. Phrasings come from each command's Tier 1 examples, in the Sock's **declaration order**, because a Sock author lists its most important command first.

Capped at **three** phrasings; the rest are counted.

| Case | German |
|---|---|
| one command | "Uhr hat einen Befehl. Sag zum Beispiel: ‚wie spät ist es‘." |
| several | "Uhr hat 2 Befehle. Sag zum Beispiel: ‚wie spät ist es‘ und ‚timer zehn minuten‘." |
| more than three | "Viele hat 7 Befehle. Sag zum Beispiel: … Und 4 weitere." |
| unknown area | "Den Bereich kenne ich nicht. Ich habe: Devi, Hilfe und Uhr." |
| area with no commands | "Uhr kann im Moment nichts." |
| no examples declared | "Uhr hat 2 Befehle, aber ich weiß gerade nicht, wie man sie sagt." |

Only **Tier 1** examples are ever offered. A Tier 2 paraphrase is by definition something the template matcher cannot reach, so advertising it would be telling the user to say something that does not work until M6.

---

## 5. Activity

No shared subscriptions; `activityFor` always returns `INACTIVE`.

## 6. Utterance collision surface

Claims: `was kannst du …`, `was kann … {sock}`, `hilfe`, `hilf mir`, `welche befehle …`, `welche/was für bereiche|module|socks …`, `wobei kannst du helfen`.

- `hilfe` bare is claimed here. A future Sock wanting "Hilfe bei X" must use a longer, more specific template.
- The `{sock}` slot is greedy but requires a `was kann` / `welche befehle hat` / `hilfe zu` prefix, so it cannot swallow ordinary commands. Asserted.

## 7. State & dashboard

None in v1. From M5 the dashboard shows the same data visually, reading it from `Introspection` directly rather than through this Sock.

## 8. Config

None.

## 9. Failure & degradation

- Never `Unavailable`. If the directory is unbound it fails per-invocation with a spoken message and stays `Ready`, because the fault is in app wiring, not in this Sock.
- Degraded Socks still appear in the listing — knowing Spotify exists but is broken is more useful than it vanishing.

## 10. Testing

- The template tables above, plus the `was kannst du` / `was kann die Uhr` shadowing assertion.
- Answer shaping: singular vs. plural, the three-phrasing cap and its remainder count, unknown area, area with no commands, no examples declared.
- Area resolution through articles and near misses.
- **It must not swallow ordinary utterances** — "wie spät ist es", "hello", "spiele musik" must never reach `help.*`.
- The unbound-directory path.
- Ordering: spoken lists by display name, phrasings in declaration order.

## 11. Open questions / out of scope (v1)

- **"Wie sage ich das nochmal?"** — asking for the phrasing of one specific command rather than a whole area.
- Search by topic ("was kannst du mit Musik") — `Introspection.search` exists and the terminal uses it; there is no voice command for it yet.
- Reading out what a Sock *cannot* do, or why it is degraded ("Spotify ist nicht verbunden").
- Localisation. Everything here is German, like the rest of the palette.
