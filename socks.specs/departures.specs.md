# Sock: Departures (Wiener Linien)

> **Implementation status.** Built and shipping in `:socks:departures`.
>
> This file began as a draft written before the feature was commissioned, for a **single**
> station addressed by its per-platform RBL ids. Both of those are gone: a station is now a
> **DIVA** id, which covers every platform and every direction of that station in one number,
> and the config holds a **list** of them. What survived unchanged is the part that was never
> about the station count — line resolution (§3), the fair-use rules (§6), the failure modes
> and the phrasing rules.

## 1. Identity

| | |
|---|---|
| **id** | `departures` |
| **displayName** | Abfahrten |
| **Purpose** | The next Wiener Linien departures for every configured station — spoken, and on the dashboard. |
| **Milestone** | M4 |
| **Dependencies** | OkHttp + kotlinx.serialization (both already in the build, for `:socks:spotify` and `:socks:weather`) |
| **Hard requirements** | Network. **No API key, no account, no registration.** |
| **Permissions** | `INTERNET` |
| **Audio** | none. It neither plays nor claims the channel, and subscribes to no chain |
| **Data** | [Stadt Wien — data.wien.gv.at](https://www.data.gv.at/katalog/dataset/wiener-linien-echtzeitdaten-via-datenschnittstelle) — CC BY 4.0. **Attribution is mandatory**, see §5 |

**Why a list of stations and not one.** The draft assumed the panel hangs in one flat with one stop outside the door, and that is true of the *flat* and not of the *question*. "Wann fährt der nächste U6" is asked by somebody deciding between the U-Bahn two streets away and the bus at the corner, and a panel that knows only one of them answers a question nobody asked. One DIVA id per station and one batched request per poll (§6.4) makes the second and third station free — they cost nothing but a query parameter.

**Why DIVA and not RBL.** An RBL is one platform in one direction: the draft's "2–4 ids for the home station, one per direction" was four numbers somebody had to look up, in a CSV, per station, and get right. A DIVA is the station — every platform, both directions, all lines — and the response says which DIVA each monitor came from (§3), so one number per station is both the whole configuration and the whole grouping key.

---

## 2. Commands

### Exclusive

| Command id | Params | Description (German, feeds Tier 2) |
|---|---|---|
| `departures.departures` | `line: string?` | Sagt die nächsten Abfahrten der eingestellten Haltestellen. |

### Shared subscriptions

**None.** This Sock answers a question and holds no state a user would ever "stop"; `activityFor` always returns `INACTIVE`.

Should a future version speak departures proactively, the announcement becomes interruptible and this Sock would subscribe to `shared.stop`. Noted in §11, not built.

---

## 3. `departures.departures`

### Params

| Name | Type | Required | Default | Notes |
|---|---|---|---|---|
| `line` | string | no | `""` | Spoken line name ("14a", "u6", "d"). Empty → all lines at all configured stations. |

### Tier 1 templates

```
wann (fährt|kommt|geht) (der|die|das) (nächste|nächster) (bus|bim|straßenbahn|tram|ubahn|u bahn|linie)
wann (fährt|kommt|geht) (der|die|das) (nächste|nächster) {line}
wann (fährt|kommt|geht) (der|die) {line}
(nächste|die nächsten|die)? abfahrten
(die|der)? (nächste|nächsten) abfahrt
wann (ist|geht) (die)? nächste abfahrt
(fahrplan|abfahrtszeiten)
```

Generic transport nouns (`bus`, `bim`, `u bahn`, …) are matched by the **first** template and produce `line=""` — "der nächste Bus" means "the next departure", not a line named "Bus". Template 2 only captures what template 1 did not already take.

**The plural is bare and the singular is anchored**, and that asymmetry is `socks.specs/README.md` §6's single-keyword rule rather than a spelling preference. „abfahrt" is seven characters, so it is fuzzed at tolerance 1 — which hands it „anfahrt", a word somebody says in a kitchen without addressing the panel, and the registry's own gate flags it. It was tried as a bare alternative and **rejected**, verified in the terminal harness: with it, „anfahrt" resolved to this command; without it, „anfahrt" and „auffahrt" reach nothing and „abfahrt" still resolves — „abfahrten" is nine characters, so its own tolerance of 2 reaches the singular anyway. („anfahrten" does still match, at one edit from the plural. It is not a sentence, and the cost of catching it would be losing the bare plural, which is what people actually say.)

