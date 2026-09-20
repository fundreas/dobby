# Radio — the Sock plan (M4)

## Context

[`socks.specs/radio.specs.md`](socks.specs/radio.specs.md) describes the Radio Sock in full. It
ends with an open item: *"Verifying and pinning the stream URLs is an **implementation task for
M4**, tracked here so it is not forgotten."* This plan closes that item and everything around
it.

`dobby-plan.md` §8 names M4 as **Radio + Departures**, and names the hard part:

> `PlaybackCoordinator` (Radio vs. Spotify arbitration), ExoPlayer […]
> *Done when:* radio and Spotify never overlap […] and "Stopp" resolves correctly in all six
> active/idle combinations of Spotify, Radio and Clock.

"Radio and Spotify never overlap" is not free with the code that exists today. §A below is about
a hole in `PlaybackCoordinator` that nothing has been able to fall into yet, because Radio is the
first Sock that plays audio **in this process**. Everything after it is ordinary Sock work.

Three things were already built in anticipation of this Sock and must be used rather than
re-invented:

| Already there | Where | For |
|---|---|---|
| `InProcessPlayers` / `DuckablePlayer` | `core/audio/TurnAudio.kt` | turn ducking (§G) |
| `AndroidTurnAudio.players` | `android/app` | the same, wired into the turn | 
| `SpecFixtures.radio()` | `core/src/test/.../registry/` | Radio's templates already sit in the cross-Sock collision matrix |

`AndroidTurnAudio`'s KDoc says *"Radio (M4) is the first and so far only implementation"* of
`DuckablePlayer`. This is that milestone.

**Standing constraint:** this is a playground project and does not want new test files. The
verification in §K is written as things to run and watch, not as suites to commit. Where a
behaviour is genuinely only observable from a test, it is called out as such and left to the
reader's judgement.

---

## A. `PlaybackCoordinator` — the hole M4 has to close first

### A1. What is wrong today

`AndroidPlayback` (`android/app/AndroidSockContext.kt`) is honest bookkeeping and nothing more.
Trace two scenarios through it with an ExoPlayer in the picture:

**Radio is playing, the user says "spiele Bohemian Rhapsody".**

1. `SpotifySock.claimAnd { … }` calls `playback.claimExternal("spotify")`.
2. `claimExternal` abandons Dobby's standing `AudioFocusRequest` — the one Radio took — and sets
   `holder = "spotify"`.
3. The Spotify app requests `AUDIOFOCUS_GAIN` in its own process. The OS has nothing of Dobby's
   left to revoke, and Dobby would not hear it if it did: the request built in
   `AndroidPlayback.grant()` carries **no `OnAudioFocusChangeListener`**.
4. Dobby's ExoPlayer keeps streaming FM4 over the top of the song.

**Spotify is playing, the user says "radio fm4".**

1. `RadioSock` calls `playback.requestFocus("radio")` → `AUDIOFOCUS_GAIN`.
2. The OS sends `AUDIOFOCUS_LOSS` to the Spotify app, which pauses itself. This direction works.

So the arbitration is one-way. It has never mattered, because until now every Sock that made a
sound either made it in another process (Spotify) or made it briefly (the Clock's chime, on
transient focus). Radio is the first Sock holding sustained audio **inside** Dobby's process,
where nothing is listening on its behalf.

Per `socks.specs/README.md` §2: *"If a Sock needs something not on `SockContext`, that is a core
change and a plan change — not a local workaround."* This is that core change, and it is the
first commit of M4.

### A2. The fix: focus loss is a callback, not a poll

Add an optional loss callback to both claim methods. Optional, so Clock and Spotify compile
untouched — and neither of them wants one. Spotify's audio is in another process that handles
its own focus loss; the Clock's chime is transient and over in two seconds.

```kotlin
// core/sock/SockContext.kt

/** Why a Sock lost the channel. Both mean "stop"; only the log line differs. */
enum class FocusLoss {
    /** Another Sock claimed the channel. Radio evicted by Spotify, or the reverse. */
    EVICTED,

    /** Another app on the device took AUDIOFOCUS_GAIN. A phone call, a video, a nav prompt. */
    SYSTEM,
}

interface PlaybackCoordinator {
    val holder: String?

    /**
     * Claims the channel for a Sock that plays audio **in this process**.
     *
     * [onLost] is invoked at most once per grant, on the coordinator's scope, when the channel
     * is taken away — by another Sock or by another app. Null for a Sock with nothing to stop.
     */
    suspend fun requestFocus(sockId: String, onLost: (suspend (FocusLoss) -> Unit)? = null): Boolean

    suspend fun requestTransientFocus(sockId: String): Boolean

    suspend fun claimExternal(sockId: String, onLost: (suspend (FocusLoss) -> Unit)? = null): Boolean

    suspend fun releaseFocus(sockId: String)
}
```

`AndroidPlayback` then does three new things:

1. **Keeps the current holder's callback** alongside `holder`. One slot, because the coordinator
   holds exactly one claim.
2. **Evicts before granting.** Any `requestFocus` / `claimExternal` from a *different* sock id
   invokes the standing callback with `EVICTED` before the new claim is recorded. From the *same*
   sock id it does not — "radio ö3" while FM4 plays is a re-tune, not an eviction, and a
   callback there would stop the player the Sock is about to hand a new URL.
3. **Registers an `OnAudioFocusChangeListener`** on the `AudioFocusRequest` it builds, and calls
   the standing callback with `SYSTEM` on `AUDIOFOCUS_LOSS`.

```kotlin
private fun grant(sockId: String, durationHint: Int): Boolean {
    evict(sockId)                       // ← new: tell the previous holder, if any
    request?.let { audio.abandonAudioFocusRequest(it) }
    external = false
    val next = AudioFocusRequest.Builder(durationHint)
        .setAudioAttributes(/* unchanged */)
        .setOnAudioFocusChangeListener { change ->
            // Permanent loss only. A transient loss is either AndroidTurnAudio ducking us for
            // our own turn — which InProcessPlayers already handles, at a volume rather than a
            // stop — or somebody else's nav prompt, which is over before a reconnect would
            // finish. Stopping a stream for either is worse than riding it out.
            if (change == AudioManager.AUDIOFOCUS_LOSS) notifyLost(FocusLoss.SYSTEM)
        }
        .build()
    // …
}
```

> **Why permanent loss only, at length.** `AndroidTurnAudio` holds its *own* focus request, from
> the same UID, for the length of every turn — `GAIN_TRANSIENT_MAY_DUCK` under `TurnDuck.DUCK`
> and `GAIN_TRANSIENT` under `TurnDuck.PAUSE`. Whether the OS delivers a focus change to another
> listener in the same app is version-dependent and not worth relying on either way. Filtering to
> `AUDIOFOCUS_LOSS` makes the question moot: neither turn mode can produce one, so a turn can
> never look like an eviction, and the in-process duck stays the only thing that quietens Radio
> during a turn. This is the same reasoning `AndroidTurnAudio`'s KDoc already gives for not
> reusing `AndroidPlayback`'s request, applied in the other direction.

`releaseFocus(sockId)` clears the callback slot alongside the rest, and — new — only for the
matching sock id, which it already checks.

### A3. `FakePlaybackCoordinator`

`core/src/testFixtures/.../FakeSockContext.kt` gains the same two parameters plus a way to drive
the callback, so the eviction path is reachable without a device:

```kotlin
class FakePlaybackCoordinator : PlaybackCoordinator {
    var holder: String? = null; private set
    var duckedBy: String? = null; private set
    var grantFocus: Boolean = true
    val transientRequests: MutableList<String> = mutableListOf()

    private var onLost: (suspend (FocusLoss) -> Unit)? = null

    /** Drives the loss path by hand: `coordinator.loseFocus(FocusLoss.SYSTEM)`. */
    suspend fun loseFocus(reason: FocusLoss) { onLost?.invoke(reason); holder = null; onLost = null }
    // …
}
```

Nothing else in the fixture changes, and the default-null parameters mean no existing caller does
either.

### A4. What the Sock does with it

```kotlin
private suspend fun claimChannel(ctx: SockContext): Boolean =
    ctx.playback.requestFocus(id) { reason ->
        ctx.log.debug("radio: lost the channel ($reason), releasing the player")
        // Not stop_radio: this is not a user decision, so `lastStation` is kept and
        // `shared.resume` can still bring it back. What goes away is the player and the socket.
        release(remember = true)
    }
```

