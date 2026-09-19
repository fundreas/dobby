package io.dobby.pipeline.download

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The stamp that stops a gigabyte being re-hashed on every service start.
 *
 * The property under test is narrow and load-bearing: **it may never answer "intact" about a
 * file that is not.** Every case below is a way the file could have changed since the stamp was
 * written, and every one of them has to come back false.
 */
class VerifiedTest {

    private val directory: File = Files.createTempDirectory("verified").toFile()

    private val hash = "a".repeat(64)

    private var hashCalls = 0

    private fun file(name: String = "model.bin", content: String = "payload"): File =
        File(directory, name).apply { writeText(content) }

    /** Stands in for SHA-256 so a test can count how often the expensive path was taken. */
    private fun counting(result: String = hash): (File) -> String = {
        hashCalls++
        result
    }

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    @Test
    fun `the first call hashes, the second does not`() {
        val target = file()
        assertTrue(Verified.isIntact(target, hash, hash = counting()))
        assertEquals(1, hashCalls)

        // This is the whole point: eight to fifteen seconds of cold start, not spent.
        assertTrue(Verified.isIntact(target, hash, hash = counting()))
        assertEquals(1, hashCalls)
    }

    @Test
    fun `a file that changed in place is re-hashed and rejected`() {
        val target = file()
        assertTrue(Verified.isIntact(target, hash, hash = counting()))

        // Same length, different bytes, and a different mtime — which is the case plain
        // length-checking misses entirely.
        target.writeText("payloa!")
        target.setLastModified(target.lastModified() + 5000)
        assertFalse(Verified.isIntact(target, hash, hash = counting("b".repeat(64))))
        assertEquals(2, hashCalls, "the changed file was answered from the stamp")
    }

    @Test
    fun `a wrong file leaves no stamp behind`() {
        val target = file()
        assertFalse(Verified.isIntact(target, hash, hash = counting("b".repeat(64))))
        assertFalse(Verified.stampFor(target).exists(), "a failed check must not look verified")
    }

    @Test
    fun `a truncated download is settled without reading it`() {
        val target = file(content = "short")
        assertFalse(Verified.isIntact(target, hash, expectedLength = 9_999, hash = counting()))
        assertEquals(0, hashCalls, "the length check should have settled it")
    }

    @Test
    fun `a missing, empty or malformed stamp means a re-hash rather than a pass`() {
        val target = file()
        Verified.write(target, hash)
        assertTrue(Verified.isIntact(target, hash, hash = counting()))
        assertEquals(0, hashCalls)

        for (nonsense in listOf("", "   ", "not a hash\n1\n2", "$hash\nnotanumber\n3", hash, "$hash\n1\n2\n3")) {
            Verified.stampFor(target).writeText(nonsense)
            hashCalls = 0
            assertTrue(Verified.isIntact(target, hash, hash = counting()), "rejected for \"$nonsense\"")
            assertEquals(1, hashCalls, "a malformed stamp was trusted: \"$nonsense\"")
        }
    }

    @Test
    fun `a stamp for a different hash does not vouch for this one`() {
        val target = file()
        Verified.write(target, "b".repeat(64))
        assertTrue(Verified.isIntact(target, hash, hash = counting()))
        assertEquals(1, hashCalls, "a stamp recording a different hash was accepted")
    }

    @Test
    fun `an absent file is never intact`() {
        assertFalse(Verified.isIntact(File(directory, "nope.bin"), hash, hash = counting()))
        assertEquals(0, hashCalls)
    }

    @Test
    fun `clear forces the next check to hash again`() {
        val target = file()
        assertTrue(Verified.isIntact(target, hash, hash = counting()))
        Verified.clear(target)
        assertTrue(Verified.isIntact(target, hash, hash = counting()))
        assertEquals(2, hashCalls)
    }
}
