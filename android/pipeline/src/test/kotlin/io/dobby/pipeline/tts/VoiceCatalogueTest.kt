package io.dobby.pipeline.tts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The voices the settings screen offers, and what they cost to get wrong.
 *
 * None of this needs a phone, and all of it is the kind of mistake a phone would only show you
 * weeks later: a URL pinned to a branch is a download that breaks the day upstream re-uploads a
 * file; a renamed id silently resets somebody's chosen voice; a missing checksum is a truncated
 * 114 MB graph that fails to load with a native error and no Kotlin stack behind it.
 */
class VoiceCatalogueTest {

    @Test
    fun `every remote file is pinned by size and lowercase checksum`() {
        val files = VoiceCatalogue.ALL.flatMap { it.files }
        assertEquals(4, files.size, "two voices, a graph and a token table each")
        for (file in files) {
            assertTrue(file.bytes > 0, "${file.path} has no expected size")
            assertEquals(64, file.sha256.length, "${file.path} has no SHA-256")
            assertTrue(
                file.sha256.all { it in "0123456789abcdef" },
                "${file.path} checksum is not lowercase hex: ${file.sha256}",
            )
        }
    }

    @Test
    fun `the Hugging Face URLs are pinned to a revision, not to a branch`() {
        val files = VoiceCatalogue.ALL.flatMap { it.files }
        for (file in files) {
            assertTrue(file.url.startsWith("https://huggingface.co/"), "unexpected host: ${file.url}")
            assertFalse(file.url.contains("/resolve/main/"), "${file.path} still points at main")
            // 40 hex characters between `/resolve/` and the filename: a commit, not a branch.
            val revision = file.url.substringAfter("/resolve/").substringBefore('/')
            assertEquals(40, revision.length, "${file.path} is not pinned to a revision")
            assertTrue(
                revision.all { it in "0123456789abcdef" },
                "${file.path} revision is not a commit hash: $revision",
            )
        }
    }

    @Test
    fun `every file lands under the voice's own directory`() {
        // The store resolves paths against the model root and the sideload scan reads the same
        // tree. A file written outside `voices/<directory>/` is a voice nothing can find again.
        for (option in VoiceCatalogue.ALL.filterNot { it.isSystem }) {
            for (file in option.files) {
                assertTrue(
                    file.path.startsWith("${VoiceCatalogue.DIRECTORY}/${option.directory}/"),
                    "${file.path} is not under ${option.directory}",
                )
            }
            assertTrue(
                option.files.any { it.path.endsWith("/${VoiceCatalogue.TOKENS}") },
                "${option.name} has no token table",
            )
        }
    }

    @Test
    fun `ids are unique, stable, and Thorsten is the default`() {
        val ids = VoiceCatalogue.ALL.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "duplicate ids: $ids")
        // Ids are persisted in settings, so renaming one silently resets somebody's choice.
        assertEquals(listOf("thorsten", "cori", "system"), ids)
        assertEquals(VoiceCatalogue.THORSTEN, VoiceCatalogue.DEFAULT)
        assertTrue(VoiceCatalogue.DEFAULT in VoiceCatalogue.ALL)
    }

    @Test
    fun `an id nothing answers to falls back to the default`() {
        // Null is a fresh install; the other two are a settings file written by a build that no
        // longer exists, and a sideloaded voice somebody deleted between runs.
        assertEquals(VoiceCatalogue.DEFAULT, VoiceCatalogue.of(null))
        assertEquals(VoiceCatalogue.DEFAULT, VoiceCatalogue.of("thorsten-medium"))
        assertEquals(VoiceCatalogue.DEFAULT, VoiceCatalogue.of("dir:vits-piper-de_DE-kerstin-low"))
        assertEquals(VoiceCatalogue.CORI, VoiceCatalogue.of("cori"))
    }

    @Test
    fun `the system voice downloads nothing and owns no directory`() {
        assertTrue(VoiceCatalogue.SYSTEM.isSystem)
        assertTrue(VoiceCatalogue.SYSTEM.files.isEmpty())
        assertEquals("", VoiceCatalogue.SYSTEM.directory)
        assertEquals("", VoiceCatalogue.SYSTEM.language)
        assertNull(VoiceCatalogue.byDirectory(""), "the empty directory is nobody's")
        // And the two Piper voices are not the system voice, which is the check that keeps
        // `isSystem` from quietly becoming true for a voice whose files list was emptied.
        assertFalse(VoiceCatalogue.THORSTEN.isSystem)
        assertFalse(VoiceCatalogue.CORI.isSystem)
    }

    @Test
    fun `the language tags are the ones the follow-up will select on`() {
        // `m2c-plan.md` Part E: a language setting picks the answer language and the voice
        // follows it. These two tags are the whole of what this milestone leaves behind for it.
        assertEquals("de-DE", VoiceCatalogue.THORSTEN.language)
        assertEquals("en-GB", VoiceCatalogue.CORI.language)
    }

    @Test
    fun `Cori's row says the awkward thing where she is chosen`() {
        // Until Part E exists, picking her is picking pronunciation and not language. Said in
        // the subtitle, the way the wake-word footnote says it for English-trained heads.
        assertTrue(
            VoiceCatalogue.CORI.description.contains("Aussprache"),
            "Cori's description does not warn about pronunciation: ${VoiceCatalogue.CORI.description}",
        )
    }

    @Test
    fun `every voice has something to say when it is chosen`() {
        assertEquals("Ich bin Thorsten.", VoiceCatalogue.THORSTEN.spokenGreeting)
        assertEquals("Hello, I'm Cori.", VoiceCatalogue.CORI.spokenGreeting)
        assertEquals("Ich bin die Android-Stimme.", VoiceCatalogue.SYSTEM.spokenGreeting)
    }

    @Test
    fun `a sideloaded directory becomes a name somebody would have said`() {
        assertEquals("Thorsten Medium", nameFor("vits-piper-de_DE-thorsten-medium"))
        assertEquals("Cori High", nameFor("vits-piper-en_GB-cori-high"))
        // Anything that is not a Piper export keeps its own shape rather than losing four
        // segments of it.
        assertEquals("Thorsten Emotional", nameFor("thorsten_emotional"))
        assertEquals("Stimme", nameFor(""))
    }
}
