package io.dobby.android

import androidx.test.platform.app.InstrumentationRegistry
import io.dobby.core.nlu.Normalizer
import io.dobby.core.nlu.llm.Step
import io.dobby.core.nlu.llm.Tier2
import io.dobby.core.nlu.llm.Tier2Outcome
import io.dobby.core.nlu.llm.Tier2Program
import io.dobby.core.nlu.llm.Tier2Request
import io.dobby.core.nlu.llm.Tier2Resolver
import io.dobby.core.registry.SockRegistry
import io.dobby.core.sock.CommandInvocation
import io.dobby.core.testing.Tier2Negatives
import io.dobby.llama.Llama
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.system.measureTimeMillis

/**
 * How good Tier 2 actually is, against the Socks that ship.
 *
 * It lives here rather than in `:android:llama` because this is the one module that can build
 * the **real** registry — `DobbySocks.create(null)` — and a 2-command fixture cannot say
 * anything about how a model behaves when it has eleven plausible answers to choose between.
 *
 * ### What it measures, and why the negatives are the important half
 *
 * Every [io.dobby.core.sock.Example] marked `heldOut` is a paraphrase the model has **never
 * been shown**: `Introspection.promptExamples` excludes them, so neither prompt contains them.
 * That is the difference between measuring understanding and measuring memorisation.
 *
 * [Tier2Negatives] is the other half, and the half that decides whether this tier may ship. A
 * model that resolves everything is worse than no model: one that turns "wie wird das Wetter
 * morgen" into a timer has made the panel unpredictable, and unpredictable is the one thing a
 * wall panel may not be. **`negativesResolved` is the number to watch, and it should be zero.**
 *
 * Because the split is two decisions, every miss is attributed to the step that made it — a
 * command the route never reached is a routing problem, and a command it reached and could not
 * fill is a fill-prompt problem. The single shot could not tell those apart.
 *
 * Needs the GGUF pushed; skips rather than fails without it:
 *
 *     adb push Qwen3-1.7B-Q4_K_M.gguf /sdcard/Android/data/io.dobby.android.test/files/
 */
class Tier2AccuracyTest {

    private val registry = SockRegistry.buildOrThrow(DobbySocks.create(null).socks)

    private val program = Tier2Program.of(registry)

    private fun gguf(): File? = InstrumentationRegistry.getInstrumentation().targetContext
        .getExternalFilesDir(null)
        ?.let { File(it, "Qwen3-1.7B-Q4_K_M.gguf") }
        ?.takeIf { it.isFile }

    /** Every held-out case in the shipped registry, as `utterance → expected command id`. */
    private fun heldOut(): List<Pair<String, String>> = registry.commands.values
        .flatMap { command ->
            command.examples.filter { it.heldOut }
                .map { Normalizer.normalize(it.utterance) to command.id }
        }
        .sortedBy { it.first }

    @Test
    fun accuracyAndNegatives() {
        val model = gguf() ?: return skip("no GGUF pushed")
        val cases = heldOut()
        assumeTrue("no held-out examples declared yet", cases.isNotEmpty())

        val llama = Llama()
        assumeTrue("CPU cannot run the library", llama.open())
        assertTrue(llama.loadModel(model))
        try {
            assertTrue(llama.newContext())
            assertTrue(llama.prefillSystem(program.route.systemPrefix) > 0)
            val tier2 = Tier2(registry, DirectResolver(llama), program)

            var correct = 0
            var routeMisses = 0
            var fillMisses = 0
            val perCommand = mutableMapOf<String, Pair<Int, Int>>()

            runBlocking {
                for ((utterance, expected) in cases) {
                    val trace = tier2.resolve(utterance)
                    val got = (trace.outcome as? Tier2Outcome.Resolved)?.invocation?.commandId
                    val hit = got == expected
                    if (hit) {
                        correct++
                    } else {
                        // Attribute the miss to the step that made it.
                        if (trace.route?.raw?.trim() == expected) fillMisses++ else routeMisses++
                        println(
                            "MISS \"$utterance\": wanted $expected, route said " +
                                "'${trace.route?.raw?.trim()}', fill said '${trace.fill?.raw?.trim()}'",
                        )
                    }
                    val (hits, total) = perCommand[expected] ?: (0 to 0)
                    perCommand[expected] = (hits + if (hit) 1 else 0) to (total + 1)
                }
            }

            // The negatives. Each of these must come back as `none`; anything else is the panel
            // acting on a sentence that was not addressed to it.
            var negativesResolved = 0
            runBlocking {
                for (sentence in Tier2Negatives.all) {
                    val trace = tier2.resolve(sentence)
                    val resolved = trace.outcome as? Tier2Outcome.Resolved
                    if (resolved != null) {
                        negativesResolved++
                        println("NEGATIVE RESOLVED \"$sentence\" → ${resolved.invocation.commandId}")
                    }
                }
            }

            println("Tier 2 accuracy: $correct/${cases.size} held-out, $routeMisses route misses, $fillMisses fill misses")
            println("Tier 2 per command: " + perCommand.entries.sortedBy { it.key }.joinToString(", ") {
                "${it.key} ${it.value.first}/${it.value.second}"
            })
            println("Tier 2 negatives resolved as a command: $negativesResolved/${Tier2Negatives.all.size}")

            // The threshold is deliberately set from the baseline rather than from taste, and
            // until a baseline has been measured on a real device the only thing asserted is
            // the one that is not a judgement call: a panel must not act on a sentence nobody
            // addressed to it. Write the accuracy number into m6b-plan.md's Measured table and
            // tighten this once there is something to compare against.
            assertEquals(
                "a negative resolved as a command — see the NEGATIVE RESOLVED lines above",
                0,
                negativesResolved,
            )
        } finally {
            llama.close()
        }
    }

