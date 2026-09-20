# `:android:sherpa` — vendored sherpa-onnx

[sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) is the STT **and TTS** runtime
(`dobby-plan.md` §4): Parakeet's offline recogniser and Piper's `OfflineTts` are the same
library, and espeak-ng is inside it. It publishes no Maven artifact, so it arrives here in two
halves.

**The Kotlin API** — `src/main/kotlin/com/k2fsa/sherpa/onnx/*.kt`, copied **verbatim** from
`sherpa-onnx/kotlin-api/` at the pinned tag. These are not ordinary Kotlin files: the JNI layer
reads their fields by name and signature, so an "improvement" to one of them is a crash at
runtime with no compiler complaint. Do not edit them, do not reformat them, do not delete the
unused demo catalogues inside them — a file that differs from upstream is a file nobody can
diff on the next version bump. That is also why this module opts out of the project's
`allWarningsAsErrors`: the alternative is editing them.

Only the files the pipeline actually needs are vendored: `OfflineRecognizer`, `OfflineStream`,
`Vad`, `FeatureConfig`, `QnnConfig`, `HomophoneReplacerConfig`, `WaveReader`, `Tts`.

`Tts.kt` brings the whole of upstream's TTS catalogue — Matcha, Kokoro, Kitten, ZipVoice,
Pocket, Supertonic — and only `OfflineTtsVitsModelConfig` is used. Those are the "unused demo
catalogues" the paragraph above means: deleting them is what makes the next version bump
undiffable.

**The native library** — `libsherpa-onnx-jni.so`, ~24 MB, downloaded and checksum-verified by
`fetchSherpaJni` in `build.gradle.kts` into `build/sherpa-jni/arm64-v8a/`. Same rule as the
models: a binary that can be re-downloaded byte-for-byte does not belong in git history.

The archive is the **static-link-onnxruntime** variant on purpose. The ordinary Android bundle
ships its own `libonnxruntime.so`, which would land in the APK next to the one
`onnxruntime-android` brings for the wake word — two copies of the same library name. The
static-link build has ONNX Runtime inside it and exports nothing but
`Java_com_k2fsa_sherpa_onnx_*`, so the wake word's runtime and the recogniser's cannot reach
each other.

## Updating

1. Bump `sherpaOnnx` in `gradle/libs.versions.toml`.
2. Re-download the archive and put its SHA-256 in `sherpaArchiveSha256`:
   ```sh
   V=1.13.8
   curl -fL -o /tmp/sherpa.tar.bz2 \
     "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$V/sherpa-onnx-v$V-android-static-link-onnxruntime.tar.bz2"
   sha256sum /tmp/sherpa.tar.bz2
   ```
3. Re-copy the Kotlin files from the same tag:
   ```sh
   for f in FeatureConfig HomophoneReplacerConfig QnnConfig OfflineStream OfflineRecognizer Vad WaveReader Tts; do
     curl -fL -o "android/sherpa/src/main/kotlin/com/k2fsa/sherpa/onnx/$f.kt" \
       "https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/v$V/sherpa-onnx/kotlin-api/$f.kt"
   done
   ```
4. Read the diff. A changed field name in a config class is a silent breakage in
   `io.dobby.pipeline.stt` or `io.dobby.pipeline.tts` — the JNI reads those fields by name, so
   nothing complains at compile time.

## Licence

sherpa-onnx is Apache-2.0. The vendored files keep their upstream copyright headers.
