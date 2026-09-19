plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

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
        ndk {
            // The device is a OnePlus Nord CE; nothing else needs the ONNX Runtime and
            // sherpa-onnx native libraries, which are most of the APK.
            abiFilters += "arm64-v8a"
        }
    }

    buildFeatures {
        compose = true
    }

    lint {
        // Same rule as the Kotlin compiler: a warning left standing is a warning nobody reads.
        warningsAsErrors = true
        disable += setOf(
            // targetSdk and compileSdk are already the newest platform the SDK offers.
            "OldTargetApi",
            "GradleDependency",
            // arm64-v8a only, on purpose: the device is a Nord CE (dobby-plan.md §4).
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
    // Winky is a development Sock and must not reach a release build (dobby-plan.md §8).
    debugImplementation(project(":socks:winky"))

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
}
