package io.dobby.socks.memo

import io.dobby.core.sock.CommandHelp
import io.dobby.core.sock.CommandInvocation
import io.dobby.core.sock.Example
import io.dobby.core.sock.ExclusiveCommandSpec
import io.dobby.core.sock.ParamSpec
import io.dobby.core.sock.ParamType
import io.dobby.core.sock.Phrase
import io.dobby.core.sock.Sock
import io.dobby.core.sock.SockContext
import io.dobby.core.sock.SockResult
import io.dobby.core.sock.patterns
import kotlinx.coroutines.flow.StateFlow
import java.time.Clock

/**
 * Memo — the things somebody said out loud so they would not have to keep holding them.
 *
 * Two halves, and the second one is what makes it a Sock rather than a notepad. Saying "erstelle
 * Memo Rechnung bezahlen" while both hands are in the sink is the easy half. The hard half is
 * getting it back: a list is only worth dictating to if it reads itself back one item at a time,
 * in an order somebody can follow by ear, saying how much is left — and then lets them say
 * "erledigt" to the one they have just done, without naming it again.
 *
 * That is the whole shape of this Sock. `latest_memo` and `oldest_memo` open a walk from one end
 * or the other, `next_memo` and `previous_memo` move along it, and `close_memo` acts on wherever
 * the walk is standing. The walk is the **memo session** ([MemoBook]), it lives across turns, and
 * it times out after five minutes — because "das Memo" is a word that only means something while
 * everyone in the room remembers which memo was last read out.
 *
 * Specified in `socks.specs/memo.specs.md`. No network, no permissions, no audio: the memos are
 * a list in the config store and everything else is German.
 *
 * @param clock injected so the session's expiry and the spoken timestamps are testable without
 *   waiting for either.
 */
