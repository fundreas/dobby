package io.dobby.pipeline.tts

/**
 * The per-sentence synthesis callback, as a **class** rather than a lambda — which is
 * load-bearing, not a style choice.
 *
 * sherpa-onnx's `generateWithCallbackImpl` resolves the callback from native code by exact
 * descriptor:
 *
 * ```cpp
 * env->GetMethodID(cls, "invoke", "([F)Ljava/lang/Integer;");
 * ```
 *
 * Kotlin 2.x compiles ordinary lambdas through `invokedynamic`, and D8 desugars those into
 * `…$$ExternalSyntheticLambda<n>` classes that implement only `Function1.invoke(Object)Object`.
 * The lookup then fails, and it fails in the worst possible way: the native side does not check
 * for a pending exception before its next JNI call, so ART aborts the whole process with
 *
 * ```
 * JNI DETECTED ERROR IN APPLICATION: JNI NewFloatArray called with pending exception
 * java.lang.NoSuchMethodError: no non-static method "…invoke([F)Ljava/lang/Integer;"
 * ```
 *
 * — a `SIGABRT` on the `dobby-tts` thread, not a Kotlin exception anything could have caught.
 * On a `START_STICKY` foreground service that is a restart, every time the panel tries to
 * speak.
 *
 * An explicit class is not desugared, so Kotlin emits the type-specialised
 * `invoke([F)Ljava/lang/Integer;` the JNI is looking for alongside the bridge.
 * `SynthesisCallbackTest` asserts exactly that descriptor exists, on the JVM, because the thing
 * that would silently undo it is a Kotlin version bump and the symptom is a phone that reboots
 * its own service.
 *
 * @param onSamples one sentence's samples, normalised to −1..1. Return 1 to carry on, 0 to stop.
 */
internal class SynthesisCallback(
    private val onSamples: (FloatArray) -> Int,
) : (FloatArray) -> Int {
    override fun invoke(samples: FloatArray): Int = onSamples(samples)

    companion object {
        /** The descriptor the native side looks up. Named here so the test cannot drift from it. */
        const val JNI_RETURN_TYPE: String = "java.lang.Integer"
    }
}
