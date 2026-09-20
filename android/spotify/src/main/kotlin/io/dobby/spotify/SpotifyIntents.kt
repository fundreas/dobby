package io.dobby.spotify

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import android.util.Log
import io.dobby.socks.spotify.IntentFallback
import io.dobby.socks.spotify.QueryHint

/**
 * `MEDIA_PLAY_FROM_SEARCH` at `com.spotify.music`, for when the App Remote will not connect.
 *
 * It brings Spotify to the foreground and tells us nothing — no URI, no confirmation, and an
 * empty dashboard card — which is exactly why it is the fallback and not the design
 * (`spotify.specs.md` §3). It is still strictly better than "Ich komme gerade nicht an Spotify
 * ran." for the one command where a blind attempt does what the user wanted.
 *
 * `FLAG_ACTIVITY_NEW_TASK` is not optional: the panel's own service has no activity stack to
 * start one from.
 */
class SpotifyIntents(context: Context) : IntentFallback {

    private val appContext = context.applicationContext

    override fun playFromSearch(query: String, hint: QueryHint): Boolean {
        val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            setPackage(SPOTIFY_PACKAGE)
            putExtra(SearchManager.QUERY, query)
            putExtra(MediaStore.EXTRA_MEDIA_FOCUS, focusFor(hint))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            appContext.startActivity(intent)
            true
        } catch (e: android.content.ActivityNotFoundException) {
            // The Spotify build on this device does not answer the intent. Nothing to salvage;
            // the Sock says "Ich komme gerade nicht an Spotify ran." and means it.
            Log.w(TAG, "spotify did not answer MEDIA_PLAY_FROM_SEARCH", e)
            false
        }
    }

    private companion object {
        const val TAG = "Dobby"
        const val SPOTIFY_PACKAGE = "com.spotify.music"

        /**
         * What kind of thing the query names, in the vocabulary the intent understands.
         *
         * `ARTIST_ONLY` is the only hint that maps cleanly — the others are either a title with
         * an artist beside it or genuinely unknown, and both are best served by the unfocused
         * search that treats the whole string as free text.
         */
        fun focusFor(hint: QueryHint): String = when (hint) {
            QueryHint.ARTIST_ONLY -> MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE
            QueryHint.TITLE_AND_ARTIST, QueryHint.UNKNOWN -> MediaStore.Audio.Media.ENTRY_CONTENT_TYPE
        }
    }
}
