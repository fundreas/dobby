package io.dobby.socks.calculator

import io.dobby.core.nlu.Normalizer
import io.dobby.core.registry.SockRegistry
import io.dobby.core.sock.CommandInvocation
import io.dobby.socks.clock.ClockSock
import io.dobby.socks.help.HelpSock
import io.dobby.socks.winky.WinkySock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The utterance tables from `calculator.specs.md`, as tests.
 *
 * The input is raw German, so the normalizer's "drei" → `3` is part of what is asserted —
 * templates are written against normalized text and nothing else.
 */
class CalculatorTemplatesTest {

    private val palette = SockRegistry.buildOrThrow(listOf(CalculatorSock())).palette

    private fun resolve(utterance: String): CommandInvocation? =
        palette.match(Normalizer.tokenize(utterance))?.invocation

    private fun assertResolves(utterance: String, commandId: String, params: Map<String, Any> = emptyMap()) {
        val invocation = resolve(utterance)
        assertEquals(commandId, invocation?.commandId, utterance)
        if (params.isNotEmpty()) assertEquals(params, invocation?.params, utterance)
    }

    private fun sum(a: Int, op: Operation, b: Int) =
        mapOf<String, Any>("a" to a, "op" to op.value, "b" to b)

    private fun step(op: Operation, b: Int) = mapOf<String, Any>("op" to op.value, "b" to b)

    @Test
    fun `a sum said in one breath, with and without a run-up`() {
        assertResolves("3 plus 5", CalculatorSock.CALCULATE, sum(3, Operation.PLUS, 5))
        assertResolves("Wie viel ist 3 plus 5", CalculatorSock.CALCULATE, sum(3, Operation.PLUS, 5))
        assertResolves("Was ist 12 minus 4", CalculatorSock.CALCULATE, sum(12, Operation.MINUS, 4))
        assertResolves("Rechne 7 mal 8", CalculatorSock.CALCULATE, sum(7, Operation.TIMES, 8))
        assertResolves("Berechne mir 7 mal 8", CalculatorSock.CALCULATE, sum(7, Operation.TIMES, 8))
        assertResolves("Was ergibt denn 6 mal 7", CalculatorSock.CALCULATE, sum(6, Operation.TIMES, 7))
        assertResolves("Sag mir mal was 6 mal 7 ist", CalculatorSock.CALCULATE, sum(6, Operation.TIMES, 7))
    }

    @Test
    fun `every operator the spec lists`() {
        assertResolves("3 plus 5", CalculatorSock.CALCULATE, sum(3, Operation.PLUS, 5))
        assertResolves("3 addiert zu 5", CalculatorSock.CALCULATE, sum(3, Operation.PLUS, 5))
        assertResolves("9 minus 4", CalculatorSock.CALCULATE, sum(9, Operation.MINUS, 4))
        assertResolves("9 weniger 4", CalculatorSock.CALCULATE, sum(9, Operation.MINUS, 4))
        assertResolves("6 mal 7", CalculatorSock.CALCULATE, sum(6, Operation.TIMES, 7))
        assertResolves("6 multipliziert mit 7", CalculatorSock.CALCULATE, sum(6, Operation.TIMES, 7))
        assertResolves("20 durch 4", CalculatorSock.CALCULATE, sum(20, Operation.DIVIDE, 4))
        assertResolves("20 geteilt durch 4", CalculatorSock.CALCULATE, sum(20, Operation.DIVIDE, 4))
        assertResolves("20 dividiert durch 4", CalculatorSock.CALCULATE, sum(20, Operation.DIVIDE, 4))
        assertResolves("2 hoch 10", CalculatorSock.CALCULATE, sum(2, Operation.POWER, 10))
        assertResolves("2 potenziert mit 10", CalculatorSock.CALCULATE, sum(2, Operation.POWER, 10))
        assertResolves("17 modulo 5", CalculatorSock.CALCULATE, sum(17, Operation.MODULO, 5))
        assertResolves("17 mod 5", CalculatorSock.CALCULATE, sum(17, Operation.MODULO, 5))
    }

    @Test
    fun `number words are digits by the time a template sees them`() {
        assertResolves("drei plus fünf", CalculatorSock.CALCULATE, sum(3, Operation.PLUS, 5))
        assertResolves("Wie viel ist zwanzig geteilt durch vier", CalculatorSock.CALCULATE, sum(20, Operation.DIVIDE, 4))
        assertResolves("zweihundert mal drei", CalculatorSock.CALCULATE, sum(200, Operation.TIMES, 3))
    }

