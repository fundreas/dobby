package io.dobby.socks.spotify

import io.dobby.core.sock.CommandHelp
import io.dobby.core.sock.CommandInvocation
import io.dobby.core.sock.Example
import io.dobby.core.sock.ExclusiveCommandSpec
import io.dobby.core.sock.FollowUp
import io.dobby.core.sock.ParamSpec
import io.dobby.core.sock.ParamType
import io.dobby.core.sock.SharedCommands
import io.dobby.core.sock.SharedSubscription
import io.dobby.core.sock.Sock
import io.dobby.core.sock.SockActivity
import io.dobby.core.sock.SockContext
import io.dobby.core.sock.SockResult
import io.dobby.core.sock.SockStatus
import io.dobby.core.sock.pattern
import io.dobby.core.sock.patterns
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Spotify — the Sock the whole product was described around.
 *
 * *"Spiele Blinding Lights von The Weeknd" works from a button press, and the Spotify Sock
 * contains every Spotify-specific line of code in the project* (`dobby-plan.md` §8, M1). The
 * second half of that sentence is what this file is shaped by: the Sock sees [SpotifyPlayer]
 * and [MusicSearch], never an SDK type and never an Android type, so the query parsing, the
 * selection heuristic, the German copy and the whole command surface are reachable from a
 * terminal with no device and no Spotify account.
 *
 * Two decisions the rest follows from (`spotify.specs.md` §1):
 *
 * 1. **Playback goes through the App Remote**, not `MEDIA_PLAY_FROM_SEARCH`. The intent takes
 *    free text and answers nothing — it starts an activity, so Spotify covers the panel, and it
 *    reports neither what it played nor whether it worked. Every other command here needs a
 *    live connection anyway, and [activityFor] needs a cached snapshot. The intent survives as
 *    a degraded fallback ([IntentFallback]) for the one command where a blind attempt still
 *    does what was asked.
 * 2. **Search goes through the Web API with an app token.** App Remote has no search at all, so
 *    a URI has to come from somewhere, and `/v1/search` is not user-scoped: one POST, no
 *    redirect, no refresh token, no settings screen.
 *
 * @param player the App Remote, or [SpotifyPlayer.NONE] off-device.
 * @param search the catalogue, or [MusicSearch.NONE] off-device.
 * @param credentials what the build was given. Empty is the normal state of a fresh clone.
 * @param fallback the intent of last resort. [IntentFallback.NONE] anywhere without an Android
 *   `Context` — which is also everywhere the intent would do nothing.
 */
