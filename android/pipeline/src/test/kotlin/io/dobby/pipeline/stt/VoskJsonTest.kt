package io.dobby.pipeline.stt

import kotlin.test.Test
import kotlin.test.assertEquals

class VoskJsonTest {

    @Test
    fun `reads the shape vosk actually emits`() {
        // Vosk pads its keys with spaces, so a naive `"text":` lookup finds nothing.
        assertEquals("wie spät ist es", VoskJson.stringField("""{"text" : "wie spät ist es"}""", "text"))
        assertEquals("wie spät", VoskJson.stringField("""{"partial" : "wie spät"}""", "partial"))
    }

    @Test
    fun `an absent or empty field is empty text, never null`() {
        assertEquals("", VoskJson.stringField("""{"partial" : ""}""", "text"))
        assertEquals("", VoskJson.stringField("""{"text" : ""}""", "text"))
        assertEquals("", VoskJson.stringField("{}", "text"))
    }

    @Test
    fun `a non-string value yields empty rather than garbage`() {
        assertEquals("", VoskJson.stringField("""{"text" : null}""", "text"))
        assertEquals("", VoskJson.stringField("""{"result" : [], "text" : 7}""", "text"))
    }

    @Test
    fun `unescapes what a transcript can contain`() {
        assertEquals("sag \"hallo\"", VoskJson.stringField("""{"text" : "sag \"hallo\""}""", "text"))
        assertEquals("a\nb", VoskJson.stringField("""{"text" : "a\nb"}""", "text"))
        assertEquals("spät", VoskJson.stringField("""{"text" : "spät"}""", "text"))
    }

    @Test
    fun `is not fooled by the field name appearing inside a value`() {
        assertEquals(
            "wanted",
            VoskJson.stringField("""{"note" : "\"text\" is here", "text" : "wanted"}""", "text"),
        )
    }

    @Test
    fun `truncated input does not throw`() {
        assertEquals("wie sp", VoskJson.stringField("""{"text" : "wie sp""", "text"))
        assertEquals("", VoskJson.stringField("""{"text" """, "text"))
    }
}
