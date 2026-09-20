package io.dobby.core.registry

import io.dobby.core.FixtureSock
import io.dobby.core.nlu.Fillers
import io.dobby.core.sock.ExclusiveCommandSpec
import io.dobby.core.sock.ParamSpec
import io.dobby.core.sock.ParamType
import io.dobby.core.sock.SockResult
import io.dobby.core.sock.patterns
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The filler list itself: what may be on it, and what the palette does about it.
 *
 * [io.dobby.core.nlu.Fillers] is the one piece of M6c that is a judgement rather than an
 * algorithm — "no command's meaning changes when this word is dropped" — so it is also the
 * piece that needs a test naming the judgement out loud. Two halves: a hand-written exclusion
 * list, which is what stops somebody adding `an` on a tired afternoon, and
 * [SockRegistry.checkFillers], which is the mechanical version run against a real palette.
 */
class FillersTest {

    private val registry = SockRegistry.buildOrThrow(SpecFixtures.all())

    private companion object {
        /**
         * Words that must never be filler, whatever a grammar book calls them.
         *
         * Not derived from anything — that is the point. Each group is a way a panel can act on
         * a sentence it was not given, and a mechanical check cannot know that `an` is the
         * difference between turning the radio on and turning it off.
         *
         * Per language, because the languages disagree: English `an` is an article and German
         * `an` is half of the radio.
         */
        val NEVER_DE: Map<String, List<String>> = mapOf(
            // Polarity and direction: the entire content of half the catalog.
            "polarity" to listOf("an", "aus", "ein", "nicht", "kein", "mehr", "weniger", "hoch", "runter"),
            // Dialogue answers. "OK" ends a conversation turn — it is a command, not noise.
            "answers" to listOf("ja", "nein", "ok", "okay", "oke", "stopp", "halt", "danke"),
            // Repetition and sequence. "mal" is fine and is on the list: "noch mal" still takes
            // its literal path, and a lone "mal" is noise.
            "sequence" to listOf("noch", "nochmal", "wieder", "weiter", "zurück"),
        )

        val NEVER_EN: Map<String, List<String>> = mapOf(
            "polarity" to listOf("on", "off", "not", "no", "up", "down", "more", "less"),
            "answers" to listOf("yes", "no", "ok", "okay", "stop"),
            "sequence" to listOf("again", "next", "back"),
        )
    }

    @Test
    fun `no word that carries a command is on either list`() {
        for ((fillers, never) in listOf(Fillers.DE to NEVER_DE, Fillers.EN to NEVER_EN)) {
            for ((group, words) in never) {
                for (word in words) {
                    assertTrue(word !in fillers, "\"$word\" ($group) is a ${fillers.language} filler")
                }
            }
        }
    }

    @Test
    fun `the three words the catalog took back off the list stay off it`() {
        // Each was in the plan's table and each lost an argument with a real Sock. Named here
        // so the next reader does not put them back as an oversight.
        assertTrue("ein" !in Fillers.DE, "\"ein\" is an article and also \"mach das Licht ein\"")
        assertTrue("halt" !in Fillers.DE, "\"halt\" is a particle and also shared.stop")
        assertTrue("danke" !in Fillers.DE, "\"danke\" is politeness and also conversation.acknowledge")
        // …and the forms that carry no polarity are still there, or "stell nen timer" is lost.
        for (article in listOf("eine", "einen", "einem", "einer", "nen")) {
            assertTrue(article in Fillers.DE, "\"$article\" fell off the list with \"ein\"")
        }
    }

    @Test
    fun `the lists are normalized the way the matcher is`() {
        // Fillers are compared against normalized tokens, so anything with an uppercase letter
        // or a space in it could never match one and would be a silent no-op.
        for (fillers in listOf(Fillers.DE, Fillers.EN)) {
            assertTrue(fillers.words.isNotEmpty(), "${fillers.language} is empty")
            for (word in fillers.words) {
                assertEquals(word.lowercase(), word, "\"$word\" is not lowercase")
                assertTrue(' ' !in word, "\"$word\" is two tokens, not one")
            }
        }
        assertTrue(Fillers.NONE.isEmpty)
    }

    @Test
    fun `the shipped palette survives its own filler list`() {
        // The mechanical half, against a real catalog: no filler is a command on its own, and
        // no two commands collapse onto the same sentence once fillers are dropped.
        assertEquals(emptyList(), registry.checkFillers())
    }

    @Test
    fun `a filler that is a command on its own is refused`() {
        val problems = SockRegistry.buildOrThrow(
            listOf(
                FixtureSock(
                    id = "toy",
                    commands = listOf(
                        ExclusiveCommandSpec(
                            id = "toy.nap",
                            templates = patterns("(bitte|schlaf)"),
                            description = "Ein Befehl, der aus einem Füllwort besteht.",
                        ),
                    ),
                    handler = { SockResult.Silent },
                ),
            ),
        ).checkFillers()
        // Two reports of the same mistake, and both are worth printing: the word satisfies a
        // template on its own, and that template has a wording made of nothing else.
        assertEquals(2, problems.size, problems.toString())
        assertTrue(problems.any { "\"bitte\"" in it }, problems.toString())
        assertTrue(problems.all { "toy.nap" in it }, problems.toString())
    }

    @Test
    fun `two commands that differ only by a filler are refused`() {
        val problems = SockRegistry.buildOrThrow(
            listOf(
                FixtureSock(
                    id = "toy",
                    commands = listOf(
                        ExclusiveCommandSpec(
                            id = "toy.one",
                            templates = patterns("timer aus"),
                            description = "Der eine.",
                        ),
                        ExclusiveCommandSpec(
                            id = "toy.two",
                            // "das" is the only thing telling this from the one above, and the
                            // recogniser drops it at random. "timer das denn aus" reaches both.
                            templates = patterns("timer das aus"),
                            description = "Der andere.",
                        ),
                    ),
                    handler = { SockResult.Silent },
                ),
            ),
        ).checkFillers()
        assertEquals(1, problems.size, problems.toString())
        assertTrue("toy.one" in problems.single() && "toy.two" in problems.single(), problems.single())
    }

    @Test
    fun `a filler on the far side of a slot is not a collision`() {
        // The pair the check has to be precise about, and the reason it is not a set
        // comparison: `{a:int} mal` and `mal {b:int}` are both "{int}" once "mal" is dropped,
        // and no utterance reaches both — the matcher never skips in front of a slot. This is
        // the calculator, and getting it wrong would cost the list its most common particle.
        assertEquals(
            emptyList(),
            SockRegistry.buildOrThrow(
                listOf(
                    FixtureSock(
                        id = "toy",
                        commands = listOf(
                            ExclusiveCommandSpec(
                                id = "toy.times",
                                params = listOf(ParamSpec("a", ParamType.Integer)),
                                templates = patterns("{a:int} mal"),
                                description = "Zahl, dann Operator.",
                            ),
                            ExclusiveCommandSpec(
                                id = "toy.continue",
                                params = listOf(ParamSpec("b", ParamType.Integer)),
                                templates = patterns("mal {b:int}"),
                                description = "Operator, dann Zahl.",
                            ),
                        ),
                        handler = { SockResult.Silent },
                    ),
                ),
            ).checkFillers(),
        )
    }
}
