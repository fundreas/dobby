import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
}

subprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        extensions.configure<KotlinJvmProjectExtension>("kotlin") {
            jvmToolchain(21)
            compilerOptions {
                // :core and the Socks are pure JVM, but they are also shipped inside the APK.
                // Java 17 bytecode is what the Android toolchain accepts without argument.
                jvmTarget.set(JvmTarget.JVM_17)
                allWarningsAsErrors.set(true)
            }
        }
        extensions.configure<JavaPluginExtension>("java") {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
        dependencies {
            add("testImplementation", kotlin("test"))
            add("testRuntimeOnly", libs.junit.platform.launcher)
        }
    }

    // The Android modules get the same discipline as the JVM ones. Since AGP 9 the Kotlin
    // plugin is built into AGP rather than applied separately, so the hook is AGP's own id.
    pluginManager.withPlugin("com.android.base") {
        extensions.configure<KotlinAndroidProjectExtension>("kotlin") {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_17)
                allWarningsAsErrors.set(true)
            }
        }
        dependencies {
            add("testImplementation", kotlin("test-junit5"))
            add("testRuntimeOnly", libs.junit.platform.launcher)
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("failed")
            exceptionFormat = TestExceptionFormat.FULL
        }
    }
}
