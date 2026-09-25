plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":core"))
    // One GET against wienerlinien.at, no key and no account (departures.specs.md §1). Plain
    // JVM libraries that also run on Android, which is what keeps the board, the German copy
    // and the whole command surface reachable from a terminal with no device.
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
}
