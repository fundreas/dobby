package io.dobby.llama

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import io.dobby.core.nlu.llm.GrammarGenerator
import io.dobby.core.nlu.llm.PromptGenerator
import io.dobby.core.nlu.llm.Tier2
import io.dobby.core.nlu.llm.Tier2Catalog
import io.dobby.core.nlu.llm.Tier2Json
import io.dobby.core.nlu.llm.Tier2Program
import io.dobby.core.nlu.llm.TokenEstimate
import io.dobby.core.registry.Introspection
import io.dobby.core.registry.SockRegistry
import io.dobby.core.sock.ExclusiveCommandSpec
import io.dobby.core.sock.ParamSpec
import io.dobby.core.sock.ParamType
import io.dobby.core.sock.Sock
import io.dobby.core.sock.SockResult
import io.dobby.core.sock.CommandInvocation
import io.dobby.core.sock.patterns
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.system.measureTimeMillis

/**
 * The Tier 2 suites that can only run on a phone.
 *
 * Everything else about Tier 2 is a JVM test, deliberately. What is left here is the three
 * things a laptop cannot answer: does the library load on this CPU, how fast does this silicon
 * actually decode, and does a 1.7B model in non-thinking mode get German paraphrases right.
 *
 * **These need the GGUF pushed to the device.** They skip rather than fail without it, because
 * a red suite on a machine that was never going to have a gigabyte of weights teaches nobody
 * anything:
 *
 *     adb push Qwen3-1.7B-Q4_K_M.gguf /sdcard/Android/data/io.dobby.llama.test/files/
 */
class Tier2DeviceTest {

    private lateinit var context: Context
    private lateinit var program: Tier2Program
    private var gguf: File? = null

    /** A small registry of its own, so the suite does not drift with the app's Sock list. */
    private fun registry(): SockRegistry = SockRegistry.buildOrThrow(listOf(TestClock()))

    private class TestClock : Sock {
        override val id = "clock"
        override val displayName = "Uhr"
        override val commands = listOf(
            ExclusiveCommandSpec(
                id = "clock.set_timer",
                params = listOf(
                    ParamSpec("amount", ParamType.Integer),
                    ParamSpec("unit", ParamType.Enumeration(listOf("sekunden", "minuten", "stunden"))),
                ),
                templates = patterns("timer {amount:int} {unit:enum}"),
                description = "Stellt einen Timer für eine bestimmte Dauer.",
            ),
            ExclusiveCommandSpec(
                id = "clock.whats_the_time",
                templates = patterns("wie spät"),
                description = "Sagt die aktuelle Uhrzeit.",
            ),
        )

