package io.dobby.core.testing

/**
 * Sentences a panel must answer "no command" to.
 *
 * These belong to no Sock, which is why they live here rather than in anybody's spec: they are
 * the things people say in a kitchen *without* addressing the panel, and the whole point is
 * that no Sock claims them.
 *
 * **They are the half of the accuracy test that matters.** A model that resolves everything is
 * worse than no model: one that turns "wie wird das Wetter morgen" into a timer has made the
 * panel unpredictable, and unpredictable is the one thing a wall panel may not be. The
 * held-out paraphrases say the tier is useful; this list says it is safe, and the number to
 * watch is how many of these come back as a command rather than as `none`.
 *
 * Written the way somebody actually talks, including the ones that start with a word a command
 * also starts with — "mach" and "spiel" and "stell" are in here on purpose, because a model
 * that keys off the first word will pass a politer list and fail this one.
 */
object Tier2Negatives {

    /** Normalized, like everything else Tier 2 sees. */
    val all: List<String> = listOf(
        // Ordinary questions a panel of this shape simply cannot answer.
        "wie wird das wetter morgen",
        "erzähl mir einen witz",
        "was kostet ein liter milch",
        "wie alt bist du eigentlich",
        "wer hat gestern gewonnen",

        // Talking to a person in the room, not to the panel.
        "kannst du mal eben die tür aufmachen",
        "hast du den müll schon rausgebracht",
        "ich geh kurz runter zum bäcker",
        "das riecht aber gut",
        "wo hast du die schlüssel hingelegt",

        // Starts like a command and is not one. A model that keys off the first word fails here.
        "mach dir keinen kopf",
        "spiel dich nicht so auf",
        "stell dir mal vor was gestern passiert ist",
        "sag mal hast du das gesehen",
        "hör mal wer da spricht",
    )
}
