package io.dobby.socks.spotify

/**
 * `MEDIA_PLAY_FROM_SEARCH`, as a fallback and never as the design (`spotify.specs.md` §3).
 *
 * The intent is a one-shot that takes free text and answers nothing: it starts an *activity*,
 * so Spotify covers the panel, and it reports neither what it played nor whether it worked.
 * That is why the App Remote is the primary path — every other command in this Sock needs a
 * live connection anyway, and `activityFor` needs a cached snapshot.
 *
 * What it is still good for is the one command where a blind attempt does what the user
 * actually wanted. When the App Remote will not connect but the Spotify app is installed,
 * firing this at `com.spotify.music` with the raw query is strictly better than
 * "Ich komme gerade nicht an Spotify ran." There is no meaningful equivalent for pause or
 * skip, and those fail instead.
 *
 * An interface because the intent is Android and this module is not.
 */
fun interface IntentFallback {

    /** @return whether an activity was actually started. It says nothing about what played. */
    fun playFromSearch(query: String, hint: QueryHint): Boolean

    companion object {
        /** Off-device, where there is no `Context` and the intent would do nothing anyway. */
        val NONE: IntentFallback = IntentFallback { _, _ -> false }
    }
}
