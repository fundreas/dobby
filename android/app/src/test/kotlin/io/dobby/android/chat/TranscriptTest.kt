package io.dobby.android.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TranscriptTest {

    @Test
    fun `a partial transcript becomes the final one in place`() {
        val transcript = Transcript()
        transcript.beginListening()
        transcript.partial("wie")
        transcript.partial("wie spät")

        val live = transcript.messages.value.single()
        assertTrue(live.live)
        assertEquals("wie spät", live.text)

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
        transcript.partial("ähm")
        transcript.abandonListening()

        assertEquals(listOf("Es ist halb 3."), transcript.messages.value.map { it.text })
    }

    @Test
    fun `a partial arriving after the live bubble is gone is ignored`() {
        // Vosk runs on the audio thread and the mirror job is cancelled from another; a late
        // frame must not resurrect a bubble or overwrite the answer that replaced it.
        val transcript = Transcript()
        transcript.beginListening()
        transcript.heard("wie spät ist es")
        transcript.said("Es ist halb 3.")

        transcript.partial("wie spät ist es dann")

        assertEquals(
            listOf("wie spät ist es", "Es ist halb 3."),
            transcript.messages.value.map { it.text },
        )
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
    fun `ids are stable so the list does not re-animate on every partial`() {
        val transcript = Transcript()
        transcript.beginListening()
        transcript.partial("wie")
        val first = transcript.messages.value.single().id
        transcript.partial("wie spät")
        assertEquals(first, transcript.messages.value.single().id)
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
