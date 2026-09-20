package io.dobby.core.sock

/**
 * How relevant a Sock is to a shared command *right now*.
 *
 * Chain dispatch ranks candidates by this, highest first. See `socks.specs/shared-commands.specs.md`.
 */
enum class SockActivity(val rank: Int) {
    /** Currently doing the thing this command would stop or change. */
    ACTIVE(2),

    /** Not doing it, but holds relevant state and could act. */
    IDLE(1),

    /** Nothing to do. Must return [SockResult.NotForMe] without performing I/O. */
    INACTIVE(0),
}

/** Self-reported health of a Sock. Degradation observed by the dispatcher is tracked separately. */
sealed interface SockStatus {
    data object Ready : SockStatus

    data class Degraded(val reason: String) : SockStatus

    data class Unavailable(val reason: String) : SockStatus
}

/** What a Sock hands back to core. Core — never the Sock — turns this into speech and UI state. */
sealed interface SockResult {
    /** Say this, in German. */
    data class Spoken(val text: String) : SockResult

    /** Handled; the side effect is its own feedback. */
    data object Silent : SockResult

    /** Handled; the Sock will speak later via [SockContext.announce]. */
    data object Deferred : SockResult

    data class Failed(val userMessage: String, val cause: Throwable? = null) : SockResult

    /**
     * Asked the user something and is waiting for the answer.
     *
     * Spoken exactly like [Spoken], but core keeps the microphone open and routes the next
     * utterance back to this Sock via [follow].
     */
    data class Asked(val text: String, val follow: FollowUp) : SockResult

    /**
     * Handled, and the conversation with it: core stops listening rather than waiting for more.
     *
     * The mirror of [Asked]. Every other result leaves the microphone open for a few seconds,
     * because the overwhelmingly common thing after one instruction is a second one — "wie spät
     * ist es", then "stell einen Timer auf zehn Minuten" — and making somebody say the wake
     * word between them is the difference between talking to a panel and operating it.
     *
     * This is for the commands where that is wrong. "Gute Nacht" and "danke, das war's" are
     * finished sentences: holding the microphone open after them is a panel that did not take
     * the hint, listening to a room that has stopped addressing it. The other kind is a command
     * that makes the room too loud to listen to — `spotify.play_music` ends its turn because
     * the next few seconds are music, not a follow-up. [text] is spoken first if there is any;
     * null closes the turn without a word, which is what a command answered by its own side
     * effect returns.
     */
    data class Ended(val text: String? = null) : SockResult

    /**
     * "Nothing to do for me here" — pass to the next Sock in the chain.
     *
     * Only legal for a `shared.*` command. Returning it for an exclusive command is a
     * programming error and is reported as [Failed].
     */
    data object NotForMe : SockResult
}

/**
 * How the answer to an [SockResult.Asked] comes back.
 *
 * The templates are compiled at ask time into a scoped palette that lives only for the rest
 * of the turn. They are never registered, so they cannot collide with anything global — which
 * is why a bare `{text}` slot, rejected everywhere else, is legal here.
 */
data class FollowUp(
    /** Where the answer lands. Must be a command id owned by the asking Sock. */
    val commandId: String,
    val templates: List<TemplatePattern>,
    val params: List<ParamSpec> = emptyList(),
    /** Opaque handle on whatever half-built state the Sock is holding. */
    val token: String,
)

/** The type of a command parameter. */
sealed interface ParamType {
    data object Text : ParamType

    data object Integer : ParamType

    data class Enumeration(val values: List<String>) : ParamType
}

data class ParamSpec(
    val name: String,
    val type: ParamType,
    val required: Boolean = true,
    val default: Any? = null,
)

/**
 * An example utterance and the params it must produce.
 *
 * Examples serve three different jobs, and conflating them is a trap.
 *
 * 1. **Tier 1 regression cases** — the utterance tables from the spec, which the template
 *    matcher must resolve and which the registry asserts on every build. The default.
 * 2. **Tier 2 few-shots** ([matchedByTemplates] = false) — paraphrases Tier 1 is deliberately
 *    unable to match, which is the entire reason the LLM tier exists. They go in the generated
 *    prompt and are exempt from the collision gate.
 * 3. **Held-out accuracy cases** ([heldOut] = true) — paraphrases the model is **never shown**
 *    and is measured against on the device.
 *
 * The third exists because an accuracy test over the few-shots measures memorisation, not
 * understanding: the model was handed those exact sentences in its prompt. A held-out case is
 * the same kind of sentence with the crucial difference that nothing has told the model about
 * it. The fallthrough log is where they come from — a logged line gets promoted either into a
 * template or into one of these, and both live in the owning Sock's spec.
 */
data class Example(
    val utterance: String,
    val params: Map<String, Any> = emptyMap(),
    val matchedByTemplates: Boolean = true,
    /**
     * A Tier 2 accuracy case. Never rendered into a prompt; asserted on-device.
     *
     * Implies [matchedByTemplates] = false for the registry's purposes — see [tier2Only] —
     * so an author writes `heldOut = true` alone rather than having to remember both.
     */
    val heldOut: Boolean = false,
) {
    /**
     * True when Tier 1 must **not** match this utterance.
     *
     * Both of the Tier 2 kinds: a few-shot Tier 1 can reach is a template described to the
     * model as though it did not exist, and a held-out case Tier 1 can reach would never get
     * as far as the model to be measured. One property so the registry and the generators ask
     * the question once.
     */
    val tier2Only: Boolean get() = !matchedByTemplates || heldOut
}

