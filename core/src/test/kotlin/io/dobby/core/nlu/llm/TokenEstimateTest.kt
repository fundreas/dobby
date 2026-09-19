package io.dobby.core.nlu.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The estimator is allowed to be wrong, in one direction.
 *
 * Its whole job is to fail the JVM build before the prompt outgrows the context window, so an
 * over-count costs a slightly early failure and an under-count costs a prompt that is truncated
 * on a phone with no test to say so. `TokenEstimateCalibrationTest` on the device asserts the
 * same property against the real `llama_tokenize`; this is the part that can run on a laptop.
 */
class TokenEstimateTest {

    @Test
    fun `counts nothing as nothing`() {
        assertEquals(0, TokenEstimate.of(""))
        assertEquals(0, TokenEstimate.of("     "))
    }

    @Test
    fun `a German compound costs more than a short word`() {
        // The case that makes words × 1.3 wrong: "Lautstärke" is one word and several tokens.
        assertTrue(TokenEstimate.of("lautstärke") > TokenEstimate.of("ton"))
        assertTrue(TokenEstimate.of("radiosender") > TokenEstimate.of("radio"))
    }

    @Test
    fun `punctuation and newlines are counted, spaces are not`() {
        // The prompt is mostly newlines and JSON punctuation, so this is most of the estimate.
        assertTrue(TokenEstimate.of("{\"c\":\"x\"}") >= 7)
        // A space between two words costs nothing: BPE absorbs it into the next token.
        assertEquals(TokenEstimate.of("abcd"), TokenEstimate.of("ab cd"))
        assertTrue(TokenEstimate.of("a\nb") > TokenEstimate.of("a b"))
    }

    @Test
    fun `digits are counted one per token, because a timer amount is short`() {
        assertEquals(4, TokenEstimate.of("3600"))
    }

    @Test
    fun `it over-counts rather than under-counts on real prompt text`() {
        // A BPE tokenizer for German averages nearer four characters per token than three, so
        // the estimate should sit comfortably above a character-count-over-four floor.
        val line = "- clock.set_timer: Stellt einen Timer für eine bestimmte Dauer."
        assertTrue(
            TokenEstimate.of(line) > line.length / 4,
            "estimate ${TokenEstimate.of(line)} is below the ${line.length / 4} floor",
        )
    }
}
