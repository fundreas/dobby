import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.library)
}

/**
 * espeak-ng's phoneme data, fetched at build time and shipped as an asset.
 *
 * Piper voices are phoneme models and espeak-ng is the text-to-phoneme step, already inside
 * `libsherpa-onnx-jni.so`. What is *not* inside it is its 355-file data directory, which it
 * opens with `fopen` — so it has to exist on a real filesystem path, and an APK asset is the
 * only way to get 18 MB of small files onto a device without 355 round trips.
 * `io.dobby.pipeline.tts.EspeakData` copies it out to `filesDir` on first run.
 *
 * Same shape as `:android:sherpa`'s `fetchSherpaJni`, deliberately: pinned by SHA-256,
 * downloaded once into the build directory, unpacked into a generated asset directory, and
 * hooked on `preBuild` so a clean checkout builds without a manual step.
 */
val espeakArchiveUrl =
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/espeak-ng-data.tar.bz2"

/**
 * 7 252 012 bytes compressed; 355 files and 17 991 651 bytes unpacked.
 *
 * Byte-identical in both voice tarballs and in the standalone archive — one copy serves every
 * voice, which is what makes shipping it in the APK defensible at all (+7 MB).
 */
val espeakArchiveSha256 = "4135ccf82e1f40613491c0874d4945ae9e9c7840933d8e25a6f9e003d9ebf533"

/** What it unpacks to. A short copy is a voice that loads, runs, and says nothing. */
val espeakFileCount = 355

val espeakAssets = layout.buildDirectory.dir("generated/espeak/assets")

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

val fetchEspeakData = tasks.register("fetchEspeakData") {
    description = "Downloads and verifies espeak-ng's phoneme data, and unpacks it into assets."
    group = "build setup"

    inputs.property("url", espeakArchiveUrl)
    inputs.property("sha256", espeakArchiveSha256)
    inputs.property("files", espeakFileCount)
    outputs.dir(espeakAssets)

    doLast {
        val archive = layout.buildDirectory
            .file("espeak-archive/espeak-ng-data.tar.bz2").get().asFile

        if (!archive.isFile || sha256(archive) != espeakArchiveSha256) {
            archive.parentFile.mkdirs()
            // Through a partial file, so an interrupted download is not mistaken for a cached
            // one on the next build — that would only be caught by the checksum, one 7 MB
            // download later.
            val partial = File(archive.parentFile, "${archive.name}.part")
            uri(espeakArchiveUrl).toURL().openStream().use { input ->
                partial.outputStream().use { input.copyTo(it) }
            }
            partial.renameTo(archive)
        }
        val actual = sha256(archive)
        check(actual == espeakArchiveSha256) {
            "espeak-ng-data archive checksum mismatch\n" +
                "  expected $espeakArchiveSha256\n  actual   $actual\n  from $espeakArchiveUrl"
        }

        val target = espeakAssets.get().asFile
        target.deleteRecursively()
        copy {
            // The archive's entries are already under `espeak-ng-data/`, which is the asset
            // path EspeakData reads and the directory name it keeps on the device.
            from(tarTree(resources.bzip2(archive)))
            into(target)
            includeEmptyDirs = false
        }

        val unpacked = File(target, "espeak-ng-data")
        check(unpacked.isDirectory) { "the archive did not contain espeak-ng-data/" }
        val count = unpacked.walkTopDown().count { it.isFile }
        check(count == espeakFileCount) {
            "espeak-ng-data unpacked to $count files, expected $espeakFileCount — " +
                "a voice missing a dictionary loads, runs, and says nothing"
        }
    }
}

android {
    namespace = "io.dobby.pipeline"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        // One source of truth for the pin: the runtime stamps the copied directory with the
        // hash, so a bump here replaces what is on the device rather than merging two versions
        // of it — and the count is what a half-copied directory is caught by.
        buildConfigField("String", "ESPEAK_DATA_SHA256", "\"$espeakArchiveSha256\"")
        buildConfigField("int", "ESPEAK_DATA_FILES", "$espeakFileCount")
    }

    buildFeatures {
        buildConfig = true
    }

    sourceSets {
        // A resolved path rather than a provider: AGP 9 refuses providers here, and the variant
        // API's `addGeneratedSourceDirectory` needs a task *type*, which a build script cannot
        // declare. The dependency is carried by the two blocks below instead.
        getByName("main") { assets.srcDir(espeakAssets.get().asFile) }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Before anything is compiled or packaged, so a clean checkout builds without a manual step.
tasks.named("preBuild") {
    dependsOn(fetchEspeakData)
}

// And explicitly before the assets are merged, which is the task that actually reads the
// directory. Without this a clean build can merge an empty one and ship a voice with no
// phonemes — a failure that shows up on a device, once, on whichever machine built it.
tasks.matching { it.name.contains("Assets") }.configureEach {
    dependsOn(fetchEspeakData)
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)
    api(libs.kotlinx.coroutines.core)
    api(project(":android:sherpa"))
    api(libs.onnxruntime.android)

    testImplementation(libs.kotlinx.coroutines.test)
    // The desktop build of the same runtime, so the wake word's inference chain can be
    // verified against the real model graphs on the JVM instead of only on a phone.
    testImplementation(libs.onnxruntime.jvm)
}