        override suspend fun handle(invocation: CommandInvocation) = SockResult.Silent
    }

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        val reg = registry()
        program = Tier2Program.of(reg, Introspection(reg))
        gguf = context.getExternalFilesDir(null)
            ?.let { File(it, "Qwen3-1.7B-Q4_K_M.gguf") }
            ?.takeIf { it.isFile }
    }

    /**
     * `NativeLibraryShapeTest` — the APK carries one of each, and no `libc++_shared.so`.
     *
     * Three native libraries share this process: ONNX Runtime for the wake word, sherpa's JNI
     * for the recogniser, and this one. Each is statically linked and exports only its own
     * entry points, and a second copy of any of them would be the kind of bug that shows up as
     * a crash in somebody else's library.
     */
    @Test
    fun nativeLibraryShape() {
        assumeTrue("needs a CPU the library was built for", Llama().open())
        val maps = File("/proc/self/maps").readText()
        for (library in listOf("libdobby-llama.so")) {
            val loaded = maps.lineSequence().filter { library in it }
                .map { it.substringAfterLast(' ') }.distinct().toList()
            assertEquals("$library should be mapped exactly once: $loaded", 1, loaded.size)
        }
        assertTrue("libc++_shared.so must not be in this process", "libc++_shared.so" !in maps)
    }

    /** `CpuFeatureGateTest` — the real `Features` line of the device this is running on. */
    @Test
    fun cpuFeatureGate() {
        val features = Llama.cpuFeatures()
        assertTrue("could not read /proc/cpuinfo", features.isNotEmpty())
        val missing = Llama.REQUIRED_CPU_FEATURES.filterNot { it in features }
        // Not an assertion that the device is supported — an unsupported one is a legitimate
        // answer. The assertion is that open() agrees with the features line either way.
        assertEquals(
            "open() and /proc/cpuinfo disagree about this CPU",
            missing.isEmpty(),
            Llama().open(),
        )
        println("device features: ${features.sorted().joinToString(" ")}")
    }

    /**
     * `Tier2GrammarContractTest` — the generated GBNF, through llama.cpp's own parser.
     *
     * The host-side `tools/gbnf_check.cpp` answers the same question without a device and is
     * the one that settled hyphens-versus-underscores. This is the version that runs against
     * the real vocabulary, which is the part the host cannot do: a grammar can parse and still
     * be unsatisfiable by a particular tokenizer.
     */
    @Test
    fun grammarLoadsAgainstTheRealVocabulary() {
        val model = gguf ?: return skip("no GGUF pushed")
        val llama = Llama()
        assumeTrue(llama.open())
        assertTrue(llama.loadModel(model))
        try {
            assertTrue(llama.newContext())
            val tokens = llama.prefillSystem(program.systemPrefix)
            assertTrue("the system prefix did not prefill: $tokens", tokens > 0)

            // The scaffold has to end on a token boundary the utterance cannot merge across,
            // or n_system is not a stable cut and the KV cache answers with a corrupted prompt.
            val prefixTokens = llama.tokenCount(program.systemPrefix)
            val withShort = llama.tokenCount(program.systemPrefix + "hallo")
            val withLong = llama.tokenCount(program.systemPrefix + "weck mich in 20 minuten")
            assertEquals(
                "the utterance's first token merged backwards into the system prefix",
                prefixTokens + llama.tokenCount("hallo"),
                withShort,
            )
            assertTrue(withLong > withShort)
        } finally {
            llama.close()
        }
    }

    /**
     * `TokenEstimateCalibrationTest` — the estimator may over-count and must never under-count.
     *
     * Prints the ratio, which is what a future tightening of [TokenEstimate] should be based on
     * rather than on taste.
     */
    @Test
    fun tokenEstimateNeverUnderCounts() {
        val model = gguf ?: return skip("no GGUF pushed")
        val llama = Llama()
        assumeTrue(llama.open())
        assertTrue(llama.loadModel(model))
        try {
            assertTrue(llama.newContext())
            val samples = listOf(
                program.systemPrefix,
                PromptGenerator.NO_COMMAND_EXAMPLE,
                "weck mich in 20 minuten",
                Tier2Json.encode("clock.set_timer", mapOf("amount" to 9999, "unit" to "sekunden")),
                Tier2Json.encode("none"),
            )
            for (text in samples) {
                val real = llama.tokenCount(text)
                val estimated = TokenEstimate.of(text)
                assertTrue(
                    "estimator UNDER-counted: $estimated < $real for ${text.take(60)}",
                    estimated >= real,
                )
                println("tokens: real=$real estimated=$estimated ratio=${"%.2f".format(estimated.toDouble() / real)}")
            }

            // And the real length of the longest grammar-legal reply, which is the number
            // Tier2.MAX_REPLY_TOKENS has to exceed.
            val longest = Tier2Catalog.of(registry()).commands.maxOf { command ->
                llama.tokenCount(
                    Tier2Json.encode(
                        command.id,
                        command.params.associate { spec ->
                            spec.name to when (val type = spec.type) {
                                is ParamType.Integer -> 9999
                                is ParamType.Text -> "a".repeat(GrammarGenerator.MAX_TEXT_CHARS)
                                is ParamType.Enumeration -> type.values.maxByOrNull { it.length } ?: ""
                            }
                        },
                    ),
                )
            }
            println("longest grammar-legal reply: $longest real tokens (cap ${Tier2.MAX_REPLY_TOKENS})")
            assertTrue(
                "the cap is below the longest reply this registry admits",
                longest <= Tier2.MAX_REPLY_TOKENS,
            )
        } finally {
            llama.close()
        }
    }

    /**
     * `Tier2ModelTest` — the measurements §5.4 and §9 are currently carrying estimates for.
     *
     * Sweeps the thread count, times the warm path, and reports the resample count. Whatever
     * this prints is what belongs in `Llama.THREADS`, in `dobby-plan.md` §5.4 and in §9 — the
     * numbers there today are inherited, not measured on a 750G.
     */
    @Test
    fun threadSweepAndWarmLatency() {
        val model = gguf ?: return skip("no GGUF pushed")
        val llama = Llama()
        assumeTrue(llama.open())
        assertTrue(llama.loadModel(model))
        try {
            for (threads in listOf(2, 4, 8)) {
                assertTrue(llama.newContext(threads = threads))
                val prefill = measureTimeMillis { llama.prefillSystem(program.systemPrefix) }
                val latencies = UTTERANCES.map { utterance ->
                    measureTimeMillis {
                        llama.generate(
                            utterance + program.assistantSuffix,
                            program.grammar,
                            GrammarGenerator.ROOT,
                            Tier2.MAX_REPLY_TOKENS,
                        )
                    }
                }.sorted()
                val timings = llama.timings()
                println(
                    "threads=$threads prefill=${prefill}ms " +
                        "p50=${latencies[latencies.size / 2]}ms p95=${latencies[(latencies.size * 95) / 100]}ms " +
                        "decode=${"%.1f".format(timings.tokensPerSecond)} tok/s " +
                        "resamples=${timings.resamples}",
                )
                llama.freeContext()
            }
        } finally {
            llama.close()
        }
    }

    /**
     * `Tier2AccuracyTest` — and the half that matters is the negatives.
     *
     * A model that resolves everything is worse than no model: it turns "wie wird das Wetter"
     * into a timer. The held-out paraphrases say the tier is useful; the non-commands say it is
     * safe.
     */
    @Test
    fun accuracyOnParaphrasesAndNonCommands() {
        val model = gguf ?: return skip("no GGUF pushed")
        val reg = registry()
        val llama = Llama()
        assumeTrue(llama.open())
        assertTrue(llama.loadModel(model))
        try {
            assertTrue(llama.newContext())
            assertTrue(llama.prefillSystem(program.systemPrefix) > 0)
            val resolver = object : io.dobby.core.nlu.llm.Tier2Resolver {
                override val available = true
                override val unavailableReason: String? = null
                override suspend fun generate(utterance: String, program: Tier2Program) =
                    llama.generate(
                        utterance + program.assistantSuffix,
                        program.grammar,
                        GrammarGenerator.ROOT,
                        Tier2.MAX_REPLY_TOKENS,
                    )
            }
            val tier2 = Tier2(reg, resolver, program)

            var correct = 0
            runBlocking {
                for ((utterance, expected) in EXPECTED) {
                    val trace = tier2.resolve(utterance)
                    val got = (trace.outcome as? io.dobby.core.nlu.llm.Tier2Outcome.Resolved)
                        ?.invocation?.commandId
                        ?: if (trace.outcome is io.dobby.core.nlu.llm.Tier2Outcome.NoCommand) "none" else "?"
                    if (got == expected) correct++ else println("MISS: \"$utterance\" → $got, wanted $expected")
                }
            }
            println("Tier 2 accuracy: $correct/${EXPECTED.size}")
            assertNotNull(program.fingerprint)
            assertTrue("accuracy below half is not a tier, it is a coin", correct * 2 >= EXPECTED.size)
        } finally {
            llama.close()
        }
    }

    private fun skip(why: String) {
        println("skipped: $why")
        assumeTrue(why, false)
    }

    private companion object {
        val UTTERANCES = listOf(
            "weck mich in 20 minuten",
            "gib mir in einer viertelstunde bescheid",
            "kannst du mir sagen wie spät es ist",
            "sag mal wie viel uhr haben wir",
            "ich will in 5 minuten erinnert werden",
        )

        /** Held out from the few-shots on purpose: these are not in the prompt. */
        val EXPECTED: List<Pair<String, String>> = listOf(
            "weck mich in 20 minuten" to "clock.set_timer",
            "erinnere mich bitte in einer halben stunde" to "clock.set_timer",
            "in 10 minuten bitte bescheid geben" to "clock.set_timer",
            "kannst du mir sagen wie spät es ist" to "clock.whats_the_time",
            "hast du die genaue zeit" to "clock.whats_the_time",
            "wie viel uhr haben wir gerade" to "clock.whats_the_time",
            // A model that resolves these is worse than no model.
            "wie wird das wetter morgen" to "none",
            "erzähl mir einen witz" to "none",
            "ruf meine mutter an" to "none",
            "was kostet ein liter milch" to "none",
        )
    }
}
