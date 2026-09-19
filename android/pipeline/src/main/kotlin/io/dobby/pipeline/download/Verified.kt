package io.dobby.pipeline.download

import java.io.File
import java.io.IOException

/**
 * A stamp saying "this exact file was hashed once and it was right".
 *
 * ### The regression this fixes
 *
 * `SpeechModelStore.isIntact` re-hashes 670 MB on *every* service start. That was tolerable
 * when it was the only model on the device; adding a 1.03 GB GGUF makes it 1.7 GB of storage
 * read before the microphone opens — eight to fifteen seconds of cold start, every start, to
 * re-answer a question that was settled the first time.
 *
 * ### Why a stamp rather than "trust the length"
 *
 * Length alone is what `isIntact` already falls back on, and it catches a truncated download
 * and nothing else: a file corrupted in place, a file swapped for a different one of the same
 * size, or a half-written re-download that happens to land on the right length all pass it.
 *
 * So the stamp records the three things that together mean "the bytes have not moved since we
 * hashed them" — [Stamp.sha256], [Stamp.length] and [Stamp.lastModified] — and any one of them
 * disagreeing, or the stamp being missing, unreadable or malformed, means a full re-hash. It
 * can only ever answer "intact" about a file it has actually seen; every other answer is "go
 * and look".
 *
 * The stamp lives beside the file as `<name>.verified`. Deleting it costs one re-hash, which
 * makes it safe to delete and safe to forget about.
 *
 * Internal for the same reason [Downloader] is: both model stores live in this module, and a
 * verification stamp is an implementation detail of how they avoid re-reading a gigabyte.
 */
internal object Verified {

    const val SUFFIX: String = ".verified"

    /** What was true about a file the last time it was hashed in full. */
    data class Stamp(val sha256: String, val length: Long, val lastModified: Long) {
        fun matches(file: File): Boolean =
            file.length() == length && file.lastModified() == lastModified

        fun serialize(): String = "$sha256\n$length\n$lastModified\n"

        companion object {
            fun parse(text: String): Stamp? {
                val lines = text.trim().lines()
                if (lines.size != 3) return null
                val sha = lines[0].trim()
                if (sha.length != SHA256_HEX_LENGTH || !sha.all { it.isHex() }) return null
                return Stamp(
                    sha256 = sha.lowercase(),
                    length = lines[1].trim().toLongOrNull() ?: return null,
                    lastModified = lines[2].trim().toLongOrNull() ?: return null,
                )
            }

            private fun Char.isHex(): Boolean = this in '0'..'9' || lowercaseChar() in 'a'..'f'

            private const val SHA256_HEX_LENGTH = 64
        }
    }

    fun stampFor(file: File): File = File(file.parentFile, file.name + SUFFIX)

    /**
     * Whether [file] is intact, hashing it only when the stamp cannot answer.
     *
     * @param expectedSha256 what the file is supposed to hash to.
     * @param expectedLength what it is supposed to be, or null to accept whatever is there.
     * @param onHashing called before a full re-hash, so a UI can say what the pause is. Not
     *   called when the stamp settles it, which is the common case and the point.
     */
    fun isIntact(
        file: File,
        expectedSha256: String,
        expectedLength: Long? = null,
        onHashing: () -> Unit = {},
        hash: (File) -> String = Downloader::sha256,
    ): Boolean {
        if (!file.isFile) return false
        // The cheap check first: a truncated download is settled without reading anything.
        if (expectedLength != null && file.length() != expectedLength) return false

        val stamp = read(file)
        if (stamp != null && stamp.matches(file) && stamp.sha256.equals(expectedSha256, ignoreCase = true)) {
            return true
        }

        onHashing()
        val actual = hash(file)
        if (!actual.equals(expectedSha256, ignoreCase = true)) {
            // A wrong file must not leave a stamp behind saying it was checked.
            clear(file)
            return false
        }
        write(file, actual)
        return true
    }

    /** Records that [file] hashes to [sha256]. Best effort: a stamp that cannot be written costs a re-hash. */
    fun write(file: File, sha256: String) {
        try {
            stampFor(file).writeText(Stamp(sha256.lowercase(), file.length(), file.lastModified()).serialize())
        } catch (_: IOException) {
            // Read-only storage, a full disk, a racing delete. None of them is worth failing a
            // verification that already succeeded.
        }
    }

    fun read(file: File): Stamp? = try {
        val stamp = stampFor(file)
        if (stamp.isFile) Stamp.parse(stamp.readText()) else null
    } catch (_: IOException) {
        null
    }

    fun clear(file: File) {
        try {
            stampFor(file).delete()
        } catch (_: SecurityException) {
            // Same as above: the worst case is a re-hash.
        }
    }
}
