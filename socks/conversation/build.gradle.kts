plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(project(":core"))
    testImplementation(testFixtures(project(":core")))
    testImplementation(libs.kotlinx.coroutines.test)

    // The collision gate is only meaningful against the Socks this one shares a palette with.
    testImplementation(project(":socks:clock"))
    testImplementation(project(":socks:calculator"))
    testImplementation(project(":socks:help"))
    testImplementation(project(":socks:winky"))
}
