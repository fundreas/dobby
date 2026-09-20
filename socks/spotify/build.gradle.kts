plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":core"))
    // The Web API half (spotify-plan.md B, C). Both are plain JVM libraries that also run on
    // Android, which is what keeps the search, the selection heuristic and the German copy
    // reachable from a terminal with no device and no Spotify account.
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(testFixtures(project(":core")))
    testImplementation(libs.kotlinx.coroutines.test)
}
