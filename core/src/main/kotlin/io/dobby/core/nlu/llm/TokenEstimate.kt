package io.dobby.core.nlu.llm

/**
 * A stand-in tokenizer, deliberately pessimistic.
 *
 * `:core` has no tokenizer and must not acquire one: the real count depends on the GGUF's own
 * BPE vocabulary, which lives on the device behind `llama_tokenize`. But the budget tripwire
 * ([PromptGenerator.MAX_TOKENS]) has to fail the **JVM** build, on a laptop, the moment somebody
 * adds the Sock that pushes the prompt over the context window — a tripwire that only fires on a
 * phone is a tripwire nobody trips until it is too late to be cheap.
 *
 * So this over-counts on purpose. Every direction it is wrong in is the safe one: a prompt this
 * estimator calls 1400 tokens is genuinely under 1500, and a prompt it calls 1600 might have
 * been fine. `TokenEstimateCalibrationTest` on the device asserts the one property that matters
 * — that it never *under*-counts against the real tokenizer — and prints the ratio so the
 * pessimism can be tightened with a measurement rather than a guess.
 *
 * The model is roughly BPE-shaped: a run of letters becomes a token per [CHARS_PER_TOKEN]
 * characters, digits tokenize one or two at a time, and every other character is its own token.
 * German compounds ("Lautstärke", "Radiosender") are what make a naive words-times-1.3 estimate
 * wrong here, and they are exactly what the per-character rule handles.
 */
object TokenEstimate {

    /**
     * Characters per token inside a word. Real German BPE averages nearer 4; 3 is the margin.
     */
    const val CHARS_PER_TOKEN: Int = 3

    /** Digits are rarely merged beyond pairs, and a timer amount is short enough not to be. */
    const val CHARS_PER_DIGIT_TOKEN: Int = 1

    fun of(text: String): Int {
        var tokens = 0
        var index = 0
        while (index < text.length) {
            val c = text[index]
            when {
                c.isLetter() -> {
                    val start = index
                    while (index < text.length && text[index].isLetter()) index++
                    tokens += ceilDiv(index - start, CHARS_PER_TOKEN)
                }

                c.isDigit() -> {
                    val start = index
                    while (index < text.length && text[index].isDigit()) index++
                    tokens += ceilDiv(index - start, CHARS_PER_DIGIT_TOKEN)
                }

                // A run of spaces is usually absorbed into the next token, but a newline is
                // almost always its own, and the prompt is mostly newlines.
                c == ' ' -> index++

                else -> {
                    tokens++
                    index++
                }
            }
        }
        return tokens
    }

    private fun ceilDiv(a: Int, b: Int): Int = (a + b - 1) / b
}