    @Test
    fun `wordings that fix the second operand carry no slot for it`() {
        assertResolves("Die Hälfte von 17", CalculatorSock.CALCULATE, sum(17, Operation.DIVIDE, 2))
        assertResolves("Was ist die Hälfte von 17", CalculatorSock.CALCULATE, sum(17, Operation.DIVIDE, 2))
        assertResolves("Das Doppelte von 21", CalculatorSock.CALCULATE, sum(21, Operation.TIMES, 2))
        assertResolves("9 zum Quadrat", CalculatorSock.CALCULATE, sum(9, Operation.POWER, 2))
    }

    @Test
    fun `the two questions a division answers are two different commands`() {
        // Remainder only.
        assertResolves("17 modulo 5", CalculatorSock.CALCULATE, sum(17, Operation.MODULO, 5))
        assertResolves("Der Rest von 17 geteilt durch 5", CalculatorSock.CALCULATE, sum(17, Operation.MODULO, 5))
        assertResolves("Was bleibt von 17 durch 5 übrig", CalculatorSock.CALCULATE, sum(17, Operation.MODULO, 5))
        // Count and remainder.
        assertResolves("Wie oft passt 5 in 17", CalculatorSock.CALCULATE, sum(17, Operation.INT_DIVIDE, 5))
        assertResolves("Wie oft geht 250 in 1000 rein", CalculatorSock.CALCULATE, sum(1000, Operation.INT_DIVIDE, 250))
        assertResolves(
            "1000 ganzzahlig geteilt durch 250",
            CalculatorSock.CALCULATE,
            sum(1000, Operation.INT_DIVIDE, 250),
        )
    }

    @Test
    fun `a sum missing its last number still matches - it becomes a question, not a dead end`() {
        assertResolves("Was ist 3 plus", CalculatorSock.CALCULATE, mapOf("a" to 3, "op" to Operation.PLUS.value))
        assertResolves("1000 geteilt durch", CalculatorSock.CALCULATE, mapOf("a" to 1000, "op" to Operation.DIVIDE.value))
    }

    @Test
    fun `a sum that names both numbers is never demoted to the operand-less form`() {
        // The short forms sit last, but ordering is not what saves them: a template match is
        // anchored at both ends, so "3 plus 5" cannot be consumed by "3 plus" at all.
        for (utterance in listOf("3 plus 5", "1000 geteilt durch 4", "2 hoch 10", "17 modulo 5")) {
            assertEquals(3, resolve(utterance)?.params?.size, "$utterance lost its second operand")
        }
    }

    @Test
    fun `continuing from the last result`() {
        assertResolves("Mal 2", CalculatorSock.CONTINUE_WITH, step(Operation.TIMES, 2))
        assertResolves("Und jetzt mal 2", CalculatorSock.CONTINUE_WITH, step(Operation.TIMES, 2))
        assertResolves("Und davon mal 2", CalculatorSock.CONTINUE_WITH, step(Operation.TIMES, 2))
        assertResolves("Plus 5", CalculatorSock.CONTINUE_WITH, step(Operation.PLUS, 5))
        assertResolves("Und dann durch 3", CalculatorSock.CONTINUE_WITH, step(Operation.DIVIDE, 3))
        assertResolves("Hoch 2", CalculatorSock.CONTINUE_WITH, step(Operation.POWER, 2))
        assertResolves("Modulo 3", CalculatorSock.CONTINUE_WITH, step(Operation.MODULO, 3))
        assertResolves("Und davon die Hälfte", CalculatorSock.CONTINUE_WITH, step(Operation.DIVIDE, 2))
        assertResolves("Das Doppelte", CalculatorSock.CONTINUE_WITH, step(Operation.TIMES, 2))
        assertResolves("Und das im Quadrat", CalculatorSock.CONTINUE_WITH, step(Operation.POWER, 2))
        assertResolves("Und wie oft passt da 250 rein", CalculatorSock.CONTINUE_WITH, step(Operation.INT_DIVIDE, 250))
    }

    @Test
    fun `a continuation never swallows a sum that names both numbers`() {
        assertResolves("250 mal 4", CalculatorSock.CALCULATE, sum(250, Operation.TIMES, 4))
        assertResolves("Wie oft passt 250 in 1000", CalculatorSock.CALCULATE, sum(1000, Operation.INT_DIVIDE, 250))
    }