class SpotifySock(
    private val player: SpotifyPlayer = SpotifyPlayer.NONE,
    private val search: MusicSearch = MusicSearch.NONE,
    private val credentials: SpotifyCredentials = SpotifyCredentials.NONE,
    private val fallback: IntentFallback = IntentFallback.NONE,
) : Sock {

    override val id: String = "spotify"

    override val displayName: String = "Spotify"

    /** Held from [onStart], the way the Clock Sock holds its own: a context is service-lifetime. */
    private var context: SockContext? = null

    private val _status = MutableStateFlow<SockStatus>(SockStatus.Ready)

    override val status: StateFlow<SockStatus> = _status.asStateFlow()

    /** What the wall panel draws (`spotify.specs.md` §7). The App Remote's cache, unchanged. */
    val nowPlaying: StateFlow<PlayerSnapshot?> get() = player.state

    /**
     * Candidate hits behind an open "which version" question, by follow-up token.
     *
     * Same shape and same reasoning as `ClockSock.asked`: there is no waiting instance and
     * nothing blocks, because returning [SockResult.Asked] is how a Sock waits. Concurrent by
     * type rather than by need — core dispatches one utterance at a time, and a map that is
     * wrong under a race would be a very quiet bug.
     */
    private val asked = ConcurrentHashMap<String, List<Hit>>()

    private val askCounter = AtomicLong()

    /** Set once a connect attempt has succeeded, so the next command reuses it (§3). */
    @Volatile
    private var connected = false

    override val commands: List<ExclusiveCommandSpec> = listOf(
        ExclusiveCommandSpec(
            id = PLAY_MUSIC,
            params = listOf(
                ParamSpec("query", ParamType.Text, required = false, default = ""),
                // Optional and **without a default**, so an invocation that says nothing about
                // the wording carries no `hint` key at all. The alternative — defaulting to
                // "unknown" — would put a param in every example table that no template ever
                // sets, which reads as though the wording had been inspected when it had not.
                ParamSpec("hint", ParamType.Enumeration(QueryHint.SPOKEN), required = false),
            ),
            templates = listOf(
                // "spiel mir" is gone from every branch below: "mir" is filler and the matcher
                // skips it (M6c). What is not gone is any optional in front of a `{query}`
                // slot — skipping happens before a keyword, never before a slot, and a slot
                // takes whatever token is sitting there.
                //
                // The wording that names a *kind* comes first because it is the most specific,
                // and because it is what makes "spiele die playlist entspannen" bind
                // `entspannen` rather than the whole phrase.
                pattern("(spiele|spiel) (den song|das lied|den titel|die playlist) {query}"),
                // The one template that fixes a param by its wording: the `von` has already
                // been eaten, so whatever is in the slot is an artist and nothing else. A
                // static param rather than a second round of German parsing in the handler
                // (`socks.specs/README.md` §6).
                pattern(
                    "(spiele|spiel) (etwas|was|irgendwas) von {query}",
                    "hint" to QueryHint.ARTIST_ONLY.name.lowercase(),
                ),
                pattern("(mach|leg|spiel) {query} (an|auf)"),
                pattern("(spiele|spiel) {query}( ab)?"),
                // Last, so it cannot swallow "spiele musik von ...".
                pattern("(musik|spotify) (an|einschalten)"),
            ),
            description = "Spielt Musik auf Spotify ab, optional nach Titel, Künstler oder Playlist.",
            help = CommandHelp(
                title = "Musik abspielen",
                detail = "Sucht auf Spotify und spielt ab. Du kannst einen Titel, einen Künstler " +
                    "oder eine Playlist nennen — oder gar nichts, dann läuft einfach Musik.",
                hints = listOf(
                    "„Spiele etwas von …“ sucht nur nach dem Künstler.",
                    "Sag die Art dazu — „den Song“, „die Playlist“ —, wenn ich sonst das Falsche finde.",
                ),
                aliases = listOf("musik", "musik abspielen", "musik anmachen", "spotify"),
            ),
            examples = listOf(
                Example(
                    "spiele blinding lights von the weeknd",
                    mapOf("query" to "blinding lights von the weeknd"),
                ),
                Example("spiel was von queen", mapOf("query" to "queen", "hint" to "artist_only")),
                Example("spiele den song bohemian rhapsody", mapOf("query" to "bohemian rhapsody")),
                Example("mach die playlist entspannen an", mapOf("query" to "die playlist entspannen")),
                Example("spiele die playlist entspannen", mapOf("query" to "entspannen")),
                // The phonetic tier, doing the job it exists for: neither word is spelled the
                // way it was said, and "schbiele" still reaches `spiele` (M6).
                Example("schbiele bleinding leitz", mapOf("query" to "bleinding leitz")),
                Example("musik an", mapOf("query" to "")),
                Example("spotify einschalten", mapOf("query" to "")),
                // Paraphrases Tier 1 is meant to miss — few-shots for the LLM tier (M6).
                Example(
                    "ich hätte gern etwas musik von den beatles",
                    mapOf("query" to "den beatles", "hint" to "artist_only"),
                    matchedByTemplates = false,
                ),
                Example(
                    "kannst du bitte bohemian rhapsody auflegen",
                    mapOf("query" to "bohemian rhapsody"),
                    matchedByTemplates = false,
                ),
                // Held out: never rendered into a prompt, measured against on the device.
                Example(
                    "hast du was von den rolling stones",
                    mapOf("query" to "den rolling stones", "hint" to "artist_only"),
                    heldOut = true,
                ),
                Example("ich will jetzt musik hören", mapOf("query" to ""), heldOut = true),
            ),
        ),
        ExclusiveCommandSpec(
            id = PAUSE,
            templates = patterns(
                // Every template names the target. Bare "stopp", "pause" and "aus" are not here
                // — they are `shared.stop`, decided per invocation by the chain.
                "(musik|spotify|wiedergabe) (aus|stoppen|anhalten|pausieren)",
                "(stopp|stoppe|stop|pausiere|pausier|halt) (die musik|die wiedergabe|spotify)",
                "(mach|schalt|schalte) (die musik|spotify) aus",
            ),
            description = "Pausiert Spotify — explizit adressiert.",
            help = CommandHelp(
                title = "Musik pausieren",
                detail = "Pausiert Spotify. Nenne dabei die Musik — ein bloßes „Stopp“ ist der " +
                    "geteilte Befehl und gilt dem, was gerade läuft.",
                aliases = listOf("musik pausieren", "musik aus", "pause"),
            ),
            examples = listOf(
                Example("musik aus"),
                Example("stopp die musik"),
                Example("pausiere spotify"),
                Example("mach die musik aus"),
                Example("spotify anhalten"),
                Example("kannst du die musik kurz ausmachen", matchedByTemplates = false),
                Example("ich will die musik nicht mehr hören", heldOut = true),
            ),
        ),
        ExclusiveCommandSpec(
            id = RESUME,
            templates = patterns(
                "(die musik|die wiedergabe|spotify) (weiter|fortsetzen|weiterspielen)",
                "(spiel|spiele|mach) (die musik|spotify) weiter",
            ),
            description = "Setzt Spotify fort — explizit adressiert.",
            help = CommandHelp(
                title = "Musik fortsetzen",
                detail = "Spielt weiter, was pausiert wurde. Auch hier gilt: „Weiter“ allein ist " +
                    "der geteilte Befehl.",
                aliases = listOf("musik fortsetzen", "musik weiter", "weiterspielen"),
            ),
            examples = listOf(
                Example("spiel die musik weiter"),
                Example("spotify fortsetzen"),
                Example("die wiedergabe weiterspielen"),
                Example("lass die musik wieder laufen", matchedByTemplates = false),
                Example("kannst du das lied weiterlaufen lassen", heldOut = true),
            ),
        ),
        ExclusiveCommandSpec(
            id = SKIP_NEXT,
            templates = patterns(
                "(nächster|nächstes|nächste) (song|lied|titel|stück|track)",
                "(überspringen|skip|skippen|weiter zum nächsten)",
                "(spiel|spiele) (den|das) (nächste|nächsten|nächstes) (song|lied|titel)",
            ),
            description = "Springt zum nächsten Titel.",
            help = CommandHelp(
                title = "Nächster Titel",
                detail = "Überspringt den laufenden Titel.",
                aliases = listOf("nächster titel", "nächster song", "überspringen", "skip"),
            ),
            examples = listOf(
                Example("nächster song"),
                Example("nächstes lied"),
                Example("überspringen"),
                Example("weiter zum nächsten"),
                Example("kannst du das lied wechseln", matchedByTemplates = false),
                Example("das lied gefällt mir nicht", heldOut = true),
            ),
        ),
        ExclusiveCommandSpec(
            id = SKIP_PREVIOUS,
            templates = patterns(
                "(vorheriger|vorheriges|vorherige|letzter|letztes|letzte) (song|lied|titel|stück|track)",
                // "(ein|einen)?" survives although both are near-filler: what follows is a
                // keyword, so the skipping pass covers the same sentences — and spelling them
                // out keeps the commonest phrasing on the strict first pass, which is where
                // the common phrasings belong (`socks.specs/README.md` §6).
                "(ein|einen)? (song|lied|titel) zurück",
                "zurück zum (vorherigen|letzten) (song|lied|titel)",
                "(spiel|spiele) (den|das) (vorherige|vorherigen|letzte|letzten) (song|lied|titel)",
            ),
            description = "Springt zum vorherigen Titel.",
            help = CommandHelp(
                title = "Vorheriger Titel",
                detail = "Geht einen Titel zurück.",
                aliases = listOf("vorheriger titel", "letzter song", "ein lied zurück"),
            ),
            examples = listOf(
                Example("vorheriger song"),
                Example("letztes lied"),
                Example("ein lied zurück"),
                Example("zurück zum letzten titel"),
                Example("spiel den letzten song"),
                Example("das lied davor war besser", matchedByTemplates = false),
                Example("geh mal einen titel zurück", heldOut = true),
            ),
        ),
        ExclusiveCommandSpec(
            id = RESTART_SONG,
            templates = patterns(
                "(nochmal|noch mal|neu) von (vorne|vorn|anfang)",
                "(von|zum) (vorne|anfang)",
                "(spiel|spiele|mach) (das lied|den song|das stück) (nochmal|noch mal|neu)",
                "(das lied|den song|den titel) (nochmal|neu) starten",
            ),
            description = "Spielt den laufenden Titel von vorne.",
            help = CommandHelp(
                title = "Titel von vorne",
                detail = "Startet den laufenden Titel noch einmal von Anfang an.",
                aliases = listOf("von vorne", "titel neu starten", "nochmal von vorne"),
            ),
            examples = listOf(
                Example("nochmal von vorne"),
                Example("von vorne"),
                Example("spiel das lied nochmal"),
                Example("den song neu starten"),
                Example("ich will den song noch mal von anfang an hören", matchedByTemplates = false),
                Example("ich hab den anfang verpasst", heldOut = true),
            ),
        ),
    )

    /**
     * Priority 50 on both chains (`shared-commands.specs.md` §4).
     *
     * Behind the Clock's 100 on `shared.stop` — a ringing alarm is the most salient thing in
     * the room — and ahead of Radio's 40 on `shared.resume`, because a paused song is the far
     * likelier referent of "weiter" than a station stopped an hour ago.
     */
    override val shared: List<SharedSubscription> = listOf(
        SharedSubscription(SharedCommands.STOP, priority = CHAIN_PRIORITY),
        SharedSubscription(SharedCommands.RESUME, priority = CHAIN_PRIORITY),
    )

    /**
     * No connection here, deliberately (`spotify.specs.md` §3).
     *
     * A panel that has not been asked for music should not be holding a Binder connection into
     * another app. What this does establish is whether there is any point in trying: no
     * credentials and no Spotify app are both `Unavailable`, and both have their own sentence.
     */
    override suspend fun onStart(ctx: SockContext) {
        context = ctx
        _status.value = when {
            !credentials.canConnect -> SockStatus.Unavailable(NOT_CONFIGURED)
            !player.isInstalled -> SockStatus.Unavailable(NOT_INSTALLED)
            else -> SockStatus.Ready
        }
    }

    override suspend fun onStop() {
        player.disconnect()
        connected = false
        asked.clear()
        context = null
    }

    /**
     * Ranks this Sock in the stop and resume chains (`spotify.specs.md` §6).
     *
     * A pure read of the App Remote's cached snapshot: no Binder call, as the contract
     * requires. The consequence is honest and is written down — a silently dead App Remote
     * leaves a stale cache, and the Sock may claim a `shared.stop` it cannot execute. It then
     * fails with the normal message rather than passing down the chain, which is the accepted
     * price of not making a round trip on every "stopp".
     */
    override fun activityFor(invocation: CommandInvocation): SockActivity {
        val snapshot = player.state.value ?: return SockActivity.INACTIVE
        return when (invocation.commandId) {
            SharedCommands.STOP.id -> if (snapshot.isPaused) SockActivity.IDLE else SockActivity.ACTIVE
            // Never ACTIVE: resuming something already playing is a no-op, and claiming the
            // command for it would starve whoever really was paused.
            SharedCommands.RESUME.id -> if (snapshot.isPaused) SockActivity.IDLE else SockActivity.INACTIVE
            else -> SockActivity.INACTIVE
        }
    }

    override suspend fun handle(invocation: CommandInvocation): SockResult =
        when (invocation.commandId) {
            PLAY_MUSIC -> playMusic(invocation)
            PAUSE -> pause()
            RESUME -> resume()
            SKIP_NEXT -> transport(SpotifyPlayer::skipNext)
            SKIP_PREVIOUS -> transport(SpotifyPlayer::skipPrevious)
            RESTART_SONG -> transport(SpotifyPlayer::seekToStart)
            SharedCommands.STOP.id -> stop()
            SharedCommands.RESUME.id -> resumeChain()
            // Unreachable in practice: the dispatcher only routes commands this Sock owns.
            else -> SockResult.NotForMe
        }

    /** The turn ended, or the user said something else entirely. Drop the candidates. */
    override suspend fun onAskCancelled(token: String) {
        asked.remove(token)
    }

    /**
     * `spotify.play_music`, in one utterance or in two.
     *
     * Three paths converge here: an answer to "welche Version" (the candidates are already
     * held), an empty query (no Web API call at all), and a query to resolve.
     */
    private suspend fun playMusic(invocation: CommandInvocation): SockResult {
        val answering = invocation.answering
        if (answering != null) {
            val candidates = asked.remove(answering) ?: return SockResult.Failed(NOT_FOUND)
            val chosen = candidates.getOrNull(ordinalOf(invocation.textOrNull("choice"))) ?: candidates.first()
            return start(chosen)
        }

        val unavailable = unavailable()
        if (unavailable != null) return SockResult.Failed(unavailable)

        val query = invocation.textOrNull("query").orEmpty().trim()
        val hint = Selection.hintFor(query, QueryHint.of(invocation.textOrNull("hint")))

        val connection = open()
        if (connection != Connection.Connected) {
            // The intent fallback is for a connection that would not come up, not for a device
            // with no Spotify on it and not for an account that may not control playback.
            if (connection is Connection.Refused) return offline(query, hint)
            return SockResult.Failed(unreachable(connection))
        }
        if (query.isEmpty()) return playSomething()

        val config = SpotifyConfig(started().config)
        val hits = try {
            // One budget over token *and* search, because that is the number a person feels.
            // A music command that takes four seconds to start feels broken, and stacking a
            // slow search on top of a Tier 2 paraphrase is exactly the case to protect against.
            withTimeout(RESOLVE_BUDGET_MS) { search.find(query, hint, config.market) }
        } catch (timeout: TimeoutCancellationException) {
            started().log.warn("spotify: search for \"$query\" ran out of budget", timeout)
            return SockResult.Failed(SEARCH_TIMEOUT)
        } catch (failure: SearchFailure) {
            return searchFailed(failure)
        }
        if (hits.isEmpty) return SockResult.Failed(NOT_FOUND)

        val candidates = if (config.askWhenUnsure) Selection.ambiguous(query, hint, hits) else emptyList()
        if (candidates.size >= 2) return askWhichVersion(candidates)

        val chosen = Selection.choose(query, hint, hits, config.preferTrackOverArtist)
            ?: return SockResult.Failed(NOT_FOUND)
        return start(chosen)
    }

    /**
     * "Musik an" — the empty query (`spotify.specs.md` §3).
     *
     * **No Web API call at all.** A paused track is the obvious thing they meant; failing that,
     * the App Remote's own home feed is recently-played, their playlists and made-for-you,
     * running inside their session with none of an app token's limits. This is the one place
     * where the auth decision made the behaviour better rather than merely cheaper.
     */
    private suspend fun playSomething(): SockResult {
        val snapshot = player.state.value
        if (snapshot != null && snapshot.isPaused) {
            return if (claimAnd { player.resume() }) ended() else SockResult.Failed(UNREACHABLE)
        }
        val name = claimAndReturn { player.resumeHome() } ?: return SockResult.Failed(NOT_FOUND)
        return ended("spotify: playing $name")
    }

    /** Plays one resolved hit. Says nothing: the music is the answer, and the card names it. */
    private suspend fun start(hit: Hit): SockResult =
        if (claimAnd { player.play(hit.uri) }) {
            ended("spotify: playing ${hit.spoken}")
        } else {
            SockResult.Failed(UNREACHABLE)
        }

    /**
     * How every successful `play_music` ends: without a word, and with the turn closed.
     *
     * Music is the case the whole panel was built around, and it is the one command whose
     * result is *audible on its own*. "Spiele Blinding Lights." on top of Blinding Lights
     * starting is Dobby talking over the thing it was asked for. Worse, the microphone would
     * then stay open for a follow-up into a room that has just gone loud — the turn would end
     * on the music rather than on silence. So the play command is [SockResult.Ended]: no
     * speech, and straight back to the wake word (§3).
     *
     * What was played is not lost — the dashboard card names it (§7), and [trace] puts it in
     * the log, which is where the confirmation went now that nobody says it out loud.
     */
    private fun ended(trace: String? = null): SockResult {
        trace?.let { context?.log?.debug(it) }
        return SockResult.Ended()
    }

    /**
     * "Ich habe zwei Versionen: …" (`spotify.specs.md` §3).
     *
     * The palette is an **enum over ordinals**, never a text slot, for the reason
     * `ClockSock.askWhichTimer` documents at length: a question's scoped palette gets first
     * refusal on the next utterance, so a bare `{text}` slot here would turn "wie spät ist es"
     * into a song title. Ordinals are closed, short, and what people actually say. Anything
     * else falls through to the global palette, the question is abandoned, and the candidates
     * are dropped — the user changed their mind, which is allowed.
     */
    private fun askWhichVersion(candidates: List<Hit>): SockResult {
        val artists = candidates.map { it.artist ?: it.name }
        val question = if (candidates.size == 2) {
            "Ich habe zwei Versionen: einmal von ${artists[0]}, einmal von ${artists[1]}. " +
                "Die erste oder die zweite?"
        } else {
            "Ich habe drei: von ${artists[0]}, von ${artists[1]} und von ${artists[2]}. " +
                "Die erste, die zweite oder die dritte?"
        }
        val token = "spotify-${askCounter.incrementAndGet()}"
        asked[token] = candidates
        return SockResult.Asked(
            text = question,
            follow = FollowUp(
                commandId = PLAY_MUSIC,
                templates = patterns("{choice:enum}", "(die|das|den) {choice:enum}"),
                params = listOf(ParamSpec("choice", ParamType.Enumeration(ORDINALS))),
                token = token,
            ),
        )
    }

    /** `spotify.pause` — explicitly addressed, so it never consults the chain. */
    private suspend fun pause(): SockResult {
        unavailable()?.let { return SockResult.Failed(it) }
        val connection = open()
        if (connection != Connection.Connected) return SockResult.Failed(unreachable(connection))
        val snapshot = player.state.value
        // Unlike the chain case the user named a target, so silence would look like a failure.
        if (snapshot == null || snapshot.isPaused) return SockResult.Spoken(NOTHING_PLAYING)
        return if (player.pause()) SockResult.Silent else SockResult.Failed(UNREACHABLE)
    }

    /** `spotify.resume` — explicitly addressed. */
    private suspend fun resume(): SockResult {
        unavailable()?.let { return SockResult.Failed(it) }
        val connection = open()
        if (connection != Connection.Connected) return SockResult.Failed(unreachable(connection))
        val snapshot = player.state.value ?: return SockResult.Failed(NOTHING_PAUSED)
        if (!snapshot.isPaused) return SockResult.Spoken(RUNNING)
        return if (claimAnd { player.resume() }) SockResult.Silent else SockResult.Failed(UNREACHABLE)
    }

    /**
     * Skip, previous and restart: one shape, because they really are one shape.
     *
     * `Silent` throughout — the music changing *is* the feedback, and a panel that says "Okay"
     * over the first second of every song is a panel people switch off.
     */
    private suspend fun transport(action: suspend SpotifyPlayer.() -> Boolean): SockResult {
        unavailable()?.let { return SockResult.Failed(it) }
        val connection = open()
        if (connection != Connection.Connected) return SockResult.Failed(unreachable(connection))
        if (player.state.value == null) return SockResult.Failed(NOTHING_PLAYING)
        return if (player.action()) SockResult.Silent else SockResult.Failed(UNREACHABLE)
    }

    /**
     * `shared.stop`.
     *
     * The activity check comes first and does no I/O: a Sock that reported anything but
     * `ACTIVE` must pass the command on without touching the connection
     * (`socks.specs/README.md` §4). `IDLE` — a track loaded but already paused — is deliberately
     * `NotForMe`: pausing an already-paused player consumes the command without doing anything
     * and would starve a Sock further down the chain.
     */
    private suspend fun stop(): SockResult {
        val snapshot = player.state.value ?: return SockResult.NotForMe
        if (snapshot.isPaused) return SockResult.NotForMe
        return if (player.pause()) SockResult.Silent else SockResult.Failed(UNREACHABLE)
    }

    /** `shared.resume`. Only ever from `IDLE`, which is the only state it means anything in. */
    private suspend fun resumeChain(): SockResult {
        val snapshot = player.state.value ?: return SockResult.NotForMe
        if (!snapshot.isPaused) return SockResult.NotForMe
        return if (claimAnd { player.resume() }) SockResult.Silent else SockResult.Failed(UNREACHABLE)
    }

    /**
     * Claims the audio channel, then does the thing that makes sound.
     *
     * [io.dobby.core.sock.PlaybackCoordinator.claimExternal] and **not** `requestFocus`, and
     * that distinction is the trap in this Sock. The audio is produced by the Spotify app's
     * process, which holds its own audio focus; asking for `AUDIOFOCUS_GAIN` on Dobby's behalf
     * would send `AUDIOFOCUS_LOSS` to Spotify and pause the music one instant before asking it
     * to play (`spotify.specs.md` §3).
     */
    private suspend fun claimAnd(action: suspend () -> Boolean): Boolean {
        context?.playback?.claimExternal(id)
        return action()
    }

    private suspend fun <T> claimAndReturn(action: suspend () -> T?): T? {
        context?.playback?.claimExternal(id)
        return action()
    }

    /**
     * Connects lazily, and reconnects exactly once before giving up (`spotify.specs.md` §3).
     *
     * An overnight disconnect is **normal** and must not set `Degraded` — that is the kind of
     * thing which otherwise ends up permanently amber in the settings screen. The two outcomes
     * that do stick are `NotInstalled` and `NotPremium`, and even those are re-attempted rather
     * than latched: a status that refuses to try again is a panel that needs a restart after
     * somebody upgrades their account, and one connect attempt per music command is cheap.
     */
    private suspend fun open(): Connection {
        if (connected && player.state.value != null) return Connection.Connected
        val outcome = player.connect()
        connected = outcome == Connection.Connected
        _status.value = when (outcome) {
            Connection.Connected -> SockStatus.Ready
            Connection.NotPremium -> SockStatus.Unavailable(NO_PREMIUM)
            Connection.NotInstalled -> SockStatus.Unavailable(NOT_INSTALLED)
            // Not a status change at all: a dead connection is the ordinary overnight case,
            // and whatever the status was before it is still true.
            is Connection.Refused -> _status.value
        }
        if (outcome is Connection.Refused) {
            context?.log?.debug("spotify: app remote refused the connection (${outcome.reason})")
        }
        return outcome
    }

    /** What to say about a connection that did not happen. Never called for `Connected`. */
    private fun unreachable(outcome: Connection): String = when (outcome) {
        Connection.NotInstalled -> NOT_INSTALLED
        Connection.NotPremium -> NO_PREMIUM
        Connection.Connected, is Connection.Refused -> UNREACHABLE
    }

    /**
     * The intent of last resort, for `play_music` alone (`spotify.specs.md` §3).
     *
     * It brings Spotify to the foreground and tells us nothing — no URI, no confirmation, and
     * an empty dashboard card — which is why it is a fallback and not the design. It is still
     * strictly better than "Ich komme gerade nicht an Spotify ran." for the one command where
     * a blind attempt does what the user wanted. There is no meaningful equivalent for pause
     * or skip, and those fail instead.
     */
    private fun offline(query: String, hint: QueryHint): SockResult {
        if (query.isEmpty()) return SockResult.Failed(UNREACHABLE)
        if (!fallback.playFromSearch(query, hint)) return SockResult.Failed(UNREACHABLE)
        // Silent like every other successful play (see [ended]), and here the handoff shows
        // itself: the intent starts an activity, so Spotify comes up over the panel. "Ich
        // versuche es über die Spotify-App." was a sentence said over both that and the music.
        return ended("spotify: handed \"$query\" to the Spotify app")
    }

    /** German copy for a search that failed, and the status it leaves behind (§3). */
    private fun searchFailed(failure: SearchFailure): SockResult {
        val message = when (failure.error) {
            SearchError.NOT_CONFIGURED -> NOT_CONFIGURED
            SearchError.NO_TOKEN -> NO_SEARCH
            SearchError.RATE_LIMITED -> RATE_LIMITED
            SearchError.OFFLINE -> NO_NETWORK
            SearchError.TIMEOUT -> SEARCH_TIMEOUT
            SearchError.FAILED -> NO_SEARCH
        }
        // Degraded, not Unavailable: the connection and every transport command still work,
        // and it is only the catalogue lookup that is out. A disconnect, by contrast, is not
        // degradation at all (§10).
        if (failure.error == SearchError.NO_TOKEN || failure.error == SearchError.RATE_LIMITED) {
            _status.value = SockStatus.Degraded(message)
        }
        return SockResult.Failed(message, failure)
    }

    /**
     * The standing reasons nothing can work, or null.
     *
     * Both are cheap reads and neither is latched: a credential that appeared in a later build
     * and an app that was installed since `onStart` both simply start working. The connection's
     * own outcomes are handled by [open], which is the one place a status is set.
     */
    private fun unavailable(): String? = when {
        !credentials.canConnect -> NOT_CONFIGURED
        !player.isInstalled -> NOT_INSTALLED
        else -> null
    }

    /**
     * Which of the candidates an ordinal names. Out of range and "egal" both mean the first.
     *
     * "Egal" is not indifference to be refused — it is the user handing the decision back,
     * which is what the first hit already was.
     */
    private fun ordinalOf(spoken: String?): Int = when (spoken) {
        "zweite", "zweiten" -> 1
        "dritte", "dritten" -> 2
        else -> 0
    }

    /**
     * The context a command needs.
     *
     * The dispatcher never calls `handle` on a Sock it did not start, so a missing one is a
     * programming error, and core turns the exception into "Das hat gerade nicht geklappt."
     */
    private fun started(): SockContext =
        checkNotNull(context) { "spotify: command before onStart()" }

    companion object {
        const val PLAY_MUSIC: String = "spotify.play_music"
        const val PAUSE: String = "spotify.pause"
        const val RESUME: String = "spotify.resume"
        const val SKIP_NEXT: String = "spotify.skip_next"
        const val SKIP_PREVIOUS: String = "spotify.skip_previous"
        const val RESTART_SONG: String = "spotify.restart_song"

        /** Tied with Radio on `shared.stop`, ahead of it on `shared.resume` (§6). */
        const val CHAIN_PRIORITY: Int = 50

        /**
         * Token plus search, end to end (`spotify.specs.md` §3).
         *
         * Failing with "Ich habe auf Spotify nichts gefunden" is worse than a two-second wait
         * and better than an eight-second one.
         */
        const val RESOLVE_BUDGET_MS: Long = 2_500

        /** The closed answer set for "welche Version". One token each, which is the point. */
        val ORDINALS: List<String> = listOf(
            "erste", "ersten", "zweite", "zweiten", "dritte", "dritten", "egal",
        )

        /** German copy, verbatim from the spec (§3). */
        const val NOT_INSTALLED: String = "Spotify ist auf diesem Gerät nicht installiert."
        const val NOT_CONFIGURED: String = "Spotify ist nicht eingerichtet."
        const val UNREACHABLE: String = "Ich komme gerade nicht an Spotify ran."
        const val NO_PREMIUM: String = "Dafür brauche ich Spotify Premium."
        const val NO_SEARCH: String = "Ich komme gerade nicht an die Spotify-Suche ran."
        const val NOT_FOUND: String = "Ich habe auf Spotify nichts gefunden."
        const val NO_NETWORK: String = "Ich habe gerade keine Internetverbindung."
        const val RATE_LIMITED: String = "Spotify lässt mich gerade nicht so oft suchen."
        const val SEARCH_TIMEOUT: String = "Die Suche hat zu lange gedauert."
        const val NOTHING_PLAYING: String = "Auf Spotify läuft gerade nichts."
        const val NOTHING_PAUSED: String = "Auf Spotify ist nichts pausiert."
        const val RUNNING: String = "Läuft."
    }
}
