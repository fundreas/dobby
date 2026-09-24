package io.dobby.socks.memo

import io.dobby.core.nlu.Fillers
import java.time.Instant

/**
 * One thing the user asked to be reminded of, and when they asked.
 *
 * [text] is Normalizer output — lowercase, no punctuation apart from the apostrophe, single
 * spaces — because that is what a `{text}` slot captures. It is stored exactly as it was
 * captured and capitalized only where it is *shown*, which is the same bargain the Clock makes
 * with its timer names: what was said is the record, [display] is the rendering.
 *
 * @param createdAt the timestamp the idea file asks for, and the reason a memo can be read back
 *   as "notiert gestern um 18:05" rather than as a line in an undated list.
 */
data class Memo(val id: Long, val text: String, val createdAt: Instant) {

    /**
     * "milch kaufen" → "Milch kaufen".
     *
     * What the panel draws and what every sentence interpolates. A speech engine cannot hear
     * the difference; the transcript on the wall can, and "brot holen. Notiert heute…" reads
     * like a typo of somebody else's shopping list.
     */
    val display: String get() = text.replaceFirstChar { it.titlecase() }
}

/**
 * What the wall panel draws (`memo.specs.md` §8).
 *
 * [memos] is newest first, which is the order `memo.latest_memo` reads them in and the order a
 * card would list them in. [spotlight] is the memo the current memo session is sitting on — the
 * one "Memo erledigt" would close — so the panel can mark it instead of leaving the user to
 * remember which one Dobby last read out.
 */
data class MemoState(val memos: List<Memo> = emptyList(), val spotlight: Long? = null) {

    val open: Int get() = memos.size
}

/**
 * Turning what was said into the memo that was meant.
 *
 * A `{text}` slot is the one slot kind the matcher cannot constrain: it takes whatever tokens
 * sit after the keyword that anchors it. That is fine for "memo milch kaufen" and it is also
 * what makes "erstelle mal ein memo bitte" arrive here as the text "bitte". So a memo made of
 * nothing but filler is not a memo — it is somebody hesitating — and saving it would put a note
 * called "Bitte" on the panel that nobody can act on and everybody has to close.
 *
 * Exactly [io.dobby.socks.clock.TimerNames]' argument, and for exactly the same reason it is
 * not solved in the templates: enumerating the particles a memo must not be is the cross-product
 * the filler list exists to avoid (`socks.specs/README.md` §6).
 */
internal object MemoText {

    /** A captured `{text}`, reduced to the words that carry it, or null if none are left. */
    fun clean(raw: String?): String? {
        if (raw == null) return null
        val words = raw.split(' ')
            .filter { it.isNotBlank() && it !in Fillers.DE }
            .dropWhile { it in SCAFFOLD }
        return words.takeIf { it.isNotEmpty() }?.joinToString(" ")
    }

    /**
     * Words that can only be holding the memo up, never be it.
     *
     * "Erstelle ein Memo für die Rechnung" hands the slot "für rechnung" once filler is gone,
     * and a memo called "Für Rechnung" is a memo somebody has to translate back. Leading only:
     * a memo is allowed to contain any of these further in, and "brot für morgen holen" keeps
     * every word it was said with.
     */
    private val SCAFFOLD = setOf("memo", "notiz", "für", "über", "von", "vom", "zum", "zur", "dass", "damit")
}
