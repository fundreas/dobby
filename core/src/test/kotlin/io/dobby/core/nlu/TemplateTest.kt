package io.dobby.core.nlu

import io.dobby.core.nlu.template.Levenshtein
import io.dobby.core.nlu.template.Node
import io.dobby.core.nlu.template.SlotKind
import io.dobby.core.nlu.template.Specificity
import io.dobby.core.nlu.template.TemplateParser
import io.dobby.core.nlu.template.TemplateSyntaxException
import io.dobby.core.nlu.template.compileTemplate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TemplateParserTest {

    @Test
    fun `parses words, alternations, optionals and slots`() {
        val root = TemplateParser.parse("(spiele|spiel) {query}( ab)?")
        assertEquals(3, root.nodes.size)
        assertTrue(root.nodes[0] is Node.Alt)
        assertEquals(Node.Slot("query", SlotKind.TEXT), root.nodes[1])
        assertTrue(root.nodes[2] is Node.Opt)
    }

    @Test
    fun `slot types`() {
        val root = TemplateParser.parse("timer {amount:int} {unit:enum} {note}")
        assertEquals(
            listOf(SlotKind.INT, SlotKind.ENUM, SlotKind.TEXT),
            root.nodes.filterIsInstance<Node.Slot>().map { it.kind },
        )
    }

    @Test
    fun `whitespace inside a group is not significant`() {
        // Matching is token-based, so "( ab)?" and "(ab)?" are the same template.
        assertEquals(
            compileTemplate("spiele {q}( ab)?").match(listOf("spiele", "x", "ab")) { null },
            compileTemplate("spiele {q}(ab)?").match(listOf("spiele", "x", "ab")) { null },
        )
    }

    @Test
    fun `rejects malformed templates`() {
        val bad = listOf(
            "(spiele|spiel",
            "spiele {query",
            "spiele }",
            "? spiele",
            "(a||b)",
            "{q:weird}",
            "",
        )
        for (template in bad) {
            assertFailsWith<TemplateSyntaxException>(template) { TemplateParser.parse(template) }
        }
    }
}

class TemplateMatcherTest {

    private fun match(template: String, utterance: String, enums: Map<String, List<String>> = emptyMap()) =
        compileTemplate(template).match(Normalizer.tokenize(utterance)) { enums[it] }

    @Test
    fun `matches anchored at both ends`() {
        assertNotNull(match("pause", "pause"))
        assertNull(match("pause", "pause bitte"))
        assertNull(match("pause", "bitte pause"))
    }

    @Test
    fun `captures a text slot verbatim`() {
        assertEquals(
            mapOf("query" to "blinding lights von the weeknd"),
            match("(spiele|spiel) {query}", "Spiele Blinding Lights von The Weeknd"),
        )
    }

    @Test
    fun `a trailing optional beats the greedy slot`() {
        // "ab" must land in the optional, not inside {query}.
        assertEquals(mapOf("query" to "queen"), match("spiele {query}( ab)?", "spiele queen ab"))
    }

    @Test
    fun `optional groups may be skipped`() {
        assertNotNull(match("(stell|stelle) (einen)? timer", "stell timer"))
        assertNotNull(match("(stell|stelle) (einen)? timer", "stell einen timer"))
    }

    @Test
    fun `int slots take digits, number words and the article one`() {
        assertEquals(mapOf("n" to "10"), match("timer {n:int} minuten", "timer zehn minuten"))
        assertEquals(mapOf("n" to "1"), match("timer {n:int} minute", "timer eine minute"))
        assertNull(match("timer {n:int} minuten", "timer viele minuten"))
    }

    @Test
    fun `enum slots snap to the declared value`() {
        val units = mapOf("unit" to listOf("sekunden", "minuten", "stunden"))
        assertEquals(mapOf("unit" to "minuten"), match("timer {unit:enum}", "timer minuten", units))
        // Singular folds into the plural enum value via the tolerance-1 fuzzy pass.
        assertEquals(mapOf("unit" to "minuten"), match("timer {unit:enum}", "timer minute", units))
        assertNull(match("timer {unit:enum}", "timer tage", units))
    }

    @Test
    fun `keywords tolerate STT slips`() {
        assertNotNull(match("(spiele|spiel) {query}", "spile queen"))
        assertNotNull(match("(spiele|spiel) {query}", "spielee queen"))
        assertNotNull(match("überspringen", "uberspringen"))
        assertNotNull(match("abfahrten", "abfarten"))
    }