### Utterances → invocation

| Utterance | Invocation |
|---|---|
| wann fährt der nächste bus | `departures(line="")` |
| wann kommt die nächste bim | `departures(line="")` |
| abfahrten | `departures(line="")` |
| die nächsten abfahrten | `departures(line="")` |
| die nächste abfahrt | `departures(line="")` |
| wann ist die nächste abfahrt | `departures(line="")` |
| wann fährt der nächste 14a | `departures(line="14a")` |
| wann kommt der u6 | `departures(line="u6")` |
| wann fährt die d | `departures(line="d")` |
| fahrplan | `departures(line="")` |

### Line resolution

STT mangles line names predictably: "14A" → "14 a" / "vierzehn a", "U6" → "u sechs", "D" → "de". The normalizer in `:core` has already turned German number words into digits by the time a template matches, so both spellings arrive and both are resolved here:

1. Normalize: lowercase, drop a leading "linie"/"bus"/"u bahn", map any surviving spelled-out digits to numerals ("u sechs" → "u6"), drop the Viennese `-er` ending ("der Sechser" → "6"), then remove the spaces between digits and a trailing letter ("14 a" → "14a").
2. Match case-insensitively, whitespace-insensitively, against the `name` values the API returned for the configured stations.
3. No match → answer with **all** lines rather than failing; the user asked about departures either way. Prefix the answer with „Die Linie habe ich nicht gefunden, aber: …".

### Behavior

1. One `GET` for **all** configured stations, with `diva` repeated:

   ```
   https://www.wienerlinien.at/ogd_realtime/monitor?diva=60201320&diva=60200657
   ```

   Verified live on 2026-09-25: those two ids return 16 monitors covering Stephansplatz and Karlsplatz, and the horizon is roughly 70 minutes of departures.
2. Parse `data.monitors[]`. Three fields carry the whole model:
   - `locationStop.properties.name` is **the DIVA id the monitor belongs to**, which is what makes one batched request attributable to the right station. (`title` is the platform's own name — "Karlsplatz" for the U-Bahn platforms and "Karlsplatz U" for the tram and bus ones, for a single DIVA — so it is a label and never a key. The configured label wins on the card.)
   - `lines[]`: `name` ("U1"), `towards` ("Leopoldau"), `direction` ("H"/"R"), `platform`, `barrierFree`, `type` (`ptMetro`, `ptTram`, `ptBusCity`, …).
   - `departures.departure[].departureTime`: `timePlanned`, `timeReal`, `countdown`. **`countdown` is the source of truth** — it is in minutes and already realtime-corrected, and `timePlanned` can be null on an unplanned extra service.
3. Group by *(station, line, destination)*. Several monitors can carry the same line and destination at one station — two platforms, or the same line listed once per direction code — and to somebody waiting they are one question. Their countdowns are merged and sorted. `direction` is kept for the record and is not the grouping key.
4. Filter to `line` if one was resolved. Sort by countdown.
5. Cache the response for `min_poll_interval_s` (§7); a voice request inside that window is answered from cache and makes **no** request. **Fair use is a functional requirement, see §6.**

### Result — with a line

`Spoken`. The **next and the overnext** departure, **per direction**, for **every** configured station that serves the line. Both directions are always spoken: a panel that answers "in 3 Minuten" without saying which way it is going has answered half the question, and the half it left out is the one that gets somebody onto the wrong train.

One configured station serves the line — the station is not named, because there is nothing to distinguish it from:

> „Der U6 Richtung Siebenhirten fährt in 3 Minuten, danach in 8. Richtung Floridsdorf in 5, danach in 12."

Several configured stations serve it — each block leads with its station, because otherwise the second half of the sentence silently changes what it is about:

> „Ab Stephansplatz fährt der U1 Richtung Leopoldau in 3 Minuten, danach in 8. Richtung Alaudagasse in 5, danach in 12. Ab Karlsplatz fährt der U1 Richtung Leopoldau in 1 Minute, danach in 9. Richtung Alaudagasse in 4, danach in 11."

Rules:
- `countdown = 0` → „fährt jetzt" / „Richtung Floridsdorf jetzt".
- Exactly one departure known for a direction → „fährt in 4 Minuten." (no „danach").
- The unit is said once per direction and not repeated in the „danach" clause: „in 3 Minuten, danach in 8", not „danach in 8 Minuten". It is one sentence about one line, and the second unit only makes it longer.
- „in 1 Minute", singular, because „in 1 Minuten" is what a wall panel sounds like when nobody read it out loud.
- The article comes from the line's `type`: U-Bahn „Der U6", tram „Die Linie D", bus „Der 14A", anything else „Die Linie …". Derived from the API rather than from guessing German grammar off the line name — and overridable per line in config (§7) for the day the derivation is wrong about one.