    /**
     * Route-only and route+fill latency, separately.
     *
     * The split is the whole point of the measurement: a zero-param command pays for one
     * request and a command with params pays for two, and **the fill prefill is the cost this
     * design adds**. If it is large, the lever is the one `m6b-plan.md` names — move the param
     * specs into the cached route prefix — and this number is what decides.
     */
    @Test
    fun latencyBySplit() {
        val model = gguf() ?: return skip("no GGUF pushed")
        val llama = Llama()
        assumeTrue(llama.open())
        assertTrue(llama.loadModel(model))
        try {
            assertTrue(llama.newContext())
            assertTrue(llama.prefillSystem(program.route.systemPrefix) > 0)

            val zeroParam = registry.commands.values.first { it.params.isEmpty() }.id
            val withParams = registry.commands.values.firstOrNull { it.params.isNotEmpty() }?.id

            val routeOnly = ROUTE_ONLY_UTTERANCES.map { utterance ->
                measureTimeMillis { llama.generate(routeTail(utterance), program.route.grammar, "root", 14) }
            }.sorted()
            println("route-only p50=${routeOnly[routeOnly.size / 2]}ms p95=${routeOnly.last()}ms ($zeroParam-shaped)")

            if (withParams != null) {
                val fill = program.fill.getValue(withParams)
                val both = WITH_PARAMS_UTTERANCES.map { utterance ->
                    measureTimeMillis {
                        llama.generate(routeTail(utterance), program.route.grammar, "root", 14)
                        llama.generate(
                            program.fillRequest(withParams, utterance)!!.tail,
                            fill.grammar,
                            "root",
                            Tier2.MAX_FILL_TOKENS,
                        )
                    }
                }.sorted()
                // The fill request's tail is the expensive, uncached part: this is its prefill.
                val timings = llama.timings()
                println("route+fill p50=${both[both.size / 2]}ms p95=${both.last()}ms ($withParams)")
                println("fill prefill=${timings.prefillMicros / 1000}ms, resamples=${timings.resamples}")
            }
        } finally {
            llama.close()
        }
    }

    private fun routeTail(utterance: String) =
        program.routeRequest(utterance).tail

    /** Calls the model directly, so the suite measures Tier 2 and not LlamaTier2's threading. */
    private class DirectResolver(private val llama: Llama) : Tier2Resolver {
        override val available = true
        override val unavailableReason: String? = null

        override suspend fun generate(request: Tier2Request): String? = llama.generate(
            tail = request.tail,
            grammar = request.grammar,
            root = request.root,
            maxTokens = request.maxTokens,
        ).also {
            if (request.step == Step.FILL && it == null) println("fill returned nothing")
        }
    }

    private fun skip(why: String) {
        println("skipped: $why")
        assumeTrue(why, false)
    }

    private companion object {
        /** Held out, and all zero-param commands, so no fill step is involved. */
        val ROUTE_ONLY_UTTERANCES = listOf(
            "was ist denn bitte gerade die uhrzeit",
            "wie viel uhr haben wir gerade",
            "der timer kann weg",
            "was geht denn hier alles",
            CommandInvocation.NONE.let { "erzähl mir einen witz" },
        )

        val WITH_PARAMS_UTTERANCES = listOf(
            "sag mir in 10 minuten bescheid",
            "in einer halben stunde bitte klingeln",
            "ich will in 5 minuten dran erinnert werden",
        )
    }
}