Losing the channel puts the Sock in `Idle(lastStation = …)`, which is exactly the state
`shared.resume` reads. Somebody who lost FM4 to an incoming call says "weiter" and gets it back.

---

## B. The station table

The user-facing half of this plan. `radio.specs.md` §3.4 ships a table with five stations and
*"verify"* in every URL cell, plus a warning that ORF rotates its endpoints.

### B1. Where the data came from

[radio-browser.info](https://www.radio-browser.info/) — a community-maintained, openly licensed
directory of internet radio streams with a free JSON API and no key.

**Server discovery.** There is no single stable hostname; the API is a pool behind a DNS
round-robin at `all.api.radio-browser.info`, and the documented way to find a member is to
resolve that name or ask any member for `/json/servers`. As of 2026-09-20 the pool answers with
one machine:

```console
$ dig +short all.api.radio-browser.info
91.98.4.78

$ curl -s https://all.api.radio-browser.info/json/servers
[{"ip":"91.98.4.78","name":"de1.api.radio-browser.info"},
 {"ip":"2a01:4f8:1c1d:699::1","name":"de1.api.radio-browser.info"}]
```

**Etiquette that the script in §B4 follows.** The API asks every client to send a descriptive
`User-Agent` and not to hammer it. Bare per-name searches got rate-limited into empty 200s
within a handful of requests while this table was assembled; one bulk
`/json/stations/bycountrycodeexact/AT` fetch (341 Austrian stations, ~375 KB) followed by local
filtering does the whole job in a single request and is what the script does.

**It is an authoring-time source, not a runtime dependency.** `radio.specs.md` §12 already put
*"radio-browser.info directory lookup"* out of scope with the note that *"the hardcoded table is
deliberate"*, and that decision stands: a wall panel that has to reach a directory before it can
play FM4 has traded a constant for a second thing that can be down. radio-browser is where the
URLs come from and where the script goes to check them; the APK contains a `Map`.

### B2. The verified table

Every URL below was fetched on **2026-09-20**: a range request for the first 2 KB, checking the
status, the content type, and — separately, with `Icy-MetaData: 1` and no range — that a real
`StreamTitle` arrives.

| id | Display | `streamUrl` | `fallbackUrl` | Codec |
|---|---|---|---|---|
| `fm4` | FM4 | `https://orf-live.ors-shoutcast.at/fm4-q2a` | `https://orf-live.ors-shoutcast.at/fm4-q1a` | MP3 192 / 128 |
| `oe3` | Ö3 | `https://orf-live.ors-shoutcast.at/oe3-q2a` | `https://orf-live.ors-shoutcast.at/oe3-q1a` | MP3 192 / 128 |
| `oe1` | Ö1 | `https://orf-live.ors-shoutcast.at/oe1-q2a` | `https://orf-live.ors-shoutcast.at/oe1-q1a` | MP3 192 / 128 |
| `wien` | Radio Wien | `https://orf-live.ors-shoutcast.at/wie-q2a` | `https://orf-live.ors-shoutcast.at/wie-q1a` | MP3 192 / 128 |
| `kronehit` | Kronehit | `https://secureonair.krone.at/kronehit1058.mp3` | `https://secureonair.krone.at/kronehit.mp3` | MP3 128 |

Provenance, for the script and for whoever reads this in a year:

| id | radio-browser station UUID | Homepage |
|---|---|---|
| `fm4` | `1e13ed4e-daa9-4728-8550-e08d89c1c8e7` (q2a) · `206f93c5-5f3c-4ba1-82c2-19a42582fcc2` (q1a) | https://fm4.orf.at/ |
| `oe3` | `e723f7f8-0db1-4bc5-a64c-64d8377f9f9a` (q2a) · `63bb901a-e09a-478b-9c5e-b7f6a1f9efc4` (q1a) | https://oe3.orf.at/ |
| `oe1` | `657b7b0f-2ef9-45df-9915-4ca4f948c54f` (q2a) · `cbd9a2cb-7e37-4362-9eb3-269ed743fc62` (q1a) | https://oe1.orf.at/ |
| `wien` | `154fc4d3-4584-4b59-b2ed-e8656e0420e8` (q2a) · `8a3685e2-711e-4764-9080-6fa143c33014` (q1a) | https://wien.orf.at/ |
| `kronehit` | `96061367-0601-11e8-ae97-52543be04c81` | https://www.kronehit.at/ |

### B3. Four decisions inside that table

**1. ORF is one uniform pattern, not four scraped URLs.**

```
https://orf-live.ors-shoutcast.at/{code}-q{1,2}a       code ∈ {fm4, oe3, oe1, wie}
```

radio-browser lists `oe3-q1a` under a different host (`ors-sn03.ors-shoutcast.at`) and lists
`oe1`/`wie` over plain `http`. All eight combinations of the four codes and the two qualities
were probed directly against `orf-live.ors-shoutcast.at` over HTTPS and all eight returned
`206 audio/mpeg`. Writing the table against the uniform host is fewer distinct things to break,
and the one-off host in the directory is what an ORS rotation looks like caught mid-rotation —
precisely what the spec warned about.

**2. q2a primary, q1a fallback — the fallback is the reconnect ladder's last rung.**

`q2a` is 192 kbps, `q1a` is 128. On a mains-powered panel on house Wi-Fi the 192 kbps stream
costs about 86 MB/hour and sounds better. `fallbackUrl` is not a quality setting the user picks;
it is what §J's reconnect ladder tries after the primary URL has failed its retries, because the
commonest reason a specific ORS endpoint stops answering is that endpoint, not the network. This
is one field and no new config key.

**3. Every station is reachable over HTTPS — including Kronehit, which radio-browser says is not.**

The directory lists Kronehit only as `http://onair-ha1.krone.at/…` and `http://onair.krone.at/…`,
and both of those fail over TLS. Probing around the `Server: secureonair.krone.at` header the
plain-HTTP endpoint returns found the TLS host:

```console
$ curl -sI https://secureonair.krone.at/kronehit1058.mp3
HTTP/1.1 200 OK   Content-Type: audio/mpeg   icy-name: Kronehit 105.8   icy-br: 128
```

**This is worth the digging.** The alternative was an `android:usesCleartextTraffic` exception or
a `network_security_config.xml` domain allowlist for `krone.at` — a permanent widening of the
app's network posture, in the manifest, for one station. With this host the Radio Sock needs no
manifest changes at all beyond `INTERNET`. If a future station is genuinely HTTP-only, the
decision to take is "leave it out", not "open cleartext".

**4. No HLS.**

radio-browser also lists `…mdn.ors.at/out/u/{code}/q4a/manifest.m3u8` (AAC ~290k) for the ORF
stations. Skipped for v1, for two reasons and one of them is not about quality: HLS needs
`media3-exoplayer-hls` as an extra dependency, and an HLS live stream does **not** carry ICY
metadata, so the dashboard's now-playing line (§H) would go dark for exactly the stations that
supply the best of it. Progressive MP3 with ICY is the right shape for this product.

### B4. ICY metadata — verified, and not what the spec assumed

`radio.specs.md` §7 hedges: *"Stream metadata title (ICY `StreamMetadata`) if the stream provides
one — many do, most are unreliable."* Measured, **all five provide one**, and reliably:

| Station | `icy-metaint` | Live `StreamTitle` on 2026-09-20 | Shape |
|---|---|---|---|
| FM4 | 16000 | `FM4 Fivas Ponyhof \| fm4.orf.at` | **show**, with a URL suffix |
| Ö3 | 16000 | `Anna Naklab feat. Alle Farben & Younotus - Supergirl` | track |
| Ö1 | 16000 | `Jetzt in Ö1: Im Zeit-Raum: Judith Mangelsdorf` | **show**, with a prefix |
| Radio Wien | 16000 | `Desireless - Voyage Voyage` | track |
| Kronehit | 1024 | `bebe rexha & faithless - new religion` | track |

So the hedge was wrong in an interesting way: the metadata is dependable, but it is not uniformly
a *track*. Two of the five broadcast the programme name instead, dressed with boilerplate. §H
says what to do about it. The spec line should be corrected — see §L.

### B5. The data model

```kotlin
// socks/radio/src/main/kotlin/io/dobby/socks/radio/Station.kt

/**
 * One station in the constant table.
 *
 * [aliases] are written the way a person says them, **not** pre-normalized. Both they and the
 * spoken parameter go through [StationKey.of], so the table stays readable and the two sides
 * cannot drift — see §C2, which is where this stopped being a style preference.
 */
data class Station(
    val id: String,
    val displayName: String,
    val aliases: List<String>,
    val streamUrl: String,
    /** Tried once after [streamUrl] exhausts its retries (§J). Null where there is no second. */
    val fallbackUrl: String? = null,
    val isDefault: Boolean = false,
    /** Where the URLs came from and when they were last checked. Read by nothing at runtime. */
    val source: Source,
) {
    /** Provenance, so `scripts/verify-streams` and a reader a year from now have a handle. */
    data class Source(
        val browserUuid: String,
        val homepage: String,
        val codec: String,
        val bitrateKbps: Int,
        val verifiedOn: String,
    )
}
```

And the table itself, `Stations.kt` — aliases follow §C:

```kotlin
object Stations {
    val ALL: List<Station> = listOf(
        Station(
            id = "fm4",
            displayName = "FM4",
            aliases = listOf("fm vier", "ef em vier", "efemvier", "f m 4", "fm 4"),
            streamUrl = "https://orf-live.ors-shoutcast.at/fm4-q2a",
            fallbackUrl = "https://orf-live.ors-shoutcast.at/fm4-q1a",
            isDefault = true,
            source = Station.Source(
                browserUuid = "1e13ed4e-daa9-4728-8550-e08d89c1c8e7",
                homepage = "https://fm4.orf.at/",
                codec = "MP3", bitrateKbps = 192, verifiedOn = "2026-09-20",
            ),
        ),
        // oe3: "ö drei", "oe drei", "o3", "hitradio", "hitradio ö3", "hitradio oe3"
        // oe1: "ö eins", "oe eins", "o1", "österreich eins"
        // wien: "radio wien", "wien", "orf wien"
        // kronehit: "krone hit", "krone", "kronen hit", "kronehit 105 8"
    )

    val byId: Map<String, Station> = ALL.associateBy { it.id }

    /** The table's own default, used when `radio.default_station` names nothing that exists. */
    val default: Station = ALL.first { it.isDefault }
}
```

> `"krone"` as a Kronehit alias is safe in a way a bare template would not be: an alias is only
> ever matched against the **content of the `{station}` slot**, which by definition sits inside
> an utterance that already said "radio" or "sender". `socks.specs/README.md` §6's single-keyword
> rule is about templates that match a whole utterance; it does not apply here.

---

## C. Station resolution

Spec §3.4 gives four steps: normalize, exact, Levenshtein ≤ 2, fail. Two things change.

### C1. The bug: the spec's aliases cannot match

`Normalizer.tokenize` (`core/nlu/Normalizer.kt`) ends with:

```kotlin
.map { token -> GermanNumbers.parse(token)?.toString() ?: token }
```

and `GermanNumbers` maps `drei → 3`, `vier → 4`, `eins → 1`. The Sock never sees the words. By the
time `{station}` reaches `handle()`:

| Spoken | Slot actually contains |
|---|---|
| radio ef em vier | `ef em 4` |
| radio ö drei | `ö 3` |
| spiel radio oe eins | `oe 1` |

The spec's alias list — `ef em vier`, `oe drei`, `ö drei`, `oe eins` — contains none of these and
would never fire. Every one of the letter-and-digit station names, which §3.4 correctly identifies
as *"what any acoustic model is worst at"*, would fall straight through to Levenshtein against
`fm4` / `oe3`, and `"ef em 4"` is nowhere near `"fm4"` at distance 2.

### C2. The fix: one pipeline, both sides

Do not hand-write normalized aliases into the table — they would be unreadable and they would rot
the first time the normalizer learns a word. Instead put the alias through **the same function**
the utterance went through:

```kotlin
// socks/radio/src/main/kotlin/io/dobby/socks/radio/StationKey.kt

/**
 * The comparison key for a station name, from either side of the match.
 *
 * Both a table alias and a spoken `{station}` slot go through here, which is the whole point:
 * "ef em vier" typed into the table and "ef em vier" said into the microphone must arrive at the
 * same string, and the microphone's copy has already been through `Normalizer` — where
 * `GermanNumbers` turned "vier" into "4" (§C1). Running the table through the same steps is the
 * only version of this that cannot drift.
 */
object StationKey {
    fun of(raw: String): String =
        Normalizer.normalize(raw)            // lowercase, punctuation out, number words → digits
            .replace("ö", "oe").replace("ä", "ae").replace("ü", "ue").replace("ß", "ss")
            .filter { !it.isWhitespace() && it != '-' }
}
```

`Normalizer.normalize` on a table alias is a no-op for everything except the number words, which
is exactly the part that matters. The keys that result:

| Written in the table | Key | Matches spoken |
|---|---|---|
| `fm vier` | `fm4` | "fm vier", "fm 4", "fm4" |
| `ef em vier` | `efem4` | "ef em vier", "ef em 4" |
| `ö drei` | `oe3` | "ö drei", "ö 3", "oe 3", "ö3" |
| `hitradio ö3` | `hitradiooe3` | "hitradio ö3", "hitradio ö drei" |
| `krone hit` | `kronehit` | "krone hit", "kronehit", "krone-hit" |

The id itself is a key too (`fm4` → `fm4`), so §3.4's "exact match against the station's `id` or
any `alias`" is one lookup in one map.

### C3. Three tiers, not two

```kotlin
class StationResolver(private val stations: List<Station> = Stations.ALL) {

    private val exact: Map<String, Station> = buildMap {
        for (station in stations) {
            put(StationKey.of(station.id), station)
            for (alias in station.aliases) put(StationKey.of(alias), station)
        }
    }

    /** Null means "the user named a station and it is not one of ours" — never the default. */
    fun resolve(spoken: String): Station? {
        val key = StationKey.of(spoken)
        if (key.isEmpty()) return null
        exact[key]?.let { return it }

        // Tier 2: edit distance, best match wins, ties lose. `<= 2` as the spec says, but
        // capped at a third of the key's length — otherwise "bayern 3" (8 chars) reaches
        // "wien" territory and a station we do not have becomes one we do.
        val budget = minOf(MAX_DISTANCE, key.length / 3)
        if (budget > 0) {
            val ranked = exact.entries
                .map { it.value to Levenshtein.distance(key, it.key) }
                .filter { it.second <= budget }
                .sortedBy { it.second }
            val best = ranked.firstOrNull()
            if (best != null && ranked.none { it.first != best.first && it.second == best.second }) {
                return best.first
            }
        }

        // Tier 3: Kölner Phonetik, the same tool M6 gave the keyword matcher. "krohnehit",
        // "kronnehit" and "cronehit" all code to the same skeleton as "kronehit" and none of
        // them is within the edit budget. Exact on the code, so it cannot widen the net.
        val coded = Phonetics.koelner(key)
        if (coded.isNotEmpty()) {
            val hits = exact.entries.filter { Phonetics.koelner(it.key) == coded }
                .map { it.value }.distinct()
            if (hits.size == 1) return hits.single()
        }
        return null
    }

    private companion object { const val MAX_DISTANCE = 2 }
}
```

Three notes on the tiers:

- **The length cap on tier 2 is load-bearing.** Spec §11 demands `"spiele radio bayern 3"` resolve
  to *nothing*, not to a default and not to a near-miss. `bayern3` → 7 chars → budget 2; without
  the cap a short key like `o1` (2 chars) would accept anything two edits away, which is most
  two-letter strings anybody might say.
- **A tie is a failure, not a coin flip.** Two stations at the same distance means the Sock does
  not know, and "Den Sender kenne ich nicht" is the honest answer.
- **Tier 3 is exact on the phonetic code**, so it only ever rescues a misspelling that codes
  identically. It cannot broaden tier 2.

`Phonetics.koelner` is already in `core/nlu/template/Phonetics.kt` and already does this job for
keywords; reusing it here is free.

### C4. The empty slot

`resolve("")` returns null, and `play_radio` treats an **empty** `station` param and an
**unresolvable** one as two different things — §3.4's *"Never silently fall back to the default
station when the user named one"*:

```kotlin
val spoken = invocation.textOrNull("station").orEmpty().trim()
val station = if (spoken.isEmpty()) configuredDefault() else resolver.resolve(spoken)
    ?: return SockResult.Failed(UNKNOWN_STATION)
```

---

## D. Module layout

### D1. Two files across two existing modules, not two new modules

The Clock precedent, not the Spotify one. `ClockHardware.kt` and `TimerAlarmReceiver` live
directly in `:android:app` while `:socks:clock` stays a plain JVM module behind the `TimerAlarm`
interface. `:android:spotify` exists as its own module for a reason that does not apply here —
the App Remote is a bare `.aar` from a GitHub release behind a bespoke local Maven repository.
Media3 is an ordinary Google Maven coordinate.

```
socks/radio/                                     ← new Gradle module, plain JVM
  build.gradle.kts
  src/main/kotlin/io/dobby/socks/radio/
    RadioSock.kt          commands, templates, handler, chain
    RadioPlayer.kt        the hardware seam + RadioPlayer.NONE
    RadioState.kt         Idle | Buffering | Playing | Error
    Station.kt            the data class
    Stations.kt           the constant table (§B5)
    StationKey.kt         normalization (§C2)
    StationResolver.kt    the three tiers (§C3)
    RadioConfig.kt        typed reads off SockConfigStore
    NowPlaying.kt         ICY StreamTitle cleanup (§H2)

android/app/src/main/kotlin/io/dobby/android/
  RadioHardware.kt        ExoRadioPlayer : RadioPlayer, DuckablePlayer  + the hardware holder
  ui/RadioCard.kt         the dashboard card
```

### D2. The seam

Exactly the shape of `TimerAlarm` and `SpotifyPlayer`: suspend functions, primitive results, a
`StateFlow` for the cached read that `activityFor` needs, and a `NONE` so the CLI and any
off-device path still get a complete palette.

```kotlin
// socks/radio/RadioPlayer.kt

/**
 * Everything the Sock does to a stream.
 *
 * `TimerAlarm` and `SpotifyPlayer` are the precedent: the Sock sees this, the device sees
 * Media3, and the station table, the resolver, the German copy and the whole command surface
 * stay reachable from a terminal with no device and no network.
 *
 * No Android type crosses this line, which is what keeps `:socks:radio` a plain JVM module.
 */
interface RadioPlayer {

    /**
     * What the player is doing, as it last reported it.
     *
     * A cached read, never a call into the player — `activityFor` reads exactly this and is a
     * pure state read under the contract (`socks.specs/README.md` §4).
     */
    val state: StateFlow<PlaybackState>

    /** The stream's own ICY `StreamTitle`, raw. Null until one arrives; many arrive late. */
    val streamTitle: StateFlow<String?>

    /**
     * Points the player at [url] and starts it. Returns when playback has actually begun.
     *
     * False means it did not start within [timeoutMs] — a 404, a refused connection, or a
     * buffer that never filled. The Sock's German copy is the same for all three (§3 of the
     * spec), so the distinction is not carried across this line.
     */
    suspend fun play(url: String, timeoutMs: Long): Boolean

    /** Stops and releases. Idempotent; safe before any [play]. */
    suspend fun release()

    companion object {
        /** Off-device: nothing plays, nothing fails, the palette is still complete. */
        val NONE: RadioPlayer = object : RadioPlayer { /* Stopped, null, false, Unit */ }
    }
}

enum class PlaybackState { STOPPED, BUFFERING, PLAYING, FAILED }
```

`play(url, timeoutMs)` returning a boolean *after* playback begins is the important shape: it
collapses the spec's 10-second buffering timeout into the call, so the Sock never has to poll a
state flow to find out whether the thing it asked for happened.

### D3. Dependencies

`gradle/libs.versions.toml` — Media3 1.11.1 is current on Google Maven as of 2026-09-20:

```toml
[versions]
media3 = "1.11.1"

[libraries]
# radio.specs.md §1. Progressive MP3 with ICY metadata; no HLS extension (radio-plan.md §B3)
# and no media3-session — a wall panel has no lock screen to put transport controls on.
androidx-media3-exoplayer = { module = "androidx.media3:media3-exoplayer", version.ref = "media3" }
```

`android/app/build.gradle.kts`:

```kotlin
implementation(project(":socks:radio"))
implementation(libs.androidx.media3.exoplayer)
```

`socks/radio/build.gradle.kts` — the same three lines every JVM Sock module has:

```kotlin
plugins { alias(libs.plugins.kotlin.jvm) }
dependencies {
    api(project(":core"))
    testImplementation(testFixtures(project(":core")))
    testImplementation(libs.kotlinx.coroutines.test)
}
```

`settings.gradle.kts` gains `include(":socks:radio")` next to the others.

**`media3-session` is dropped from the spec's dependency list.** §1 names it; nothing in the spec
uses it. A `MediaSession` publishes transport controls to the lock screen, Bluetooth buttons and
Android Auto — none of which a kitchen wall panel has, and all of which would give a second,
unarbitrated way to start audio that `PlaybackCoordinator` knows nothing about. See §L.

### D4. Manifest

Nothing. `INTERNET` is implied by `minSdk 33`'s platform defaults only in the sense that it is
normal — declare it explicitly beside the others for the same reason every other permission in
that file is declared explicitly:

```xml
<!-- radio.specs.md §1: internet radio streams. No cleartext exception — every station in the
     table is reachable over HTTPS, including Kronehit (radio-plan.md §B3). -->
<uses-permission android:name="android.permission.INTERNET" />
```

`WAKE_LOCK` is already declared (`dobby-plan.md` §7.1) and is what `setWakeMode` needs in §E3.

### D5. Wiring

`android/app/DobbySocks.kt` — the property `dobby-plan.md` §8 asks for is that adding a Sock is
one line. It is three here, and two of them are the same pattern `SpotifyHardware` already
established:

```kotlin
fun create(
    hardware: ClockHardware? = null,
    spotify: SpotifyHardware? = null,
    radio: RadioHardware? = null,          // ← new
): Wiring {
    // …
    val tuner = RadioSock(player = radio?.player ?: RadioPlayer.NONE)
    val socks = buildList {
        add(clock); add(music); add(tuner)
        add(CalculatorSock()); add(ConversationSock()); add(HelpSock { directory })
        addAll(DevSocks.create())
    }
    return Wiring(socks, clock, music, tuner) { directory = it }
}
```

`Wiring` gains `val radio: RadioSock` for the same reason it holds `clock` and `spotify` — the
panel draws its card.

`DobbyService` creates `RadioHardware(this, scope, turnAudio.players)` beside the other two,
passes it to both `DobbySocks.create(…)` call sites (there are two — the second is the registry
build at line ~172), and releases it in `onDestroy`. **The `InProcessPlayers` instance must be the
one `AndroidTurnAudio` was constructed with**, so the service has to build `turnAudio` before
`radioHardware` rather than inline at the call site where it sits today. That reordering is the
single easiest thing in M4 to get subtly wrong: a second `InProcessPlayers` compiles, runs, and
ducks nothing.

`cli/Main.kt` adds `RadioSock()` to the list beside `SpotifySock()`. With `RadioPlayer.NONE` the
terminal harness can exercise every template, the resolver and the whole chain — which is most of
this Sock — with no device and no network.

---

## E. Playback

### E1. `ExoRadioPlayer`

```kotlin
// android/app/RadioHardware.kt

/**
 * The device half of the Radio Sock, and the only place Media3 is named.
 *
 * Parallel to `ClockHardware` and `SpotifyHardware`: created by `DobbyService`, released in
 * `onDestroy`, invisible to the Sock — which sees `RadioPlayer` and nothing else.
 *
 * @param players the turn-duck registry. **Must be `AndroidTurnAudio`'s own instance** (§D5).
 */
class RadioHardware(context: Context, scope: CoroutineScope, players: InProcessPlayers) {
    private val exo = ExoRadioPlayer(context, scope, players)
    val player: RadioPlayer = exo
    fun release() { exo.releaseNow() }
}
```

### E2. The threading trap

ExoPlayer must be touched only from the `Looper` it was built on, and it throws otherwise. Two
call paths reach this player from somewhere else:

1. `RadioSock.handle()` runs on the dispatcher core dispatches on — a suspend call, fine to
   marshal with `withContext(Dispatchers.Main)`.
2. **`DuckablePlayer.duck(volume)` is not suspend and never will be.** `InProcessPlayers.duck` is
   a plain synchronous loop over registered players, called from `AndroidTurnAudio.duck()` at the
   start of every turn. It cannot suspend and it must not block.

So the duck path posts and returns:

```kotlin
private val handler = Handler(Looper.getMainLooper())

// The player is built on the main looper, so this is the one it wants.
private val exo = ExoPlayer.Builder(context)
    .setLooper(Looper.getMainLooper())
    .setLoadControl(liveLoadControl())
    .build()

override fun duck(volume: Float) { handler.post { exo.volume = volume } }
override fun restore()           { handler.post { exo.volume = 1f } }
```

Fire-and-forget is correct here and not a shortcut: a duck that lands a frame late is inaudible,
and `InProcessPlayers` already catches and reports a throwing player rather than letting one
break everybody else's duck.

### E3. Building the player

```kotlin
private fun liveLoadControl() = DefaultLoadControl.Builder()
    // A live stream has no seek and no lookahead worth the name; what it has is a person
    // waiting for sound after they said "radio fm4". Start on 1.5 s rather than the 2.5 s
    // default, and keep the steady-state buffer generous so a Wi-Fi hiccup is inaudible.
    .setBufferDurationsMs(15_000, 50_000, 1_500, 3_000)
    .build()

exo.setAudioAttributes(
    AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .build(),
    /* handleAudioFocus = */ false,          // ← see below
)
// Keeps the CPU and the Wi-Fi radio alive with the screen off. WAKE_LOCK is already declared.
exo.setWakeMode(C.WAKE_MODE_NETWORK)
```

> **`handleAudioFocus = false` is not optional, and it is the second-easiest thing in M4 to get
> wrong.** Passing `true` makes ExoPlayer request and manage audio focus on its own. It would then
> hold a *third* focus request from this UID, alongside `AndroidPlayback`'s and
> `AndroidTurnAudio`'s, and it would react to focus changes by ducking or pausing itself —
> fighting `InProcessPlayers` for the volume during every single turn, and second-guessing
> `PlaybackCoordinator` for the channel. Focus is core's business here (§A) and the volume is
> `InProcessPlayers`' (§G). The player's job is to decode.

### E4. `play(url, timeoutMs)`

```kotlin
override suspend fun play(url: String, timeoutMs: Long): Boolean = withContext(Dispatchers.Main) {
    _state.value = PlaybackState.BUFFERING
    _streamTitle.value = null                 // the old station's title must not linger
    exo.setMediaItem(MediaItem.fromUri(url))
    exo.prepare()
    exo.play()
    // Suspends until the listener reports READY-and-playing, an error, or the budget runs out.
    withTimeoutOrNull(timeoutMs) { awaitPlaying() } ?: run {
        exo.stop(); _state.value = PlaybackState.FAILED; false
    }
}
```

A `Player.Listener` drives `_state` and completes whatever `awaitPlaying()` is suspended on:

| Callback | Effect |
|---|---|
| `onIsPlayingChanged(true)` | `_state = PLAYING`, resume the waiter with `true` |
| `onPlaybackStateChanged(STATE_BUFFERING)` | `_state = BUFFERING` — used by the card, not by the waiter |
| `onPlayerError(e)` | `_state = FAILED`, resume the waiter with `false`, and feed §J's ladder |
| `onMetadata(metadata)` | pull any `IcyInfo` out and push `.title` into `_streamTitle` |

ICY works out of the box on a progressive MP3 stream: Media3 sends `Icy-MetaData: 1`, parses the
interleaved blocks and surfaces them as `androidx.media3.extractor.metadata.icy.IcyInfo` entries
through `onMetadata`. `IcyHeaders` arrives once at the start and carries `name` / `genre`, which
§H does not use — the table's `displayName` is better copy than `icy-name: digital`.

### E5. Lifecycle

The spec is emphatic (§3, behaviour 4): the player is created lazily and released on
`stop_radio`, on focus loss, and in `onStop()`. *"A live HTTP stream left open is both battery
and bandwidth."*

Lazily means **the `ExoPlayer` instance itself**, not just the stream — an idle ExoPlayer holds
buffers and a renderer. `ExoRadioPlayer` therefore constructs its `ExoPlayer` on the first
`play()` and destroys it in `release()`, and both are on the main looper. The registration with
`InProcessPlayers` follows that same lifetime exactly (§G).

Four things release the player, and the Sock must reach all four:

| Trigger | Keeps `lastStation`? | Result |
|---|---|---|
| `radio.stop_radio` | yes — it backs `shared.resume`'s `IDLE` | `Silent` |
| `shared.stop` while `ACTIVE` | yes | `Silent` |
| Focus loss (§A4) | yes | nothing spoken; the eviction is its own feedback |
| `onStop()` — service teardown | irrelevant, the process is going | — |

`lastStation` is session-scoped and never persisted (spec §5): *"after a service restart, 'weiter'
with no history must be `NotForMe`, not a surprise burst of FM4 at 3 a.m."* It lives in
`RadioState.Idle`, in memory, and dies with the service.

---

## F. Commands and the chain

The templates come from spec §3.2 and §4.1 and are already mirrored in
`core/src/test/.../registry/SpecFixtures.kt` as `SpecFixtures.radio()` — which means the
cross-Sock collision gate has been enforcing them since before this Sock existed, including the
Spotify collision the spec calls a regression test.

**When `RadioSock` lands, `SpecFixtures.radio()` does not get deleted.** `:core` cannot depend on
a Sock module, so the fixture is how Radio stays in the matrix — exactly as `SpecFixtures.clock()`
already does, with the same comment: *"If the two drift apart, this file is the one that is
wrong."* Copy the real templates into the fixture verbatim, character for character.

### F1. The two exclusive commands

```kotlin
override val commands: List<ExclusiveCommandSpec> = listOf(
    ExclusiveCommandSpec(
        id = PLAY_RADIO,
        params = listOf(ParamSpec("station", ParamType.Text, required = false, default = "")),
        templates = patterns(
            // Specific → generic. Every one of these contains `radio` or `sender`, which is
            // what puts them ahead of Spotify's greedy `(spiele|spiel) {query}( ab)?` under the
            // registry's "more literal keywords" ordering key (`dobby-plan.md` §297).
            "(spiele|spiel|mach|schalt|schalte) (den)? (sender|radiosender) {station}( an| ein)?",
            "(spiele|spiel|mach|schalt|schalte) (das|den)? radio {station}( an| ein)?",
            "(spiele|spiel|mach|schalt|schalte) (das|den)? radio (an|ein)?",
            "radio {station}",
            "radio (an|ein)?",
        ),
        description = "Spielt einen Radiosender ab. Ohne Sendername den Standardsender.",
        help = CommandHelp(
            title = "Radio hören",
            detail = "Spielt einen der eingebauten Sender als Internet-Stream. Ohne Sendernamen " +
                "läuft der Standardsender aus den Einstellungen.",
            hints = listOf(
                "Ich kenne FM4, Ö3, Ö1, Radio Wien und Kronehit.",
                "„Weiter“ holt den zuletzt gestoppten Sender zurück.",
            ),
            aliases = listOf("radio", "sender", "radio anmachen", "radiosender"),
        ),
        examples = listOf(
            Example("spiele radio fm4", mapOf("station" to "fm4")),
            Example("spiel radio ö3", mapOf("station" to "ö3")),
            Example("mach radio wien an", mapOf("station" to "wien")),
            Example("schalt den sender ö1 ein", mapOf("station" to "ö1")),
            Example("radio kronehit", mapOf("station" to "kronehit")),
            Example("radio an", mapOf("station" to "")),
            Example("radio", mapOf("station" to "")),
            Example("spiele radio", mapOf("station" to "")),
            Example("kannst du mir bitte fm4 anmachen", mapOf("station" to "fm4"),
                    matchedByTemplates = false),
            Example("ich will nachrichten hören", mapOf("station" to "ö1"), heldOut = true),
        ),
    ),
    ExclusiveCommandSpec(
        id = STOP_RADIO,
        templates = patterns(
            "radio (aus|stopp|stop|ausschalten|abschalten|beenden)",
            "(mach|schalt|schalte|stopp|stoppe) (das|den)? radio (aus|ab)",
            "(stopp|stoppe|beende) (das|den)? radio",
        ),
        description = "Beendet die Radiowiedergabe — explizit adressiert.",
        help = CommandHelp(
            title = "Radio ausschalten",
            detail = "Beendet den Stream. Das bloße „Stopp“ steht hier nicht — das entscheidet " +
                "sich im Moment des Sagens und gehört zu „Stopp“.",
            aliases = listOf("radio aus", "radio ausschalten"),
        ),
        examples = listOf(
            Example("radio aus"), Example("radio stopp"), Example("mach das radio aus"),
            Example("stopp das radio"), Example("radio ausschalten"),
            Example("das radio kannst du jetzt ausmachen", matchedByTemplates = false),
        ),
    ),
)
```

`CommandHelp` is optional on `CommandSpec` and defaults to null, but a Sock landing now should
carry it — it is what the panel's help screen and "erkläre das Kommando …" read, and the Help
Sock's coverage is only as good as the newest Sock. **The usage line is not written by hand**:
`io.dobby.core.nlu.template.Syntax` derives it from the templates, for the reason `CommandHelp`'s
KDoc gives — a hand-written copy of the grammar is a lie waiting for the first template edit.

Two notes on the ordering, both of which the collision gate already enforces:

- **`radio {station}` must come after the longer `radio`-prefixed forms** and before bare
  `radio (an|ein)?`. `{station}` is a trailing open slot — README §6: *"put such templates
  last"* — and "radio an" must bind `station=""`, not `station="an"`. The registry's specific →
  generic ordering does this by keyword count; the example table asserts the outcome.
- **The Spotify collision is the regression test the spec names.** `spiele radio fm4` must reach
  `radio.play_radio`, not `spotify.play_music(query="radio fm4")`. Both are in
  `SpecFixtures.all()`, so `PaletteCollisionTest` and `SpecPaletteTest` already cover it.

### F2. `activityFor` — a pure read of `RadioState`

```kotlin
override fun activityFor(invocation: CommandInvocation): SockActivity =
    when (invocation.commandId) {
        SharedCommands.STOP.id -> when (state.value) {
            is RadioState.Playing, is RadioState.Buffering -> SockActivity.ACTIVE
            else -> SockActivity.INACTIVE            // never IDLE: spec §5
        }
        SharedCommands.RESUME.id -> when (val current = state.value) {
            // IDLE iff a station was stopped in *this* session. `Idle(null)` — a cold service —
            // is INACTIVE, which is what keeps 3 a.m. quiet.
            is RadioState.Idle -> if (current.lastStation != null) SockActivity.IDLE
                                  else SockActivity.INACTIVE
            else -> SockActivity.INACTIVE
        }
        else -> SockActivity.INACTIVE
    }
```

No player call, no network, no config read — a field read off a `StateFlow`, which trivially
satisfies the under-5 ms contract. `Buffering` counts as `ACTIVE` because a person who says
"stopp" two seconds after "radio fm4" means the thing that is currently connecting.

`Error(station, reason)` is `INACTIVE` for `shared.stop` — there is nothing to stop — and
`INACTIVE` for `shared.resume` too, which is a judgement call worth writing down: a station that
just failed is not a good answer to "weiter", and the user can name it again in four words.

### F3. Subscriptions and what they consume

```kotlin
override val shared: List<SharedSubscription> = listOf(
    SharedSubscription(SharedCommands.STOP, priority = STOP_PRIORITY),      // 50
    SharedSubscription(SharedCommands.RESUME, priority = RESUME_PRIORITY),  // 40
)
```

| Command | Activity | Behaviour | Result |
|---|---|---|---|
| `shared.stop` | `ACTIVE` | release the player, release focus, keep `lastStation` | `Silent` |
| `shared.stop` | `INACTIVE` | — | `NotForMe`, no I/O |
| `shared.resume` | `IDLE` | re-tune `lastStation` | `Spoken("{displayName}.")` |
| `shared.resume` | `INACTIVE` | — | `NotForMe`, no I/O |

Spec §5 justifies both numbers, and both are already in `shared-commands.specs.md` §3.3 and §4.3
awaiting this Sock. The 50 on `shared.stop` is a tie with Spotify that `PlaybackCoordinator` is
supposed to make unreachable — and §A is what finally makes that guarantee true rather than
aspirational.

`shared.resume` **speaks**, unlike Spotify's silent resume. Spec §5: *"because a radio stream
takes a second to buffer and silence would read as a no-op."*

### F4. German copy, verbatim from the spec

```kotlin
const val UNKNOWN_STATION: String = "Den Sender kenne ich nicht."
const val UNREACHABLE: String     = "Der Sender ist gerade nicht erreichbar."
const val NO_NETWORK: String      = "Ich habe gerade keine Internetverbindung."
const val NO_FOCUS: String        = "Gerade nicht möglich."
const val STREAM_DROPPED: String  = "Der Radiostream ist abgerissen."   // via ctx.announce
```

Success is `Spoken("$displayName.")` — "FM4." — and nothing more. Spec §3: *"Deliberately terse;
the audio starting is the real feedback."*

---

## G. Turn ducking

Spec §6 and `TurnAudio.kt`'s KDoc already specify this completely and agree with each other. The
implementation is four lines and one rule.

```kotlin
// Registration is the player's lifecycle and nothing else's (spec §6).
private fun create(): ExoPlayer = ExoPlayer.Builder(context)./* … */.build()
    .also { players.register(this) }

private fun releaseNow() {
    players.unregister(this)
    exo?.release()
    exo = null
}
```

**The rule: registration is bracketed by player construction and player release, not by playback
state.** A Sock that forgets to unregister leaves a dead player holding a duck — `InProcessPlayers`
would keep calling `duck`/`restore` on a released ExoPlayer, which throws, which
`InProcessPlayers` catches and reports, and the symptom is a log line nobody reads and a duck
that silently does nothing.

Three properties that come free from `InProcessPlayers` and should not be re-implemented:

- **`TurnDuck.PAUSE` mutes rather than pauses.** `InProcessPlayers.MUTE_VOLUME = 0f`, and its
  KDoc already cites `radio.specs.md` for why: a re-buffer on every turn versus a few seconds of
  discarded stream.
- **A player registered mid-turn starts ducked.** `register()` checks the `ducked` flag. A stream
  that connects while somebody is still talking does not swell up underneath them.
- **The duck is invisible to `RadioState`.** Nothing in the duck path touches `_state` or
  `PlaybackCoordinator`. The Sock is still `Playing` at volume 0.2, `shared.stop` still resolves
  to `ACTIVE`, and the Sock's own focus is untouched. Routing a turn duck through the coordinator
  is the mistake spec §6 and `TurnAudio`'s KDoc both exist to prevent.

---

## H. State and the dashboard

### H1. `RadioState`

```kotlin
sealed interface RadioState {
    /** [lastStation] is what backs `shared.resume`'s IDLE. Null before anything ever played. */
    data class Idle(val lastStation: Station? = null) : RadioState
    data class Buffering(val station: Station) : RadioState
    data class Playing(val station: Station, val nowPlaying: String?) : RadioState
    data class Error(val station: Station, val reason: String) : RadioState
}
```

Exposed from the Sock as `val state: StateFlow<RadioState>`, the way `ClockSock` exposes its
`ClockState` and `SpotifySock` exposes `nowPlaying`.

### H2. `nowPlaying` — cleaning up what the streams actually send

§B4 measured what arrives. Two of the five broadcast the programme with boilerplate around it
rather than a track:

```
FM4         FM4 Fivas Ponyhof | fm4.orf.at        → "Fivas Ponyhof"
Ö1          Jetzt in Ö1: Im Zeit-Raum: Judith…    → "Im Zeit-Raum: Judith Mangelsdorf"
Ö3          Anna Naklab feat. … - Supergirl       → unchanged
Radio Wien  Desireless - Voyage Voyage            → unchanged
Kronehit    bebe rexha & faithless - new religion → unchanged
```

`NowPlaying.kt` is a handful of pure string rules, unit-testable with no stream in sight:

```kotlin
object NowPlaying {
    /** Null out rather than render: spec §7, "never show a placeholder". */
    fun clean(raw: String?, station: Station): String? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val stripped = text
            .substringBefore(" | ")                       // "… | fm4.orf.at"
            .removePrefix("Jetzt in ${station.displayName}: ")
            .removePrefix("${station.displayName} ")       // "FM4 Fivas Ponyhof"
            .trim()
        // What is left is sometimes the station's own name and nothing else, which the card
        // already shows in 40 point type.
        return stripped.takeIf { it.isNotEmpty() && !it.equals(station.displayName, true) }
    }
}
```

**Rendering it capitalised is deliberately not done.** Kronehit lower-cases everything
(`bebe rexha & faithless - new religion`) and a title-caser would produce "Bebe Rexha & Faithless"
correctly and "Ac/Dc" and "R.e.m." wrongly. Render it as it comes.

### H3. The card

`ui/RadioCard.kt`, drawn in `ChatScreen` beside `ClockCard` and `NowPlayingCard`, **shown only
while Radio holds playback focus** — i.e. `state !is RadioState.Idle`. Spec §7:

- `displayName`, large — the one thing that is always right.
- `nowPlaying`, one line, only when non-null. Never a placeholder.
- A buffering / reconnecting indicator, off `Buffering` and off §J's ladder.

`RadioCard` reads `RadioSock.state`, which `Wiring.radio` hands to the screen. Unlike Spotify
there is no artwork and so no second source to combine — everything the card draws is already in
the JVM module, which means `RadioCard` takes a `RadioState` and nothing else.

---

## I. Config

Spec §9, unchanged, read through a small typed wrapper the way `SpotifyConfig` wraps
`SockConfigStore`:

| Key | Type | Default | Where |
|---|---|---|---|
| `radio.default_station` | station id | `fm4` | Settings, a dropdown over `Stations.ALL` |
| `radio.buffer_timeout_s` | int | `10` | Settings (advanced) |
| `radio.reconnect_attempts` | int | `3` | Settings (advanced) |

```kotlin
class RadioConfig(private val store: SockConfigStore) {
    /** Falls back to the table's own default when the stored id names nothing that exists. */
    val defaultStation: Station
        get() = store.getString("radio.default_station")
            ?.let { Stations.byId[it] } ?: Stations.default
    val bufferTimeoutMs: Long get() = (store.getInt("radio.buffer_timeout_s") ?: 10) * 1000L
    val reconnectAttempts: Int get() = store.getInt("radio.reconnect_attempts") ?: 3
}
```

Read per invocation, not captured at `onStart` — the same reason `AndroidTurnAudio` reads its
mode through a lambda: a setting should take effect on the next command, not the next restart.

The settings dropdown is the one place the UI enumerates stations, and it is why `Stations.ALL` is
a `List` with a stable order rather than a `Map`.

---

## J. Failure and degradation

### J1. The reconnect ladder

Spec §10 specifies retries at 1 s / 3 s / 9 s. Extended by one rung using `fallbackUrl` (§B3):

```
play(streamUrl)                 fails →
  wait 1 s  → play(streamUrl)   fails →
  wait 3 s  → play(streamUrl)   fails →
  wait 9 s  → play(fallbackUrl) fails →
  release, state = Error, ctx.announce("Der Radiostream ist abgerissen.")
```

- `reconnect_attempts` counts the retries after the first failure, so the default 3 is the ladder
  above. Setting it to 0 means one attempt and no retry; the fallback URL is tried only as the
  last rung and so disappears with it, which is the right reading of "no retries".
- The ladder runs in `ctx.scope`, not inside `handle()` — README §2: *"Block. `handle()` runs
  under a timeout."* A mid-stream drop is asynchronous by nature and its user-facing outcome is a
  `ctx.announce`, which is exactly what that method is for.
- **A user-initiated stop cancels the ladder.** Spec §10: *"Never auto-restart after a
  user-initiated `stop_radio`."* `stop_radio`, `shared.stop` and a focus eviction all cancel the
  reconnect `Job` before releasing the player. A ladder that survives a "radio aus" and brings the
  stream back nine seconds later is the worst bug this Sock could have.

### J2. Status

`SockStatus.Ready`, always. Spec §10: *"there is no account or device prerequisite that can make
radio permanently unavailable; failures are per-invocation."* No credentials, no companion app,
no permission that can be revoked. `RadioSock` does not override `status` at all and inherits
`Sock`'s `ALWAYS_READY`.

### J3. Failure → copy

| Condition | Detected by | Result |
|---|---|---|
| Station named, unresolvable | `StationResolver.resolve` → null | `Failed(UNKNOWN_STATION)` |
| No network | `play` fails and connectivity is absent | `Failed(NO_NETWORK)` |
| 404 / refused / buffer timeout | `play(url, timeout)` → false | `Failed(UNREACHABLE)` |
| Focus denied | `requestFocus` → false | `Failed(NO_FOCUS)` |
| Mid-stream drop, retries exhausted | §J1 | `announce(STREAM_DROPPED)`, state `Error` |

> Distinguishing "no network" from "this stream is down" needs something the Sock cannot see — a
> `ConnectivityManager` is an Android type and `:socks:radio` is a JVM module. Rather than widen
> `SockContext` for one sentence, `ExoRadioPlayer` can map ExoPlayer's own
> `PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED` to a boolean the seam already
> carries. **Simpler v1: don't.** Let both say `UNREACHABLE` and add the distinction only if the
> generic sentence turns out to be confusing in use. Recorded here so the omission reads as a
> decision.

---

## K. Verification

### K1. `scripts/verify-streams` — the thing that keeps §B honest

ORF rotates endpoints; that is why the spec hedged every URL in the first place. A table verified
once is a table that will be wrong eventually and silently. The script is the answer, and running
it is a thing a person does before a release, not a test the build runs — a CI job that fails
because a broadcaster is having a bad afternoon teaches everyone to ignore it.

```
scripts/verify-streams          # python3, stdlib only, no dependencies
```

It does four things, in one radio-browser request plus one per station:

1. Resolves a radio-browser member via `/json/servers`, sending a real `User-Agent` (§B1).
2. Fetches `/json/stations/bycountrycodeexact/AT` **once** and looks every `browserUuid` from
   `Stations.kt` up in the result, reporting any whose `url_resolved` has drifted away from the
   table's `streamUrl`, and any whose `lastcheckok` is 0.
3. Opens each `streamUrl` and `fallbackUrl` with `Icy-MetaData: 1`, asserting a 2xx, an
   `audio/*` content type, and — for the primary — that an `icy-metaint` is present and a
   non-empty `StreamTitle` arrives within a few blocks.
4. Prints today's date next to every station that passed, so `Station.Source.verifiedOn` can be
   updated by hand from the output.

It parses `Stations.kt` for the URLs and UUIDs rather than holding its own copy. Two tables is
one table too many, and the failure mode of a drifted script copy is a green run against URLs the
app does not use.

### K2. On the JVM, with no device

`RadioPlayer.NONE` and the CLI harness cover most of this Sock. Worth walking through by hand in
`./gradlew :cli:run`:

- Every utterance in §F1's example tables resolves to the right command and params. The registry
  asserts these on every build anyway (README §7) — the example tables *are* the test.
- `spiele radio fm4` → `radio.play_radio`, **not** `spotify.play_music`. The single most
  important line in this Sock's surface.
- Station resolution, in a scratch `main` or a REPL: the alias table, `ö drei` → `oe3` through
  the number normalizer (§C1 — this is the one that was broken on paper), `bayern 3` → null,
  `krohnehit` → `kronehit` via phonetics, and a deliberate tie → null.
- `NowPlaying.clean` against the five real `StreamTitle` strings recorded in §B4.

### K3. On the device

The test device is a **vivo IV2201 (MediaTek MT6877)** — `arm64-v8a`, which is what
`android/app/build.gradle.kts` already filters to, though the comment beside `abiFilters` still
says OnePlus Nord CE and should be corrected while M4 is in there.

`dobby-plan.md` §8 defines M4 as done when *"radio and Spotify never overlap"* and *"'Stopp'
resolves correctly in all six active/idle combinations."* The rows below are the ones no fake can
reach, and the first two are what §A was written for:

| # | Do this | Expect |
|---|---|---|
| 1 | "radio fm4", then "spiele bohemian rhapsody" | the stream **stops**, the song plays. Before §A, both play at once. |
| 2 | Play something on Spotify, then "radio ö3" | Spotify pauses, the stream plays |
| 3 | Radio playing, incoming phone call | the stream stops; after the call "weiter" brings Ö3 back |
| 4 | Radio playing, say the wake word | the stream drops to ~20 % for the turn and is back at full volume the instant it ends |
| 5 | Same, with `TurnDuck.PAUSE` in settings | the stream goes silent, and comes back **without** a re-buffer |
| 6 | Radio playing → "stopp" | stream stops (Radio `ACTIVE`) |
| 7 | Timer ringing over the radio → "stopp" | the chime stops, **the radio keeps playing** (Clock's priority 100) |
| 8 | Nothing playing, radio cold → "stopp" | "Es läuft gerade nichts." |
| 9 | "radio aus", then "weiter" | FM4 comes back, spoken "FM4." |
| 10 | Force-stop the service, restart it, say "weiter" | "Es ist gerade nichts pausiert." — **not** a burst of FM4 |
| 11 | Radio playing, pull the Wi-Fi for ~15 s | reconnect indicator, then recovery; leave it off → "Der Radiostream ist abgerissen." |
| 12 | "radio aus" during a reconnect wait | it stays off. Nothing comes back nine seconds later. |
| 13 | Screen off, radio playing, 30 minutes | still playing (`WAKE_MODE_NETWORK`) |

Row 7 is the one `shared-commands.specs.md` §3.4 calls *"the row that justifies the whole
design"*, now with a real third subscriber instead of a documented intention.

---

## L. Spec deltas

Changes to fold back into [`socks.specs/radio.specs.md`](socks.specs/radio.specs.md) as part of
M4 — the spec is the contract, and a plan that silently diverges from it is how the two rot apart.

| § | Change | Why |
|---|---|---|
| §1 | Drop `media3-session` from Dependencies | Unused. A wall panel has no lock screen, and a `MediaSession` is a second unarbitrated way to start audio (§D3). |
| §1 | Add: every stream is HTTPS; no cleartext exception | §B3 — a real constraint on any station added later. |
| §3.4 | Rewrite the alias table in spoken form + note `StationKey.of` | The listed aliases could not match: the normalizer digitises number words before the Sock sees them (§C1). |
| §3.4 | Add a third tier: Kölner Phonetik after Levenshtein | §C3 — reuses `Phonetics` that M6 already built. |
| §3.4 | Add the `length / 3` cap on the edit budget | Without it §11's `bayern 3` negative case is not safe (§C3). |
| §3.4 | Add: a distance tie resolves to *unresolvable* | §C3 — an ambiguous station is a question the Sock cannot answer. |
| §3.4 | Replace the five *(verify)* cells with §B2's table + `fallbackUrl` + provenance | **This closes §12's open item.** |
| §7 | Correct "many do, most are unreliable" → all five carry ICY; two carry the *programme*, not the track, and need cleanup | §B4, measured. |
| §10 | Add `fallbackUrl` as the ladder's last rung | §J1. |
| §10 | Add: a user-initiated stop cancels a pending reconnect | §J1 — implied but not written, and the worst possible bug. |
| §12 | Move "verify and pin the stream URLs" from open to done, dated | Done here. |
| §12 | Add: HLS variants exist and are deliberately not used | §B3 — so the next reader does not re-derive it. |
| §3, §4 | Add the `CommandHelp` copy for both commands | `CommandSpec.help` landed after the spec was written; the help screen and "erkläre das Kommando …" read it (§F1). |

And outside the Sock's own spec:

| File | Change |
|---|---|
| `socks.specs/README.md` §8 | Radio ✅ |
| `socks.specs/shared-commands.specs.md` §2, §3.3, §4.3 | Radio ✅, and the note *"Radio arrives in M4; until then both chains are real but short"* comes out |
| `socks.specs/README.md` §1 / core docs | `PlaybackCoordinator` gains the `onLost` callback (§A2) |
| `dobby-plan.md` §8 | M4 half-done — Radio landed, Departures outstanding |
| `android/app/build.gradle.kts` | the `abiFilters` comment says Nord CE; the device is a vivo IV2201 / MT6877 (§K3) |

---

## M. Work order

Eight steps, each one a commit that builds and leaves the app working.

| # | Step | Touches | Done when |
|---|---|---|---|
| **R0** | `PlaybackCoordinator` focus loss (§A) | `core/sock/SockContext.kt`, `android/app/AndroidSockContext.kt`, `core/testFixtures/FakeSockContext.kt` | Default-null params mean nothing else changes; the app builds and behaves identically |
| **R1** | `:socks:radio` module: `Station`, `Stations`, `StationKey`, `StationResolver` (§B5, §C) | new module, `settings.gradle.kts`, `libs.versions.toml` | `ö drei` → `oe3`, `bayern 3` → null, from a scratch main |
| **R2** | `RadioPlayer` seam + `RadioState` + `RadioConfig` (§D2, §H1, §I) | `:socks:radio` | `RadioPlayer.NONE` compiles and does nothing convincingly |
| **R3** | `RadioSock`: commands, templates, handler, `RadioSock` in `cli/Main.kt` (§F1) | `:socks:radio`, `:cli` | Every §F1 example resolves in the terminal; `spiele radio fm4` beats Spotify |
| **R4** | Chain: `activityFor`, `shared.stop` / `shared.resume` (§F2, §F3); sync `SpecFixtures.radio()` | `:socks:radio`, `core/src/test/.../SpecFixtures.kt` | The chain behaves in the terminal; the collision gate is green |
| **R5** | `ExoRadioPlayer` + `RadioHardware` + `DobbySocks` / `DobbyService` wiring (§D5, §E) | `android/app`, `libs.versions.toml`, manifest | "radio fm4" plays on the device; **K3 rows 1, 2, 6, 13** |
| **R6** | Turn ducking: `DuckablePlayer`, registration lifecycle (§G) | `android/app/RadioHardware.kt` | **K3 rows 4, 5** |
| **R7** | Reconnect ladder + `ctx.announce` + focus-loss release (§J1, §A4) | `:socks:radio`, `android/app` | **K3 rows 3, 11, 12** |
| **R8** | `RadioCard`, `NowPlaying` cleanup, settings dropdown (§H2, §H3, §I) | `android/app/ui`, `:socks:radio` | The card draws the station and a clean title, and nothing while idle |
| **R9** | `scripts/verify-streams`, then the §L spec deltas | `scripts/`, `socks.specs/`, `dobby-plan.md` | The script passes against §B2; the spec no longer says *(verify)* |

**R0 first is not negotiable.** Everything from R5 on is audible, and without R0 the first audible
thing is two audio sources at once — which is the failure `dobby-plan.md` §8 names as M4's
definition of done.

R1–R4 are all off-device and all exercisable from `./gradlew :cli:run`. That is roughly two thirds
of this Sock, which is the payoff for the `RadioPlayer` seam and the reason to draw it before
writing a line of Media3.

---

## Out of scope

Carried over from spec §12, plus what this plan adds:

- **Station favourites, "nächster Sender" cycling.** Needs an ordering the user controls; the
  constant table has no opinion about what comes after FM4.
- **A user-editable station list.** v1 is a constant table plus a default-station setting. The
  moment a user can add a URL, this Sock owns an input-validation problem it does not have today.
- **radio-browser.info at runtime.** Reaffirmed: it is where the table came from (§B1) and what
  `scripts/verify-streams` checks against, and the APK ships a `Map`.
- **HLS / AAC variants.** No ICY metadata and an extra dependency (§B3).
- **`MediaSession` / lock-screen and Bluetooth transport controls** (§D3).
- **The ORF programme API** (`audioapi.orf.at/{station}/json/4.0/live`, which answers with the
  current show and its description) — richer than ICY for FM4 and Ö1, and a second network
  dependency on the dashboard's critical path for a line of text. If §H2's string cleanup turns
  out to be unsatisfying, this is where to look next.
- **Distinguishing "no network" from "this stream is down"** (§J3).
- **Podcasts, recording, a sleep timer for radio.** The Clock Sock's timer does not stop playback
  and is not being taught to.
