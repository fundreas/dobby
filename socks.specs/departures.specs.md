# Sock: Departures (Wiener Linien)

> **Added on Dobby's own initiative.** The four Sock specs requested did not cover `departures`, but the plan's product pitch (§1) and §5.4 specified it in detail. It is captured here rather than dropped. **Delete this file if the feature is cut** — nothing else depends on it.

## 1. Identity

| | |
|---|---|
| **id** | `departures` |
| **displayName** | Abfahrten |
| **Purpose** | Next Wiener Linien departures for the configured home stop(s), spoken and on the dashboard. |
| **Milestone** | M4 |
| **Dependencies** | OkHttp + kotlinx.serialization (shared from `SockContext`) |
| **Hard requirements** | Network. **No API key.** |
| **Permissions** | `INTERNET` |
| **Audio** | None |
| **Licensing** | Data is CC BY 4.0 — attribution is **mandatory**, see §6 |

## 2. Commands

### Exclusive

| Command id | Params | Description (German, feeds Tier 2) |
|---|---|---|
| `departures.departures` | `line: string?` | Sagt die nächsten Abfahrten der konfigurierten Haltestelle. |

### Shared subscriptions

**None.** This Sock answers a question and holds no state a user would ever "stop"; `activityFor` always returns `INACTIVE`.

Should a future version speak departures proactively, the announcement becomes interruptible and this Sock would subscribe to `shared.stop`. Noted in §11, not built.

---

## 3. `departures.departures`

### Params

| Name | Type | Required | Default | Notes |
|---|---|---|---|---|
| `line` | string | no | `""` | Spoken line name ("14a", "u6", "d"). Empty → all configured lines. |

### Tier 1 templates

```
wann (fährt|kommt|geht) (der|die|das) nächste (bus|bim|straßenbahn|tram|u-bahn|ubahn|linie)
wann (fährt|kommt|geht) (der|die|das) nächste {line}
wann (fährt|kommt|geht) (der|die) {line}
(nächste |die nächsten )?abfahrten
wann (fährt|kommt) (der|die) nächste
(abfahrt|abfahrten|fahrplan|abfahrtszeiten)
```

Generic transport nouns (`bus`, `bim`, `u-bahn`, …) are matched by the **first** template and produce `line=""` — "der nächste Bus" means "the next departure", not a line named "Bus". Template 2 only captures line-shaped tokens.

### Utterances → invocation

| Utterance | Invocation |
|---|---|
| wann fährt der nächste bus | `departures(line="")` |
| wann kommt die nächste bim | `departures(line="")` |
| abfahrten | `departures(line="")` |
| wann fährt der nächste 14a | `departures(line="14a")` |
| wann kommt der u6 | `departures(line="u6")` |
| wann fährt die d | `departures(line="d")` |
| fahrplan | `departures(line="")` |

### Line resolution

Vosk mangles line names predictably: "14A" → "14 a" / "vierzehn a", "U6" → "u sechs", "D" → "de". Resolution:

1. Normalize: lowercase, remove spaces between digits and a trailing letter ("14 a" → "14a"), map spelled-out digits to numerals ("u sechs" → "u6").
2. Match case-insensitively against the `line` names returned by the API for the configured stops.
3. No match → answer with **all** lines rather than failing; the user asked about departures either way. Prefix the answer with "Die Linie habe ich nicht gefunden, aber: …".

### Behavior

1. `GET https://www.wienerlinien.at/ogd_realtime/monitor?stopId=<id>&stopId=<id>…` for every configured stop id, in **one** request (the endpoint accepts repeated `stopId`).
2. Parse `data.monitors[].lines[]`: `name`, `towards`, `departures.departure[].departureTime.countdown` (minutes).
3. Filter to `line` if resolved. Sort by countdown. Take the first two per line/direction.
4. Cache the response for `min_poll_interval_s` (§7); a voice request inside that window is answered from cache. **Fair use is a functional requirement, see §6.**

### Result

`Spoken`, one sentence per line/direction, at most **three** sentences (a wall panel answering for 30 seconds is useless):

> "Der 14A Richtung Reumannplatz fährt in 4 Minuten, danach in 12."

Rules:
- `countdown = 0` → "fährt jetzt"
- exactly one departure known → "fährt in 4 Minuten." (no "danach")
- U-Bahn lines read as "Der U6", trams as "Die Linie D", buses as "Der 14A" — article table in config, not hardcoded German grammar guessing.

Also wakes the screen for 30 s; the dashboard board (§5) is the full answer, the speech is the summary.

### Failure modes

