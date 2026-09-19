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
            val tokens = llama.prefillSystem(program.route.systemPrefix)
            assertTrue("the route prefix did not prefill: $tokens", tokens > 0)

            // Every fill grammar too, not just the route one: a grammar can parse and still be
            // unsatisfiable by a particular tokenizer, and only the device can say so.
            for ((id, fill) in program.fill) {
                val reply = llama.generate(
                    program.fillRequest(id, "weck mich in 20 minuten")!!.tail,
                    fill.grammar,
                    GrammarGenerator.ROOT,
                    Tier2.MAX_FILL_TOKENS,
                )
                assertNotNull("fill grammar for $id produced nothing", reply)
                println("$id fill → $reply")
            }

            // The scaffold has to end on a token boundary the utterance cannot merge across,
            // or n_system is not a stable cut and the KV cache answers with a corrupted prompt.
            val prefixTokens = llama.tokenCount(program.route.systemPrefix)
            val withShort = llama.tokenCount(program.route.systemPrefix + "hallo")
            val withLong = llama.tokenCount(program.route.systemPrefix + "weck mich in 20 minuten")
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
                program.route.systemPrefix,
                PromptGenerator.NO_COMMAND_EXAMPLES.first(),
                "weck mich in 20 minuten",
                Tier2Json.encodeParams(mapOf("amount" to 9999, "unit" to "sekunden")),
                "none",
            ) + program.fill.values.map { it.userTurn }
            for (text in samples) {
                val real = llama.tokenCount(text)
                val estimated = TokenEstimate.of(text)
                assertTrue(
                    "estimator UNDER-counted: $estimated < $real for ${text.take(60)}",
                    estimated >= real,
                )
                println("tokens: real=$real estimated=$estimated ratio=${"%.2f".format(estimated.toDouble() / real)}")
            }

            // The real length of the longest label, and of the longest params-only reply: the
            // two numbers MAX_ROUTE_TOKENS and MAX_FILL_TOKENS have to exceed.
            val longestLabel = program.route.labels.maxOf { llama.tokenCount(it) }
            println(
                "longest route label: $longestLabel real tokens " +
                    "(cap ${Tier2.maxRouteTokens(program.route.labels)})",
            )
            assertTrue(
                "the route cap is below the longest label this registry admits",
                longestLabel <= Tier2.maxRouteTokens(program.route.labels),
            )

            val longestFill = Tier2Catalog.of(registry()).withParams.maxOfOrNull { command ->
                llama.tokenCount(
                    Tier2Json.encodeParams(
                        command.params.associate { spec ->
                            spec.name to when (val type = spec.type) {
                                is ParamType.Integer -> 9999
                                is ParamType.Text -> "a".repeat(GrammarGenerator.MAX_TEXT_CHARS)
                                is ParamType.Enumeration -> type.values.maxByOrNull { it.length } ?: ""
                            }
                        },
                    ),
                )
            } ?: 0
            println("longest params-only reply: $longestFill real tokens (cap ${Tier2.MAX_FILL_TOKENS})")
            assertTrue(
                "the fill cap is below the longest reply this registry admits",
                longestFill <= Tier2.MAX_FILL_TOKENS,
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
     *
     * This is the **route** path only; `Tier2AccuracyTest.latencyBySplit` in `:android:app` is
     * where route-only and route+fill are compared against each other, because that is the one
     * module that can build the real registry.
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
                val prefill = measureTimeMillis { llama.prefillSystem(program.route.systemPrefix) }
                val latencies = UTTERANCES.map { utterance ->
                    measureTimeMillis {
                        llama.generate(
                            program.routeRequest(utterance).tail,
                            program.route.grammar,
                            GrammarGenerator.ROOT,
                            Tier2.maxRouteTokens(program.route.labels),
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
    }
}
