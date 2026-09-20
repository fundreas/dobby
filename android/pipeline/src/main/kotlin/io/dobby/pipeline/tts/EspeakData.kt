package io.dobby.pipeline.tts

import android.content.Context
import android.content.res.AssetManager
import io.dobby.pipeline.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * espeak-ng's phoneme data, on a real filesystem path.
 *
 * Piper voices are phoneme models: the text-to-phoneme step is espeak-ng, inside the same
 * `libsherpa-onnx-jni.so` the recogniser already uses, and it wants its 355-file data directory
 * by path because it opens those files with `fopen`. An APK asset has no path — it is a range
 * inside a zip — so the directory is fetched at **build** time into the module's assets (7 MB
 * compressed, one copy shared by every voice) and copied out to `filesDir` on first run.
 *
 * ~18 MB of small files, a second or two, once. A [STAMP] file records the archive's SHA-256 so
 * a version bump replaces the directory rather than merging two of them, and so the second run
 * does no work at all. The stamp is written **last**: a copy that dies half-way leaves no stamp
 * and the next run starts again, which is the only failure mode worth designing for here.
 *
 * Why not download the 355 files at runtime: 355 round trips for 18 MB. Why not ship the
 * archive and unpack it on the phone: a bzip2 decoder as a dependency, for one file, once.
 */
object EspeakData {

    /** Both the asset path and the directory name under the model root. */
    const val DIRECTORY: String = "espeak-ng-data"

    /** Holds the SHA-256 of the archive the copy came from. */
    const val STAMP: String = "VERSION"

    /**
     * What the archive unpacks to: 355 files, pinned in the build file and checked there too.
     *
     * A half-copied directory is otherwise a voice that loads, runs, and says nothing —
     * espeak-ng reports a missing dictionary by producing no phonemes, not by failing. So the
     * count is checked twice: once when the archive is unpacked into the assets, and once here
     * when it is copied onto the device.
     */
    val fileCount: Int get() = BuildConfig.ESPEAK_DATA_FILES

    /** The SHA-256 the build pinned, which is what a stamp is compared against. */
    val archiveSha256: String get() = BuildConfig.ESPEAK_DATA_SHA256

    /**
     * Copies the phoneme data out of the assets if it is not already there, and returns it.
     *
     * Null when the copy failed — storage full, or an asset directory that is not in this
     * build. The caller's answer to that is the system voice, not an exception.
     */
    suspend fun ensure(context: Context, root: File): File? = withContext(Dispatchers.IO) {
        val target = File(root, DIRECTORY)
        val stamp = File(target, STAMP)
        if (isCurrent(readOrNull(stamp), archiveSha256)) return@withContext target

        try {
            target.deleteRecursively()
            target.mkdirs()
            copy(context.applicationContext.assets, DIRECTORY, target)
            val copied = target.walkTopDown().count { it.isFile }
            check(copied == fileCount) { "espeak-ng-data copied $copied of $fileCount files" }
            // Last, so a copy that died half-way leaves no stamp and the next run starts again.
            stamp.writeText(archiveSha256)
            target
        } catch (e: IOException) {
            // Half a phoneme directory is worse than none: it loads and mispronounces.
            target.deleteRecursively()
            null
        } catch (e: IllegalStateException) {
            target.deleteRecursively()
            null
        }
    }

    /**
     * Whether a stamp says the directory on disk is the one this build ships.
     *
     * Pure, and separate from the copy, because every interesting case is a file that is
     * missing, stale or unreadable — and none of those is worth a device to find out about.
     */
    fun isCurrent(stamp: String?, expected: String): Boolean =
        stamp != null && stamp.trim().equals(expected, ignoreCase = true)

    /** Recursive because the archive has `lang/` and `voices/` trees inside it. */
    private fun copy(assets: AssetManager, path: String, target: File) {
        val children = assets.list(path).orEmpty()
        if (children.isEmpty()) {
            // AssetManager cannot tell an empty directory from a file, and the archive has no
            // empty directories — so nothing here is a file, and everything else is.
            assets.open(path).use { source ->
                target.outputStream().use { source.copyTo(it) }
            }
            return
        }
        target.mkdirs()
        for (child in children) copy(assets, "$path/$child", File(target, child))
    }

    private fun readOrNull(file: File): String? = try {
        if (file.isFile) file.readText() else null
    } catch (_: IOException) {
        null
    }
}
