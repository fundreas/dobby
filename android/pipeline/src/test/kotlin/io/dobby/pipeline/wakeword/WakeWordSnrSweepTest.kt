package io.dobby.pipeline.wakeword

import io.dobby.pipeline.audio.MicProfile
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * How far the wake word gets when there is music under it: the detection curve, measured.
 *
 * This is the offline half of `m2b-plan.md` B3, and it answers a question the device
 * measurement cannot isolate: **how much of the failure is the detector, rather than the echo
 * canceller?** Recorded phrases are mixed with recorded music at a sweep of signal-to-noise
 * ratios and scored through the real graphs. No phone, no speaker, no room — one variable.
 *
 * It measures the *detector*. It says nothing about acoustic echo cancellation, which is about
 * the panel hearing its own output and can only be measured on the hardware that does the
 * cancelling. A good curve here and a deaf panel in a room together mean the echo path is the
 * problem; a bad curve here means the threshold is, and no AEC would have saved it.
 *
 * **Opt-in: it skips itself when the fixtures are absent**, the way `SttTemplateTest` does,
 * because the recordings are somebody's voice in somebody's kitchen and do not belong in git.
 *
 * Supplying fixtures — 16 kHz mono 16-bit WAV, as everything else in this pipeline is:
 *
 * ```
 * android/pipeline/src/test/resources/snr/phrases/  one utterance of the wake phrase per file
 * android/pipeline/src/test/resources/snr/music/    what the panel will be played over
 * ```
 *
 * Record the phrases at the distance the panel is spoken to from, and take the music from what
 * actually gets played in that room. A clean desk recording mixed with pink noise measures a
 * different product.
 *
 * The curve is printed and written to `build/reports/wakeword-snr.txt`, in the shape the
 * README's table wants.
 */
class WakeWordSnrSweepTest {

    @Test
    fun `the detection curve against music, at the shipped threshold`() {
        val phrases = SnrMix.wavsIn("snr/phrases", javaClass.classLoader)
        val music = SnrMix.wavsIn("snr/music", javaClass.classLoader)
        assumeTrue(
            phrases.isNotEmpty() && music.isNotEmpty(),
            "no SNR fixtures in src/test/resources/snr — see this class's docs",
        )
        val files = WakeWordTestModels.files()
        assumeTrue(files != null, "openWakeWord models unavailable (offline?)")

        val report = StringBuilder()
        report.appendLine("wake word: ${files!!.phrase}")
        report.appendLine("profile:   ${MicProfile.DEFAULT} (threshold ${MicProfile.DEFAULT.threshold})")
        report.appendLine("fixtures:  ${phrases.size} phrases × ${music.size} music beds")
        report.appendLine()
        report.appendLine("  SNR (dB) | detected | rate")
        report.appendLine("  ---------|----------|------")

        val rates = mutableMapOf<Int, Double>()
        WakeWordModels.load(files).use { loaded ->
            for (snr in SNRS) {
                var fired = 0
                var attempts = 0
                for (phrase in phrases) {
                    for (bed in music) {
                        attempts++
                        if (detects(loaded, SnrMix.mix(phrase, bed, snr))) fired++
                    }
                }
                val rate = fired.toDouble() / attempts
                rates[snr] = rate
                report.appendLine(
                    "  %8d | %4d/%-4d| %.0f %%".format(snr, fired, attempts, rate * 100),
                )
            }
        }

        println(report)
        File("build/reports").mkdirs()
        File("build/reports/wakeword-snr.txt").writeText(report.toString())

        // The one assertion. Everything above is a measurement and has no pass mark — the
        // numbers are the output, and picking a threshold from them is a decision somebody
        // makes, not one a test can make. What a test *can* say is that the harness measured
        // something: a phrase that is barely interfered with and still never fires means the
        // fixtures are not what they claim to be, and every number under it is noise.
        val cleanest = SNRS.max()
        assertTrue(
            rates.getValue(cleanest) > 0.5,
            "only ${rates.getValue(cleanest) * 100} % of phrases were detected at " +
                "$cleanest dB SNR — the fixtures are probably not the wake phrase, or not " +
                "16 kHz mono; every number below this one is meaningless until that is fixed",
        )
    }

    /** True if the detector fired at any point while the mix played through it. */
    private fun detects(models: WakeWordModels, samples: ShortArray): Boolean {
        var fired = false
        val detector = WakeWordDetector.forProfile(models, MicProfile.DEFAULT) { fired = true }
        try {
            val frame = ShortArray(AudioWindow.FRAME)
            var offset = 0
            while (offset + frame.size <= samples.size) {
                samples.copyInto(frame, 0, offset, offset + frame.size)
                detector.onFrames(frame, frame.size)
                offset += frame.size
            }
        } finally {
            detector.close()
        }
        return fired
    }

    private companion object {
        /**
         * The sweep. Wide on purpose: the interesting part is where the curve falls off, and
         * nobody knows where that is yet — which is the entire reason for measuring it.
         *
         * Positive is the voice louder than the music. A panel on a shelf beside a speaker
         * playing at a normal volume is somewhere around 0 dB at three metres, which is the
         * cell of this table the product lives or dies in.
         */
        val SNRS = listOf(30, 20, 15, 10, 5, 0, -5, -10)
    }
}
