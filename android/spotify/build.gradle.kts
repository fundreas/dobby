import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.library)
}

/**
 * The Spotify App Remote SDK, packaged for this project.
 *
 * Spotify publishes no Maven artifact for it — `com.spotify.android` on Maven Central holds
 * `auth` and `auth-store` and nothing else — so the AAR is fetched from the GitHub release,
 * pinned by SHA-256, into a git-ignored local Maven repository that `settings.gradle.kts`
 * declares for this one coordinate. Same reasoning as `:android:sherpa`'s `fetchSherpaJni` and
 * `:android:pipeline`'s `fetchEspeakData`: a binary that can be re-downloaded byte-for-byte
 * does not belong in history, and a third-party binary is the last thing in this project to
 * take on trust.
 *
 * If a Maven coordinate ever appears, this whole file collapses to one dependency line.
 */
val appRemoteVersion = libs.versions.spotifyAppRemote.get()

val appRemoteUrl =
    "https://github.com/spotify/android-sdk/releases/download/" +
        "v$appRemoteVersion-appremote_v2.1.0-auth/spotify-app-remote-release-$appRemoteVersion.aar"

/** 132 749 bytes. Verified against the release asset on 2026-09-20. */
val appRemoteSha256 = "b5a6dd880eaf01f63a871cba9ef7af77c341f8a94ffc8fdf2e9021f9a9d4c198"

/**
 * Where the AAR has to land: a Maven layout with no POM beside it, which is why the repository
 * in `settings.gradle.kts` declares `metadataSources { artifact() }`.
 */
val appRemoteAar = layout.projectDirectory
    .file("libs/m2/com/spotify/android/app-remote/$appRemoteVersion/app-remote-$appRemoteVersion.aar")

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

/**
 * Fetched at **configuration time**, and the other two download blocks in this repo are not.
 *
 * The difference is what consumes the file. `fetchSherpaJni` produces a `.so` that a packaging
 * task reads, and a task dependency is enough. This produces a *module dependency*, and Gradle
 * resolves a configuration with no knowledge of the task that would have created the artifact —
 * `:android:app:collectReleaseDependencies` really does go looking for it, in parallel, before
 * `:android:spotify:preBuild` has run. A task here is a race that fails one build in three.
 *
 * The cost is bounded by the checksum: the download happens exactly once per checkout, and
 * every configuration after it is one SHA-256 of 130 KB.
 */
fun ensureAppRemoteAar() {
    val target = appRemoteAar.asFile
    if (target.isFile && sha256(target) == appRemoteSha256) return
    logger.lifecycle("Fetching the Spotify App Remote SDK ($appRemoteVersion)…")
    target.parentFile.mkdirs()
    // Through a partial file, so an interrupted download is not mistaken for a cached one on
    // the next build — that would only be caught by the checksum, one download later.
    val partial = File(target.parentFile, "${target.name}.part")
    uri(appRemoteUrl).toURL().openStream().use { input ->
        partial.outputStream().use { input.copyTo(it) }
    }
    val actual = sha256(partial)
    if (actual != appRemoteSha256) {
        partial.delete()
        error(
            "Spotify App Remote AAR checksum mismatch\n" +
                "  expected $appRemoteSha256\n  actual   $actual\n  from $appRemoteUrl",
        )
    }
    target.delete()
    partial.renameTo(target)
}

ensureAppRemoteAar()

android {
    namespace = "io.dobby.spotify"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // The AAR is third-party and unvendorable; holding it to our own rules only teaches
        // people to add suppressions. Same call as :android:sherpa.
        checkDependencies = false
    }
}

dependencies {
    api(project(":socks:spotify"))
    implementation(libs.kotlinx.coroutines.android)
    // Out of the `spotifyAppRemote` repository in settings.gradle.kts, which the block above
    // fills. `@aar` because there is no POM to tell Gradle what the artifact is.
    implementation("com.spotify.android:app-remote:$appRemoteVersion@aar")
    // The App Remote needs Gson at runtime and does not declare it (spotify.specs.md §1).
    implementation(libs.gson)
}
