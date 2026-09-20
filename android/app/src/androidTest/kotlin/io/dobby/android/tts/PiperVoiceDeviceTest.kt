package io.dobby.android.tts

import android.os.Debug
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import io.dobby.pipeline.tts.EspeakData
import io.dobby.pipeline.tts.PiperSpeaker
import io.dobby.pipeline.tts.VoiceCatalogue
import io.dobby.pipeline.tts.VoiceOption
import io.dobby.pipeline.tts.VoiceStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.system.measureTimeMillis

/**
 * Every voice on the device, synthesising the sentences Dobby actually says.
 *
 * This is the suite `m2c-plan.md` step 0 exists for, and it stays afterwards as the regression
 * net. It runs on the device because that is the only place the 114 MB graph and the 18 MB
 * phoneme directory are, and because the number that decides whether `high` is affordable is a
 * number about *this* CPU — a real-time factor of 0.3 on a laptop says nothing about two
 * Cortex-A78 cores.
 *
 * It skips itself rather than failing when a voice is absent: voices arrive by running the app
 * once and letting it download, or by the sideload recipe in `VoiceStore`'s docs.
 *
 * **What it can catch that nothing else can.** A graph that loads with the wrong token table, a
 * phoneme directory that copied 300 of 355 files, a sample rate that is not what the
 * `AudioTrack` was opened at — none of them throw. They produce a voice that loads, runs, and
 * returns silence or noise, which on the wall sounds like a panel that stopped talking and in a
 * log looks like nothing. So the assertions are about the samples themselves.
 *
 * The measurements go to logcat under [TAG] and into `m2c-plan.md`'s *Measured* table:
 *
 * ```sh
 * ./gradlew :android:app:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=io.dobby.android.tts.PiperVoiceDeviceTest
 * adb logcat -d -s DobbyVoice
 * ```
 */
@RunWith(AndroidJUnit4::class)
class PiperVoiceDeviceTest {

    @Test
    fun everyVoiceOnThisDeviceSpeaksTheAnswersDobbyGives() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = VoiceStore(context.filesDir)
        val present = VoiceCatalogue.ALL.filterNot { it.isSystem }.filter { store.isPresent(it) }
        assumeTrue(
            "no Piper voice on this device — run the app once, or sideload one (see VoiceStore)",
            present.isNotEmpty(),
        )

        val phonemes = runBlocking { EspeakData.ensure(context, context.filesDir) }
        assumeTrue("espeak-ng-data could not be prepared", phonemes != null)
        assertEquals(
            "the phoneme directory is not the one this build ships",
            EspeakData.fileCount.toLong(),
            phonemes!!.walkTopDown().count { it.isFile && it.name != EspeakData.STAMP }.toLong(),
        )