/**
 * One template, plus any params it fixes by virtue of its wording.
 *
 * Most templates bind everything through slots. Some cannot: "viel lauter" and "lauter" are the
 * same command with a different step size, and "ton aus" versus "ton an" differ only in which
 * mute state the German implies. Encoding that in [params] keeps the distinction declarative
 * instead of pushing a second round of German parsing into the handler.
 */
data class TemplatePattern(val pattern: String, val params: Map<String, Any> = emptyMap())

/** Plain templates, no fixed params. */
fun patterns(vararg patterns: String): List<TemplatePattern> = patterns.map { TemplatePattern(it) }

/** A template that fixes some params by its wording. */
fun pattern(pattern: String, vararg params: Pair<String, Any>): TemplatePattern =
    TemplatePattern(pattern, params.toMap())

enum class ChainMode {
    /** Stop at the first Sock that consumes. The only mode used in v1. */
    FIRST_CONSUMER,

    /** Offer to every subscriber regardless of who consumes. */
    BROADCAST,
}

sealed interface CommandSpec {
    /** `<sockId>.<name>` for exclusive commands, `shared.<name>` for shared ones. */
    val id: String
    val params: List<ParamSpec>
    val templates: List<TemplatePattern>

    /** One German line; becomes a tool definition in the Tier 2 system prompt. */
    val description: String
    val examples: List<Example>

    /**
     * How this command is explained to a person. Null falls back to [id] and [description].
     *
     * Part of the command interface rather than of any one surface, because the same
     * explanation is read on the panel's help screen and spoken by "erkläre das Kommando …".
     */
    val help: CommandHelp? get() = null
}

/**
 * The human half of a command: what to call it, and what to say about it beyond [CommandSpec.description].
 *
 * What is deliberately *not* here is the usage line. That is derived from the templates by
 * [io.dobby.core.nlu.template.Syntax] — a hand-written one is a copy of the grammar that
 * nothing keeps honest, and the first phrasing somebody deletes makes it a lie.
 *
 * [description] stays the one-liner the Tier 2 prompt renders as a tool definition, which is
 * written for a model and paid for by the token. [detail] is written for a person and costs
 * nothing, so it may say the thing the one-liner had to leave out.
 */
data class CommandHelp(
    /** Short German name: "Timer stellen". Shown as the heading, and what a spoken request for this command is resolved against. */
    val title: String,
    /** A sentence or two of German prose for the help screen. Null falls back to [CommandSpec.description]. */
    val detail: String? = null,
    /** German notes: what is optional, what happens when something is left out. */
    val hints: List<String> = emptyList(),
    /** Other names somebody might use for this command when asking about it out loud. */
    val aliases: List<String> = emptyList(),
)

/** A command owned by exactly one Sock. */
data class ExclusiveCommandSpec(
    override val id: String,
    override val templates: List<TemplatePattern>,
    override val description: String,
    override val params: List<ParamSpec> = emptyList(),
    override val examples: List<Example> = emptyList(),
    override val help: CommandHelp? = null,
) : CommandSpec

/**
 * A command owned by nobody, dispatched down a chain of subscribing Socks.
 *
 * Lives in the [SharedCommands] catalog — plain data, not dispatcher logic.
 */
data class SharedCommandSpec(
    override val id: String,
    override val templates: List<TemplatePattern>,
    override val description: String,
    val unconsumedResponse: SockResult,
    override val params: List<ParamSpec> = emptyList(),
    override val examples: List<Example> = emptyList(),
    override val help: CommandHelp? = null,
    val chainMode: ChainMode = ChainMode.FIRST_CONSUMER,
) : CommandSpec

/** One Sock's participation in one chain. */
data class SharedSubscription(
    val command: SharedCommandSpec,
    /** Breaks ties between Socks reporting the same [SockActivity]. Higher goes first. */
    val priority: Int = 0,
    /** Utterances only this Sock adds to the shared command. */
    val extraTemplates: List<TemplatePattern> = emptyList(),
    val extraExamples: List<Example> = emptyList(),
)

/** A parsed, type-coerced command ready to dispatch. */
data class CommandInvocation(
    val commandId: String,
    val params: Map<String, Any> = emptyMap(),
    /** The [FollowUp.token] this invocation answers, or null for a fresh command. */
    val answering: String? = null,
) {
    val isShared: Boolean get() = commandId.startsWith(SHARED_PREFIX)

    fun text(name: String): String = params[name] as? String
        ?: error("param '$name' of $commandId is not Text")

    fun textOrNull(name: String): String? = params[name] as? String

    fun int(name: String): Int = params[name] as? Int
        ?: error("param '$name' of $commandId is not Integer")

    fun intOrNull(name: String): Int? = params[name] as? Int

    companion object {
        const val SHARED_PREFIX: String = "shared."

        /** Core's own command: no Sock claims this utterance. */
        const val NONE: String = "none"
    }
}
