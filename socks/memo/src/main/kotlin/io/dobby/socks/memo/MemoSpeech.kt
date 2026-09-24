package io.dobby.socks.memo

import io.dobby.core.sock.Lang
import io.dobby.core.sock.Phrase
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * How a memo is read out loud.
 *
 * Three sentences at most, always in the same order: the memo, when it was noted, and how much
 * else is waiting. That order is the product decision — the text is what somebody asked for,
 * the timestamp is what tells them whether it is still worth doing, and the count is what tells
 * them whether to ask for the next one. Anything that is zero is left out rather than said as a
 * zero: "Es sind noch 0 weitere Memos offen" is a sentence only a computer would produce.
 *
 * Every string here is built inside a [Phrase], never before one, which is the i18n contract
 * (`socks.specs/README.md` §2a): a memo noted in German and read back after the voice was
 * switched is read in English, because nothing was rendered until it was said.
 */
internal object MemoSpeech {

    /** "Samstag" — the weekday a memo from earlier this week is dated by. */
    private val WEEKDAY_DE = DateTimeFormatter.ofPattern("EEEE", Locale.GERMAN)
    private val WEEKDAY_EN = DateTimeFormatter.ofPattern("EEEE", Locale.ENGLISH)

    /** "12. September" — no year, for the same reason the Clock's spoken date carries none. */
    private val DATE_DE = DateTimeFormatter.ofPattern("d. MMMM", Locale.GERMAN)
    private val DATE_EN = DateTimeFormatter.ofPattern("MMMM", Locale.ENGLISH)

    /**
     * One memo, read out: "Milch kaufen. Notiert heute um 14:30. Es sind noch 2 weitere Memos offen."
     *
     * @param lead where this memo sits in the pile, when that is news — the newest or the oldest.
     *   Null while walking the pile, because "Dein neuestes Memo" in front of the third one
     *   would be a lie and in front of the second one is noise.
     * @param others how many memos are open besides this one.
     */
    fun read(memo: Memo, lead: Direction?, others: Int, zone: ZoneId, now: Instant): Phrase =
        Phrase { lang ->
            buildString {
                append(leadIn(lead, lang))
                // The displayed form, not the captured one — see [Memo.display].
                append(memo.display)
                append(". ")
                append(noted(memo.createdAt, zone, now, lang))
                append(others(others, lang))
            }
        }

    /** "Memo gespeichert: Milch kaufen." */
    fun saved(memo: Memo): Phrase = Phrase { lang ->
        if (lang == Lang.EN) "Memo saved: ${memo.display}." else "Memo gespeichert: ${memo.display}."
    }

    /** "Erledigt: Milch kaufen. Es sind noch 2 Memos offen." */
    fun closed(memo: Memo, open: Int): Phrase = Phrase { lang ->
        val english = lang == Lang.EN
        val head = if (english) "Done: ${memo.display}." else "Erledigt: ${memo.display}."
        val rest = when {
            open == 0 && english -> " No memos are open any more."
            open == 0 -> " Es sind keine Memos mehr offen."
            open == 1 && english -> " One memo is still open."
            open == 1 -> " Es ist noch ein Memo offen."
            english -> " $open memos are still open."
            else -> " Es sind noch $open Memos offen."
        }
        head + rest
    }

    /** "Ich habe schon 50 Memos offen. Erledige erst ein paar." */
    fun tooMany(max: Int): Phrase = Phrase { lang ->
        if (lang == Lang.EN) {
            "I'm already holding $max memos. Close a few first."
        } else {
            "Ich habe schon $max Memos offen. Erledige erst ein paar."
        }
    }

    private fun leadIn(lead: Direction?, lang: Lang): String = when {
        lead == null -> ""
        lead == Direction.NEWEST_FIRST && lang == Lang.EN -> "Your newest memo: "
        lead == Direction.NEWEST_FIRST -> "Dein neuestes Memo: "
        lang == Lang.EN -> "Your oldest memo: "
        else -> "Dein ältestes Memo: "
    }

    /**
     * "Notiert heute um 14:30." — the half of a memo that says whether it is still current.
     *
     * Four resolutions, coarsening with age, because that is how somebody refers to their own
     * notes: today and yesterday by name, this week by weekday, and anything older by date. The
     * clock time is dropped with the weekday for the oldest ones — "am 12. September um 9:15"
     * is a log line, and nobody asks at what time last month they thought of the recycling.
     */
    private fun noted(createdAt: Instant, zone: ZoneId, now: Instant, lang: Lang): String {
        val moment = LocalDateTime.ofInstant(createdAt, zone)
        val today = LocalDateTime.ofInstant(now, zone).toLocalDate()
        val days = ChronoUnit.DAYS.between(moment.toLocalDate(), today)
        val english = lang == Lang.EN
        val clock = if (english) englishTime(moment) else "%d:%02d Uhr".format(moment.hour, moment.minute)
        val stamp = when {
            // A memo from the future is a clock that was wrong, not a memo — read it as today's
            // rather than as "in 2 Tagen", which is a promise this Sock does not make.
            days <= 0L && english -> "today at $clock"
            days <= 0L -> "heute um $clock"
            days == 1L && english -> "yesterday at $clock"
            days == 1L -> "gestern um $clock"
            days < DAYS_PER_WEEK && english -> "on ${WEEKDAY_EN.format(moment)} at $clock"
            days < DAYS_PER_WEEK -> "am ${WEEKDAY_DE.format(moment)} um $clock"
            english -> "on ${DATE_EN.format(moment)} ${ordinal(moment.dayOfMonth)}"
            else -> "am ${DATE_DE.format(moment)}"
        }
        // "p.m." is already the full stop. A second one is a stutter in the audio rather than a
        // typo nobody hears, which is the same care `SpokenTime.speak` takes with the same tail.
        return if (english) "Noted $stamp".let { if (it.endsWith('.')) it else "$it." } else "Notiert $stamp."
    }

    /** " Es sind noch 2 weitere Memos offen.", and nothing at all when there are none. */
    private fun others(others: Int, lang: Lang): String {
        val english = lang == Lang.EN
        return when {
            others <= 0 -> ""
            others == 1 && english -> " There's one other memo open."
            others == 1 -> " Es ist noch ein weiteres Memo offen."
            english -> " There are $others other memos open."
            else -> " Es sind noch $others weitere Memos offen."
        }
    }

    /**
     * "2:30 p.m." — the English 12-hour form, for the same reason `SpokenTime` uses one.
     *
     * A German 24-hour time read to an English voice is understood and sounds like a timetable;
     * this is what a person says. It is deliberately the plain form and not "half past two" —
     * a memo's timestamp is a fact to check against, not the answer to "what time is it".
     */
    private fun englishTime(moment: LocalDateTime): String {
        val meridiem = if (moment.hour < HOURS_PER_HALF_DAY) "a.m." else "p.m."
        val hour = (moment.hour % HOURS_PER_HALF_DAY).let { if (it == 0) HOURS_PER_HALF_DAY else it }
        return "$hour:%02d $meridiem".format(moment.minute)
    }

    /** 1 → "1st", 14 → "14th". Days only, so the teens are the whole of the special case. */
    private fun ordinal(day: Int): String {
        val suffix = when {
            day in TEENS -> "th"
            day % DECIMAL == 1 -> "st"
            day % DECIMAL == 2 -> "nd"
            day % DECIMAL == 3 -> "rd"
            else -> "th"
        }
        return "$day$suffix"
    }

    private val TEENS = 11..13

    private const val DECIMAL = 10
    private const val DAYS_PER_WEEK = 7
    private const val HOURS_PER_HALF_DAY = 12
}
