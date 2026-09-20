package io.dobby.socks.radio

import io.dobby.core.sock.CommandHelp
import io.dobby.core.sock.CommandInvocation
import io.dobby.core.sock.Example
import io.dobby.core.sock.ExclusiveCommandSpec
import io.dobby.core.sock.ParamSpec
import io.dobby.core.sock.ParamType
import io.dobby.core.sock.SharedCommands
import io.dobby.core.sock.SharedSubscription
import io.dobby.core.sock.Sock
import io.dobby.core.sock.SockActivity
import io.dobby.core.sock.SockContext
import io.dobby.core.sock.SockResult
import io.dobby.core.sock.patterns
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Radio — Austrian internet streams, from a constant table.
 *
 * The Sock sees [RadioPlayer] and nothing else, which is the same bargain `ClockSock` makes
 * with `TimerAlarm` and `SpotifySock` makes with the App Remote: the device sees Media3, and
 * the station table, the resolver, the German copy and the whole command surface stay
 * reachable from a terminal with no device and no network.
 *
 * Two things this Sock is the first to need, both of them in core rather than here:
 *
 * 1. **A focus-loss callback** on `PlaybackCoordinator` (`radio-plan.md` §A). Radio is the
 *    first Sock holding sustained audio *inside* Dobby's process, so it is the first that has
 *    to be told when the channel changes hands.
 * 2. **`InProcessPlayers`**, which attenuates that same audio for the length of a turn. The
 *    registration lives on the device side with the player, because it is the player's
 *    lifecycle and nothing else's (`radio.specs.md` §6).
 *
 * @param player the stream, or [RadioPlayer.NONE] off-device.
 */
class RadioSock(private val player: RadioPlayer = RadioPlayer.NONE) : Sock {

    override val id: String = "radio"

    override val displayName: String = "Radio"

    /** Held from [onStart], the way every other Sock holds its own: a context is service-lifetime. */
    private var context: SockContext? = null

    private val _state = MutableStateFlow<RadioState>(RadioState.Idle())

    /** What the chain ranks itself by and what the panel draws (`radio.specs.md` §5, §7). */
    val state: StateFlow<RadioState> = _state.asStateFlow()

    private val resolver = StationResolver()

    /**
     * The reconnect ladder, while one is climbing. Cancelled by every user-initiated stop.
     *
     * A ladder that survives a "radio aus" and brings the stream back nine seconds later is the
     * worst bug this Sock could have (`radio.specs.md` §10).
     */
    private var reconnect: Job? = null

    /** Watches the player for a mid-stream drop. Lives as long as the Sock is started. */
    private var watchdog: Job? = null

    /** Feeds the ICY title into [RadioState.Playing]. Same lifetime as [watchdog]. */
    private var titles: Job? = null

