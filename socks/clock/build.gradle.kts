plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(project(":core"))
    testImplementation(testFixtures(project(":core")))
    testImplementation(libs.kotlinx.coroutines.test)
}