    @Test
    fun `asking for the last result, and forgetting it`() {
        for (utterance in listOf(
            "Was war das Ergebnis",
            "Wie war das Ergebnis nochmal",
            "Wie viel war das nochmal",
            "Wie war das nochmal",
            "Was kam raus",
            "Was kam dabei raus",
            "Das Ergebnis bitte",
            "Sag mir nochmal das Ergebnis",
            "Was hab ich zuletzt gerechnet",
        )) {
            assertResolves(utterance, CalculatorSock.LAST_RESULT)
        }
        for (utterance in listOf(
            "Vergiss das Ergebnis",
            "Vergiss die Zahl",
            "Lösche die Rechnung",
            "Rechner zurücksetzen",
            "Fang neu an",
            "Von vorne anfangen",
        )) {
            assertResolves(utterance, CalculatorSock.CLEAR)
        }
    }

    @Test
    fun `things that are not sums stay unmatched`() {
        assertNull(resolve("guten morgen"))
        assertNull(resolve("drei"))
        assertNull(resolve("plus"))
        assertNull(resolve("wie viel ist das"))
    }

    @Test
    fun `the one false positive this Sock knowingly accepts`() {
        // "hoch" is four characters, so tolerance 1 hands it "noch", "doch" and "koch"
        // (`socks.specs/README.md` §6, and §7 of this Sock's spec). Pinned rather than hidden:
        // whoever changes the operator templates should see this move.
        //
        // It is survivable because a match is anchored at both ends — "noch zwei Minuten" and
        // every other real use of the word sits inside a longer sentence and cannot reach the
        // template — and because what it produces is a question, not an action.
        assertResolves("noch 2", CalculatorSock.CONTINUE_WITH, step(Operation.POWER, 2))
        assertNull(resolve("noch 2 Minuten"))
        assertNull(resolve("das war 2"))
        assertNull(resolve("und 5"))
        assertNull(resolve("mal sehen"))
    }

    @Test
    fun `two operators in one breath are not matched, and that is the honest answer`() {
        // Neither of Dobby's grammars is an *expression* grammar: the template DSL compiles
        // to a flat tree with no production a slot can expand into, and Tier 2's GBNF
        // constrains the reply's shape, not the utterance's. Precedence needs a parse tree
        // over the utterance and nothing builds one — so a wall panel that silently picks an
        // association order is worse than one that says it did not understand (§12).
        assertNull(resolve("3 plus 5 mal 2"))
        assertNull(resolve("wie viel ist 3 plus 5 mal 2"))
    }

    @Test
    fun `registers cleanly and every declared example resolves to it`() {
        val registry = SockRegistry.buildOrThrow(listOf(CalculatorSock()))
        assertEquals(emptyList(), registry.checkExamples())
    }

    @Test
    fun `claims no template a single keyword can satisfy`() {
        // "hälfte", "quadrat" and "doppelte" all sit inside the 4–7 character band where
        // tolerance 1 has neighbours, so each is written with a word in front of it
        // (`socks.specs/README.md` §6). This asserts none slipped back out.
        val registry = SockRegistry.buildOrThrow(listOf(CalculatorSock()))
        assertEquals(emptyList(), registry.checkSingleKeywordTemplates())
    }

    @Test
    fun `composes with the Socks it ships beside`() {
        // The collision gate: every Sock's own examples, run through the palette they all share.
        val registry = SockRegistry.buildOrThrow(
            listOf(CalculatorSock(), ClockSock(), WinkySock(), HelpSock { null }),
        )
        assertEquals(emptyList(), registry.checkExamples())
    }

    @Test
    fun `leaves the clock's utterances to the clock`() {
        val shared = SockRegistry.buildOrThrow(listOf(CalculatorSock(), ClockSock())).palette

        fun owner(utterance: String) = shared.match(Normalizer.tokenize(utterance))?.invocation?.commandId

        // "Timer auf 10" is an amount with no unit — the shape most likely to be mistaken for
        // half a sum, and the one the clock has to keep.
        assertEquals(ClockSock.SET_TIMER, owner("Timer auf 10"))
        assertEquals(ClockSock.SET_TIMER, owner("Stell einen Timer auf 5 Minuten"))
        assertEquals(ClockSock.WHATS_THE_TIME, owner("Wie spät ist es"))
        assertEquals(ClockSock.WHATS_THE_TIME, owner("Was ist die Zeit"))
        assertTrue(owner("3 plus 5")?.startsWith("calculator.") == true)
    }
}
