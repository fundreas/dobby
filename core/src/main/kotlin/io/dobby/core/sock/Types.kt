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
     * "Nothing to do for me here" — pass to the next Sock in the chain.
     *
     * Only legal for a `shared.*` command. Returning it for an exclusive command is a
     * programming error and is reported as [Failed].
     */
    data object NotForMe : SockResult
}

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
 * Examples serve two different jobs, and conflating them is a trap. Most are **Tier 1
 * regression cases**: the utterance tables from the spec, which the template matcher must
 * resolve, and which the registry asserts on every build. Some are **Tier 2 few-shots**:
 * paraphrases that Tier 1 is deliberately unable to match — that is the entire reason the LLM
 * tier exists. Marking the latter [matchedByTemplates] = false keeps them in the generated
 * system prompt without failing the collision gate.
 */
data class Example(
    val utterance: String,
    val params: Map<String, Any> = emptyMap(),
    val matchedByTemplates: Boolean = true,
)

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
}

/** A command owned by exactly one Sock. */
data class ExclusiveCommandSpec(
    override val id: String,
    override val templates: List<TemplatePattern>,
    override val description: String,
    override val params: List<ParamSpec> = emptyList(),
    override val examples: List<Example> = emptyList(),
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