| Condition | Result | German TTS |
|---|---|---|
| No stop ids configured | `Failed` | "Es ist noch keine Haltestelle eingestellt." |
| No network | `Failed`, serve stale cache if < 10 min old with a caveat | "Ich habe gerade keine Internetverbindung." / "Stand vor {n} Minuten: …" |
| API 4xx/5xx or timeout (5 s) | `Failed` | "Die Wiener Linien antworten gerade nicht." |
| Response parses but has no departures | `Spoken` | "Für die nächste Zeit sind keine Abfahrten gemeldet." |

---

## 4. Activity

No shared subscriptions, so `activityFor` always returns `INACTIVE` and the chain never consults this Sock.

---

## 5. State & dashboard

`DashboardCard` — the departure board, the panel's most-looked-at element:

- Rows: line badge (colored per mode: U-Bahn line color, tram red, bus blue), destination, countdown in minutes.
- Max 6 rows, sorted by countdown.
- A row at `0` reads "jetzt".
- Greyed while a refresh is in flight; last-updated timestamp in small type.
- **Attribution, permanently visible on the card:** `Datenquelle: Stadt Wien – data.wien.gv.at`

Exposed state: `StateFlow<DeparturesState>` = `fetchedAt: Instant?`, `rows: List<DepartureRow>`, `error: String?`, `isStale: Boolean`.

## 6. Fair use — hard rules

The OGD realtime endpoint is free and unauthenticated. Abusing it risks an IP block, which would take the feature down permanently. These are requirements, not guidelines:

1. Poll **only** while the screen is on, or on an explicit voice request.
2. Minimum interval **30 s** (the published spec minimum is 15 s — Dobby deliberately doubles it).
3. Only the configured stop ids. Never enumerate, never crawl.
4. One request per poll, all stop ids batched.
5. A descriptive `User-Agent` identifying the app.
6. Back off exponentially on any 5xx or 429, up to 15 minutes.
7. Attribution rendered wherever the data is shown (§5).

A test asserts (2) and (4) against a fake clock and a request-counting fake HTTP client. Treat a failure there as a release blocker.

## 7. Config

| Key | Type | Default | Where set |
|---|---|---|---|
| `departures.stop_ids` | list<int> | empty | Settings screen — 2–4 RBL / Haltepunkt ids for the home station, one per direction |
| `departures.station_label` | string | `""` | Settings — shown on the dashboard card |
| `departures.min_poll_interval_s` | int | `30` | Settings (advanced), **floor of 30 enforced in code** |
| `departures.max_spoken_lines` | int | `3` | Settings |
| `departures.line_articles` | map<string,string> | seed table | Settings (advanced) — "u6" → "der", "d" → "die Linie" |

Stop ids come from the published *haltepunkte* CSV at data.wien.gv.at. First-run UX: the settings screen takes raw ids; a search-by-station-name helper is out of scope for v1.

## 8. Failure & degradation

- No stop ids → `Degraded("keine Haltestelle konfiguriert")`; the command stays registered and explains itself when invoked.
- Repeated 5xx → `Degraded("Wiener Linien nicht erreichbar")`, exponential backoff, recovers automatically on the first good response.
- The dashboard card renders the last good data with a visible staleness marker rather than disappearing.

## 9. Utterance collision surface

Exclusively claimed: `wann fährt/kommt/geht …`, `abfahrt(en)`, `abfahrtszeiten`, `fahrplan`. Contributes to no chain.

No overlap with the other four Socks. The riskiest neighbour is a future Weather Sock ("wann kommt der Regen") — whoever writes it must check this file. That one is a true collision, not a shared command: which Sock answers depends on the *words*, not on runtime state, so it is fixed by tightening templates, not by a chain.

## 10. Testing

- Template table (§3) as a parameterized unit test, including the generic-noun → `line=""` rule.
- Line normalization: "14 a", "vierzehn a", "u sechs", "de", plus unresolvable input falling back to all lines.
- Parsing against **recorded JSON fixtures**: normal response, empty `departures`, missing `towards`, a monitor with no lines, a malformed `countdown`.
- Answer-phrasing table incl. `countdown = 0`, single departure, article selection, the three-sentence cap.
- Fair-use test per §6.
- No test hits the live endpoint.

## 11. Open questions / out of scope (v1)

- Station search by name; disruption/`trafficInfo` messages from the API; walking-time offset ("in 4 Minuten" is useless if the stop is 5 minutes away); multiple saved stations; other transit operators.
- Proactive announcements ("dein Bus fährt gleich") — needs a triggering model Dobby does not have, and would make this Sock a `shared.stop` subscriber (§2).
