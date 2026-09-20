package io.dobby.socks.conversation

import io.dobby.core.sock.CommandHelp
import io.dobby.core.sock.CommandInvocation
import io.dobby.core.sock.Example
import io.dobby.core.sock.ExclusiveCommandSpec
import io.dobby.core.sock.Sock
import io.dobby.core.sock.SockResult
import io.dobby.core.sock.patterns

/**
 * Conversation — "OK", and Dobby goes back to waiting for its name.
 *
 * A turn does not end when one instruction has been carried out: the microphone stays open for
 * a few seconds, because the common thing after one instruction is a second one
 * (`dobby-plan.md` §5.1). The cost is the other case — somebody who is finished standing in
 * front of an open microphone, waiting for it to notice. "OK" is what a person says there
 * anyway, so it is what ends the turn.
 *
 * Being dismissed is a capability, so it is a Sock rather than a special case in core — the
 * same argument the Help Sock makes for discovery. Core still knows no commands; what it knows
 * is [SockResult.Ended], which has existed since M1 with nothing returning it. This is the
 * first Sock that does.
 *
 * It is the mirror of the wake word: that opens a turn with no Sock involved, this closes one
 * with the smallest Sock in the repo. See `socks.specs/conversation.specs.md`.
 */
class ConversationSock : Sock {

    override val id: String = "conversation"

    override val displayName: String = "Gespräch"

    override val commands: List<ExclusiveCommandSpec> = listOf(
        ExclusiveCommandSpec(
            id = DISMISS,
            templates = patterns(
                // Single-keyword templates, and the spec (§5) says why they are legitimate
                // here: "ok" is two characters, so tolerance is 0; "okay" is four with no
                // German neighbour at distance 1; "danke" has two, but neither is a sentence
                // anybody says alone — and this is the one command where a false positive
                // costs a wake word rather than an action. The registry logs all three, and
                // that log line is expected rather than a warning to be fixed.
                "(ok|okay|oke|okey)",
                "(alles klar|alles gut)",
                "(danke|danke dir|danke schön|dankeschön|vielen dank)",
                "(danke )?(das war's|das wars|das wär's|das wärs|das ist alles)",
                // Bare "passt" and bare "klar" are deliberately absent: at tolerance 1 they
                // also answer to "fasst", "hasst", "lasst" and "kar".
                "(schon gut|passt schon|passt so|lass gut sein)",
            ),
            description = "Beendet das Gespräch, ohne sonst etwas zu tun.",
            help = CommandHelp(
                title = "Gespräch beenden",
                detail = "Nach einem Befehl bleibt das Mikrofon ein paar Sekunden offen, weil " +
                    "meistens noch etwas kommt. Das hier sagt: es kommt nichts mehr. Ich " +
                    "antworte nicht, ich höre auf zuzuhören.",
                aliases = listOf("gespräch beenden", "danke", "ok"),
            ),
            examples = listOf(
                Example("ok"),
                Example("okay"),
                Example("alles klar"),
                Example("danke"),
                Example("danke dir"),
                Example("vielen dank"),
                Example("das war's"),
                Example("danke das war's"),
                Example("passt schon"),
                Example("schon gut"),
                // Tier 2 few-shots: dismissals Tier 1 is meant to miss, because listing every
                // way of saying "I am finished" as a template is a losing game.
                Example("ja gut dann lass mal", matchedByTemplates = false),
                Example("du kannst wieder schlafen", matchedByTemplates = false),
                // Held out: never shown to the model, asserted on the device.
                Example("ich brauche sonst nichts mehr", heldOut = true),
                Example("gut dann bis später", heldOut = true),
            ),
        ),
    )

    /**
     * Returns [SockResult.Ended] and nothing else — the whole Sock is its result type.
     *
     * Null text on purpose: an answer to "OK" is a sentence nobody wanted, played into a room
     * that has just stopped addressing the panel, and it would hold the turn open for as long
     * as it takes to say. Going quiet is the acknowledgement.
     */
    override suspend fun handle(invocation: CommandInvocation): SockResult =
        when (invocation.commandId) {
            DISMISS -> SockResult.Ended()

            else -> SockResult.NotForMe
        }

    companion object {
        const val DISMISS: String = "conversation.dismiss"
    }
}
