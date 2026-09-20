package io.dobby.socks.system

import io.dobby.core.sock.CommandHelp
import io.dobby.core.sock.CommandInvocation
import io.dobby.core.sock.Example
import io.dobby.core.sock.ExclusiveCommandSpec
import io.dobby.core.sock.ParamSpec
import io.dobby.core.sock.ParamType
import io.dobby.core.sock.Sock
import io.dobby.core.sock.SockContext
import io.dobby.core.sock.SockResult
import io.dobby.core.sock.SockStatus
import io.dobby.core.sock.pattern
import io.dobby.core.sock.patterns
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * System — the device itself: how loud it is, and whether it makes a sound at all.
 *
 * The thinnest wrapper over the platform in the project, and still a Sock, because core must
 * not know that "lauter" exists (`system.specs.md` §1). Everything the device provides sits
 * behind [VolumeControl], so the whole command surface resolves in a terminal with no
 * `AudioManager` anywhere.
 *
 * **Percent is the currency.** The spec's original `steps` model could only say "a bit more
 * than before"; the commands people actually want — "volle Lautstärke", "Lautstärke auf
 * mittel" — are absolute, and an absolute command needs a scale that means the same thing on a
 * 7-step stream and a 25-step one. So [SET_VOLUME] takes a percentage, [VOLUME] moves by one
 * (`system.volume_step_percent`, five points), and [VolumeScale] owns the rounding. This closes
 * the "absolute volume" gap `system.specs.md` §11 left open.
 *
 * Screen control (`system.turn_on_screen` / `system.turn_off_screen`) is specified and **not
 * implemented here yet** — it needs a device-admin receiver, which is a manifest and a setup
 * step rather than a handler.
 *
 * @param volume the media stream, or [VolumeControl.NONE] where there is none — the Sock is
 *   then `Unavailable` and says so, the same shape the Spotify Sock has off-device.
 */
class SystemSock(private val volume: VolumeControl = VolumeControl.NONE) : Sock {

    override val id: String = "system"

    override val displayName: String = "System"

    private val _status = MutableStateFlow<SockStatus>(SockStatus.Ready)

    override val status: StateFlow<SockStatus> = _status.asStateFlow()

    private var config: SystemConfig? = null

    /**
     * What the stream was set to when Dobby muted it.
     *
     * The platform restores this by itself on unmute, and Dobby remembers it anyway, because
     * "Lautstärke wieder an" has to work after the *process* died as well as after the stream
     * was muted — and because a device that comes back at index 0 is indistinguishable from one
     * that is still muted, which is exactly the state a voice panel cannot talk its way out of.
     */
    @Volatile
    private var beforeMute: Int? = null