### Result — without a line

`Spoken`, the summary across all configured stations: the soonest `max_spoken_lines` departures (default 3), one clause each, in one sentence.

> „Der U6 Richtung Siebenhirten fährt in 3 Minuten, die Linie 33 Richtung Friedrich-Engels-Platz in 5, der 13A Richtung Skodagasse in 7."

With more than one station configured each clause says which one it is from:

> „Der U1 Richtung Leopoldau fährt ab Stephansplatz in 3 Minuten, die U4 Richtung Heiligenstadt ab Karlsplatz in 5, der 13A Richtung Skodagasse ab Josefstädter Straße in 7."

The cap is the point, not a limitation: **the card is the full answer and the speech is the summary** (§5). A wall panel reading out eleven departures for thirty seconds is a panel somebody walks away from, and every one of those eleven is already on the screen it is standing on. Only the soonest departure of each *(station, line, destination)* competes for a place — the same line twice in a row would spend the budget saying one thing.

Every answer also wakes the screen for 30 s, so the card is showing by the time the sentence ends.

### Failure modes

| Condition | Result | German TTS |
|---|---|---|
| No stations configured | `Failed` | „Es ist noch keine Haltestelle eingestellt." |
| No network | `Failed`, or the cache with a caveat when there is one | „Ich habe gerade keine Internetverbindung." / „Stand von vor {n} Minuten: …" |
| API 4xx/5xx or timeout (3 s connect, 3 s read) | `Failed`, same cache-with-caveat rule | „Die Wiener Linien antworten gerade nicht." |
| 429, or a 5xx during the backoff window (§6.6) | `Failed`, same rule | „Die Wiener Linien lassen mich gerade nicht so oft fragen." |
| Response parses but has no departures | `Spoken` | „Für die nächste Zeit sind keine Abfahrten gemeldet." |
| The Sock has not been started | `Failed` | „Ich komme gerade nicht an die Abfahrten." |

A stale cache is answered from rather than refused, exactly as the Weather Sock does it and for the same reason: four-minute-old departures are still departures, and the caveat is what keeps them honest.

---

## 4. Activity

No shared subscriptions, so `activityFor` always returns `INACTIVE` and the chain never consults this Sock.

---

## 5. State & dashboard

Exposed state: `StateFlow<DeparturesState>` = `stations`, `board` (the last successful fetch, which survives a failed one), `refreshing`, `error` (German, drawn rather than spoken).

**`DeparturesCard`** (`android/app/.../ui/DeparturesCard.kt`) sits below the weather card, and is drawn **whenever a station is configured** — the same rule as the memo and weather cards: departures are not something the panel is *doing*, they are something that is true, and a board you have to ask for is a board you walk past.

