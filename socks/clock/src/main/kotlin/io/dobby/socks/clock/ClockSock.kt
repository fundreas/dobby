package io.dobby.socks.clock

import io.dobby.core.sock.CommandInvocation
import io.dobby.core.sock.Example
import io.dobby.core.sock.ExclusiveCommandSpec
import io.dobby.core.sock.Sock
import io.dobby.core.sock.SockContext
import io.dobby.core.sock.SockResult
import io.dobby.core.sock.patterns
import java.time.Clock
import java.time.LocalTime

/**
 * Clock — time of day.
 *
 * The first product Sock. It needs no network, no account and no audio, which makes it the
 * cheapest possible proof that the registry, palette and dispatcher compose with something
 * real rather than only with fixtures.
 *
 * Specified in `socks.specs/clock.specs.md`. That spec also defines `set_timer` and
 * `cancel_timer` and a `shared.stop` subscription; those are **not implemented yet**, and
 * this Sock deliberately does not declare them — a command that is declared but unhandled is
 * worse than one that is absent, because the palette would advertise it.
 *
 * @param clock injected so tests are deterministic; on the device this stays the default.
 */
class ClockSock(
    private val clock: Clock = Clock.systemDefaultZone(),
    private val screenWakeSeconds: Int = DEFAULT_SCREEN_WAKE_SECONDS,
) : Sock {

    override val id: String = "clock"

    override val displayName: String = "Uhr"

    /**
     * Held from [onStart] so [handle] can reach the screen.
     *
     * Not passed into `handle` by design: a Sock's context is service-lifetime, while an
     * invocation is a single utterance.
     */
    private var context: SockContext? = null

    override val commands: List<ExclusiveCommandSpec> = listOf(
        ExclusiveCommandSpec(
            id = WHATS_THE_TIME,
            templates = patterns(
                "wie (spät|viel uhr) ist es",
                "wie spät",
                "was ist die uhrzeit",
                "sag (mir)? (die)? uhrzeit",
                "(uhrzeit|die uhrzeit)",
            ),
            description = "Sagt die aktuelle Uhrzeit.",
            examples = listOf(
                Example("wie spät ist es"),
                Example("wie viel uhr ist es"),
                Example("wie spät"),
                Example("uhrzeit"),
                Example("sag mir die uhrzeit"),
                // Paraphrases Tier 1 is meant to miss — few-shots for the LLM tier (M6).
                Example("kannst du mir sagen wie spät es ist", matchedByTemplates = false),
                Example("hast du die genaue zeit", matchedByTemplates = false),
            ),
        ),
    )

    override suspend fun onStart(ctx: SockContext) {
        context = ctx
    }

    override suspend fun onStop() {
        context = null
    }

    override suspend fun handle(invocation: CommandInvocation): SockResult =
        when (invocation.commandId) {
            WHATS_THE_TIME -> {
                // The dashboard clock is the visual half of the answer, so wake the screen
                // even though the spoken reply stands on its own.
                context?.screen?.wakeFor(screenWakeSeconds)
                SockResult.Spoken(GermanTime.speak(LocalTime.now(clock)))
            }

            // Unreachable in practice: the dispatcher only routes commands this Sock owns.
            // Reported as a bug rather than silently swallowed.
            else -> SockResult.NotForMe
        }

    companion object {
        const val WHATS_THE_TIME: String = "clock.whats_the_time"
        const val DEFAULT_SCREEN_WAKE_SECONDS: Int = 30
    }
}