    override val commands: List<ExclusiveCommandSpec> = listOf(
        ExclusiveCommandSpec(
            id = VOLUME,
            params = listOf(
                ParamSpec("direction", ParamType.Enumeration(Direction.SPOKEN)),
                ParamSpec("steps", ParamType.Integer, required = false, default = SystemConfig.DEFAULT_STEPS),
            ),
            templates = listOf(
                // The modifier is folded into `steps` by the template, not re-parsed from German
                // in the handler (`socks.specs/README.md` §6). Five points a step, so these are
                // 25 %, 5 % and — by the default — 10 %.
                pattern("(viel|deutlich|wesentlich|sehr) {direction:enum}", "steps" to LOUD_STEPS),
                pattern("(ein bisschen|bisschen|etwas|leicht) {direction:enum}", "steps" to GENTLE_STEPS),
                // The article branches are spelled out rather than left to filler skipping:
                // "mach die Musik lauter" is a phrasing that belongs on the fast path, and
                // skipping is a second pass over the whole palette (README §6).
                pattern("($CHANGE_VERBS) (die musik|den ton|die lautstärke|musik|ton|lautstärke)? {direction:enum}"),
                pattern("lautstärke {direction:enum}"),
                pattern("(erhöh|erhöhe|steigere|steigre) lautstärke", "direction" to LAUTER),
                pattern("(verringere|verringer|reduziere|reduzier|senke|senk) lautstärke", "direction" to LEISER),
                pattern("lautstärke (hoch|rauf|höher|hochdrehen)", "direction" to LAUTER),
                pattern("lautstärke (runter|herunter|niedriger|runterdrehen)", "direction" to LEISER),
            ) + patterns(
                // Last, and bare: a closed candidate set of two words, which is the one shape
                // `socks.specs/README.md` §6 allows a single-token template to have.
                "{direction:enum}",
            ),
            description = "Ändert die Lautstärke schrittweise, lauter oder leiser.",
            help = CommandHelp(
                title = "Lauter und leiser",
                detail = "Verstellt die Lautstärke relativ zu dem, was gerade eingestellt ist. " +
                    "Ein Schritt sind fünf Prozentpunkte; ohne nähere Angabe sind es zwei.",
                hints = listOf(
                    "„viel lauter“ macht größere Schritte, „ein bisschen lauter“ kleinere.",
                    "Am Anschlag sage ich Bescheid, sonst bleibe ich still.",
                ),
                aliases = listOf("lauter", "leiser", "lautstärke"),
            ),
            examples = listOf(
                Example("lauter", mapOf("direction" to LAUTER, "steps" to SystemConfig.DEFAULT_STEPS)),
                Example("leiser", mapOf("direction" to LEISER, "steps" to SystemConfig.DEFAULT_STEPS)),
                Example("mach lauter", mapOf("direction" to LAUTER, "steps" to SystemConfig.DEFAULT_STEPS)),
                Example("dreh die musik leiser", mapOf("direction" to LEISER, "steps" to SystemConfig.DEFAULT_STEPS)),
                Example("viel lauter", mapOf("direction" to LAUTER, "steps" to LOUD_STEPS)),
                Example("ein bisschen leiser", mapOf("direction" to LEISER, "steps" to GENTLE_STEPS)),
                Example("lautstärke hoch", mapOf("direction" to LAUTER, "steps" to SystemConfig.DEFAULT_STEPS)),
                Example("lautstärke runter", mapOf("direction" to LEISER, "steps" to SystemConfig.DEFAULT_STEPS)),
                Example("erhöhe die lautstärke", mapOf("direction" to LAUTER, "steps" to SystemConfig.DEFAULT_STEPS)),
                // A paraphrase Tier 1 is meant to miss: no keyword of ours is in it, and "zu
                // laut" is a judgement about the room rather than an instruction with a verb.
                Example(
                    "das ist mir zu laut",
                    mapOf("direction" to LEISER, "steps" to SystemConfig.DEFAULT_STEPS),
                    matchedByTemplates = false,
                ),
            ),
        ),
        ExclusiveCommandSpec(
            id = SET_VOLUME,
            params = listOf(ParamSpec("level", ParamType.Integer)),
            templates = listOf(
                // The named levels, each fixed by its wording. A `{level:enum}` slot plus a
                // lookup in the handler would be the same table one layer further from the
                // words it belongs to.
                pattern("($SET_VERBS)? lautstärke (auf)? (voll|maximal|maximum|anschlag)", "level" to FULL),
                pattern("(volle|maximale|höchste) lautstärke", "level" to FULL),
                pattern("($SET_VERBS)? lautstärke (auf)? laut", "level" to LOUD),
                pattern("($SET_VERBS)? lautstärke (auf)? (mittel|mitte|halb|normal)", "level" to MEDIUM),
                pattern("(mittlere|halbe|normale) lautstärke", "level" to MEDIUM),
                pattern("($SET_VERBS)? lautstärke (auf)? leise", "level" to QUIET),
            ) + patterns(
                // Absolute percent, which is what the named levels are underneath. Last,
                // because an integer slot must not get first refusal at "laut".
                "($SET_VERBS)? lautstärke (auf)? {level:int}( prozent)?",
            ),
            description = "Setzt die Lautstärke auf einen festen Wert, in Prozent.",
            help = CommandHelp(
                title = "Lautstärke einstellen",
                detail = "Setzt die Lautstärke auf einen festen Wert statt sie zu verschieben. " +
                    "„Leise“ sind 20 Prozent, „mittel“ 50, „laut“ 80, „voll“ 100.",
                hints = listOf(
                    "Prozent geht auch direkt: „Lautstärke auf 35 Prozent“.",
                    "Eine Obergrenze in den Einstellungen gilt auch für „volle Lautstärke“.",
                ),
                aliases = listOf("volle lautstärke", "lautstärke auf"),
            ),
            examples = listOf(
                Example("volle lautstärke", mapOf("level" to FULL)),
                Example("lautstärke auf laut", mapOf("level" to LOUD)),
                Example("lautstärke auf mittel", mapOf("level" to MEDIUM)),
                Example("lautstärke auf leise", mapOf("level" to QUIET)),
                Example("stell die lautstärke auf mittel", mapOf("level" to MEDIUM)),
                Example("mach die lautstärke voll", mapOf("level" to FULL)),
                Example("lautstärke auf 35 prozent", mapOf("level" to 35)),
            ),
        ),
        ExclusiveCommandSpec(
            id = MUTE,
            params = listOf(
                // The param is the *mute* state, not the sound state: `an` means muted. Every
                // template binds it explicitly so the German never has to be reasoned about at
                // runtime (`system.specs.md` §4).
                ParamSpec(
                    "state",
                    ParamType.Enumeration(MuteState.SPOKEN),
                    required = false,
                    default = MuteState.AN.spoken,
                ),
            ),
            templates = listOf(
                pattern("(stumm|stummschalten|stumm schalten|sei still|ruhe)", "state" to MUTED),
                pattern("($CHANGE_VERBS|schalt|schalte) stumm", "state" to MUTED),
                // "ton aus" mutes the stream and lets playback run on silently; "musik aus"
                // pauses Spotify. Deliberately different commands — see §7 of the spec, which
                // says so because it will look like a bug in a log.
                pattern("(ton|lautstärke) aus", "state" to MUTED),
                pattern("(ton|lautstärke) (wieder)? (an|ein)", "state" to UNMUTED),
                pattern("(nicht mehr stumm|stumm aus|entstummen|wieder laut|laut stellen)", "state" to UNMUTED),
            ),
            description = "Schaltet den Ton stumm oder wieder an.",
            help = CommandHelp(
                title = "Stumm schalten",
                detail = "Macht das Gerät still, ohne die Wiedergabe anzuhalten — die Musik " +
                    "läuft weiter, man hört sie nur nicht. „Ton wieder an“ stellt die " +
                    "Lautstärke von vorher wieder her.",
                hints = listOf(
                    "Stumm heißt stumm: auch ein ablaufender Timer bleibt still.",
                    "„Musik aus“ ist etwas anderes — das hält Spotify an.",
                ),
                aliases = listOf("stumm", "ton aus", "ruhe"),
            ),
            examples = listOf(
                Example("stumm", mapOf("state" to MUTED)),
                Example("stummschalten", mapOf("state" to MUTED)),
                Example("ton aus", mapOf("state" to MUTED)),
                Example("lautstärke aus", mapOf("state" to MUTED)),
                Example("sei still", mapOf("state" to MUTED)),
                Example("ton an", mapOf("state" to UNMUTED)),
                Example("lautstärke wieder an", mapOf("state" to UNMUTED)),
                Example("nicht mehr stumm", mapOf("state" to UNMUTED)),
            ),
        ),
    )