class MemoSock(
    private val clock: Clock = Clock.systemDefaultZone(),
    private val screenWakeSeconds: Int = DEFAULT_SCREEN_WAKE_SECONDS,
) : Sock {

    override val id: String = "memo"

    override val displayName: String = "Memos"

    /**
     * Held from [onStart] so [handle] can reach the config store, the screen and the log.
     *
     * Not passed into `handle` by design: a Sock's context is service-lifetime, while an
     * invocation is a single utterance.
     */
    private var context: SockContext? = null

    private val book = MemoBook(clock)

    /** What the wall panel draws (§8): the open memos, newest first, and the one being read. */
    val state: StateFlow<MemoState> = book.state

    override val commands: List<ExclusiveCommandSpec> = listOf(
        ExclusiveCommandSpec(
            id = CREATE_MEMO,
            params = listOf(ParamSpec("text", ParamType.Text)),
            templates = patterns(
                // The text slot is greedy for the rest of the utterance, so every template here
                // needs a keyword in front of it — and "memo" or "notiz" is that keyword in all
                // but the last two. These sort *after* every closed template in the palette
                // (`Specificity.ORDER`), which is what keeps "memo weiter" a navigation command
                // rather than a memo called "Weiter".
                "(erstelle|erstell|erzeuge|speicher|speichere|schreib|schreibe) (ein|neues)? (memo|notiz) {text}",
                "(neues|neue) (memo|notiz) {text}",
                "(memo|notiz) {text}",
                "(merk|merke) dir {text}",
                "(notier|notiere) {text}",
            ),
            description = "Speichert ein Memo mit dem gesagten Text.",
            help = CommandHelp(
                title = "Memo erstellen",
                detail = "Speichert, was du danach sagst, als Memo — mit Zeitstempel, damit du " +
                    "beim Vorlesen hörst, wie alt es ist.",
                hints = listOf(
                    "Das Memo ist alles nach dem Wort „Memo“: „erstelle Memo Rechnung bezahlen“.",
                    "Ich lese es nicht von selbst vor. Frag mit „welche Memos sind offen“.",
                ),
                aliases = listOf("memo", "notiz", "memo erstellen", "notieren", "merken"),
            ),
            examples = listOf(
                Example("erstelle memo milch kaufen", mapOf("text" to "milch kaufen")),
                Example("erstell ein memo rechnung bezahlen", mapOf("text" to "rechnung bezahlen")),
                Example("neues memo fenster schließen", mapOf("text" to "fenster schließen")),
                Example("memo mama anrufen", mapOf("text" to "mama anrufen")),
                Example("notiz brot holen", mapOf("text" to "brot holen")),
                Example("schreibe ein memo müll rausbringen", mapOf("text" to "müll rausbringen")),
                Example("merk dir brot kaufen", mapOf("text" to "brot kaufen")),
                Example("notiere reifen wechseln lassen", mapOf("text" to "reifen wechseln lassen")),
                // Paraphrases Tier 1 is meant to miss — few-shots for the LLM tier. Written in
                // *normalized* German, because Tier 2 is handed the same string Tier 1 saw.
                Example(
                    "kannst du dir merken dass ich die rechnung bezahlen muss",
                    mapOf("text" to "rechnung bezahlen"),
                    matchedByTemplates = false,
                ),
                Example(
                    "schreib mir auf dass morgen der müll rausmuss",
                    mapOf("text" to "morgen müll rausbringen"),
                    matchedByTemplates = false,
                ),
                // Held out: never shown to the model, asserted on the device.
                Example("ich darf nicht vergessen die pakete abzuholen", mapOf("text" to "pakete abholen"), heldOut = true),
                Example("erinnere mich später an die wäsche", mapOf("text" to "wäsche"), heldOut = true),
            ),
        ),
        ExclusiveCommandSpec(
            id = LATEST_MEMO,
            templates = patterns(
                // No filler-only optionals: the matcher skips "es", "die", "mir" and "noch"'s
                // neighbours on its second pass (`socks.specs/README.md` §6). "(gibt es)?" is
                // spelled out anyway because it is the commonest phrasing of all and belongs on
                // the strict first pass; "noch" and "offene" are content words and stay.
                "(gibt es|gibts|hab ich|habe ich|haben wir) (noch)? (offene)? (memos|notizen)",
                "(liste|zeig|zeige|nenn|nenne|sag) (alle|meine)? (memos|notizen)",
                "welche (memos|notizen) (sind)? (noch)? (offen|da)",
                "(memos|notizen) (vorlesen|bitte)",
                "(letztes|neuestes|aktuelles) (memo|notiz)",
            ),
            description = "Liest das neueste Memo vor und sagt, wie viele weitere offen sind.",
            help = CommandHelp(
                title = "Memos vorlesen",
                detail = "Liest das neueste Memo vor, mit Zeitstempel, und sagt, wie viele " +
                    "weitere offen sind. Danach kommst du mit „nächstes Memo“ durch die Liste.",
                hints = listOf(
                    "Von hier an gilt „Memo erledigt“ für das zuletzt vorgelesene — fünf Minuten lang.",
                ),
                aliases = listOf("memos", "memos vorlesen", "offene memos", "welche memos sind offen"),
            ),
            examples = listOf(
                Example("gibt es memos"),
                Example("gibt es noch offene memos"),
                Example("habe ich memos"),
                Example("liste die memos"),
                Example("zeig mir meine memos"),
                Example("welche memos sind offen"),
                Example("welche notizen sind noch da"),
                Example("memos vorlesen"),
                Example("letztes memo"),
                Example("neuestes memo"),
                Example("was steht noch an memos", matchedByTemplates = false),
                Example("sag mal was hab ich mir da alles aufgeschrieben", matchedByTemplates = false),
                Example("was wollte ich nochmal alles erledigen", heldOut = true),
                Example("lies mir vor was ich mir notiert habe", heldOut = true),
            ),
        ),
        ExclusiveCommandSpec(
            id = OLDEST_MEMO,
            templates = patterns(
                "(ältestes|aeltestes|älteste|aelteste|erstes|erste) (memo|notiz)",
                "(memos|notizen) von (vorne|unten|anfang)",
                "(fang|starte) (beim|mit dem) (ältesten|aeltesten|ersten) (memo|notiz)? an",
            ),
            description = "Liest das älteste Memo vor und geht von dort aufwärts weiter.",
            help = CommandHelp(
                title = "Ältestes Memo",
                detail = "Wie „Memos vorlesen“, nur von der anderen Seite: das älteste zuerst. " +
                    "„Nächstes Memo“ geht dann zum nächstälteren.",
                hints = listOf("Gut, wenn etwas schon lange liegen geblieben ist."),
                aliases = listOf("ältestes memo", "erstes memo", "von vorne"),
            ),
            examples = listOf(
                Example("ältestes memo"),
                Example("das älteste memo"),
                Example("erstes memo"),
                Example("memos von vorne"),
                Example("fang beim ältesten memo an"),
                Example("was liegt am längsten rum", matchedByTemplates = false),
                Example("womit hab ich denn ganz am anfang angefangen", heldOut = true),
            ),
        ),
        ExclusiveCommandSpec(
            id = NEXT_MEMO,
            templates = patterns(
                // Bare "weiter" belongs to `shared.resume` and bare "nächstes" is nobody's; both
                // are left alone. Every template here names a memo, which is also what keeps
                // Spotify's "nächster song" and this one apart at the only place they could
                // collide (§7).
                "(nächstes|naechstes|nächste|naechste) (memo|notiz)",
                "(memo|notiz) weiter",
                "weiter (zum|zur)? (nächsten|naechsten)? (memo|notiz)",
                "(noch|und)? ein (memo|notiz) weiter",
            ),
            description = "Liest das nächste Memo in der begonnenen Richtung vor.",
            help = CommandHelp(
                title = "Nächstes Memo",
                detail = "Geht ein Memo weiter — abwärts, wenn du beim neuesten angefangen " +
                    "hast, aufwärts, wenn du beim ältesten angefangen hast.",
                hints = listOf("Ohne vorheriges Vorlesen fange ich beim neuesten an."),
                aliases = listOf("nächstes memo", "memo weiter", "weiter"),
            ),
            examples = listOf(
                Example("nächstes memo"),
                Example("nächste notiz"),
                Example("memo weiter"),
                Example("weiter zum nächsten memo"),
                Example("ein memo weiter"),
                Example("und was steht als nächstes drauf", matchedByTemplates = false),
                Example("gib mir mal das nächste von der liste", heldOut = true),
            ),
        ),
        ExclusiveCommandSpec(
            id = PREVIOUS_MEMO,
            templates = patterns(
                // "letztes memo" is *not* here: in German it means the most recent one, and it
                // belongs to `latest_memo` (§7). "vorheriges" and "voriges" carry this command.
                "(vorheriges|vorherige|voriges|vorige) (memo|notiz)",
                "(memo|notiz) (zurück|zurueck|davor)",
                "(ein)? (memo|notiz) zurück",
                "zurück zum (vorherigen|vorigen) (memo|notiz)",
            ),
            description = "Liest das vorherige Memo vor, also eines zurück.",
            help = CommandHelp(
                title = "Vorheriges Memo",
                detail = "Geht ein Memo zurück, gegen die Richtung, in der du gerade durch die " +
                    "Liste gehst.",
                aliases = listOf("vorheriges memo", "memo zurück", "eins zurück"),
            ),
            examples = listOf(
                Example("vorheriges memo"),
                Example("voriges memo"),
                Example("memo zurück"),
                Example("ein memo zurück"),
                Example("zurück zum vorherigen memo"),
                Example("was war nochmal das davor", matchedByTemplates = false),
                Example("geh mal wieder eins nach oben", heldOut = true),
            ),
        ),
        ExclusiveCommandSpec(
            id = CLOSE_MEMO,
            templates = patterns(
                // Bare "erledigt" is deliberately absent, and §5 of the spec records why: at
                // eight characters the fuzzy tolerance is two, and this is the one command in
                // the Sock that throws something away.
                "(memo|notiz) (ist)? (erledigt|fertig|abgehakt|weg)",
                "(erledige|erledigt|schließe|schließ|schliesse|lösch|lösche|loesche|streich|streiche|entferne) " +
                    "(das|die)? (memo|notiz)",
                "(hak|hake) (das|die)? (memo|notiz) ab",
                "(memo|notiz) (löschen|loeschen|schließen|schliessen|streichen|entfernen)",
                "(das|die)? (memo|notiz) kann weg",
            ),
            description = "Erledigt das zuletzt vorgelesene Memo.",
            help = CommandHelp(
                title = "Memo erledigen",
                detail = "Schließt das Memo, das ich zuletzt vorgelesen habe. Danach ist es weg " +
                    "und zählt nicht mehr zu den offenen.",
                hints = listOf(
                    "Nur direkt nach dem Vorlesen: nach fünf Minuten frage ich lieber nach, " +
                        "statt das falsche Memo wegzuwerfen.",
                ),
                aliases = listOf("memo erledigt", "memo löschen", "erledigt", "abhaken"),
            ),
            examples = listOf(
                Example("memo erledigt"),
                Example("memo ist erledigt"),
                Example("notiz fertig"),
                Example("lösche das memo"),
                Example("schließe das memo"),
                Example("hak das memo ab"),
                Example("memo löschen"),
                Example("das memo kann weg"),
                Example("das hab ich erledigt kannst du wegwerfen", matchedByTemplates = false),
                Example("das ist abgehakt brauche ich nicht mehr", heldOut = true),
            ),
        ),
    )

    override suspend fun onStart(ctx: SockContext) {
        context = ctx
        // Memos that outlived the process are picked up here. A note somebody dictated and a
        // reboot swallowed is the one failure this Sock may not have (§10).
        book.restore(ctx)
    }

    override suspend fun onStop() {
        // The memos stay on disk; the walk does not. A cursor that survived a restart would make
        // "Memo erledigt" mean something decided before the panel was even switched off.
        book.forgetSession()
        context = null
    }

    override suspend fun handle(invocation: CommandInvocation): SockResult {
        val ctx = context ?: return SockResult.Failed(NOT_READY)
        return when (invocation.commandId) {
            CREATE_MEMO -> create(ctx, invocation)
            LATEST_MEMO -> read(ctx, book.open(ctx, Direction.NEWEST_FIRST))
            OLDEST_MEMO -> read(ctx, book.open(ctx, Direction.OLDEST_FIRST))
            NEXT_MEMO -> read(ctx, book.step(ctx, forward = true))
            PREVIOUS_MEMO -> read(ctx, book.step(ctx, forward = false))
            CLOSE_MEMO -> close(ctx)

            // Unreachable in practice: the dispatcher only routes commands this Sock owns.
            // Reported as a bug rather than silently swallowed.
            else -> SockResult.NotForMe
        }
    }

    /**
     * `memo.create_memo` — everything after the word "Memo" becomes the memo.
     *
     * The text is cleaned first ([MemoText]): a `{text}` slot takes whatever tokens follow its
     * anchor, so "erstelle mal ein Memo bitte" arrives here as "bitte", and saving that would put
     * a note on the panel that nobody can act on and everybody has to close.
     */
    private suspend fun create(ctx: SockContext, invocation: CommandInvocation): SockResult {
        val text = MemoText.clean(invocation.textOrNull("text"))
            ?: return SockResult.Failed(NOTHING_TO_NOTE)
        return when (val outcome = book.add(ctx, text)) {
            is AddOutcome.Added -> {
                // The panel shows the memo written out, which is the half of the answer that
                // survives being misheard — and the half somebody checks before walking away.
                ctx.screen.wakeFor(screenWakeSeconds)
                SockResult.Spoken(MemoSpeech.saved(outcome.memo))
            }

            AddOutcome.TooMany -> SockResult.Failed(MemoSpeech.tooMany(MemoConfig(ctx.config).maxMemos))
        }
    }

    /** The one place a memo is spoken and the panel is woken. All four readers land here. */
    private fun read(ctx: SockContext, step: Step): SockResult = when (step) {
        is Step.At -> {
            ctx.screen.wakeFor(screenWakeSeconds)
            SockResult.Spoken(
                MemoSpeech.read(step.memo, step.opening, step.others, clock.zone, clock.instant()),
            )
        }

        Step.Empty -> SockResult.Spoken(NOTHING_OPEN)

        // Not a failure: the walk is intact and the cursor has not moved, so "erledigt" still
        // means the memo they are standing on.
        is Step.Edge -> SockResult.Spoken(if (step.forward) LAST_ONE else FIRST_ONE)
    }

    /**
     * `memo.close_memo` — the destructive one, and the reason the session has a timeout.
     *
     * With no live session there is nothing this can honestly act on, so it says so instead of
     * picking the newest memo and hoping. "Das Memo" is a word about a conversation; five minutes
     * after the conversation, it is a word about nothing.
     */
    private suspend fun close(ctx: SockContext): SockResult = when (val outcome = book.close(ctx)) {
        is CloseOutcome.Closed -> {
            ctx.screen.wakeFor(screenWakeSeconds)
            SockResult.Spoken(MemoSpeech.closed(outcome.memo, outcome.open))
        }

        CloseOutcome.NoSession -> SockResult.Spoken(NO_SESSION)
    }

    /**
     * The check mark on a memo row (§8): the Sock's own close, run from a finger.
     *
     * By id, and deliberately not through the session the spoken command uses — pointing at a
     * memo says which one, so there is nothing to disambiguate and nothing to time out. It is
     * the same argument `ClockSock.cancelFromPanel` makes: a button press must not be routed
     * through the one part of the system that can misunderstand it.
     */
    suspend fun closeFromPanel(id: Long) {
        book.closeById(context ?: return, id)
    }

    companion object {
        const val CREATE_MEMO: String = "memo.create_memo"
        const val LATEST_MEMO: String = "memo.latest_memo"
        const val OLDEST_MEMO: String = "memo.oldest_memo"
        const val NEXT_MEMO: String = "memo.next_memo"
        const val PREVIOUS_MEMO: String = "memo.previous_memo"
        const val CLOSE_MEMO: String = "memo.close_memo"

        const val DEFAULT_SCREEN_WAKE_SECONDS: Int = 30

        /** Spec copy (§3–§5), German verbatim and English alongside it. */
        val NOTHING_OPEN: Phrase = Phrase.of("Es sind keine Memos offen.", "You have no open memos.")
        val NOTHING_TO_NOTE: Phrase = Phrase.of(
            "Ich habe nicht verstanden, was ich mir merken soll.",
            "I didn't catch what I should note down.",
        )
        val LAST_ONE: Phrase =
            Phrase.of("Das war schon das letzte Memo.", "That was the last memo.")
        val FIRST_ONE: Phrase =
            Phrase.of("Davor gibt es kein weiteres Memo.", "There's nothing before that one.")
        val NO_SESSION: Phrase = Phrase.of(
            "Ich weiß gerade nicht, welches Memo du meinst. Frag mich zuerst nach deinen Memos.",
            "I'm not sure which memo you mean. Ask me for your memos first.",
        )

        /**
         * Before `onStart`, which the dispatcher should never reach — and "should never" is not a
         * reason to lose the answer if it does. The same guard `CalculatorSock` keeps.
         */
        val NOT_READY: Phrase = Phrase.of(
            "Ich komme gerade nicht an meine Memos.",
            "I can't get at my memos right now.",
        )
    }
}
