plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.dobby.pipeline"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
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