    override suspend fun onStart(ctx: SockContext) {
        config = SystemConfig(ctx.config)
        _status.value = if (volume.isAvailable) {
            SockStatus.Ready
        } else {
            // Not `Degraded`: there is no reduced service here, there is no stream at all.
            SockStatus.Unavailable(NO_STREAM)
        }
    }

    override suspend fun onStop() {
        config = null
    }

    override suspend fun handle(invocation: CommandInvocation): SockResult {
        if (!volume.isAvailable) return SockResult.Failed(NO_STREAM_SPOKEN)
        val settings = config ?: return SockResult.Failed(NO_STREAM_SPOKEN)
        return when (invocation.commandId) {
            VOLUME -> change(invocation, settings)
            SET_VOLUME -> setTo(invocation.int("level"), settings)
            MUTE -> mute(invocation.textOrNull("state") != MuteState.AUS.spoken, settings)
            else -> SockResult.NotForMe
        }
    }

    /** `system.volume` — a relative move, in whole steps of [SystemConfig.stepPercent]. */
    private fun change(invocation: CommandInvocation, settings: SystemConfig): SockResult {
        val up = invocation.textOrNull("direction") != LEISER
        val steps = (invocation.intOrNull("steps") ?: settings.defaultSteps)
            .coerceIn(1, SystemConfig.MAX_STEPS)

        // Muted and asked for more: unmute first, then raise from wherever that landed (§3).
        // Muted and asked for less is already as quiet as it gets, and saying so is the only
        // way a broken volume path is distinguishable from a working one at the extremes.
        if (volume.isMuted) {
            if (!up) return SockResult.Spoken(ALREADY_QUIET)
            unmute(settings)
        }

        val from = volume.index
        val delta = steps * settings.stepPercent * (if (up) 1 else -1)
        val target = VolumeScale.shift(from, delta, volume.maxIndex, settings.maxVolumePercent)
        if (target == from) return SockResult.Spoken(if (up) ALREADY_LOUD else ALREADY_QUIET)

        volume.setIndex(target)
        beforeMute = null
        return SockResult.Silent
    }

