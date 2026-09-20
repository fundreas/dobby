package io.dobby.pipeline.tts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The stamp that decides whether 18 MB of phoneme data gets copied again, and the two numbers
 * the copy is checked against.
 *
 * Every interesting case here is a file that is missing, stale or unreadable, and none of them
 * is worth a phone to find out about — which is why [EspeakData.isCurrent] is a pure function
 * taking the stamp's text rather than a method that reads it.
 *
 * The pins are asserted against literals on purpose. Both also live in the build file, where
 * the archive is downloaded and unpacked, and a bump that changes one without the other is a
 * device that either re-copies 355 files on every start or trusts a directory it has not seen.
 */
class EspeakDataTest {

    @Test
    fun `the archive is pinned, and the pin is the one the voices were checked against`() {
        assertEquals(
            "4135ccf82e1f40613491c0874d4945ae9e9c7840933d8e25a6f9e003d9ebf533",
            EspeakData.archiveSha256,
        )
        assertEquals(355, EspeakData.fileCount)
    }

    @Test
    fun `a matching stamp means the directory on disk is this build's`() {
        assertTrue(EspeakData.isCurrent(EspeakData.archiveSha256, EspeakData.archiveSha256))
        // Written with a trailing newline by anything that edits it by hand, and by some
        // editors on save. A whitespace difference is not a different archive.
        assertTrue(EspeakData.isCurrent("${EspeakData.archiveSha256}\n", EspeakData.archiveSha256))
        assertTrue(EspeakData.isCurrent(EspeakData.archiveSha256.uppercase(), EspeakData.archiveSha256))
    }

    @Test
    fun `a missing stamp means copy`() {
        // First run, and also the run after a copy that died half-way: the stamp is written
        // last, so "no stamp" and "half a directory" are the same answer.
        assertFalse(EspeakData.isCurrent(null, EspeakData.archiveSha256))
    }

    @Test
    fun `a stale stamp means replace, not merge`() {
        // The previous archive. Two versions of espeak-ng's dictionaries in one directory is a
        // voice that mispronounces exactly the words the bump was meant to fix.
        val previous = "0".repeat(64)
        assertFalse(EspeakData.isCurrent(previous, EspeakData.archiveSha256))
    }

    @Test
    fun `an unreadable stamp means copy`() {
        // Truncated by a full disk, or something else entirely. Anything that is not the hash
        // is "go and look", because the cost of being wrong is a silent voice.
        assertFalse(EspeakData.isCurrent("", EspeakData.archiveSha256))
        assertFalse(EspeakData.isCurrent("not a hash", EspeakData.archiveSha256))
        assertFalse(EspeakData.isCurrent(EspeakData.archiveSha256.dropLast(1), EspeakData.archiveSha256))
    }

    @Test
    fun `the stamp lives inside the directory it describes`() {
        // So deleting the directory deletes the stamp, and there is no way to have one without
        // the other. `VERSION` rather than a dotfile: it is meant to be found by whoever is
        // looking at `run-as io.dobby.android ls files/espeak-ng-data`.
        assertEquals("espeak-ng-data", EspeakData.DIRECTORY)
        assertEquals("VERSION", EspeakData.STAMP)
    }
}
