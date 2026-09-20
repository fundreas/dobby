package io.dobby.socks.spotify

import io.dobby.core.nlu.Fillers
import io.dobby.core.nlu.Normalizer

/**
 * Which hit to play, and whether to ask instead.
 *
 * **Trust the ranker, break ties.** Spotify's ordering is already the best signal available;
 * this heuristic's job is to pick a *kind* — track, artist or playlist — not to re-rank a list
 * it understands worse than they do (`spotify.specs.md` §3).
 *
 * The fuzziness that matters already happened inside Spotify's ranker, so [resembles] is one
 * small pure function: normalize, drop the filler words, compare token sets. Not Levenshtein
 * and not phonetics — those are the template matcher's job, one layer earlier, against a
 * closed candidate set rather than against the world's catalogue.
 */
object Selection {

    /**
     * The word that splits a title from an artist, and the reason [QueryHint] exists.
     *
     * Spelled with its spaces: "Bon Iver" and "Von Wegen Lisbeth" are bands, and a bare
     * `contains("von")` would cut both in half.
     */
    const val VON: String = " von "

    /** What the query said about the artist, when it said anything at all. */
    fun artistPart(query: String): String? =
        query.substringAfterLast(VON, "").trim().takeIf { it.isNotEmpty() }

    /** The hint the wording implies, for the templates that do not fix one themselves. */
    fun hintFor(query: String, fixed: QueryHint): QueryHint = when {
        fixed != QueryHint.UNKNOWN -> fixed
        artistPart(query) != null -> QueryHint.TITLE_AND_ARTIST
        else -> QueryHint.UNKNOWN
    }

    /**
     * Picks the hit to play, or null when nothing in any bucket fits.
     *
     * @param preferTrack `spotify.prefer_track_over_artist`. False makes an artist name beat a
     *   same-named track, which is the right answer for somebody who mostly says band names.
     */
    fun choose(query: String, hint: QueryHint, hits: Hits, preferTrack: Boolean): Hit? = when (hint) {
        // They named both halves, so the only open question is which recording — and an artist
        // that does not match means the recogniser mangled the artist, not the intent.
        QueryHint.TITLE_AND_ARTIST -> {
            val spokenArtist = artistPart(query)
            val matching = spokenArtist?.let { artist ->
                hits.tracks.firstOrNull { resembles(it.artist.orEmpty(), artist) }
            }
            matching ?: hits.tracks.firstOrNull()
        }

        // "spiel was von queen" — the artist's own URI starts that artist's tracks, which is
        // exactly what the sentence means. A name that does not come back is a mishearing, and
        // the top track is the better guess than an artist nobody asked for.
        QueryHint.ARTIST_ONLY -> {
            val artist = hits.artists.firstOrNull()
            if (artist != null && resembles(artist.name, query)) artist else hits.tracks.firstOrNull()
        }

        QueryHint.UNKNOWN -> chooseUnknown(query, hits, preferTrack)
    }

    /**
     * The open case: a bare "{query}" that could be a song, a band or a playlist.
     *
     * A track wins by default, because "spiele bohemian rhapsody" means the song far more often
     * than it means anything else. The exception is the query that *is* an artist name — "spiele
     * queen" — where an artist hit that the tracks do not clearly beat is the better reading.
     * A playlist is the answer only when there is no track and no artist at all.
     */
    private fun chooseUnknown(query: String, hits: Hits, preferTrack: Boolean): Hit? {
        val track = hits.tracks.firstOrNull()
        val artist = hits.artists.firstOrNull()
        if (track == null) return artist ?: hits.playlists.firstOrNull()
        if (artist == null) return track
        val namesTheArtist = resembles(artist.name, query)
        return when {
            !preferTrack && namesTheArtist -> artist
            namesTheArtist && track.popularity - artist.popularity < DECISIVE_GAP -> artist
            else -> track
        }
    }

    /**
     * Whether to ask which version was meant, instead of guessing (`spotify.specs.md` §3).
     *
     * A deliberately **narrow** trigger: a panel that interrogates you about every song is
     * worse than one that occasionally plays the wrong version. All four conditions have to
     * hold — the wording was not explicit, the top two tracks are by different artists, neither
     * is a clearly stronger hit, and the query was not simply an artist's name (that case is
     * already decided one function up).
     *
     * @return the candidates to offer, most likely first, or an empty list to just play.
     */
    fun ambiguous(query: String, hint: QueryHint, hits: Hits): List<Hit> {
        if (hint == QueryHint.TITLE_AND_ARTIST) return emptyList()
        if (hits.artists.firstOrNull()?.let { resembles(it.name, query) } == true) return emptyList()
        val first = hits.tracks.firstOrNull() ?: return emptyList()
        // One per artist: two pressings of the same recording are not a choice anybody wants
        // to be asked about, and close in popularity is exactly what they will be.
        val distinct = mutableListOf(first)
        for (hit in hits.tracks.drop(1)) {
            if (distinct.size == MAX_CANDIDATES) break
            if (first.popularity - hit.popularity >= DECISIVE_GAP) break
            if (distinct.none { resembles(it.artist.orEmpty(), hit.artist.orEmpty()) }) distinct += hit
        }
        return if (distinct.size < 2) emptyList() else distinct
    }

    /**
     * Loose equality between two spoken-ish strings: normalized, filler-free token overlap.
     *
     * The threshold is over the *smaller* set, so "queen" resembles "queen" and also resembles
     * the full name of a band the recogniser gave two thirds of. Both sides empty is not a
     * match — an absent artist must never resemble an absent one and make every track a hit.
     */
    fun resembles(a: String, b: String): Boolean {
        val left = contentWords(a)
        val right = contentWords(b)
        if (left.isEmpty() || right.isEmpty()) return false
        val shared = left.count { it in right }
        return shared.toDouble() / minOf(left.size, right.size) >= OVERLAP
    }

    private fun contentWords(text: String): Set<String> =
        Normalizer.tokenize(text).filterNot { it in Fillers.DE }.toSet()

    /**
     * How much more popular a track has to be for the tie to be over.
     *
     * Spotify's popularity is 0–100 and moves slowly; ten points is the difference between "the
     * famous one" and "one of several". Used in both directions: below it two hits are a
     * question, above it the first one is the answer.
     */
    private const val DECISIVE_GAP = 10

    /** Six tenths of the shorter side. High enough to reject a coincidence, low enough for ASR. */
    private const val OVERLAP = 0.6

    /** Three is what "die erste, die zweite oder die dritte" can carry. */
    private const val MAX_CANDIDATES = 3
}