    /** `system.set_volume` — an absolute level in percent. Zero is mute, because it is. */
    private fun setTo(level: Int, settings: SystemConfig): SockResult {
        if (level <= 0) return mute(muted = true, settings = settings)

        val capped = level.coerceAtMost(settings.maxVolumePercent)
        if (volume.isMuted) volume.setMuted(false)
        // At least one index: a level the user named out loud must never round to silence.
        volume.setIndex(VolumeScale.toIndex(capped, volume.maxIndex).coerceAtLeast(1))
        beforeMute = null
        return SockResult.Silent
    }

    /**
     * `system.mute` — and the restore that makes "Lautstärke wieder an" mean something.
     *
     * Muting leaves playback alone: it does not pause Spotify, does not release focus, and does
     * not stop a timer counting. It only makes the device silent.
     */
    private fun mute(muted: Boolean, settings: SystemConfig): SockResult {
        if (muted) {
            if (volume.isMuted || volume.index == 0) return SockResult.Silent
            beforeMute = volume.index
            volume.setMuted(true)
            // Silent, obviously: an acknowledgement would be the last thing the room hears, and
            // it would be Dobby talking over the request to be quiet.
            return SockResult.Silent
        }

        val wasSilent = volume.isMuted || volume.index == 0
        unmute(settings)
        // Already audible: nothing happened, so nothing is said (§4).
        return if (wasSilent) SockResult.Spoken(SOUND_BACK) else SockResult.Silent
    }

    /** Unmutes and puts the stream back where it was, however it came to be silent. */
    private fun unmute(settings: SystemConfig) {
        if (volume.isMuted) volume.setMuted(false)
        if (volume.index > 0) {
            beforeMute = null
            return
        }
        val restored = beforeMute
            ?: VolumeScale.toIndex(settings.unmutePercent, volume.maxIndex)
        volume.setIndex(restored.coerceAtLeast(1))
        beforeMute = null
    }

    companion object {
        const val VOLUME: String = "system.volume"
        const val SET_VOLUME: String = "system.set_volume"
        const val MUTE: String = "system.mute"

        /** The named levels, in percent. The words are in the templates, the numbers are here. */
        const val FULL: Int = 100
        const val LOUD: Int = 80
        const val MEDIUM: Int = 50
        const val QUIET: Int = 20

        /** "viel lauter" — 25 %. "ein bisschen lauter" — 5 %. */
        const val LOUD_STEPS: Int = 5
        const val GENTLE_STEPS: Int = 1

        const val ALREADY_LOUD: String = "Schon ganz laut."
        const val ALREADY_QUIET: String = "Schon ganz leise."
        const val SOUND_BACK: String = "Ton ist wieder an."

        const val NO_STREAM: String = "Keine Lautstärkeregelung"
        const val NO_STREAM_SPOKEN: String = "Ich komme hier an die Lautstärke nicht heran."

        private val LAUTER = Direction.LAUTER.spoken
        private val LEISER = Direction.LEISER.spoken
        private val MUTED = MuteState.AN.spoken
        private val UNMUTED = MuteState.AUS.spoken

        private const val CHANGE_VERBS = "mach|mache|dreh|drehe|stell|stelle"
        private const val SET_VERBS = "mach|mache|dreh|drehe|stell|stelle|setz|setze"
    }
}

/**
 * Which way the volume goes.
 *
 * The enum values are the words the user says, because they are also the `{direction:enum}`
 * candidate set — and a two-word closed set is what makes a bare "lauter" a legitimate
 * single-token template (`socks.specs/README.md` §6).
 */
enum class Direction(val spoken: String) {
    LAUTER("lauter"),
    LEISER("leiser"),
    ;

    companion object {
        val SPOKEN: List<String> = entries.map { it.spoken }
    }
}

/**
 * Muted or not — stated as the *mute* state, not the sound state.
 *
 * `an` means muted, which reads backwards until you remember what the param is called. Every
 * template binds it by its wording, so the German is decided at compile time and never in a
 * handler (`system.specs.md` §4).
 */
enum class MuteState(val spoken: String) {
    AN("an"),
    AUS("aus"),
    ;

    companion object {
        val SPOKEN: List<String> = entries.map { it.spoken }
    }
}
