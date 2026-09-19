package io.dobby.llama

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The CPU gate, on the JVM, where the interesting case can actually be constructed.
 *
 * `CpuFeatureGateTest` on a device can only ever test the device it is running on. The case
 * that matters — a CPU *without* `asimddp`, which is legal on an A55 and would be a SIGILL at
 * the first matmul — has to be synthesised, and that is what this does.
 */
class CpuFeatureTest {

    /** A real `/proc/cpuinfo` from a Snapdragon-class arm64 device, trimmed to what is read. */
    private val nordCe = """
        processor	: 0
        BogoMIPS	: 38.40
        Features	: fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid asimdrdm lrcpc dcpop asimddp
        CPU implementer	: 0x51
        CPU architecture: 8

        processor	: 1
        Features	: fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid asimdrdm lrcpc dcpop asimddp
    """.trimIndent()

    @Test
    fun `the features line is parsed`() {
        val features = Llama.cpuFeatures { nordCe }
        assertTrue("asimddp" in features)
        assertTrue("fphp" in features)
        assertTrue("crc32" in features)
        assertEquals(false, "i8mm" in features, "i8mm needs armv8.6-a and is not on this part")
    }

    @Test
    fun `a CPU without dotprod is caught before the library is loaded`() {
        val without = nordCe.replace(" asimddp", "")
        val features = Llama.cpuFeatures { without }
        val missing = Llama.REQUIRED_CPU_FEATURES.filterNot { it in features }
        // The whole point of the gate: this is the answer that must stop System.loadLibrary,
        // because the alternative is SIGILL on a START_STICKY service, which is a boot loop.
        assertEquals(Llama.REQUIRED_CPU_FEATURES, missing)
    }

    @Test
    fun `an unreadable cpuinfo is treated as unsupported, not as supported`() {
        assertEquals(emptySet(), Llama.cpuFeatures { null })
        assertEquals(emptySet(), Llama.cpuFeatures { "" })
        assertEquals(emptySet(), Llama.cpuFeatures { "processor : 0\nBogoMIPS : 38.40" })
        // Fail closed: no Features line means no evidence, and no evidence means no load.
        assertTrue(Llama.REQUIRED_CPU_FEATURES.none { it in Llama.cpuFeatures { null } })
    }

    @Test
    fun `the required features match what the library was built for`() {
        // build.gradle.kts compiles ggml for `armv8.2-a+fp16+dotprod`. `asimddp` is the
        // kernel's name for `+dotprod`, and it is the optional one on an A55. If the arch in
        // the build file changes, this is what says the runtime check has to change with it.
        assertTrue("dotprod" in BuildConfig.CPU_ARCH, "CPU_ARCH is ${BuildConfig.CPU_ARCH}")
        assertEquals(listOf("asimddp"), Llama.REQUIRED_CPU_FEATURES)
        assertTrue(
            "i8mm" !in BuildConfig.CPU_ARCH,
            "+i8mm needs armv8.6-a; this part is armv8.2-a",
        )
    }
}
