plugins {
    alias(libs.plugins.kotlin.jvm) apply false
}

subprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension>("kotlin") {
            jvmToolchain(21)
            compilerOptions {
                allWarningsAsErrors.set(true)
            }
        }
        dependencies {
            add("testImplementation", kotlin("test"))
            add("testRuntimeOnly", libs.junit.platform.launcher)
        }
        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            testLogging {
                events("failed")
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            }
        }
    }
}
