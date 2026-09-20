package io.dobby.socks.radio

/**
 * The station table, verified on 2026-09-20 (`radio-plan.md` §B2).
 *
 * The URLs came from [radio-browser.info](https://www.radio-browser.info/) and were then
 * probed directly: a range request for the first 2 KB checking the status and the content
 * type, and a second request with `Icy-MetaData: 1` checking that a real `StreamTitle`
 * arrives. `scripts/verify-streams` is that check, automated, and it parses this file rather
 * than holding a copy of it.
 *
 * Three decisions are baked into the rows and are worth not re-deriving:
 *
 * - **ORF is one uniform pattern**, `orf-live.ors-shoutcast.at/{code}-q{1,2}a`, not four
 *   scraped hostnames. The directory lists `oe3-q1a` on a different host and `oe1`/`wie` over
 *   plain `http`; all eight combinations answer `206 audio/mpeg` over HTTPS on the uniform
 *   host, and the odd one out in the directory is what an ORS rotation looks like caught
 *   mid-rotation.
 * - **q2a primary (192 kbps), q1a fallback (128).** The fallback is the reconnect ladder's
 *   last rung, not a quality setting.
 * - **Every station is HTTPS, including Kronehit**, which the directory lists only over
 *   cleartext. The alternative was a `network_security_config.xml` exception for one station
 *   — a permanent widening of the app's network posture. If a future station is genuinely
 *   HTTP-only, the decision to take is "leave it out".
 *
 * A `List` rather than a `Map` because the settings dropdown enumerates it and wants a stable
 * order; [byId] is the lookup.
 */
object Stations {

    val ALL: List<Station> = listOf(
        Station(
            id = "fm4",
            displayName = "FM4",
            aliases = listOf("fm vier", "fm 4", "f m 4", "ef em vier", "efemvier"),
            streamUrl = "https://orf-live.ors-shoutcast.at/fm4-q2a",
            fallbackUrl = "https://orf-live.ors-shoutcast.at/fm4-q1a",
            isDefault = true,
            source = Station.Source(
                browserUuid = "1e13ed4e-daa9-4728-8550-e08d89c1c8e7",
                homepage = "https://fm4.orf.at/",
                codec = "MP3",
                bitrateKbps = 192,
                verifiedOn = VERIFIED_ON,
            ),
        ),
        Station(
            id = "oe3",
            displayName = "Ö3",
            aliases = listOf("ö drei", "oe drei", "o3", "hitradio", "hitradio ö3", "hitradio oe3"),
            streamUrl = "https://orf-live.ors-shoutcast.at/oe3-q2a",
            fallbackUrl = "https://orf-live.ors-shoutcast.at/oe3-q1a",
            source = Station.Source(
                browserUuid = "e723f7f8-0db1-4bc5-a64c-64d8377f9f9a",
                homepage = "https://oe3.orf.at/",
                codec = "MP3",
                bitrateKbps = 192,
                verifiedOn = VERIFIED_ON,
            ),
        ),
        Station(
            id = "oe1",
            displayName = "Ö1",
            aliases = listOf("ö eins", "oe eins", "o1", "österreich eins"),
            streamUrl = "https://orf-live.ors-shoutcast.at/oe1-q2a",
            fallbackUrl = "https://orf-live.ors-shoutcast.at/oe1-q1a",
            source = Station.Source(
                browserUuid = "657b7b0f-2ef9-45df-9915-4ca4f948c54f",
                homepage = "https://oe1.orf.at/",
                codec = "MP3",
                bitrateKbps = 192,
                verifiedOn = VERIFIED_ON,
            ),
        ),
        Station(
            id = "wien",
            displayName = "Radio Wien",
            aliases = listOf("radio wien", "wien", "orf wien"),
            streamUrl = "https://orf-live.ors-shoutcast.at/wie-q2a",
            fallbackUrl = "https://orf-live.ors-shoutcast.at/wie-q1a",
            source = Station.Source(
                browserUuid = "154fc4d3-4584-4b59-b2ed-e8656e0420e8",
                homepage = "https://wien.orf.at/",
                codec = "MP3",
                bitrateKbps = 192,
                verifiedOn = VERIFIED_ON,
            ),
        ),
        Station(
            id = "kronehit",
            displayName = "Kronehit",
            // "krone" alone is safe in a way a bare template would not be: an alias is only
            // ever matched against the *content of the `{station}` slot*, which by definition
            // sits inside an utterance that already said "radio" or "sender". The README's
            // single-keyword rule is about templates that match a whole utterance.
            aliases = listOf("krone hit", "kronen hit", "krone", "kronehit 105 8"),
            streamUrl = "https://secureonair.krone.at/kronehit1058.mp3",
            fallbackUrl = "https://secureonair.krone.at/kronehit.mp3",
            source = Station.Source(
                browserUuid = "96061367-0601-11e8-ae97-52543be04c81",
                homepage = "https://www.kronehit.at/",
                codec = "MP3",
                bitrateKbps = 128,
                verifiedOn = VERIFIED_ON,
            ),
        ),
    )

    val byId: Map<String, Station> = ALL.associateBy { it.id }

    /** The table's own default, used when `radio.default_station` names nothing that exists. */
    val default: Station = ALL.first { it.isDefault }

    /** The date every URL above was last probed. Updated from `scripts/verify-streams` output. */
    private const val VERIFIED_ON = "2026-09-20"
}
