package io.dobby.pipeline.wakeword

/**
 * Which of the two ways of hearing its own name the panel uses.
 *
 * They are genuinely different trades rather than an implementation detail, which is why this
 * is a setting and not a constant:
 *
 * |                | [CLASSIFIER]                       | [TRANSCRIPT]                          |
 * |----------------|------------------------------------|---------------------------------------|
 * | what runs      | three small ONNX graphs per 80 ms   | Silero VAD, then Parakeet per utterance |
 * | cost           | negligible, forever                 | four threads of ASR whenever the room talks |
 * | the phrase     | whatever somebody trained a head for | anything you can type                |
 * | latency        | mid-phrase, ~200 ms                 | end of phrase + ~0.5–1.5 s            |
 * | in one breath  | no — the phrase, a pause, the command | yes — "Hey Dobby, spiele Musik" is one utterance |
 *
 * The classifier stays the default because it is the one that can run all day on a wall panel
 * without being noticed. [TRANSCRIPT] exists because the classifier's accuracy is a property of
 * a model somebody else trained on English synthetic voices, and when it will not answer to the
 * name you want there is nothing to tune — whereas the recogniser is already loaded, already
 * understands German, and will answer to any phrase you type.
 */
enum class WakeMode {
    /** openWakeWord: a purpose-trained classifier head, scored on every frame. */
    CLASSIFIER,

    /** The command recogniser, run over each utterance and matched against a typed phrase. */
    TRANSCRIPT,

    ;

    companion object {
        /** The cheap one. A panel that listens for a week is a panel that listens cheaply. */
        val DEFAULT: WakeMode = CLASSIFIER

        /** Parses a stored name, falling back to [DEFAULT] for anything unrecognised. */
        fun of(name: String?): WakeMode = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