    @Test
    fun `edit distance does not catch phonetic slips`() {
        // The plan offers "schbiele" as the motivating example for fuzzy matching, but it is
        // edit distance 3 from "spiele" — no threshold that catches it is safe for a 6-letter
        // word. Catching this class of error needs phonetic matching (Kölner Phonetik), not
        // Levenshtein. Documented here so the limitation is a known one rather than a surprise.
        assertEquals(3, Levenshtein.distance("schbiele", "spiele"))
        assertNull(match("(spiele|spiel) {query}", "schbiele queen"))
    }

    @Test
    fun `short keywords are matched exactly, or every utterance becomes a false positive`() {
        assertEquals(0, Levenshtein.tolerance("aus".length))
        assertFalse(Levenshtein.fuzzyEquals("an", "aus"))
        assertNull(match("radio aus", "radio an"))
    }

    @Test
    fun `slot content is never fuzzy-matched`() {
        // The slot takes whatever is there; only keywords are forgiving.
        assertEquals(mapOf("q" to "xyzzy"), match("spiele {q}", "spiele xyzzy"))
    }

    @Test
    fun `an enum slot is reached phonetically, but only when the answer is unambiguous`() {
        val template = compileTemplate("timer {unit:enum}")
        val units = listOf("sekunden", "minuten", "stunden")
        fun match(utterance: String) =
            template.match(utterance.split(" ")) { units }?.get("unit")

        // Exact, then Levenshtein, then the code. The real units are distinct by code —
        // 84626 / 6626 / 82626 — so a closed candidate set is its own contested check.
        assertEquals("minuten", match("timer minuten"))
        assertEquals("minuten", match("timer minute"))
        // Two edits away, which Levenshtein will not have: this is the phonetic tier working.
        assertEquals("stunden", match("timer schtunden"))
        assertEquals("sekunden", match("timer schekunden"))

        // A vowel-only neighbour is a different word, not a garble: "monaten" is not "minuten".
        assertNull(match("timer monaten"))

        // Two candidates sharing a code is an ambiguity, and an ambiguous unit is worse than
        // asking again. The pair is synthetic — the real enums have distinct codes — because
        // the guard has to hold for the enum somebody declares next, not only for these three.
        val ambiguous = compileTemplate("timer {unit:enum}")
        assertNull(
            ambiguous.match("timer zdunden".split(" ")) { listOf("stunden", "schtunden") },
        )
    }
}

class SpecificityTest {

    /** True if [first] is tried before [second] in the palette. */
    private fun firstWins(first: String, second: String): Boolean =
        Specificity.ORDER.compare(
            compileTemplate(first).specificity,
            compileTemplate(second).specificity,
        ) < 0

    @Test
    fun `closed templates rank before open ones`() {
        assertFalse(compileTemplate("radio (aus|stopp)").specificity.endsOpen)
        assertTrue(compileTemplate("radio {station}").specificity.endsOpen)
        assertTrue(firstWins("radio (aus|stopp)", "radio {station}"))
    }

    @Test
    fun `a trailing optional does not close a template`() {
        assertTrue(compileTemplate("spiele {query}( ab)?").specificity.endsOpen)
    }

    @Test
    fun `more keywords rank first`() {
        assertEquals(2, compileTemplate("(spiele|spiel) radio {station}").specificity.literalWords)
        assertEquals(1, compileTemplate("(spiele|spiel) {query}").specificity.literalWords)
        assertTrue(firstWins("(spiele|spiel) radio {station}", "(spiele|spiel) {query}"))
    }

    @Test
    fun `alternations count their cheapest branch`() {
        assertEquals(1, compileTemplate("(spiele|spiel mir)").specificity.literalWords)
        assertEquals(0, compileTemplate("(spiele)? {q:enum}").specificity.literalWords)
    }

    @Test
    fun `fewer text slots rank first at equal keyword count`() {
        // "mach das radio an" must reach radio, not spotify's "(mach|leg|spiel) {query} (an|auf)".
        val radio = "(mach|schalt) (das)? radio (an|ein)?"
        val spotify = "(mach|leg|spiel) {query} (an|auf)"
        assertEquals(
            compileTemplate(radio).specificity.literalWords,
            compileTemplate(spotify).specificity.literalWords,
        )
        assertTrue(firstWins(radio, spotify))
    }
}