        for (option in present) {
            val files = store.resolve(option) ?: continue
            val before = residentKb()
            var tts: OfflineTts? = null
            val loadMs = measureTimeMillis {
                tts = OfflineTts(
                    config = OfflineTtsConfig(
                        model = OfflineTtsModelConfig(
                            vits = OfflineTtsVitsModelConfig(
                                model = files.model.absolutePath,
                                tokens = files.tokens.absolutePath,
                                dataDir = phonemes.absolutePath,
                            ),
                            numThreads = PiperSpeaker.DEFAULT_THREADS,
                        ),
                        maxNumSentences = 1,
                    ),
                )
            }
            val voice = requireNotNull(tts)
            try {
                val after = residentKb()
                Log.i(
                    TAG,
                    "${option.id}: load ${loadMs} ms, ${voice.sampleRate()} Hz, " +
                        "${voice.numSpeakers()} speaker(s), RSS ${before} → ${after} kB " +
                        "(+${after - before})",
                )

                // Never hard-coded anywhere in the pipeline — the AudioTrack is opened at
                // whatever the graph says — but asserted here, because a voice at a rate the
                // catalogue did not expect is worth knowing about before a user hears it.
                assertEquals(
                    "${option.id} is not a 22.05 kHz voice",
                    22_050L,
                    voice.sampleRate().toLong(),
                )
                assertEquals(
                    "${option.id} is a multi-speaker model",
                    1L,
                    voice.numSpeakers().toLong(),
                )

                for (sentence in SENTENCES) {
                    speak(voice, option, sentence)
                }
            } finally {
                voice.free()
            }
        }
    }

    /** Synthesises one sentence, times the first chunk, and checks what came back is audio. */
    private fun speak(voice: OfflineTts, option: VoiceOption, sentence: String) {
        val started = System.nanoTime()
        var firstChunkMs = -1L
        var samples = 0
        var peak = 0f
        var energy = 0.0

        val audio = voice.generateWithCallback(sentence) { chunk ->
            if (firstChunkMs < 0) firstChunkMs = (System.nanoTime() - started) / NANOS_PER_MILLI
            samples += chunk.size
            for (value in chunk) {
                peak = maxOf(peak, abs(value))
                energy += value.toDouble() * value
            }
            1
        }
        val elapsedMs = (System.nanoTime() - started) / NANOS_PER_MILLI
        val audioMs = samples * MILLIS_PER_SECOND / voice.sampleRate()
        val rtf = if (audioMs > 0) elapsedMs.toDouble() / audioMs else Double.NaN
        val rms = if (samples > 0) Math.sqrt(energy / samples) else 0.0

        Log.i(
            TAG,
            "${option.id}: \"$sentence\" → ${audioMs} ms audio in ${elapsedMs} ms " +
                "(RTF ${"%.2f".format(rtf)}, first chunk ${firstChunkMs} ms, " +
                "peak ${"%.2f".format(peak)}, RMS ${"%.4f".format(rms)})",
        )

        // A sentence that produced nothing is the failure this suite exists for: a missing
        // dictionary, a token table from another voice, or text espeak-ng could not phonemise
        // all arrive as an empty or silent buffer rather than as an exception.
        assertTrue("${option.id} produced no audio for \"$sentence\"", samples > 0)
        assertTrue("${option.id} produced silence for \"$sentence\"", peak > SILENCE)
        // sherpa normalises to −1..1. Anything outside it would clip on the way to the track,
        // and would mean the gain in the catalogue is being applied twice somewhere.
        assertTrue("${option.id} peaked at $peak on \"$sentence\"", peak <= 1f)
        assertEquals(
            "${option.id} streamed a different number of samples than it returned",
            samples.toLong(),
            audio.samples.size.toLong(),
        )
    }

    /** This process's PSS. The test runs in the app's process, so it is the panel's number. */
    private fun residentKb(): Int {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        return info.totalPss
    }

    private companion object {
        const val TAG = "DobbyVoice"

        /** Below this a "sentence" is a buffer of zeros with a rounding error in it. */
        const val SILENCE = 0.01f

        const val NANOS_PER_MILLI = 1_000_000L
        const val MILLIS_PER_SECOND = 1_000

        /**
         * Real answers, not lorem ipsum.
         *
         * One per shape Dobby actually produces: a time as German digits, a calculation read
         * back, a three-sentence Help answer (the case sentence streaming exists for), a
         * departures line full of abbreviations, and an English title inside a German sentence —
         * which is the whole reason Parakeet is multilingual and the place espeak-ng is most
         * likely to disagree with what Google TTS used to say.
         */
        val SENTENCES = listOf(
            "Es ist halb 3.",
            "8 mal 2 ist 16.",
            "Ich kann die Uhr, den Rechner und die Hilfe. Frag mich nach der Zeit oder stell " +
                "einen Timer. Sag \"Was kann die Uhr?\", wenn du mehr wissen willst.",
            "Die nächste U4 Richtung Heiligenstadt fährt in 4 Minuten ab Schwedenplatz.",
            "Ich spiele Blinding Lights von The Weeknd.",
        )
    }
}
