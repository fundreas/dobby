package io.dobby.socks.radio

import io.dobby.core.nlu.Normalizer

/**
 * The comparison key for a station name, from either side of the match.
 *
 * Both a table alias and a spoken `{station}` slot go through here, which is the whole point:
 * "ef em vier" typed into the table and "ef em vier" said into the microphone must arrive at
 * the same string, and the microphone's copy has already been through
 * [io.dobby.core.nlu.Normalizer] — where `GermanNumbers` turned "vier" into "4".
 *
 * That is not a detail. The spec's alias list was written as `ef em vier`, `oe drei`, `ö drei`,
 * and **none of them could ever have matched**: by the time the slot reaches the Sock it reads
 * `ef em 4` and `oe 3`, and the letter-and-digit station names — exactly the ones the spec
 * identifies as what any acoustic model is worst at — would have fallen straight through to an
 * edit distance they are nowhere near. Hand-writing normalized aliases into the table would fix
 * it once and rot the first time the normalizer learns a word; running the table through the
 * same function cannot drift (`radio-plan.md` §C2).
 *
 * The umlaut folding is the spec's own §3.4 step, applied after normalization rather than
 * before it, because `Normalizer` deliberately leaves `ö` intact.
 */
object StationKey {

    fun of(raw: String): String =
        Normalizer.normalize(raw)
            .replace("ö", "oe")
            .replace("ä", "ae")
            .replace("ü", "ue")
            .replace("ß", "ss")
            .filter { !it.isWhitespace() && it != '-' }
}
