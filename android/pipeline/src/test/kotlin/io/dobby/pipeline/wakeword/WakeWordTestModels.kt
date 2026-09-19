package io.dobby.pipeline.wakeword

import java.io.File
import java.io.IOException
import java.net.URI

/**
 * The real openWakeWord graphs, on the JVM, fetched once and cached under `build/`.
 *
 * Shared by every test that needs the actual models — the inference contract
 * ([WakeWordModelContractTest]) and the SNR sweep ([WakeWordSnrSweepTest]) — because two copies
 * of a download-and-cache helper is two places for the cache path to drift.
 *
 * Returns null rather than throwing when the models cannot be fetched: being offline is a
 * reason to skip these tests, not to fail them.
 */
internal object WakeWordTestModels {

    fun files(): WakeWordFiles? {
        val directory = File("build/oww-models")
        val files = WakeWordFiles(
            melspectrogram = File(directory, "melspectrogram.onnx"),
            embedding = File(directory, "embedding_model.onnx"),
            classifier = File(directory, "hey_jarvis_v0.1.onnx"),
            phrase = "Hey Jarvis",
        )
        val all = listOf(files.melspectrogram, files.embedding, files.classifier)
        if (all.all { it.isFile }) return files

        directory.mkdirs()
        return try {
            for (file in all) {
                if (!file.isFile) {
                    URI("$URI_BASE/${file.name}").toURL().openStream().use { input ->
                        file.outputStream().use { input.copyTo(it) }
                    }
                }
            }
            files
        } catch (e: IOException) {
            // A half-written model is worse than none: it loads and scores nothing.
            all.forEach { it.delete() }
            null
        }
    }

    private const val URI_BASE = "https://github.com/dscripka/openWakeWord/releases/download/v0.5.1"
}
