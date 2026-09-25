plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":core"))
    // One GET against api.open-meteo.com, no key and no account (weather.specs.md §1). Both
    // are plain JVM libraries that also run on Android, which is what keeps the forecast, the
    // German copy and the whole command surface reachable from a terminal with no device.
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(testFixtures(project(":core")))
    testImplementation(libs.kotlinx.coroutines.test)

    // The collision gate is only meaningful against the Socks this one shares a palette with —
    // and this Sock claims "heute", "morgen" and "übermorgen", which is the Clock's calendar
    // ground with a different noun in front of it.
    testImplementation(project(":socks:clock"))
    testImplementation(project(":socks:help"))
}
