package io.dobby.android.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TranscriptTest {

    @Test
    fun `the live bubble becomes the transcript in place`() {
        // Parakeet answers once, for the whole utterance, so the bubble is empty until it
        // does. That it is replaced rather than appended to is what keeps the list from
        // jumping under someone's eyes at the moment they are reading it.
        val transcript = Transcript()
        transcript.beginListening()

        val live = transcript.messages.value.single()
        assertTrue(live.live)
        assertEquals("", live.text)

        transcript.heard("wie spät ist es")

        val settled = transcript.messages.value.single()
        assertFalse(settled.live)
        assertEquals("wie spät ist es", settled.text)
    }

    @Test
    fun `saying nothing leaves no trace`() {
        val transcript = Transcript()
        transcript.said("Es ist halb 3.")
        transcript.beginListening()
        transcript.abandonListening()

        assertEquals(listOf("Es ist halb 3."), transcript.messages.value.map { it.text })
    }

    @Test
    fun `an answer replaces a live bubble rather than sitting under it`() {
        val transcript = Transcript()
        transcript.beginListening()
        transcript.said("Das habe ich nicht verstanden.")

        val only = transcript.messages.value.single()
        assertEquals(Voice.DOBBY, only.voice)
    }

    @Test
    fun `the answer carries the command that produced it`() {
        val transcript = Transcript()
        transcript.said("Es ist halb 3.", detail = "clock.whats_the_time · clock CONSUMED")

        assertEquals("clock.whats_the_time · clock CONSUMED", transcript.messages.value.single().detail)
    }

    @Test
    fun `scrollback is bounded because the panel runs for weeks`() {
        val transcript = Transcript(limit = 4)
        repeat(10) { transcript.said("Antwort $it") }

        val texts = transcript.messages.value.map { it.text }
        assertEquals(listOf("Antwort 6", "Antwort 7", "Antwort 8", "Antwort 9"), texts)
    }
}
