package io.dobby.pipeline.tts

import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The one method signature that sherpa-onnx's TTS JNI resolves by hand.
 *
 * `generateWithCallbackImpl` does `GetMethodID(cls, "invoke", "([F)Ljava/lang/Integer;")` on
 * whatever object it was handed, and then does **not** check for a pending exception before its
 * next JNI call. So an object without that exact method is not a `NoSuchMethodError` anybody can
 * catch — it is `JNI DETECTED ERROR IN APPLICATION`, a `SIGABRT`, and on a `START_STICKY`
 * foreground service a restart loop every time the panel tries to speak.
 *
 * That is not a hypothetical: it is what shipped in the first cut of M2c. A Kotlin lambda
 * compiles through `invokedynamic`, D8 desugars it to a `$$ExternalSyntheticLambda` class
 * carrying only `invoke(Object)Object`, and the panel aborted on its first sentence.
 *
 * The fix is one class, and the thing that would silently undo it is a Kotlin version bump.
 * A phone is the worst place to find that out and this is the cheapest: a reflective check with
 * no device, no model and no native library in it.
 */
class SynthesisCallbackTest {

    @Test
    fun `the callback carries the exact descriptor the native side looks up`() {
        val invoke = SynthesisCallback::class.java
            .getDeclaredMethod("invoke", FloatArray::class.java)

        // ([F)Ljava/lang/Integer; — the boxed return matters as much as the parameter. A method
        // returning primitive `int` has a different descriptor and would not be found.
        assertEquals(
            SynthesisCallback.JNI_RETURN_TYPE,
            invoke.returnType.name,
            "the JNI looks up invoke([F)Ljava/lang/Integer; and would abort the process",
        )
        assertTrue(Modifier.isPublic(invoke.modifiers), "the JNI needs it public")
    }

    @Test
    fun `an ordinary Kotlin lambda is why this class exists`() {
        // Not a requirement — it is the evidence. If this ever stops holding, Kotlin has changed
        // how it compiles lambdas and the KDoc above needs rewriting; until then it is the
        // reason nobody should "simplify" the call site back to a lambda.
        val lambda: (FloatArray) -> Int = { it.size }
        val direct = lambda.javaClass.methods.filter { method ->
            method.name == "invoke" &&
                method.parameterTypes.contentEquals(arrayOf(FloatArray::class.java))
        }
        assertTrue(
            direct.none { it.returnType.name == SynthesisCallback.JNI_RETURN_TYPE },
            "a lambda now has the descriptor too: ${direct.map { it.toGenericString() }}",
        )
    }

    @Test
    fun `it passes the samples through and returns what the body decided`() {
        val seen = mutableListOf<Int>()
        val callback = SynthesisCallback { samples ->
            seen += samples.size
            if (samples.size > 2) 0 else 1
        }

        // Through the Function1 the JNI sees, not through the lambda it wraps.
        assertEquals(1, callback.invoke(FloatArray(2)))
        assertEquals(0, callback.invoke(FloatArray(3)))
        assertEquals(listOf(2, 3), seen)
    }
}
