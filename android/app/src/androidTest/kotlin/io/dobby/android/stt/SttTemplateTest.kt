package io.dobby.android.stt

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.WaveReader
import io.dobby.android.DobbySocks
import io.dobby.core.nlu.Normalizer
import io.dobby.core.registry.SockRegistry
import io.dobby.pipeline.audio.AudioSource
import io.dobby.pipeline.stt.ParakeetRecognizer
import io.dobby.pipeline.stt.SpeechModelStore
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Recorded German commands, through the real recogniser, into the real palette.
 *
 * This is the suite that catches the failures nothing else can see. A wrong `model_type`, a
 * wrong feature dimension, a token table that does not belong to the encoder, a normalizer that
 * has drifted from what the templates are written against — none of them throw. They produce a
 * recogniser that loads, runs, and returns text that no template matches, which on the wall
 * looks like "Dobby stopped understanding me" and in a log looks like nothing.
 *
 * It runs on the device because that is the only place the 670 MB model is, and it skips itself
 * rather than failing when the model or the recordings are absent — the model arrives by having
 * run the app once, and the recordings arrive by someone recording them.
 *
 * **Adding a case:** record the utterance as 16 kHz mono 16-bit WAV, drop it in
 * `src/androidTest/assets/stt/`, and add a line to `src/androidTest/assets/stt/expected.tsv`:
 *
 * ```
 * timer_zehn_minuten.wav<TAB>clock.set_timer
 * ```
 *
 * One recording per Tier-1 template is the target (`dobby-plan.md` §5.2). Record them in the
 * room the panel lives in, at the distance it is spoken to from — a clean desk recording proves
 * the wiring and nothing about the product.
 *
 * The recordings M6c is waiting for are named in that README: "wie spät ist das" and three more
 * filler-heavy takes. They are the cases the filler list was written from, and this is the only
 * suite that can tell whether the recogniser really produces what the list assumes.
 */
@RunWith(AndroidJUnit4::class)
class SttTemplateTest {

    private data class Case(val wav: String, val commandId: String)

    @Test
    fun recordedGermanCommandsReachTheTemplatesTheyWereRecordedFor() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val assets = instrumentation.context.assets

        val cases = readCases(assets.list(ASSET_DIRECTORY).orEmpty().toList()) {
            assets.open("$ASSET_DIRECTORY/$MANIFEST").bufferedReader().readText()
        }
        assumeTrue(
            "no recordings in androidTest/assets/$ASSET_DIRECTORY — see this class's docs",
            cases.isNotEmpty(),
        )

        // The app under test, not the instrumentation APK: the model lives in its files dir.
        val models = SpeechModelStore(instrumentation.targetContext.filesDir).resolved()
        assumeTrue(
            "speech model not on this device — run the app once and let it download",
            listOf(models.encoder, models.decoder, models.joiner, models.tokens).all { it.isFile },
        )

        val palette = registry().palette
        val recognizer = ParakeetRecognizer.load(models, AudioSource.SAMPLE_RATE)
        val failures = mutableListOf<String>()
        try {
            for (case in cases) {
                val wav = copyToCache(case.wav, assets.open("$ASSET_DIRECTORY/${case.wav}").readBytes())
                val audio = WaveReader.readWave(wav.absolutePath)
                val transcript = recognizer.transcribe(audio.samples)
                val matched = palette.match(Normalizer.tokenize(transcript))?.invocation?.commandId

                if (matched != case.commandId) {
                    failures += "${case.wav}: heard \"$transcript\" → ${matched ?: "no match"}, " +
                        "expected ${case.commandId}"
                }
            }
        } finally {
            recognizer.close()
        }

        // Every case, not the first failure: when an engine or a normalizer changes, what
        // matters is which utterances moved, and a suite that stops at the first one hides that.
        check(failures.isEmpty()) {
            "${failures.size} of ${cases.size} recordings did not reach their template:\n" +
                failures.joinToString("\n") { "  $it" }
        }
    }

    /**
     * The Socks as the panel registers them, minus the hardware none of this needs.
     *
     * The palette has to be the real one: a test that matches against a hand-built template
     * table tests the template table, and what is in question here is whether a transcript
     * reaches the command it was recorded for across *every* Sock that is installed.
     */
    private fun registry(): SockRegistry =
        SockRegistry.buildOrThrow(DobbySocks.create(hardware = null).socks)

    private fun readCases(present: List<String>, manifest: () -> String): List<Case> {
        if (MANIFEST !in present) return emptyList()
        return manifest().lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { line ->
                val parts = line.split('\t', limit = 2).map(String::trim)
                require(parts.size == 2) { "$MANIFEST: expected '<file>\\t<command id>', got: $line" }
                Case(parts[0], parts[1])
            }
            .toList()
    }

    private fun copyToCache(name: String, bytes: ByteArray): File {
        // WaveReader is native and takes a path; an asset has neither.
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        return File(cache, "stt-$name").apply { writeBytes(bytes) }
    }

    private companion object {
        const val ASSET_DIRECTORY = "stt"
        const val MANIFEST = "expected.tsv"
    }
}
