rootProject.name = "dobby"

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

include(":core")
include(":socks:devi")
include(":socks:clock")
include(":socks:help")
include(":cli")

// Phase B — everything that only exists on the device.
include(":android:sherpa")
include(":android:pipeline")
include(":android:app")
