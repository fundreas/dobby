# Sock: Weather

> **Implementation status.** Built and shipping in `:socks:weather`.

## 1. Identity

| | |
|---|---|
| **id** | `weather` |
| **displayName** | Wetter |
| **Purpose** | What the sky is doing where the panel is standing — now, for the rest of today, and for the next two days. |
| **Milestone** | M6 — the first Sock that has to be told *where it is* |
| **Dependencies** | OkHttp + kotlinx.serialization (both already in the build, for `:socks:spotify`) |
| **Hard requirements** | Network. **No API key, no account.** |
| **Permissions** | `INTERNET`, `ACCESS_COARSE_LOCATION` |
| **Audio** | none. It neither plays nor claims the channel, and subscribes to no chain |
| **Data** | [Open-Meteo](https://open-meteo.com) — CC BY 4.0, free for non-commercial use, no key |

**Why it is a Sock and not a widget.** The card is half of it and the voice is the other half, and they answer different questions. A card says what the weather *is*; a voice answers "wie kalt wird es heute noch", which is a question about a subset of the hours, asked by somebody whose hands are full and who is deciding whether to shut a window. Neither half is worth much alone: a forecast you have to walk up to and read is a forecast you check twice a day, and an answer with nothing drawn behind it is one you have to ask for again because you were not listening the first time.

**Why it needs a Setup button, which nothing else in Dobby does.** Every other Sock either needs nothing from the world (Clock, Calculator, Memo), takes its addresses from a constant table (Radio), or asks another app (Spotify). A forecast is *about a place*, there is no defensible default place, and a forecast for the wrong city is worse than no forecast because it is confidently wrong and nothing on the panel says so. So the panel asks the phone once, saves the answer, and never asks again unless it is told the panel has moved (§6).

---

## 2. Commands

### Exclusive

| Command id | Params | Description (German, feeds Tier 2) |
|---|---|---|
| `weather.temperature` | `day`, `bound` | Sagt die Temperatur — jetzt, im Tageshöchstwert oder im Tiefstwert. |
| `weather.forecast` | `day` | Sagt, wie das Wetter ist oder wird. |
| `weather.rain` | `day`, `kind` | Sagt, ob Regen oder Schnee zu erwarten ist. |
| `weather.sun` | `day` | Sagt, ob die Sonne scheint oder scheinen wird. |
| `weather.wind` | `day` | Sagt, wie stark der Wind weht. |
| `weather.update_location` | — | Fragt das Handy neu, wo es steht, und merkt sich den Ort für das Wetter. |

**Five questions, not fifteen commands.** Every weather question varies on the same axis — which day — so `day` is declared once, identically, by all five. Without that the command surface would be `temperature_today`, `temperature_tomorrow`, `rain_today` and so on, which is the same grammar written out five times and five times as much Tier 2 prompt to pay for.

`bound` and `kind` are the two places where a *second* axis exists and no slot can carry it, because the word that decides it is part of the verb phrase: "wie **warm** wird es" against "wie **kalt** wird es", "**regnet** es" against "**schneit** es". Both are fixed by the template's wording (`socks.specs/README.md` §6, static params) rather than re-parsed in the handler.

### Shared subscriptions

**None**, and none was close. This Sock answers a question and holds nothing anybody would ever say "stopp" to; `activityFor` always returns `INACTIVE` and is never consulted.

---

## 3. Where the data comes from

One `GET` per refresh:

```
https://api.open-meteo.com/v1/forecast
  ?latitude=<lat>&longitude=<lon>
  &current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m
  &daily=weather_code,temperature_2m_max,temperature_2m_min,
         precipitation_probability_max,precipitation_sum,
         wind_speed_10m_max,sunrise,sunset
  &hourly=temperature_2m,precipitation_probability,weather_code
  &timezone=auto
  &forecast_days=3
```

Three deviations from the request this Sock was specified from, all of them deliberate:

| Asked for | Shipped | Why |
|---|---|---|
| `forecast_days=2` | `forecast_days=3` | Two forecast days is *today and tomorrow*. The commands are specified for **übermorgen** in the same breath, so two days would have answered a question about the day after tomorrow with nothing — or, worse, off by one. Three is a few kilobytes more. |
| `timezone=Europe/Vienna` | `timezone=auto` | The location comes from the phone, so pinning the zone would pin it to a place the user is allowed to change. With `auto` the response carries the zone its own hours were computed in, and "heute" means the day where the panel is standing. |
| five `daily` fields | eight | `wind_speed_10m_max`, because "wie stark wird der Wind morgen" is a sentence the command surface accepts and the other five cannot answer. `sunrise`/`sunset`, because "scheint die Sonne" is otherwise wrong twice a day — WMO code 0 is "klar" at two in the morning as much as at noon, and the first live run of this Sock answered a question asked at half past six in September with "ab 21 Uhr wird es teils bewölkt", which is a forecast of moonlight. |

**Refresh every ten minutes** (§9), which is also Open-Meteo's own update cadence for the `current` block: polling faster would burn a free service's fair use to redraw the same number. The tick is a plain loop on `ctx.scope` rather than an `AlarmManager` job, because a forecast nobody is looking at is worth nothing — this exists so the card is right when somebody walks past it, and the panel is either running or it is not.

**A command does not normally fetch.** It reads the cache, which is what makes "wie warm ist es" instant. It only goes to the network when the cache is older than twice the refresh interval or absent, and then the short HTTP timeouts matter: the dispatcher gives a handler five seconds (`Dispatcher.DEFAULT_TIMEOUT`) and a panel that spends all five holding the microphone open and then says nothing is worse than one that says it is offline.

### Failure modes

| Condition | Result | German |
|---|---|---|
| No location saved | `Failed` | „Ich weiß noch nicht, wo ich stehe. Tipp auf dem Panel einmal auf ‚Standort einrichten'." |
| No network, nothing cached | `Failed` | „Ich habe gerade keine Internetverbindung." |
| HTTP error or unparseable body, nothing cached | `Failed` | „Der Wetterdienst antwortet gerade nicht." |
| Rate limited, nothing cached | `Failed` | „Der Wetterdienst lässt mich gerade nicht so oft fragen." |
| Any of the above, **something cached** | `Spoken`, with a caveat | „Stand von vor 25 Minuten: es sind 14 Grad." |
| Asked about a day past the forecast | `Spoken` | „So weit reicht meine Vorhersage nicht." |
| Before `onStart` | `Failed` | „Ich komme gerade nicht an das Wetter." |

**A stale answer with a caveat beats a refusal**, and that is the one judgement in this table. Twenty-minute-old weather is still weather; refusing it would be a panel throwing away the thing it was asked for because it could not get a fresher copy. What is not optional is saying so — silence about staleness is what turns an old number into a wrong one.

**Open-Meteo signals its limits with 400, not only 429.** Over the free tier it answers `{"error":true,"reason":"Minutely API request weight exceeded…"}` with a 400, and a panel that reports that as "der Wetterdienst antwortet nicht" sends somebody to look at their router. The error body is read and matched, which is the difference between a true sentence and a plausible one.

---

## 4. `weather.temperature`

### Params

| Name | Type | Required | Default | Constraints |
|---|---|---|---|---|
| `day` | Enumeration | no | `heute` | `heute`, `morgen`, `übermorgen`, `uebermorgen` |
| `bound` | Enumeration | no | `jetzt` | `jetzt`, `warm`, `kalt` — fixed by the template's wording |

### Tier 1 templates

Every one of them is a *head* plus the shared day tail, which is written once in `WeatherSock.DAY_TAIL`:

```
<head> (es)? (draußen|draussen|drausen)? (denn)? (noch)? ({day:enum})? (noch)?
```

| head | `bound` |
|---|---|
| `wie (warm\|kalt) ist` | `jetzt` |
| `(wie viel\|wieviel) grad (haben wir\|hat\|haben\|sind\|hats)` | `jetzt` |
| `(wie\|was) ist die temperatur` | `jetzt` |
| `(sag\|nenn\|nenne) (mir)? die temperatur` | `jetzt` |
| `temperatur (draußen\|draussen)` | `jetzt` |
| `wie (warm\|heiß\|heiss) (wird\|wirds)` | `warm` |
| `wie (hoch) (wird\|steigt) die temperatur` | `warm` |
| `was ist die (höchsttemperatur\|hoechsttemperatur)` | `warm` |
| `wie (warm\|heiß\|heiss) soll es werden` | `warm` |
| `wie (kalt\|kühl\|kuehl) (wird\|wirds)` | `kalt` |
| `wie (tief\|weit) (sinkt\|fällt\|faellt) die temperatur` | `kalt` |
| `was ist die (tiefsttemperatur\|tiefstemperatur)` | `kalt` |

plus one closed template of its own, because "heute Nacht" is a phrasing and not a day word:

```
wie (kalt|kühl|kuehl) (wird|wirds) (es)? (heute)? nacht        → bound = kalt, day = heute
```

**`(es)?` and `(denn)?` are spelled out although both are on `Fillers.DE`.** The matcher skips filler in front of a *keyword* and never in front of a slot, and the thing after them here is the day slot — drop them and "regnet es denn morgen" matches nothing at all. This is `socks.specs/README.md` §6's "unless the next thing is a slot", and it is the single easiest rule in the template DSL to forget.

**`(noch)?` appears on both sides of the day**, because "heute noch" and "noch heute" are both ordinary German and `noch` is not filler — it is a content word meaning *the rest of*, which is exactly the distinction this command turns into a different answer.

### Utterances → invocation

| Utterance | Invocation |
|---|---|
| wie warm ist es | `temperature(day=heute, bound=jetzt)` |
| wie kalt ist es | `temperature(day=heute, bound=jetzt)` |
| wie warm ist es draußen | `temperature(day=heute, bound=jetzt)` |
| wie viel grad hat es | `temperature(day=heute, bound=jetzt)` |
| wie viel grad haben wir | `temperature(day=heute, bound=jetzt)` |
| wie ist die temperatur | `temperature(day=heute, bound=jetzt)` |
| wie warm ist es morgen | `temperature(day=morgen, bound=jetzt)` |
| wie warm ist es übermorgen | `temperature(day=übermorgen, bound=jetzt)` |
| wie warm wird es heute noch | `temperature(day=heute, bound=warm)` |
| wie warm wird es morgen | `temperature(day=morgen, bound=warm)` |
| wie heiß wird es morgen | `temperature(day=morgen, bound=warm)` |
| wie kalt wird es heute noch | `temperature(day=heute, bound=kalt)` |
| wie kalt wird es morgen | `temperature(day=morgen, bound=kalt)` |
| wie kalt wird es heute nacht | `temperature(day=heute, bound=kalt)` |
| was ist die höchsttemperatur morgen | `temperature(day=morgen, bound=warm)` |

Tier 2 few-shots (`matchedByTemplates = false`): "muss ich mir morgen eine jacke anziehen oder wird es warm", "sag mal frierts heute nacht". Held out: "ist es draußen noch angenehm", "wird das übermorgen ein warmer tag".

### Behavior

**Today is not the same question as tomorrow, and that is the whole of this command.**

For a *future* day the answer is the daily maximum or minimum, which is what a forecast is. For **today** the daily minimum is last night: answering "wie kalt wird es heute noch" with it at two in the afternoon is a panel that is confidently wrong about something somebody is about to act on. So today's bounds are taken over `hoursFrom(now)` — the hours that have not happened yet — which is the only reason the `hourly` block is fetched at all.

| `day` | `bound` | answer is |
|---|---|---|
| heute | jetzt | the thermometer, plus the felt temperature when it differs by a degree or more |
| heute | warm | the warmest hour still to come, and roughly when |
| heute | kalt | the coldest hour still to come, and roughly when |
| heute | warm/kalt, **no hours left** | the thermometer, and the words "nicht mehr" |
| morgen/übermorgen | jetzt | both ends — the present tense used about tomorrow is a figure of speech, and a question that named neither end gets both |
| morgen/übermorgen | warm | the day's maximum |
| morgen/übermorgen | kalt | the day's minimum |

### Result

| Case | German TTS |
|---|---|
| now | „Es sind 18 Grad, gefühlt 16 Grad." |
| now, felt within a degree | „Es sind 18 Grad." |
| rest of today, warmest | „Heute wird es noch bis zu 21 Grad, am wärmsten gegen 15 Uhr." |
| rest of today, coldest | „Heute fällt es noch auf 14 Grad, am kältesten gegen 23 Uhr." |
| rest of today, nothing warmer left | „Wärmer wird es heute nicht mehr, es sind 18 Grad." |
| a future day, both ends | „Morgen zwischen 10 und 21 Grad." |
| a future day, maximum | „Morgen wird es bis zu 21 Grad." |
| a future day, minimum | „Übermorgen sinkt es auf 10 Grad." |

**A negative temperature is a word, not a sign.** „minus 3 Grad", never „-3 Grad": a TTS engine handed `-3` says „drei", and a panel that says it is three degrees on a night it is minus three is the one weather error with a cost attached to it.

**„Wärmer wird es heute nicht mehr, es sind 18 Grad"** quotes the thermometer rather than the forecast's remaining peak, and that is a fix from the first live run: at half past six the forecast's best remaining hour was 17 while the window said 18, and „wärmer als 17 Grad wird es nicht mehr" is a panel arguing with the room.

---

## 5. `weather.forecast`, `weather.rain`, `weather.sun`, `weather.wind`

All four take `day` alone, except `rain`, which also takes `kind` ∈ {`regen`, `schnee`}.

### Tier 1 templates

Two shapes, and German needs both. `dayForms` puts the day **after** the question ("regnet es morgen"); `dayInfix` puts it **inside** it ("wird es morgen regnen"), which is where German puts it when the verb that carries the meaning is at the end. A Sock that shipped only one of them would be a Sock that hears half of what is said to it.

```
weather.forecast
    wie (ist|wird) das wetter …
    wie (siehts|sieht es) wettermäßig aus …
    was sagt (der)? (wetterbericht|wetterdienst) …
    (wetterbericht|wettervorhersage|wetteraussichten) …
    (ist|wird) es (bewölkt|bewoelkt|wolkig|neblig|nebelig) …   + the infix order
    (das)? wetter {day:enum} (noch)?                            ← day REQUIRED, see §7
    wie (wirds|wird es) {day:enum}

weather.rain   (kind = regen)
    (regnet|regnets) es (denn)? (noch)? ({day:enum})? (noch)?   ← "es" REQUIRED, see §7
    (regnet|regnets) (denn)? (noch)? {day:enum} (noch)?         ← day REQUIRED instead
    (wird|wirds) es regnen …                                    + the infix order
    (gibt|kommt) es regen …                                     + the infix order
    (brauche|brauch) ich … (einen|nen)? (regenschirm|schirm)
    wie (hoch|gross|groß) ist die regenwahrscheinlichkeit …

weather.rain   (kind = schnee)
    (schneit|schneits) es …   ·   (schneit|schneits) … {day:enum}
    (wird|wirds) es schneien …   ·   (gibt|kommt) es schnee …   + both infix orders

weather.sun
    (scheint|scheints) (die)? sonne …            + the infix order
    (ist|wird|ists|wirds) es sonnig …            + the infix order
    (gibt|kommt) es sonne …                      + the infix order
    (haben|kriegen) wir sonne …                  + the infix order
    (lässt|laesst) (die)? sonne sich blicken …

weather.wind
    wie (stark|schnell|heftig) (ist|wird) der wind …
    wie (ist|wird) der wind …
    (wie viel|wieviel) wind (haben wir|gibt es)? …
    (ist|wird|ists|wirds) es windig …            + the infix order
    (weht|bläst|blaest) der wind …
    (gibt|kommt) es (sturm|starken wind) …       + the infix order

weather.update_location
    (aktualisiere|aktualisier|erneuere|erneuer|bestimme|bestimm) (den|meinen)? standort (neu)?
    standort (aktualisieren|erneuern|bestimmen|neu bestimmen|neu)
    (neuer|neuen) standort
    (wetter|wetterstandort) (neu)? (einrichten|einstellen)
    (merk|merke) dir (den)? (neuen)? standort
```

### Utterances → invocation

| Utterance | Invocation |
|---|---|
| wie ist das wetter | `forecast(heute)` |
| wie wird das wetter morgen | `forecast(morgen)` |
| wie ist das wetter übermorgen | `forecast(übermorgen)` |
| das wetter morgen | `forecast(morgen)` |
| wetterbericht | `forecast(heute)` |
| wettervorhersage morgen | `forecast(morgen)` |
| was sagt der wetterbericht morgen | `forecast(morgen)` |
| ist es morgen bewölkt | `forecast(morgen)` |
| regnet es | `rain(heute, regen)` |
| regnet es heute noch | `rain(heute, regen)` |
| regnet es noch heute | `rain(heute, regen)` |
| regnet es morgen | `rain(morgen, regen)` |
| regnet es übermorgen | `rain(übermorgen, regen)` |
| regnets morgen | `rain(morgen, regen)` |
| wird es morgen regnen | `rain(morgen, regen)` |
| gibt es morgen regen | `rain(morgen, regen)` |
| brauche ich morgen einen regenschirm | `rain(morgen, regen)` |
| schneit es morgen | `rain(morgen, schnee)` |
| gibt es übermorgen schnee | `rain(übermorgen, schnee)` |
| scheint die sonne | `sun(heute)` |
| scheint die sonne morgen | `sun(morgen)` |
| scheint die sonne heute noch | `sun(heute)` |
| ist es morgen sonnig | `sun(morgen)` |
| wird es übermorgen sonnig | `sun(übermorgen)` |
| gibt es morgen sonne | `sun(morgen)` |
| wie stark ist der wind | `wind(heute)` |
| wie stark wird der wind morgen | `wind(morgen)` |
| ist es windig | `wind(heute)` |
| wird es morgen windig | `wind(morgen)` |
| wie viel wind haben wir | `wind(heute)` |
| gibt es morgen sturm | `wind(morgen)` |
| aktualisiere den standort | `update_location()` |
| standort aktualisieren | `update_location()` |
| bestimme meinen standort neu | `update_location()` |
| neuer standort | `update_location()` |
| wetter neu einrichten | `update_location()` |

Tier 2 few-shots: "kann ich morgen im garten grillen oder wird das nichts", "soll ich die wäsche lieber reinholen morgen", "kann ich morgen mit dem kinderwagen in den park", "kann ich den sonnenschirm draußen stehen lassen", "ich bin umgezogen stell das wetter neu ein". Held out: "was erwartet uns denn so übermorgen draußen", "kann ich das fahrrad heute draußen stehen lassen", "reißt der himmel heute noch auf", "zieht es morgen draußen ordentlich", "wir wohnen jetzt woanders".

### Behavior and result

**`weather.forecast`** — for today it starts with *now*, because somebody asking "wie ist das Wetter" out loud at three in the afternoon is looking out of a window they cannot see, and the sky they are asking about is the one that is there. For a future day it is the day's own summary.

| Case | German TTS |
|---|---|
| today | „Gerade bedeckt bei 18 Grad. Heute noch bis zu 21 Grad." |
| today, no warmer hour left | „Gerade bedeckt bei 18 Grad." |
| a future day | „Morgen bedeckt, zwischen 10 und 21 Grad." |
| any, rain worth mentioning | + „ Regenwahrscheinlichkeit 80 Prozent." |

The rain clause is left off below 30 % rather than said, for the reason the Memo Sock leaves out a zero: „Regenwahrscheinlichkeit 10 Prozent" is a sentence only a computer would produce, and a twenty-percent chance of rain is the background hum of an Austrian afternoon.

**`weather.rain`** — for today the answer comes out of the *hours*, because "ab 17 Uhr" is the part somebody standing in a doorway actually needs; a daily probability would say "80 Prozent" about a shower that fell at six this morning. For a future day it is the daily probability and the day's own code.

| Case | German TTS |
|---|---|
| today, an hour at ≥ 50 % or with rain in its code | „Ja, heute ab 17 Uhr, Wahrscheinlichkeit 70 Prozent." |
| today, best remaining chance 30–49 % | „Eher nicht, die Wahrscheinlichkeit liegt bei 40 Prozent." |
| today, under 30 % | „Nein, heute bleibt es trocken." |
| a future day, likely | „Ja, morgen: Regenschauer, Wahrscheinlichkeit 80 Prozent, etwa 5 Millimeter." |
| a future day, unlikely | „Eher nicht, die Wahrscheinlichkeit liegt bei 40 Prozent." / „Nein, morgen bleibt es trocken." |
| `kind = schnee`, expected | „Ja, morgen: leichter Schneefall." |
| `kind = schnee`, not expected | „Nein, Schnee ist morgen nicht zu erwarten." |

**Fifty percent is where "ja" starts**, and the band below it is deliberate rather than a rounding choice: a flat "nein" at forty-five percent is a panel that will be wrong twice a week and sound certain both times.

**Snow is the same command with the other answer.** Splitting it into `weather.snow` would have made a command that is wrong eleven months of the year and doubled the Tier 2 prompt for it.

**`weather.sun`** — "gerade nicht, aber ab 15 Uhr" is the answer this command exists for. A flat no on an overcast morning that clears by lunchtime is technically defensible and useless.

| Case | German TTS |
|---|---|
| today, dark, before dawn | „Die Sonne geht erst um 6:45 Uhr auf." |
| today, dark, after dusk | „Die Sonne ist um 18:46 Uhr untergegangen." |
| today, sun out now | „Ja, gerade ist es klar." |
| today, sun later | „Gerade nicht, aber ab 15 Uhr wird es teils bewölkt." |
| today, no sun left | „Nein, heute bleibt es bedeckt." |
| a future day | „Ja, morgen wird es überwiegend klar." / „Nein, morgen wird es bedeckt." |

Sunrise and sunset are fetched for this table and nothing else, and they are not optional — see §3.

**`weather.wind`** — the word first, then the number, because "zwölf Kilometer pro Stunde" means something to a sailor and nothing to somebody deciding whether to put the parasol up. The bands are the Beaufort scale collapsed to the six distinctions German makes in a kitchen (`WindForce`).

| Case | German TTS |
|---|---|
| today | „Es ist windstill, 5 Kilometer pro Stunde." / „Mäßiger Wind, 24 Kilometer pro Stunde." |
| a future day | „Morgen schwacher Wind, bis zu 9 Kilometer pro Stunde." |
| a future day, no wind forecast | „Für den Tag habe ich keine Windvorhersage." |

**`weather.update_location`** — the spoken twin of the card's location chip (§8). Both run the same `WeatherWatch.locate`, so a tap and an utterance are one event, exactly as the radio card's picker and "spiele radio ö1" are.

| Case | German TTS |
|---|---|
| located | „Standort gespeichert. Ich hole das Wetter." |
| no fix, or permission refused | „Ich konnte den Standort nicht bestimmen. Ist die Standortfreigabe für Dobby an?" |

**The forecast is not waited for.** A spoken update runs inside the dispatcher's five-second budget, and a location fix plus an HTTP round trip does not reliably fit in it — so the fix is the answer and the forecast arrives on the card a moment later, which is where somebody is looking anyway.

---

## 6. The location

**Asked for twice in the life of the panel**, and that is the design rather than an optimisation: once when somebody presses Setup, and again whenever they tap the card's location chip because the panel has moved. A wall panel does not travel; polling the position would be a permission prompt, a radio wake-up and a battery cost for a number that has not changed since it was screwed to the wall.

**`ACCESS_COARSE_LOCATION`, not `FINE`.** A forecast grid cell is kilometres across, so a fine fix buys nothing and costs the scariest permission dialog Android has. `FUSED_PROVIDER` honours coarse permission by returning a deliberately blurred position, which is exactly the position this feature wants.

**The permission is asked for at the button and not at launch.** The microphone is what Dobby *is*, and asking for it on the first screen is honest; location is one Sock's setting, and a panel that demanded to know where it is before it would listen to anything would look like it is collecting rather than helping. The dialog can only be shown by an Activity, which is why `MainActivity.setUpWeather` exists rather than the card calling the controller directly.

**A refused fix leaves the saved location alone.** Somebody who taps the chip in a flat with location services switched off should end up with the forecast they already had, not with an unconfigured panel — losing a working setup is a worse outcome than not updating it. Refused permission, location switched off and no fix in time are one case with one sentence, because the panel does the same thing with all three.

**A new location drops the old forecast.** A card still drawing Vienna's afternoon under a new set of coordinates would be lying with a straight face.

`getCurrentLocation` with a 2.5 s timeout and the last known fix behind it, never `requestLocationUpdates`: a subscription would be a listener to unregister, a battery cost, and a stream of positions for a device screwed to a wall. For a panel that has not moved, the last known fix *is* the right answer.

---

## 7. Utterance collision surface

Exclusively claimed: `warm`, `kalt`, `heiß`, `kühl`, `grad`, `temperatur`, `höchsttemperatur`, `tiefsttemperatur`, `wetter`, `wetterbericht`, `wettervorhersage`, `wetteraussichten`, `wettermäßig`, `wetterdienst`, `bewölkt`, `wolkig`, `neblig`, `regnet`, `regnen`, `regen`, `regenschirm`, `schirm`, `regenwahrscheinlichkeit`, `schneit`, `schneien`, `schnee`, `sonne`, `sonnig`, `wind`, `windig`, `sturm`, `weht`, `bläst`, `standort`, plus the day words `heute`, `morgen`, `übermorgen` — **and those three only ever inside an `{day:enum}` slot that sits behind a weather keyword.**

That last clause is the rule the whole Sock follows, and it is what keeps it out of the Clock's way:

- `welcher tag ist morgen` and `welches datum ist übermorgen` are `clock.date_in`; `wie warm ist es morgen` is this Sock. Same day word, different noun in front of it. The Clock claims `morgen` only beside `tag`, `wochentag` or `datum`; this Sock claims it only beside a weather word.
- `wie spät` and `wie viel uhr` are `clock.whats_the_time`; `wie viel grad` is this Sock. `grad` is the distinguishing word and it is a content word.
- `wie viel ist 7 mal 8` is `calculator.calculate`, whose templates all need an integer immediately after the run-up; `wie viel grad haben wir` cannot reach them because `grad` is not a number.
- `wie lange noch` is `clock.timer_remaining`; every `noch` in this Sock sits beside a weather verb.

Deliberately **not** claimed, and the rejections are recorded here so the next author does not read them as oversights:

- **Bare `wetter`.** Six characters at tolerance 1, which also hands it `wetten` and `vetter` — both ordinary German, and `wetten` is what somebody says in front of a television. So every forecast template carries a second word (`wie ist das wetter`) or requires the day slot (`wetter {day:enum}`). This is `socks.specs/README.md` §6's single-keyword rule applied exactly as written.
- **Bare `regnet` and bare `schneit`.** Six and seven characters at tolerance 1: `regnen`, `regnete` and `schneien` are all inside it, and all three are things somebody says in a kitchen without addressing the panel — "es regnet schon wieder" is a remark, not a question. So the question form is required: `regnet **es**`, or a day word instead. The cost to the user is zero, because "regnet es" is what they say anyway.
- **Bare `temperatur`.** Ten characters, so `checkSingleKeywordTemplates` would not even flag it, and it is left out anyway: it would be the one template in the Sock with no anchor at all, and "wie ist die Temperatur" costs three words nobody minds saying.
- **Bare `standort`.** Eight characters is the loosest fuzzy radius in the system, and this is the command that throws a working setup away and asks the phone again. Every template names it beside a verb.
- **`wie war das wetter gestern`.** There is no history in this Sock and Open-Meteo's archive API is a different endpoint. It is a Tier 2 *negative* (`Tier2Negatives`), not a few-shot.
- **`brauche ich eine jacke`.** A question about temperature *and* rain at once, with no rule for which to answer. It is a Tier 2 few-shot on `weather.temperature`, which is what that tier is for.

**One line left `Tier2Negatives` because of this Sock.** "wie wird das wetter morgen" headed that list as the example of a question the panel cannot answer; it is a command now, and a negative case that became a command is a test asserting the panel got worse. "wie war das wetter letzte woche" took its place, which is the same shape and still true.

---

## 8. State & dashboard

`WeatherState` — the saved location, the last successful report, whether a fix or a fetch is in flight, and the German for whatever last went wrong. Four fields in one object rather than a sealed hierarchy, because three of them can be true at once: a panel that has a location, is holding a twenty-minute-old report, is refreshing, and failed the last attempt is a real and ordinary state, and a card that could draw only one of those facts would have to pick the least useful.

**`WeatherCard`** (`android/app/.../ui/WeatherCard.kt`) sits below the memo card and above the conversation, and is three cards in one composable because they are three states of one thing — a panel that swapped *components* between them would move everything underneath:

1. **Un-set-up.** One line and a Setup button. The only thing in the whole panel that asks the user for something, and it asks once.
2. **Set up, nothing fetched.** A spinner, or the error.
3. **A forecast**, which is what this Sock exists for.

It is drawn **whenever the Sock is set up**, unlike the two music cards and like `MemoCard`: the weather is not something the panel is *doing*, it is something that is true, and a forecast that appears only while you are asking about it is a forecast you have to ask about.

The forecast card is four rows:

- **The headline** — the condition's symbol, one large temperature, and a line of detail. Today shows the *thermometer*; the other two days show the day's *high*, because they are different numbers answering different questions and putting tomorrow's maximum in today's slot would be the card quietly changing what it means. The detail line says which it is.
- **Three day chips** — Heute · Morgen · Übermorgen, each with its symbol and its high. Every day is shown including the selected one, for the reason the radio card's station picker shows every station: a row that disappears when chosen makes the others move, and a wall panel is operated from muscle memory at arm's length.
- **The hour strip** — one column per second hour: temperature, symbol, a bar for the rain probability, the hour. **For today it starts at the current hour**, which is the whole point of it and the same rule the voice follows in §4. The bar is drawn as height rather than printed as a percentage because on a strip of twelve columns the *shape* is the information — a wet afternoon is a hump you can see from across the room, which twelve numbers cannot be.
- **The footer** — the location chip and the freshness line.

**The footer is where the panel gets moved**, and it is the answer to "how does somebody update the location without a settings screen". The coordinates the forecast is for are drawn where a caption belongs, with a location pin beside them — and the caption is a button. It reads as information until you want it to be a control, which is exactly the right weight for something done once a year. A "Standort ändern" row in settings would be one more row to read past every time somebody goes looking for the voice; a long-press would be a gesture nobody discovers.

The freshness line beside it is the other tap target and the honest half of a cached card: „vor 4 Minuten" most of the time, the error in red when the last fetch failed, and both together when there is both. Tapping it refreshes, rather than making somebody wait out the ten-minute tick.

Everything the card draws in German comes from `WeatherSpeech`, resolved for the screen, and the numbers come from the same `Double.degrees()` the voice rounds with — a card reading 17° beside an answer saying „18 Grad" would be two thermometers in one room.

---

## 9. Config

| Key | Type | Default | Meaning |
|---|---|---|---|
| `weather.refresh_minutes` | Int | 10 | How often the forecast is re-fetched. Zero or negative falls back to the default rather than turning the panel into a scraper. |

`weather.latitude` and `weather.longitude` are **not** config: they are where the saved location is persisted, exactly as `clock.pending_timers` holds the timer deadlines and `memo.entries` holds the memos. They are written by `WeatherWatch.locate` and by nothing else, and a stored latitude with no longitude — or one outside its range — is read as *no location*, which puts the Setup button back and is the one recovery the user can perform without being told anything.

The cached forecast is deliberately **not** persisted. It is a ten-minute-old fact about the sky, worth caching and worth nothing after a restart; the first tick fetches it again before anybody has finished reading the clock. Persisting it would mean a panel that comes up showing yesterday evening's temperature as though it were now, which is the one way a weather card can actively mislead.

The settings screen does not expose the refresh interval yet (§12).

---

## 10. Failure & degradation

`Degraded` for both of its failures, never `Unavailable`, and that is a distinction worth keeping:

| State | Reason shown in settings |
|---|---|
| No location saved | „Kein Standort eingerichtet" |
| Last fetch failed | „Keine Verbindung" / „Wetterdienst überlastet" / „Wetterdienst antwortet nicht" |

A panel with no location is one button press from working and one that cannot reach Open-Meteo will be fine again when the router is. `Unavailable` means a dependency that is not coming back — that is what it means on the Spotify Sock, and it must keep meaning it.

Three failures it handles rather than propagates:

- **A day the forecast does not reach.** `Spoken`, not `Failed`: "so weit reicht meine Vorhersage nicht" is an answer.
- **A missing field in an otherwise healthy response.** `precipitation_probability` is documented as null outside its range; a null becomes "no probability" and the sentence leaves the clause out. A missing `current` block is the one thing that is fatal, because the half that is missing is the one the commonest question asks about.
- **Missing sunrise/sunset.** `isDaylight` returns true, so a sunny afternoon is never turned into "die Sonne ist schon untergegangen". The two failures are not symmetric: being told there is no sun when there is costs somebody a look out of the window, the other way round costs them the washing.

---

## 11. Testing

Per the project's standing instruction, this Sock ships without unit tests. What would be covered if that changes, in the order it matters:

- The utterance tables (§4, §5) as template assertions — already asserted on every build by the registry's example gate, which is how the infix word order ("wird es morgen regnen") was caught missing.
- The collision gate against Clock and Help, which is what `:socks:weather`'s test dependencies are for: `heute`, `morgen` and `übermorgen` are the Clock's calendar ground with a different noun in front of them.
- **"The rest of today", on a `MutableClock`.** The same forecast read at 09:00, 14:00 and 23:00 gives three different answers to "wie warm wird es heute noch", and the 14:00 one is the reason the hourly block is fetched. This is the test worth writing first.
- The sun-after-dark branch, which the live run could not reach and a fixed clock can.
- Staleness: a report at 19 minutes answers plainly, at 21 minutes it refetches, and a failed refetch answers with the caveat.
- `OpenMeteo` against a recorded response body, including the nulls: an hour with no probability, a day with no wind, a response with two days instead of three.

What *was* verified before shipping, against the live API: every sentence in §4 and §5 for a real Vienna forecast, both languages, plus the two after-dark branches on a fixed clock.

---

## 12. Open questions / out of scope (v1)

- **More than three days.** Open-Meteo would happily forecast a fortnight. `WeatherDay` stops at the day after tomorrow because that is where the *words* stop: "am Freitag" needs the Clock's `Weekday` enum, a rule for which Friday is meant, and a card that can show seven columns. It is the obvious next milestone and it is not this one.
- **A place that is not the panel's own.** "Wie ist das Wetter in Salzburg" needs a geocoder, a name resolver with the Radio Sock's fuzzy-matching problems, and an answer that says which place it is about. Nothing in the idea file asks for it.
- **Severe-weather warnings.** A thunderstorm code answers a question when asked; nothing speaks first. An unprompted announcement needs a rule for *when* that nothing in the system has an opinion about yet — the same open question `memo.specs.md` §12 leaves for proactive reminding, and it belongs in the same milestone.
- **The settings screen** does not expose `weather.refresh_minutes`. It is a default that has never needed changing on this panel; the key exists so that when it does, it is a settings row and not a release.
- **Humidity, UV, air pressure, pollen.** All one query parameter away and none of them a question anybody has asked out loud in this kitchen.
- **History.** "Wie war das Wetter gestern" is a different Open-Meteo endpoint (the archive API) and a different command; today it is a Tier 2 negative (§7).
