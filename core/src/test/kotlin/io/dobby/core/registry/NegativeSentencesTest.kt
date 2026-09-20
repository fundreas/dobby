package io.dobby.core.registry

import io.dobby.core.nlu.Fillers
import io.dobby.core.nlu.Normalizer
import io.dobby.core.testing.NegativeSentences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Filler skipping against sentences nobody said to the panel.
 *
 * [NegativeCorpusTest] asks whether a word can reach a keyword. This asks the question skipping
 * actually creates: a template is anchored at both ends, and skipping loosens that anchor by
 * exactly the words on the list — so an ordinary remark, made in the same room while the
 * microphone is open, must still reach nothing.
 *
 * The asymmetry from `socks.specs/README.md` §6 is why this file exists at all: a miss costs a
 * repeat, and a panel that acts on a sentence nobody addressed to it costs trust.
 */
class NegativeSentencesTest {

    private val palette = SockRegistry.buildOrThrow(SpecFixtures.all()).palette

    private val sentences: List<String> = NegativeSentences.load()

    @Test
    fun `the corpus is actually loaded, and is actually about fillers`() {
        // A resource that silently fails to load would turn this file into a green light.
        assertTrue(sentences.size >= 50, "only ${sentences.size} sentences")
        val thin = sentences.filter { sentence ->
            Normalizer.tokenize(sentence).none { it in Fillers.DE }
        }
        assertEquals(emptyList(), thin, "these sentences exercise nothing: they hold no filler")
    }

    @Test
    fun `no everyday sentence reaches a command, with skipping on`() {
        val offences = sentences.mapNotNull { sentence ->
            val match = palette.match(Normalizer.tokenize(sentence))
            match?.let { "\"$sentence\" → ${it.invocation.commandId} via \"${it.entry.template.source}\"" }
        }
        assertEquals(
            emptyList(),
            offences,
            "filler skipping routed ordinary German into a command; take the word off Fillers.DE, " +
                "or anchor the template it walked into",
        )
    }

    @Test
    fun `the palette under test really is the one with fillers on`() {
        // Otherwise the assertion above would pass for the wrong reason for the rest of time.
        assertEquals(Fillers.DE.language, palette.fillers.language)
        assertTrue(palette.fillers.words.isNotEmpty())
    }
}
