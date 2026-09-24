plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(project(":core"))
    testImplementation(testFixtures(project(":core")))
    testImplementation(libs.kotlinx.coroutines.test)

    // The collision gate is only meaningful against the Socks this one shares a palette with —
    // and this Sock claims "nächstes", "zurück" and "weiter", which is Spotify's and the
    // chain's ground with one word changed.
    testImplementation(project(":socks:clock"))
    testImplementation(project(":socks:help"))
    testImplementation(project(":socks:spotify"))
}
