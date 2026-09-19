plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

dependencies {
    implementation(project(":core"))
    implementation(project(":socks:winky"))
    implementation(project(":socks:clock"))
    implementation(project(":socks:help"))
}

application {
    mainClass.set("io.dobby.cli.MainKt")
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}
