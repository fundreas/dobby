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
include(":socks:winky")
include(":socks:clock")
include(":socks:help")
include(":cli")

// Phase B — everything that only exists on the device.
include(":android:sherpa")
include(":android:pipeline")

// The first NDK/CMake build in the repo. Needs `git submodule update --init` — see
// android/llama/README.md.
include(":android:llama")
include(":android:app")
