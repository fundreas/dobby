package io.dobby.core.nlu.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The hostile table.
 *
 * Every case here is something the grammar should have prevented, which is exactly why the
 * decoder is tested against it: `:core` cannot verify that the native side applied the grammar,
 * and a decoder that only works on grammar-legal input is a decoder that works until the day it
 * matters.
 */
class Tier2JsonTest {

    @Test
    fun `encodes the minimal shape`() {
        assertEquals("""{"c":"shared.stop"}""", Tier2Json.encode("shared.stop"))
        assertEquals(
            """{"c":"clock.set_timer","amount":20,"unit":"minuten"}""",
            Tier2Json.encode("clock.set_timer", linkedMapOf("amount" to 20, "unit" to "minuten")),
        )
        assertEquals("""{"c":"none"}""", Tier2Json.encode("none"))
    }

    @Test
    fun `round-trips everything it encodes`() {
        val reply = Tier2Reply("clock.set_timer", mapOf("amount" to 20, "unit" to "minuten"))
        assertEquals(reply, Tier2Json.decode(Tier2Json.encode(reply)))
    }

    @Test
    fun `tolerates prose around the object, because a grammar that was not applied produces it`() {
        val expected = Tier2Reply("shared.stop", emptyMap())
        assertEquals(expected, Tier2Json.decode("""Sicher! {"c":"shared.stop"}"""))
        assertEquals(expected, Tier2Json.decode("""{"c":"shared.stop"} — fertig."""))
        assertEquals(expected, Tier2Json.decode("""<think></think>{"c":"shared.stop"}"""))
        assertEquals(expected, Tier2Json.decode("  \n\t {\"c\" : \"shared.stop\"}  \n"))
    }

    @Test
    fun `drops nulls, so ParamCoercion can apply the spec's own default`() {
        assertEquals(
            Tier2Reply("clock.set_timer", mapOf("amount" to 10)),
            Tier2Json.decode("""{"c":"clock.set_timer","amount":10,"unit":null}"""),
        )
    }

    @Test
    fun `rejects what the flat shape has no meaning for`() {
        // Nested objects: the shape is flat, and a nested one is a different protocol.
        assertNull(Tier2Json.decode("""{"c":"x","params":{"amount":5}}"""))
        // Arrays: no param is a list.
        assertNull(Tier2Json.decode("""{"c":"x","amount":[1,2]}"""))
        // Floats: no param is one, and truncating silently is a timer of the wrong length.
        assertNull(Tier2Json.decode("""{"c":"x","amount":2.5}"""))
        assertNull(Tier2Json.decode("""{"c":"x","amount":1e3}"""))
        // Duplicate keys: two answers in one object. Taking the last is what a JSON library
        // does and what a command router must not.
        assertNull(Tier2Json.decode("""{"c":"a","c":"b"}"""))
        assertNull(Tier2Json.decode("""{"c":"x","amount":1,"amount":2}"""))
    }

    @Test
    fun `rejects the malformed without throwing`() {
        for (hostile in listOf(
            "",
            "   ",
            "kein Befehl passt",
            "{",
            "}",
            """{"c":}""",
            """{"c":"x" """,
            """{"c":"x",}""",
            """{"c" "x"}""",
            """{}""",
            """{"amount":5}""",
            """{"c":5}""",
            """{"c":""}""",
            """{"c":true}""",
            """{"c":"x" "y":1}""",
            """{"c":"x"} {"c":"y"}garbage{""",
            "{\"c\":\"x\",\"t\":\"" + "a".repeat(41) + "\"}",
            "{\"c\":\"" + "a".repeat(3000) + "\"}",
        )) {
            // Never throws is half the contract; the other half is that nothing malformed
            // becomes a command.
            val decoded = Tier2Json.decode(hostile)
            if (hostile == """{"c":"x"} {"c":"y"}garbage{""") {
                // The first balanced object wins and the rest is ignored — that one IS legal.
                assertEquals(Tier2Reply("x", emptyMap()), decoded)
            } else {
                assertNull(decoded, "\"$hostile\" decoded to $decoded")
            }
        }
    }

    @Test
    fun `handles escapes the grammar forbids, because it cannot check that it was applied`() {
        assertEquals(
            Tier2Reply("x", mapOf("q" to """a"b""")),
            Tier2Json.decode("""{"c":"x","q":"a\"b"}"""),
        )
        assertEquals(
            Tier2Reply("x", mapOf("q" to "ä")),
            Tier2Json.decode("""{"c":"x","q":"ä"}"""),
        )
        assertNull(Tier2Json.decode("""{"c":"x","q":"\q"}"""))
        assertNull(Tier2Json.decode("""{"c":"x","q":"\u00"}"""))
    }

    @Test
    fun `negative integers survive, because a param spec may one day want one`() {
        assertEquals(Tier2Reply("x", mapOf("n" to -5)), Tier2Json.decode("""{"c":"x","n":-5}"""))
        assertNull(Tier2Json.decode("""{"c":"x","n":-}"""))
    }
}
