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
        /**
         * The Spotify App Remote SDK, which is on no public repository at all — `com.spotify.android`
         * on Maven Central holds `auth` and `auth-store` and nothing else. `:android:spotify` fetches
         * the AAR from the GitHub release into this directory, pinned by SHA-256, and it is
         * git-ignored (`spotify.specs.md` §1).
         *
         * A repository rather than `implementation(files(...))`, which reads better and does not
         * work: AGP refuses to build a library AAR that has a local `.aar` file dependency, because
         * the result would silently not contain it.
         *
         * `metadataSources { artifact() }` because there is no POM — the release is a bare `.aar` —
         * and `content { includeModule }` so this repository is consulted for exactly one coordinate
         * and never stands in front of Maven Central for anything else.
         */
        maven {
            name = "spotifyAppRemote"
            url = File(rootDir, "android/spotify/libs/m2").toURI()
            metadataSources { artifact() }
            content { includeModule("com.spotify.android", "app-remote") }
        }
    }
}

include(":core")
include(":socks:winky")
include(":socks:clock")
include(":socks:calculator")
include(":socks:help")
include(":socks:conversation")
include(":socks:spotify")
include(":socks:radio")
include(":socks:system")
include(":cli")

// Phase B — everything that only exists on the device.
include(":android:sherpa")
include(":android:pipeline")

// The App Remote SDK behind the interfaces :socks:spotify declares. Fetches a .aar from
// Spotify's GitHub releases at build time — see android/spotify/README.md.
include(":android:spotify")

// The first NDK/CMake build in the repo. Needs `git submodule update --init` — see
// android/llama/README.md.
include(":android:llama")
include(":android:app")
