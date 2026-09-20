package io.dobby.socks.radio

import io.dobby.core.nlu.template.Levenshtein
import io.dobby.core.nlu.template.Phonetics

/**
 * Which station somebody meant, or nothing.
 *
 * Three tiers, in widening order, and each one only ever fires when the one before it found
 * nothing (`radio.specs.md` §3.4, extended by `radio-plan.md` §C3):
 *
 * 1. **Exact**, against [StationKey.of] of every id and every alias. One map, one lookup.
 * 2. **Edit distance**, best match wins — with a budget capped at a third of the key's length.
 * 3. **Kölner Phonetik**, exact on the code, which rescues a misspelling that sounds identical
 *    and cannot widen the net any further than that.
 *
 * **Null is never the default station.** "Spiele radio bayern 3" must fail rather than quietly
 * start FM4; the spec is explicit, and the length cap in tier 2 is what makes that safe.
 */
class StationResolver(private val stations: List<Station> = Stations.ALL) {

    private val exact: Map<String, Station> = buildMap {
        for (station in stations) {
            put(StationKey.of(station.id), station)
            for (alias in station.aliases) put(StationKey.of(alias), station)
        }
    }

    /** Null means "the user named a station and it is not one of ours" — never the default. */
    fun resolve(spoken: String): Station? {
        val key = StationKey.of(spoken)
        if (key.isEmpty()) return null
        exact[key]?.let { return it }
        byDistance(key)?.let { return it }
        return byPhonetics(key)
    }

    /**
     * Tier 2, with two rules that are both load-bearing.
     *
     * **The length cap.** A flat `<= 2` lets a two-character key like `o1` accept most
     * two-letter strings anybody might say, and lets `bayern3` (7 characters) reach into
     * territory it has no business in. A third of the key's length means short names are
     * matched strictly and only a long one gets the full budget.
     *
     * **A tie is a failure, not a coin flip.** Two stations at the same distance means the Sock
     * does not know which, and "Den Sender kenne ich nicht" is the honest answer to that.
     */
    private fun byDistance(key: String): Station? {
        val budget = minOf(MAX_DISTANCE, key.length / LENGTH_DIVISOR)
        if (budget <= 0) return null
        val ranked = exact.values.distinct()
            .map { station -> station to bestDistance(station, key) }
            .filter { (_, distance) -> distance <= budget }
            .sortedBy { (_, distance) -> distance }
        val best = ranked.firstOrNull() ?: return null
        val tied = ranked.any { (station, distance) -> station != best.first && distance == best.second }
        return if (tied) null else best.first
    }

    /** How close this station gets on its best-matching name. Ids and aliases count alike. */
    private fun bestDistance(station: Station, key: String): Int =
        (listOf(station.id) + station.aliases)
            .minOf { name -> Levenshtein.distance(key, StationKey.of(name)) }

    /**
     * Tier 3 — the tool M6 already built for the keyword matcher, pointed at a closed set.
     *
     * "krohnehit", "kronnehit" and "cronehit" all code to the same skeleton as "kronehit" and
     * none of them is within tier 2's budget. Exact on the code and ambiguity-free or nothing,
     * so it can only ever rescue a misspelling — it cannot broaden tier 2.
     */
    private fun byPhonetics(key: String): Station? {
        val coded = Phonetics.koelner(key)
        if (coded.length < MIN_CODE_LENGTH) return null
        val hits = exact.entries
            .filter { (alias, _) -> Phonetics.koelner(alias) == coded }
            .map { (_, station) -> station }
            .distinct()
        return hits.singleOrNull()
    }

    private companion object {
        /** The spec's own tolerance, before the cap below narrows it for short names. */
        const val MAX_DISTANCE = 2

        /** One edit per three characters. `bayern3` gets two, `o1` gets none. */
        const val LENGTH_DIVISOR = 3

        /**
         * Below this a code says nothing (`Phonetics`' own hazard warning, and
         * `KeywordMatcher.MIN_CODE_LENGTH` for the same reason).
         *
         * `oe3` codes to the empty string — every vowel is a dropped `0` and the digit is not
         * in the table at all — and a one-digit code is most short words.
         */
        const val MIN_CODE_LENGTH = 2
    }
}
