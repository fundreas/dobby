rootProject.name = "dobby"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

include(":core")
include(":socks:devi")
include(":socks:clock")
include(":socks:help")
include(":cli")
