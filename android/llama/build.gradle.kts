plugins {
    alias(libs.plugins.android.library)
}

/**
 * The `-march` llama.cpp's CPU backend is compiled for.
 *
 * The Snapdragon 750G is 2×A77 + 6×A55, and `+dotprod` is *optional* on A55 — the A55 cluster
 * is what binds, because ggml's threads synchronise at a barrier per op. Confirm before
 * changing this:
 *
 *     adb shell grep -m1 Features /proc/cpuinfo    # look for asimddp
 *
 * `+i8mm` is definitively out: it needs armv8.6-a and this part is armv8.2-a.
 *
 * The flag is not trusted at run time. [io.dobby.llama.Llama] reads `/proc/cpuinfo` in Kotlin
 * and compares it against `REQUIRED_CPU_FEATURES` *before* `System.loadLibrary`, so a device
 * without the feature disables Tier 2 with a reason instead of taking SIGILL. If you change the
 * arch here, change that list too — `CpuFeatureGateTest` asserts they agree.
 */
val cpuArch = "armv8.2-a+fp16+dotprod"

android {
    namespace = "io.dobby.llama"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            // One ABI. The device is a Nord CE; every other ABI is megabytes for nothing.
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_static",
                    "-DDOBBY_CPU_ARCH=$cpuArch",
                    "-DCMAKE_BUILD_TYPE=Release",
                )
                // Release for both variants: AGP hands the debug variant -O0, and the debug
                // APK is the one that gets sideloaded.
                cppFlags += "-O3"
            }
        }
        buildConfigField("String", "CPU_ARCH", "\"$cpuArch\"")
    }

    buildFeatures {
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path = file("CMakeLists.txt")
            version = "3.31.6"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            // Nothing to exclude and nothing to pick from: one .so, built here, statically
            // linked. The absence of a rule is the point — see README.md.
            keepDebugSymbols += "**/libdobby-llama.so"
        }
    }
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    api(project(":core"))

    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