- **One block per configured station**, in configured order, each headed by its label. Grouping by station rather than sorting everything into one list: the countdown is only meaningful next to the place it is counting down at, and a merged board would ask somebody to read the station column of every row.
- **Rows are departures**, not lines: line badge, destination, countdown. Sorted by countdown within the station, at most six — a seventh row on a wall panel is a row nobody reads, and the whole horizon is 70 minutes anyway. The same line appearing twice is what a real departure board looks like.
- **The badge carries the line's colour**: the five U-Bahn lines in the city's own colours, tram red, bus blue, anything else grey. At arm's length the colour is read before the text.
- `0` renders as **„jetzt"**. It is the one countdown that means something different in kind — not "soon" but "you are not catching this one" — and "0 min" makes it look like the smallest number in the column rather than the one that has run out.
- **The footer** is the freshness line — „Stand vor 2 Minuten", „wird geholt…", or the error in red — and, permanently beside it, the attribution: `Datenquelle: Stadt Wien – data.wien.gv.at`. The data is CC BY 4.0 and the attribution is a licence term, so it is not a tooltip and not an about screen: it is on the card, always, in the same type as the freshness. Tapping the freshness line refreshes, within the limits of §6.
- **Stale is marked, never blanked.** While a refresh is in flight, or after one has failed, the rows are drawn dimmed with the caveat in the footer. A card that empties itself the first time a kitchen's Wi-Fi hiccups is a card that looks broken twice a day.
- **No station configured** → one line pointing at the settings screen, and tapping it goes there. This Sock's setup is typing a number, which is a keyboard's job and not a card's.

Auto-refresh runs on a regular interval **only while the screen is on** — see §6.1, which is where that rule comes from and what it is for.

## 6. Fair use — hard rules

The OGD realtime endpoint is free and unauthenticated. Abusing it risks an IP block, which would take the feature down permanently. These are requirements, not guidelines:

1. Poll **only** while the screen is on, or on an explicit voice request.
2. Minimum interval **30 s** (the published spec minimum is 15 s — Dobby deliberately doubles it).
3. Only the configured stations. Never enumerate, never crawl.
4. One request per poll, all stations batched.
5. A descriptive `User-Agent` identifying the app.
6. Back off exponentially on any 5xx or 429, up to 15 minutes.
7. Attribution rendered wherever the data is shown (§5).

Where each of them lives in the code, because a rule with no address is a rule that gets refactored away:

| Rule | Enforced in |
|---|---|
| 1 | `DeparturesSock.onStart` — the ticker fetches only when `ctx.screen.isOn`. That property is a core addition this Sock required: `ScreenController` could wake the screen and not say whether it was awake. |
| 2 | `DeparturesWatch.mayFetch` — a floor of 30 s over `min_poll_interval_s`, applied to the ticker, the voice path and the card's refresh tap alike. There is no bypass, including for a settings change. |
| 3 | `DeparturesConfig.stations` is the only source of ids anything reads. |
| 4 | `WienerLinien.fetch` takes the whole station list and builds one URL with repeated `diva` parameters. |
| 5 | `WienerLinien.USER_AGENT`. |
| 6 | `DeparturesWatch.backoff` — doubling from the poll interval, capped at 15 minutes, cleared by the first good response. |
| 7 | `DeparturesCard` footer, unconditional. |

## 7. Config

| Key | Type | Default | Where set |
|---|---|---|---|
| `departures.stations` | string | `""` | Settings screen — `diva=label` entries separated by `\|`, e.g. `60201320=Stephansplatz\|60200657=Karlsplatz` |
| `departures.min_poll_interval_s` | int | `30` | Settings (advanced), **floor of 30 enforced in code** |
| `departures.max_spoken_lines` | int | `3` | Settings (advanced) |
| `departures.line_articles` | string | `""` | Settings (advanced) — `u6=der,d=die Linie`, overriding what §3 derives from the API's `type` |