    override val commands: List<ExclusiveCommandSpec> = listOf(
        ExclusiveCommandSpec(
            id = PLAY_RADIO,
            params = listOf(ParamSpec("station", ParamType.Text, required = false, default = "")),
            templates = patterns(
                // Specific → generic. Every one of these contains `radio` or `sender`, which is
                // what puts them ahead of Spotify's greedy `(spiele|spiel) {query}( ab)?` under
                // the registry's "more literal keywords" ordering key. "spiele radio fm4" must
                // reach this command and not `spotify.play_music(query="radio fm4")`, and that
                // is the regression the spec calls out by name (§11).
                //
                // **The trailing "an"/"ein" is spelled out rather than left optional**, and
                // that is not cosmetic. `Specificity.ORDER` sorts closed templates ahead of
                // open ones *before* it counts keywords, so `… radio {station}( an| ein)?` —
                // which may end on its slot — loses to Spotify's closed
                // `(mach|leg|spiel) {query} (an|auf)`, and "mach radio wien an" resolves to
                // `spotify.play_music(query="radio wien")`. Written as two templates, the
                // closed one carries three keywords against Spotify's two and wins on its own
                // merits. The open one still exists, one rung down, for "spiele radio fm4".
                "(spiele|spiel|mach|schalt|schalte) (den)? (sender|radiosender) {station} (an|ein)",
                "(spiele|spiel|mach|schalt|schalte) (das|den)? radio {station} (an|ein)",
                "(spiele|spiel|mach|schalt|schalte) (den)? (sender|radiosender) {station}",
                "(spiele|spiel|mach|schalt|schalte) (das|den)? radio {station}",
                "(spiele|spiel|mach|schalt|schalte) (das|den)? radio (an|ein)?",
                // A trailing open slot, so it goes after the longer `radio`-prefixed forms and
                // before the bare one — "radio an" has to bind `station=""`, not `station="an"`
                // (`socks.specs/README.md` §6).
                "radio {station}",
                "radio (an|ein)?",
            ),
            // One line, and the second sentence the spec's §2 table carries lives in
            // `help.detail` instead. A description is paid for by the token in every Tier 2
            // route prompt; `detail` is written for a person and costs nothing (`Types.kt`).
            description = "Spielt einen Radiosender ab.",
            help = CommandHelp(
                title = "Radio hören",
                detail = "Spielt einen der eingebauten Sender als Internet-Stream. Ohne " +
                    "Sendernamen läuft der Standardsender aus den Einstellungen.",
                hints = listOf(
                    "Ich kenne FM4, Hitradio Ö Drei, Ö1, Radio Wien und Kronehit.",
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
                // Paraphrases Tier 1 is meant to miss — few-shots for the LLM tier (M6).
                Example(
                    "kannst du mir bitte fm4 anmachen",
                    mapOf("station" to "fm4"),
                    matchedByTemplates = false,
                ),
                // Held out: never rendered into a prompt, measured against on the device.
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
            description = "Beendet die Radiowiedergabe.",
            help = CommandHelp(
                title = "Radio ausschalten",
                detail = "Beendet den Stream. Das bloße „Stopp“ steht hier nicht — das " +
                    "entscheidet sich im Moment des Sagens und gehört zu „Stopp“.",
                aliases = listOf("radio aus", "radio ausschalten"),
            ),
            examples = listOf(
                Example("radio aus"),
                Example("radio stopp"),
                Example("mach das radio aus"),
                Example("stopp das radio"),
                Example("radio ausschalten"),
                // Not "das radio kannst du ausmachen": "das" is filler, so what is left starts
                // on `radio` and the open `radio {station}` template takes it as a station
                // name. A paraphrase has to be one Tier 1 genuinely cannot reach.
                Example("ich will kein radio mehr hören", matchedByTemplates = false),
            ),
        ),
    )

    /**
     * Both chains (`radio.specs.md` §5, `shared-commands.specs.md` §3.3 and §4.3).
     *
     * 50 on `shared.stop` is a tie with Spotify that `PlaybackCoordinator` is supposed to make
     * unreachable — only one of them can be `ACTIVE` — and is written down anyway so the
     * ordering is deterministic if that invariant ever breaks. 40 on `shared.resume` is below
     * Spotify's 50, because a paused song is a far likelier referent of "weiter" than a station
     * stopped an hour ago.
     */
    override val shared: List<SharedSubscription> = listOf(
        SharedSubscription(SharedCommands.STOP, priority = STOP_PRIORITY),
        SharedSubscription(SharedCommands.RESUME, priority = RESUME_PRIORITY),
    )

    override suspend fun onStart(ctx: SockContext) {
        context = ctx
        // A drop is asynchronous by nature, so it is watched for rather than returned from
        // anything. `Buffering` is deliberately not a trigger: a stream that never started is
        // §3's timeout and already has an answer, and the ladder is for one that did start and
        // then went away.
        watchdog = ctx.scope.launch {
            player.state.collect { playback ->
                val current = state.value
                if (playback == PlaybackState.FAILED && current is RadioState.Playing) {
                    climb(current.station)
                }
            }
        }
        // Many titles arrive a few seconds after the sound does, and a station change clears
        // the old one, so the card is fed rather than asked.
        titles = ctx.scope.launch {
            player.streamTitle.collect { raw ->
                val current = state.value
                if (current is RadioState.Playing) {
                    _state.value = current.copy(nowPlaying = NowPlaying.clean(raw, current.station))
                }
            }
        }
    }

    /**
     * Service teardown. The stream goes with it — a live HTTP connection left open is both
     * battery and bandwidth (`radio.specs.md` §3).
     */
    override suspend fun onStop() {
        watchdog?.cancel()
        watchdog = null
        titles?.cancel()
        titles = null
        reconnect?.cancel()
        reconnect = null
        player.release()
        context?.playback?.releaseFocus(id)
        _state.value = RadioState.Idle()
        context = null
    }

    /**
     * Ranks this Sock in the two chains — a pure read of [state] and nothing else.
     *
     * No player call, no network, no config: one field off a `StateFlow`, which trivially
     * satisfies the under-5 ms contract (`socks.specs/README.md` §4). `Buffering` counts as
     * `ACTIVE` because somebody who says "stopp" two seconds after "radio fm4" means the thing
     * that is currently connecting.
     */
    override fun activityFor(invocation: CommandInvocation): SockActivity =
        when (invocation.commandId) {
            // Never IDLE. A released player has nothing to stop, and claiming the command for
            // it would starve whoever further down the chain really is running (§5).
            SharedCommands.STOP.id -> when (state.value) {
                is RadioState.Playing, is RadioState.Buffering -> SockActivity.ACTIVE
                else -> SockActivity.INACTIVE
            }

            // IDLE iff a station was stopped in *this* session. `Idle(null)` — a cold service —
            // is INACTIVE, which is what keeps 3 a.m. quiet. `Error` is INACTIVE too: a station
            // that just failed is a poor answer to "weiter", and naming it again is four words.
            SharedCommands.RESUME.id -> when (val current = state.value) {
                is RadioState.Idle ->
                    if (current.lastStation != null) SockActivity.IDLE else SockActivity.INACTIVE

                else -> SockActivity.INACTIVE
            }

            else -> SockActivity.INACTIVE
        }

    override suspend fun handle(invocation: CommandInvocation): SockResult =
        when (invocation.commandId) {
            PLAY_RADIO -> playRadio(invocation)
            STOP_RADIO -> stopRadio()
            SharedCommands.STOP.id -> stopChain()
            SharedCommands.RESUME.id -> resumeChain()
            // Unreachable in practice: the dispatcher only routes commands this Sock owns.
            else -> SockResult.NotForMe
        }

    /**
     * `radio.play_radio`.
     *
     * An **empty** `station` and an **unresolvable** one are two different things, which is
     * §3.4's "never silently fall back to the default station when the user named one": the
     * first is the configured default, the second is a sentence saying so.
     */
    private suspend fun playRadio(invocation: CommandInvocation): SockResult {
        val ctx = started()
        val config = RadioConfig(ctx.config)
        val spoken = invocation.textOrNull("station").orEmpty().trim()
        val station = if (spoken.isEmpty()) {
            config.defaultStation
        } else {
            resolver.resolve(spoken) ?: return SockResult.Failed(UNKNOWN_STATION)
        }
        return tuneTo(station, config)
    }

    /**
     * Claims the channel, buffers, and gets out of the way.
     *
     * **Says nothing, and ends the turn.** The terse "FM4." the spec's §3 asked for was one
     * word too many: the audio starting *is* the feedback, and a panel that announces the
     * station over the first bar of it is talking across the thing it was asked for. Ending
     * the turn is the same reasoning one step on — the seconds after "radio an" are music,
     * not a follow-up, so the microphone closes and the wake word comes straight back, which
     * is what `spotify.play_music` already does for the same reason ([SockResult.Ended]).
     */
    private suspend fun tuneTo(station: Station, config: RadioConfig): SockResult {
        val ctx = started()
        if (!claimChannel(ctx)) return SockResult.Failed(NO_FOCUS)
        _state.value = RadioState.Buffering(station)
        if (!player.play(station.streamUrl, config.bufferTimeoutMs)) {
            // The spec's §3.5: a buffer that never filled is stop, release, Failed — not a
            // ladder. The ladder is for a stream that was playing and dropped (§10), where the
            // user has already been told it works and is owed a recovery rather than a verdict.
            player.release()
            ctx.playback.releaseFocus(id)
            _state.value = RadioState.Idle(station)
            return SockResult.Failed(UNREACHABLE)
        }
        _state.value = RadioState.Playing(station)
        return SockResult.Ended()
    }

    /** `radio.stop_radio` — explicitly addressed, and idempotent as the spec requires. */
    private suspend fun stopRadio(): SockResult {
        release(remember = true)
        return SockResult.Silent
    }

    /**
     * The card's X (`radio.specs.md` §7): the same stop, from a finger instead of a sentence.
     *
     * Deliberately the *same* call `radio.stop_radio` makes, `remember = true` and all — a
     * stop is a stop however it was asked for, and "weiter" must still bring the station back
     * after one. Public because the panel holds this Sock directly, the way it already reads
     * [state] directly; idempotent, so a second tap on a card mid-teardown is harmless.
     */
    suspend fun stopFromPanel() {
        release(remember = true)
    }

    /**
     * `shared.stop` — a bare "stopp" that turned out to mean the radio.
     *
     * The state check repeats [activityFor] rather than trusting it, because a Sock that
     * reported anything but `ACTIVE` must pass the command on **without performing I/O**, and
     * that rule is easier to keep when the guard sits next to the I/O it guards.
     */
    private suspend fun stopChain(): SockResult {
        val current = state.value
        if (current !is RadioState.Playing && current !is RadioState.Buffering) {
            return SockResult.NotForMe
        }
        release(remember = true)
        return SockResult.Silent
    }

    /**
     * `shared.resume` — a bare "weiter" that reached this far down the chain.
     *
     * Silent like every other way into [tuneTo], and the earlier "speaks, because buffering
     * reads as a no-op" is gone with it: the station coming back is audible within the second
     * the sentence would have taken to say, and the card shows the spinner meanwhile.
     */
    private suspend fun resumeChain(): SockResult {
        val last = (state.value as? RadioState.Idle)?.lastStation ?: return SockResult.NotForMe
        return tuneTo(last, RadioConfig(started().config))
    }

    /**
     * Stops the stream and gives the channel back.
     *
     * @param remember whether the station survives in [RadioState.Idle], which is what
     *   `shared.resume` reads. True for every stop a person can hear; false only for teardown.
     */
    private suspend fun release(remember: Boolean) {
        reconnect?.cancel()
        reconnect = null
        player.release()
        context?.playback?.releaseFocus(id)
        _state.value = RadioState.Idle(if (remember) state.value.stationOrNull else null)
    }

    /**
     * Claims the channel, and says what to do when it is taken away.
     *
     * Focus is core's business and the player never asks for its own. The callback is what
     * makes Radio-vs-Spotify arbitration two-way: a Sock playing audio *in this process* is not
     * reachable by the OS's own focus revocation once Dobby's request has been abandoned
     * (`radio-plan.md` §A).
     *
     * Losing the channel is **not** `stop_radio`. It is not a user decision, so `lastStation`
     * is kept and `shared.resume` can still bring it back: somebody who lost FM4 to an incoming
     * call says "weiter" and gets it back. What goes away is the player and the socket.
     */
    private suspend fun claimChannel(ctx: SockContext): Boolean =
        ctx.playback.requestFocus(id) { reason ->
            ctx.log.debug("radio: lost the channel ($reason), releasing the player")
            // Both reasons keep it: an eviction by Spotify is no more a decision about
            // the radio than a phone call is.
            release(remember = true)
        }

    /**
     * The reconnect ladder (`radio.specs.md` §10, extended by `radio-plan.md` §J1).
     *
     * ```
     * wait 1 s → streamUrl · wait 3 s → streamUrl · wait 9 s → fallbackUrl · give up
     * ```
     *
     * `reconnect_attempts` counts the retries *after* the first failure, so the default 3 is
     * exactly that ladder. The fallback URL is the last rung and nothing else — the commonest
     * reason a specific ORS endpoint stops answering is that endpoint, not the network — which
     * also means setting the config to 0 removes both, and "no retries" is the right reading
     * of that.
     *
     * Runs in `ctx.scope` and not inside `handle()`, which runs under a timeout
     * (`socks.specs/README.md` §2). Its user-facing outcome is a [SockContext.announce], which
     * is exactly what that method is for.
     */
    private fun climb(station: Station) {
        val ctx = context ?: return
        reconnect = ctx.scope.launch {
            val config = RadioConfig(ctx.config)
            val attempts = config.reconnectAttempts
            for (attempt in 0 until attempts) {
                delay(BACKOFF_MS[minOf(attempt, BACKOFF_MS.lastIndex)])
                val last = attempt == attempts - 1
                val url = if (last) station.fallbackUrl ?: station.streamUrl else station.streamUrl
                _state.value = RadioState.Buffering(station)
                if (player.play(url, config.bufferTimeoutMs)) {
                    _state.value = RadioState.Playing(station)
                    return@launch
                }
            }
            player.release()
            ctx.playback.releaseFocus(id)
            _state.value = RadioState.Error(station, STREAM_DROPPED)
            ctx.announce(STREAM_DROPPED)
        }
    }

    /**
     * The context a command needs.
     *
     * The dispatcher never calls `handle` on a Sock it did not start, so a missing one is a
     * programming error, and core turns the exception into "Das hat gerade nicht geklappt."
     */
    private fun started(): SockContext = checkNotNull(context) { "radio: command before onStart()" }

    companion object {
        const val PLAY_RADIO: String = "radio.play_radio"
        const val STOP_RADIO: String = "radio.stop_radio"

        /** Tied with Spotify on `shared.stop`, behind the Clock's 100 (§5). */
        const val STOP_PRIORITY: Int = 50

        /** Behind Spotify's 50 on `shared.resume` (§5). */
        const val RESUME_PRIORITY: Int = 40

        /** The spec's backoff. A shorter ladder takes the first rungs, never the last. */
        val BACKOFF_MS: List<Long> = listOf(1_000, 3_000, 9_000)

        /** German copy, verbatim from the spec (§3). */
        const val UNKNOWN_STATION: String = "Den Sender kenne ich nicht."
        const val UNREACHABLE: String = "Der Sender ist gerade nicht erreichbar."
        /**
         * Spec copy with no caller, and that is a decision rather than an omission (§J3).
         *
         * Telling "no network" apart from "this stream is down" needs a
         * `ConnectivityManager`, which is an Android type this module must not see. Widening
         * `SockContext` for one sentence is not worth it until the generic one turns out to be
         * confusing in use; this is where to start when it does.
         */
        const val NO_NETWORK: String = "Ich habe gerade keine Internetverbindung."
        const val NO_FOCUS: String = "Gerade nicht möglich."
        const val STREAM_DROPPED: String = "Der Radiostream ist abgerissen."
    }
}
