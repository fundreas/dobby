package io.dobby.socks.radio

import io.dobby.core.sock.CommandHelp
import io.dobby.core.sock.CommandInvocation
import io.dobby.core.sock.Example
import io.dobby.core.sock.ExclusiveCommandSpec
import io.dobby.core.sock.ParamSpec
import io.dobby.core.sock.ParamType
import io.dobby.core.sock.Sock
import io.dobby.core.sock.SockContext
import io.dobby.core.sock.SockResult
import io.dobby.core.sock.patterns
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
            description = "Spielt einen Radiosender ab. Ohne Sendername den Standardsender.",
            help = CommandHelp(
                title = "Radio hören",
                detail = "Spielt einen der eingebauten Sender als Internet-Stream. Ohne " +
                    "Sendernamen läuft der Standardsender aus den Einstellungen.",
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
            description = "Beendet die Radiowiedergabe — explizit adressiert.",
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

    override suspend fun onStart(ctx: SockContext) {
        context = ctx
    }

    /**
     * Service teardown. The stream goes with it — a live HTTP connection left open is both
     * battery and bandwidth (`radio.specs.md` §3).
     */
    override suspend fun onStop() {
        player.release()
        context?.playback?.releaseFocus(id)
        _state.value = RadioState.Idle()
        context = null
    }

    override suspend fun handle(invocation: CommandInvocation): SockResult =
        when (invocation.commandId) {
            PLAY_RADIO -> playRadio(invocation)
            STOP_RADIO -> stopRadio()
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
     * Claims the channel, buffers, and says the station's name.
     *
     * Terse on purpose (`radio.specs.md` §3): the audio starting is the real feedback, and
     * "FM4." is what is left when the sentence is cut down to the part that carries
     * information.
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
        return SockResult.Spoken("${station.displayName}.")
    }

    /** `radio.stop_radio` — explicitly addressed, and idempotent as the spec requires. */
    private suspend fun stopRadio(): SockResult {
        release(remember = true)
        return SockResult.Silent
    }

    /**
     * Stops the stream and gives the channel back.
     *
     * @param remember whether the station survives in [RadioState.Idle], which is what
     *   `shared.resume` reads. True for every stop a person can hear; false only for teardown.
     */
    private suspend fun release(remember: Boolean) {
        player.release()
        context?.playback?.releaseFocus(id)
        _state.value = RadioState.Idle(if (remember) state.value.stationOrNull else null)
    }

    /** Claims the channel. Focus is core's business, and the player never asks for its own. */
    private suspend fun claimChannel(ctx: SockContext): Boolean = ctx.playback.requestFocus(id)

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