**A single string rather than a list type**, because `SockConfigStore` stores strings and a Sock that needed a second store shape would be a core change for one Sock's convenience. An entry with no `=` is read as a DIVA with no label and falls back to displaying the id; an entry whose DIVA is not a number is dropped rather than sent, because a malformed id in a batched request costs the whole request.

**The settings screen takes a raw DIVA id and a label**, and that is v1 on purpose. The ids come from the published station list at
`https://www.wienerlinien.at/ogd_realtime/doku/ogd/wienerlinien-ogd-haltestellen.csv` (`DIVA;PlatformText;Municipality;…`, roughly 2000 rows, no auth) — one lookup, once, for a panel that does not move. A search-by-name field would mean shipping or fetching that CSV to save somebody a one-time copy-paste, and the entry field is where the label is typed anyway: „Haltestelle" on the card should read the way the household says it, which no CSV knows.

Changing the station list clears the held board rather than refetching immediately — §6.2 has no bypass. The next tick fills it, which with the screen on is within 30 seconds of pressing the button.

## 8. Failure & degradation

- No stations → `Degraded("keine Haltestelle eingestellt")`; the command stays registered and explains itself when invoked.
- Repeated 5xx or a 429 → `Degraded("Wiener Linien nicht erreichbar")`, exponential backoff (§6.6), recovers automatically on the first good response.
- The dashboard card renders the last good data with a visible staleness marker rather than disappearing.

## 9. Utterance collision surface

Exclusively claimed: `wann fährt/kommt/geht …`, `abfahrten` (bare), `nächste abfahrt`, `abfahrtszeiten`, `fahrplan`. Contributes to no chain.

**A bare `abfahrt` was rejected** under `socks.specs/README.md` §6's single-keyword rule — see §3 for the test that rejected it, so the next author reads the gap as a decision rather than an oversight.

The one real neighbour is the **Clock**, which owns `clock.date_of_weekday` — "wann ist der nächste Samstag". Both start with `wann ist/fährt … der nächste`, and they are kept apart by the word that follows it: the Clock's template requires a `{weekday:enum}`, whose candidate set is closed, and this Sock's requires one of `fährt`/`kommt`/`geht`. Neither can be satisfied by the other's utterance, which is why this is a template question and not a shared command.

The **Weather** Sock is the near miss that never happened: "wann kommt der Regen" would be a true collision — which Sock answers would depend on the words and not on runtime state — but Weather claims no `wann` template at all (`weather.specs.md` §2), so there is nothing to arbitrate. Whoever adds one must read this section first.

## 10. Testing

**None.** This repository is a playground and carries no tests for this Sock — which overrides `socks.specs/README.md` §7's testing section for this file alone, deliberately and with the cost written down: the utterance table in §3 is normally asserted by the registry on every build, and the fair-use rules in §6 are normally asserted against a fake clock and a request-counting HTTP client.

What that leaves standing is the registry's own build-time gates, which are not tests in this Sock: the collision gate, the filler gate and the Tier 2 program generator all run over the shipped Sock list, so a template here that swallows another Sock's utterance still fails the build in `:android:app`.

The two things a test would have caught, written down instead: the 30-second floor and the one-request-per-poll rule (§6, rows 2 and 4) have no automated guard, and a refactor that moved the fetch out of `DeparturesWatch` could quietly lose both.

## 11. Open questions / out of scope (v1)

- Station search by name in settings; disruption and `trafficInfo` messages from the API; walking-time offset („in 4 Minuten" is useless if the stop is 5 minutes away); `barrierFree` on the card, which is parsed and not yet drawn; other transit operators.
- Filtering a configured station down to the lines somebody cares about. A DIVA brings everything the station has, which at Karlsplatz is four U-Bahn lines, a tram and three bus lines — fine on the card, and the reason the spoken summary is capped.
- Proactive announcements („dein Bus fährt gleich") — needs a triggering model Dobby does not have, and would make this Sock a `shared.stop` subscriber (§2).
