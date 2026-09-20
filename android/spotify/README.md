# `:android:spotify`

The Spotify App Remote SDK, behind the interfaces `:socks:spotify` declares. Every Spotify SDK
type in the project is in this module and nowhere else — which is what keeps the query parsing,
the selection heuristic and the whole command surface of the Sock reachable from a terminal with
no device and no Spotify account.

Two files:

- `AppRemotePlayer` — `SpotifyPlayer` on a Binder connection into `com.spotify.music`: lazy
  single-flight connect, the `PlayerState` subscription flattened into a `PlayerSnapshot`, and
  album art off the App Remote's own `ImagesApi`.
- `SpotifyIntents` — `IntentFallback`, the `MEDIA_PLAY_FROM_SEARCH` of last resort.

## The AAR

Spotify publishes **no Maven artifact** for the App Remote SDK. `com.spotify.android` on Maven
Central holds `auth` and `auth-store` and nothing else; the App Remote ships as a bare `.aar`
attached to a GitHub release. So `build.gradle.kts` fetches it, pinned by SHA-256, into a
git-ignored local Maven repository at `libs/m2/`, which `settings.gradle.kts` declares for that
one coordinate. Same reasoning as `:android:sherpa`'s `libsherpa-onnx-jni.so` and
`:android:pipeline`'s espeak data: a binary that can be re-downloaded byte-for-byte does not
belong in history, and a third-party binary is the last thing in this project to take on trust.

Two things about it are worth knowing before changing them.

**The fetch runs at configuration time**, and the other two download blocks in this repo do not.
The difference is what consumes the file. A `.so` is read by a packaging task, and a task
dependency is enough. This produces a *module dependency*, and Gradle resolves a configuration
with no knowledge of the task that would have created the artifact — `:android:app`'s dependency
collection really does go looking for it, in parallel, before `:android:spotify:preBuild` has
run. The checksum bounds the cost: one download per checkout, and one hash of 130 KB per
configuration after that.

**A repository, not `implementation(files(...))`.** The file dependency reads better and does
not work: AGP refuses to build a library AAR that has a local `.aar` file dependency, because
the result would silently not contain it.

If a Maven coordinate ever appears, all of this collapses to one dependency line.

## Updating it

1. Find the release: <https://github.com/spotify/android-sdk/releases>.
2. Bump `spotifyAppRemote` in `gradle/libs.versions.toml`.
3. Put the new SHA-256 in `appRemoteSha256`. The build prints the actual one when it mismatches.
4. Check the release tag still has the shape the URL assumes — it carries the auth library's
   version too (`v0.8.0-appremote_v2.1.0-auth`), so a new auth release renames it.

## What it needs on the device

- The Spotify app installed and logged in.
- **Premium.** App Remote playback control is Premium-only, and a free account refuses
  authorization rather than reporting a subscription level.
- The app registered in the Spotify developer dashboard with the package name `io.dobby.android`,
  the redirect URI, and the **SHA-1 of both the debug and the release signing key**. A missing
  debug SHA-1 is the classic "it works nowhere and the error says nothing".
- `spotify.client.id`, `spotify.client.secret` and `spotify.redirect.uri` in `local.properties`
  (`spotify.specs.md` §1).
