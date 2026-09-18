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
    api(libs.vosk.android)

    testImplementation(libs.kotlinx.coroutines.test)
}
