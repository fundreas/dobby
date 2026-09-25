import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/**
 * Device-local secrets, out of git and into `BuildConfig`.
 *
 * `local.properties` is already git-ignored and already where this project puts device-local
 * configuration. Empty strings rather than a build failure when a key is missing: a fresh
 * clone must build, and missing credentials make the Spotify Sock `Unavailable` at `onStart`
 * with a reason that says so — the same path as a missing Spotify app.
 *
 * **The secret is in the APK and extractable.** For a wall panel in one flat the blast radius
 * is somebody burning our Spotify search rate limit, and the fix is rotating it in the
 * developer dashboard. Same class of accepted risk as the non-commercial wake-word models
 * (`dobby-plan.md` §9), written down so it is not rediscovered as a surprise. If Dobby is ever
 * handed to anyone else, this is the first thing that has to change — to PKCE, whose seam is
 * `SpotifyTokens.bearer()` (`spotify.specs.md` §1).
 */
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use(::load)
}

fun secret(key: String): String = (localProperties[key] as? String).orEmpty()

android {
    namespace = "io.dobby.android"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "io.dobby.android"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        // Instrumented tests only (`dobby-plan.md` §5.2): the STT suite needs the real model on
        // a real device. JVM unit tests stay on JUnit 5 and never touch this runner.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionName = "0.2.0-phase-b"

        // spotify.specs.md §1. The redirect URI is registered in the Spotify developer
        // dashboard alongside the package name and both signing SHA-1s; App Remote hands it
        // back through the consent dialog and never through a browser, so nothing here has to
        // answer to it.
        buildConfigField("String", "SPOTIFY_CLIENT_ID", "\"${secret("spotify.client.id")}\"")
        buildConfigField("String", "SPOTIFY_CLIENT_SECRET", "\"${secret("spotify.client.secret")}\"")
        buildConfigField(
            "String",
            "SPOTIFY_REDIRECT_URI",
            "\"${localProperties["spotify.redirect.uri"] as? String ?: "dobby://spotify-callback"}\"",
        )
        ndk {
            // The device is a vivo IV2201 (MediaTek MT6877); nothing else needs the ONNX
            // Runtime and sherpa-onnx native libraries, which are most of the APK.
            abiFilters += "arm64-v8a"
        }
    }

    buildFeatures {
        compose = true
        // For the Spotify credentials above. Nothing else in this module reads BuildConfig.
        buildConfig = true
    }

    lint {
        // Same rule as the Kotlin compiler: a warning left standing is a warning nobody reads.
        warningsAsErrors = true
        disable += setOf(
            // targetSdk and compileSdk are already the newest platform the SDK offers.
            "OldTargetApi",
            "GradleDependency",
            // arm64-v8a only, on purpose: the device is a vivo IV2201 (dobby-plan.md §4).
            "ChromeOsAbiSupport",
            // There is nothing to back up — the model is re-downloadable and config is local.
            "DataExtractionRules",
        )
    }

    testOptions {
        unitTests {
            // Log.d on a JVM test classpath throws "not mocked" by default. The controller
            // logs; nothing under test depends on what the log did.
            isReturnDefaultValues = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":android:pipeline"))
    // The local model. One .so, built from the pinned llama.cpp submodule — see
    // android/llama/README.md for why it is a submodule and not a prebuilt.
    implementation(project(":android:llama"))
    implementation(project(":socks:clock"))
    implementation(project(":socks:calculator"))
    implementation(project(":socks:help"))
    implementation(project(":socks:conversation"))
    implementation(project(":socks:spotify"))
    implementation(project(":socks:radio"))
    implementation(project(":socks:system"))
    implementation(project(":socks:memo"))
    implementation(project(":socks:weather"))
    implementation(project(":socks:departures"))
    // The App Remote, behind the interfaces :socks:spotify declares.
    implementation(project(":android:spotify"))
    // Winky is a development Sock and must not reach a release build (dobby-plan.md §8).
    debugImplementation(project(":socks:winky"))

    // The only place Media3 is named. Behind the RadioPlayer interface :socks:radio declares.
    implementation(libs.androidx.media3.exoplayer)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(testFixtures(project(":core")))
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    // The held-out accuracy suite drives the real model through the real registry, so it needs
    // both the native module and core's Tier2Negatives.
    androidTestImplementation(testFixtures(project(":core")))
}
