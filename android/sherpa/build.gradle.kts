import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.library)
}

/**
 * sherpa-onnx, packaged for this project.
 *
 * Upstream publishes no Maven artifact (`dobby-plan.md` §4), so the two halves arrive
 * separately: the Kotlin API is vendored verbatim under `src/main/kotlin` and lives in git, and
 * the 24 MB `libsherpa-onnx-jni.so` is fetched here at build time into `src/main/jniLibs`,
 * which is git-ignored. Same reasoning as the models — a binary that can be re-downloaded
 * byte-for-byte does not belong in history.
 *
 * It lands in the ordinary source directory rather than under `build/` on purpose: that is the
 * one place every AGP version agrees jniLibs come from, and this module's whole job is to be
 * boring. See `README.md` in this directory for the update procedure.
 */
val sherpaVersion = libs.versions.sherpaOnnx.get()

/**
 * The **static-link** build, and that is load-bearing.
 *
 * The ordinary Android bundle ships its own `libonnxruntime.so`, which would land in the APK
 * beside the one `onnxruntime-android` brings for the wake word — two files with one name. This
 * variant has ONNX Runtime linked inside it and exports nothing but
 * `Java_com_k2fsa_sherpa_onnx_*`, so the wake word's runtime and the recogniser's cannot reach
 * each other at all. For a single-ABI build it is also the smaller of the two.
 */
val sherpaArchiveUrl =
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaVersion/" +
        "sherpa-onnx-v$sherpaVersion-android-static-link-onnxruntime.tar.bz2"

/** A native library is the last thing in this project to take on trust. */
val sherpaArchiveSha256 = "7583ca385ae7d981e65468455c2ea2c9f2da383921dccfc5658d0dc19d309e6f"

val jniLibsDirectory = layout.projectDirectory.dir("src/main/jniLibs")
val sherpaLibrary = jniLibsDirectory.file("arm64-v8a/libsherpa-onnx-jni.so")

fun sha256(file: File): String = file.inputStream().buffered().use { stream ->
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(1 shl 16)
    while (true) {
        val read = stream.read(buffer)
        if (read < 0) break
        digest.update(buffer, 0, read)
    }
    digest.digest().joinToString("") { "%02x".format(it) }
}

val fetchSherpaJni = tasks.register("fetchSherpaJni") {
    description = "Downloads and verifies libsherpa-onnx-jni.so for arm64-v8a."
    group = "build setup"

    inputs.property("url", sherpaArchiveUrl)
    inputs.property("sha256", sherpaArchiveSha256)
    outputs.file(sherpaLibrary)

    doLast {
        val archive = layout.buildDirectory
            .file("sherpa-jni-archive/sherpa-onnx-$sherpaVersion.tar.bz2").get().asFile

        if (!archive.isFile || sha256(archive) != sherpaArchiveSha256) {
            archive.parentFile.mkdirs()
            uri(sherpaArchiveUrl).toURL().openStream().use { input ->
                archive.outputStream().use { input.copyTo(it) }
            }
        }
        val actual = sha256(archive)
        check(actual == sherpaArchiveSha256) {
            "sherpa-onnx archive checksum mismatch\n" +
                "  expected $sherpaArchiveSha256\n  actual   $actual\n  from $sherpaArchiveUrl"
        }

        val target = jniLibsDirectory.asFile
        target.deleteRecursively()
        copy {
            from(tarTree(resources.bzip2(archive))) {
                // arm64-v8a only: the device is a Nord CE (`dobby-plan.md` §4), and the other
                // ABIs in the archive are another 70 MB for nothing.
                include("**/arm64-v8a/libsherpa-onnx-jni.so")
                eachFile { path = "arm64-v8a/$name" }
            }
            into(target)
            includeEmptyDirs = false
        }
        check(sherpaLibrary.asFile.isFile) {
            "the sherpa-onnx archive did not contain arm64-v8a/libsherpa-onnx-jni.so"
        }
    }
}

android {
    namespace = "com.k2fsa.sherpa.onnx"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // Vendored third-party source. The rule this project holds itself to is in the root
        // build file; holding someone else's code to it only teaches people to add suppressions.
        checkDependencies = false
        abortOnError = false
    }
}

// Before anything is compiled or packaged, so a clean checkout builds without a manual step.
tasks.named("preBuild") {
    dependsOn(fetchSherpaJni)
}

// Same reason as the lint block: `allWarningsAsErrors` is our discipline, not upstream's, and
// the alternative is editing vendored files — which is the one thing that makes them unvendorable.
extensions.configure<KotlinAndroidProjectExtension>("kotlin") {
    compilerOptions {
        allWarningsAsErrors.set(false)
    }
}
