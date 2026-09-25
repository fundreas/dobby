package io.dobby.socks.weather

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
import io.dobby.core.sock.SockStatus
import io.dobby.core.sock.TemplatePattern
import io.dobby.core.sock.pattern
import io.dobby.core.sock.patterns
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDateTime

/**
 * Weather — what the sky is doing here, now, and for the next two days.
 *
 * The first Sock that has to be told **where it is**. Every other one either needs nothing from
 * the world (Clock, Calculator, Memo), or takes an address from a constant table (Radio), or
 * asks another app (Spotify). This one needs a position, it is not allowed to take one for
 * itself (`socks.specs/README.md` §2), and there is no sensible default — a forecast for the
 * wrong city is worse than no forecast, because it is confidently wrong and nothing on the
 * panel says so. Hence the Setup button and [DeviceLocation]: the panel asks the phone once,
 * saves the answer, and never asks again unless somebody taps the card's location chip.
 *
 * The command surface is five questions and a housekeeping one, and the five are the five
 * shapes a weather question comes in. They all take the same optional `day` — `heute`,
 * `morgen`, `übermorgen` — because that is the one axis every one of them varies on, and
 * declaring it once is what keeps "wie warm ist es", "wie warm wird es morgen" and "regnet es
 * übermorgen" the same three words of grammar rather than fifteen commands:
 *
 * | command | the question |
 * |---|---|
 * | [TEMPERATURE] | how warm — now, at its warmest, or at its coldest |
 * | [FORECAST] | what the sky is doing, in one sentence |
 * | [RAIN] | is water coming out of it, and when |
 * | [SUN] | is the sun out |
 * | [WIND] | how hard is it blowing |
 * | [UPDATE_LOCATION] | the panel has moved |
 *
 * Specified in `socks.specs/weather.specs.md`. No audio, no chain, no focus: it answers a
 * question and holds nothing anybody would ever say "stopp" to.
 *
 * @param source where a forecast comes from. [WeatherSource.NONE] off-device, where every
 *   command still resolves and every one of them says it is offline.
 * @param location the phone's position. [DeviceLocation.NONE] off-device, where the panel is
 *   permanently un-set-up and says so — which is the same code path as a refused permission.
 * @param clock injected so "the rest of today" and staleness are testable without waiting for
 *   either.
 */
class WeatherSock(
    private val source: WeatherSource = WeatherSource.NONE,
    location: DeviceLocation = DeviceLocation.NONE,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val screenWakeSeconds: Int = DEFAULT_SCREEN_WAKE_SECONDS,
) : Sock {

    override val id: String = "weather"

    override val displayName: String = "Wetter"

    /** Held from [onStart], the way every other Sock holds its own: a context is service-lifetime. */
    private var context: SockContext? = null

    private val watch = WeatherWatch(source, location, clock)

    /** What the wall panel draws (§8): the location, the forecast, and what went wrong. */
    val state: StateFlow<WeatherState> = watch.state

    private val _status = MutableStateFlow<SockStatus>(SockStatus.Ready)

    override val status: StateFlow<SockStatus> = _status.asStateFlow()

    /** The ten-minute tick. Lives exactly as long as the Sock is started. */
    private var ticker: Job? = null

    override val commands: List<ExclusiveCommandSpec> = listOf(
        ExclusiveCommandSpec(
            id = TEMPERATURE,
            params = listOf(
                dayParam(),
                // Which end of the thermometer the question is about. Fixed by the *wording*
                // rather than captured, because "warm" and "kalt" are the words that carry it
                // and no slot can hold a word that is part of the verb phrase.
                ParamSpec(
                    "bound",
                    ParamType.Enumeration(listOf(NOW, WARM, COLD)),
                    required = false,
                    default = NOW,
                ),
            ),
            templates =
                // "ist" — the thermometer as it stands. With a day on it the present tense is
                // a figure of speech ("wie warm ist es morgen"), and §4 reads it as the range.
                dayForms("wie (warm|kalt) ist", "bound" to NOW) +
                    dayForms("(wie viel|wieviel) grad (haben wir|hat|haben|sind|hats)", "bound" to NOW) +
                    dayForms("(wie|was) ist die temperatur", "bound" to NOW) +
                    dayForms("(sag|nenn|nenne) (mir)? die temperatur", "bound" to NOW) +
                    dayForms("temperatur (draußen|draussen)", "bound" to NOW) +
                    // "wird" — the future, and the warm half of it.
                    dayForms("wie (warm|heiß|heiss) (wird|wirds)", "bound" to WARM) +
                    dayForms("wie (hoch) (wird|steigt) die temperatur", "bound" to WARM) +
                    dayForms("was ist die (höchsttemperatur|hoechsttemperatur)", "bound" to WARM) +
                    dayForms("wie (warm|heiß|heiss) soll es werden", "bound" to WARM) +
                    // …and the cold half.
                    dayForms("wie (kalt|kühl|kuehl) (wird|wirds)", "bound" to COLD) +
                    dayForms("wie (tief|weit) (sinkt|fällt|faellt) die temperatur", "bound" to COLD) +
                    dayForms("was ist die (tiefsttemperatur|tiefstemperatur)", "bound" to COLD) +
                    listOf(
                        // "heute nacht" is its own phrasing and not a day word: the night that
                        // belongs to today is the tail of today's hours, which is exactly what
                        // `bound = kalt` on `heute` already answers.
                        pattern(
                            "wie (kalt|kühl|kuehl) (wird|wirds) (es)? (heute)? nacht",
                            "bound" to COLD,
                            WeatherDay.PARAM to WeatherDay.HEUTE.spoken,
                        ),
                    ),
            description = "Sagt die Temperatur — jetzt, im Tageshöchstwert oder im Tiefstwert.",
            help = CommandHelp(
                title = "Temperatur",
                detail = "Sagt, wie warm es ist. Mit „wird“ statt „ist“ bekommst du den " +
                    "Höchstwert, mit „wie kalt wird es“ den Tiefstwert — für heute jeweils " +
                    "nur für die Stunden, die noch kommen.",
                hints = listOf(
                    "Ohne Tag meine ich heute. „Morgen“ und „übermorgen“ gehen auch.",
                    "„Wie kalt wird es heute noch“ rechnet ab jetzt, nicht ab Mitternacht.",
                ),
                aliases = listOf("temperatur", "wie warm", "wie kalt", "grad"),
            ),
            examples = listOf(
                Example("wie warm ist es", temperature(WeatherDay.HEUTE, NOW)),
                Example("wie kalt ist es", temperature(WeatherDay.HEUTE, NOW)),
                Example("wie viel grad hat es", temperature(WeatherDay.HEUTE, NOW)),
                Example("wie ist die temperatur", temperature(WeatherDay.HEUTE, NOW)),
                Example("wie warm ist es morgen", temperature(WeatherDay.MORGEN, NOW)),
                Example("wie warm ist es übermorgen", temperature(WeatherDay.UEBERMORGEN, NOW)),
                Example("wie warm wird es heute noch", temperature(WeatherDay.HEUTE, WARM)),
                Example("wie warm wird es morgen", temperature(WeatherDay.MORGEN, WARM)),
                Example("wie heiß wird es morgen", temperature(WeatherDay.MORGEN, WARM)),
                Example("wie kalt wird es heute noch", temperature(WeatherDay.HEUTE, COLD)),
                Example("wie kalt wird es morgen", temperature(WeatherDay.MORGEN, COLD)),
                Example("wie kalt wird es heute nacht", temperature(WeatherDay.HEUTE, COLD)),
                Example("was ist die höchsttemperatur morgen", temperature(WeatherDay.MORGEN, WARM)),
                // Paraphrases Tier 1 is meant to miss — few-shots for the LLM tier, written in
                // *normalized* German because Tier 2 is handed the same string Tier 1 saw.
                Example(
                    "muss ich mir morgen eine jacke anziehen oder wird es warm",
                    temperature(WeatherDay.MORGEN, WARM),
                    matchedByTemplates = false,
                ),
                Example(
                    "sag mal frierts heute nacht",
                    temperature(WeatherDay.HEUTE, COLD),
                    matchedByTemplates = false,
                ),
                // Held out: never shown to the model, asserted on the device.
                Example("ist es draußen noch angenehm", temperature(WeatherDay.HEUTE, NOW), heldOut = true),
                Example("wird das übermorgen ein warmer tag", temperature(WeatherDay.UEBERMORGEN, WARM), heldOut = true),
            ),
        ),
        ExclusiveCommandSpec(
            id = FORECAST,
            params = listOf(dayParam()),
            templates =
                dayForms("wie (ist|wird) das wetter") +
                    dayForms("wie (siehts|sieht es) wettermäßig aus") +
                    dayForms("was sagt (der)? (wetterbericht|wetterdienst)") +
                    dayForms("(wetterbericht|wettervorhersage|wetteraussichten)") +
                    dayForms("(ist|wird) es (bewölkt|bewoelkt|wolkig|neblig|nebelig)") +
                    dayInfix("(ist|wird)", "(bewölkt|bewoelkt|wolkig|neblig|nebelig)") +
                    listOf(
                        // A day word is **required** here, which is what keeps a bare "wetter"
                        // out of the palette — see §7 for why that word cannot be claimed on
                        // its own.
                        TemplatePattern("(das)? wetter {day:enum} (noch)?"),
                        TemplatePattern("wie (wirds|wird es) {day:enum}"),
                    ),
            description = "Sagt, wie das Wetter ist oder wird.",
            help = CommandHelp(
                title = "Wetterbericht",
                detail = "Der Überblick in einem Satz: Himmel, Temperatur und, wenn sie " +
                    "nennenswert ist, die Regenwahrscheinlichkeit.",
                hints = listOf("Für heute fange ich mit dem an, was gerade draußen ist."),
                aliases = listOf("wetter", "wetterbericht", "vorhersage", "wie wird das wetter"),
            ),
            examples = listOf(
                Example("wie ist das wetter", mapOf(WeatherDay.PARAM to WeatherDay.HEUTE.spoken)),
                Example("wie wird das wetter morgen", mapOf(WeatherDay.PARAM to WeatherDay.MORGEN.spoken)),
                Example(
                    "wie ist das wetter übermorgen",
                    mapOf(WeatherDay.PARAM to WeatherDay.UEBERMORGEN.spoken),
                ),
                Example("das wetter morgen", mapOf(WeatherDay.PARAM to WeatherDay.MORGEN.spoken)),
                Example("wetterbericht", mapOf(WeatherDay.PARAM to WeatherDay.HEUTE.spoken)),
                Example("wettervorhersage morgen", mapOf(WeatherDay.PARAM to WeatherDay.MORGEN.spoken)),
                Example("was sagt der wetterbericht morgen", mapOf(WeatherDay.PARAM to WeatherDay.MORGEN.spoken)),
                Example("ist es morgen bewölkt", mapOf(WeatherDay.PARAM to WeatherDay.MORGEN.spoken)),
                Example(
                    "kann ich morgen im garten grillen oder wird das nichts",
                    mapOf(WeatherDay.PARAM to WeatherDay.MORGEN.spoken),
                    matchedByTemplates = false,
                ),
                Example(
                    "was erwartet uns denn so übermorgen draußen",
                    mapOf(WeatherDay.PARAM to WeatherDay.UEBERMORGEN.spoken),
                    heldOut = true,
                ),
            ),
        ),
        ExclusiveCommandSpec(
            id = RAIN,
            params = listOf(
                dayParam(),
                // Rain and snow are one command because they are one question with two
                // answers — "kommt da was runter" — and splitting them would make
                // `weather.snow` a command that is wrong eleven months of the year.
                ParamSpec(
                    "kind",
                    ParamType.Enumeration(listOf(RAINFALL, SNOWFALL)),
                    required = false,
                    default = RAINFALL,
                ),
            ),
            templates =
                // "regnet" and "schneit" are the two heads in this Sock that a single spoken
                // word could satisfy, and both fail §6's test: "regnen" and "regnete" are one
                // edit from the first, "schneien" is close to the second, and all four are
                // things somebody says in a kitchen without addressing the panel. So the
                // question form is required — see §7.
                anchoredDayForms("(regnet|regnets)", "kind" to RAINFALL) +
                    dayForms("(wird|wirds) es regnen", "kind" to RAINFALL) +
                    dayInfix("(wird|wirds)", "regnen", "kind" to RAINFALL) +
                    dayForms("(gibt|kommt) es regen", "kind" to RAINFALL) +
                    dayInfix("(gibt|kommt)", "regen", "kind" to RAINFALL) +
                    dayInfix("(brauche|brauch) ich", "(einen|nen)? (regenschirm|schirm)", "kind" to RAINFALL) +
                    dayForms("wie (hoch|gross|groß) ist die regenwahrscheinlichkeit", "kind" to RAINFALL) +
                    anchoredDayForms("(schneit|schneits)", "kind" to SNOWFALL) +
                    dayForms("(wird|wirds) es schneien", "kind" to SNOWFALL) +
                    dayInfix("(wird|wirds)", "schneien", "kind" to SNOWFALL) +
                    dayForms("(gibt|kommt) es schnee", "kind" to SNOWFALL) +
                    dayInfix("(gibt|kommt)", "schnee", "kind" to SNOWFALL),
            description = "Sagt, ob Regen oder Schnee zu erwarten ist.",
            help = CommandHelp(
                title = "Regen und Schnee",
                detail = "Antwortet mit Ja oder Nein und sagt dazu, ab wann und wie " +
                    "wahrscheinlich. Für heute zählen nur die Stunden, die noch kommen.",
                hints = listOf(
                    "„Schneit es morgen“ ist die gleiche Frage mit der anderen Antwort.",
                    "Unter 50 Prozent sage ich „eher nicht“ und nenne die Wahrscheinlichkeit.",
                ),
                aliases = listOf("regen", "regnet es", "schnee", "regenschirm"),
            ),
            examples = listOf(
                Example("regnet es", rain(WeatherDay.HEUTE, RAINFALL)),
                Example("regnet es heute noch", rain(WeatherDay.HEUTE, RAINFALL)),
                Example("regnet es morgen", rain(WeatherDay.MORGEN, RAINFALL)),
                Example("regnet es übermorgen", rain(WeatherDay.UEBERMORGEN, RAINFALL)),
                Example("wird es morgen regnen", rain(WeatherDay.MORGEN, RAINFALL)),
                Example("gibt es morgen regen", rain(WeatherDay.MORGEN, RAINFALL)),
                Example("brauche ich morgen einen regenschirm", rain(WeatherDay.MORGEN, RAINFALL)),
                Example("schneit es morgen", rain(WeatherDay.MORGEN, SNOWFALL)),
                Example("gibt es übermorgen schnee", rain(WeatherDay.UEBERMORGEN, SNOWFALL)),
                Example(
                    "soll ich die wäsche lieber reinholen morgen",
                    rain(WeatherDay.MORGEN, RAINFALL),
                    matchedByTemplates = false,
                ),
                Example(
                    "kann ich das fahrrad heute draußen stehen lassen",
                    rain(WeatherDay.HEUTE, RAINFALL),
                    heldOut = true,
                ),
            ),
        ),
        ExclusiveCommandSpec(
            id = SUN,
            params = listOf(dayParam()),
            templates =
                dayForms("(scheint|scheints) (die)? sonne") +
                    dayInfix("(scheint|scheints)", "(die)? sonne") +
                    dayForms("(ist|wird|ists|wirds) es sonnig") +
                    dayInfix("(ist|wird|ists|wirds)", "sonnig") +
                    dayForms("(gibt|kommt) es sonne") +
                    dayInfix("(gibt|kommt)", "sonne") +
                    dayForms("(haben|kriegen) wir sonne") +
                    dayInfix("(haben|kriegen) wir", "sonne") +
                    dayForms("(lässt|laesst) (die)? sonne sich blicken"),
            description = "Sagt, ob die Sonne scheint oder scheinen wird.",
            help = CommandHelp(
                title = "Sonne",
                detail = "Antwortet mit Ja oder Nein. Für heute sage ich dazu, ab wann — " +
                    "„gerade nicht, aber ab 15 Uhr“ ist die nützlichere Antwort als ein Nein.",
                aliases = listOf("sonne", "sonnig", "scheint die sonne"),
            ),
            examples = listOf(
                Example("scheint die sonne", mapOf(WeatherDay.PARAM to WeatherDay.HEUTE.spoken)),
                Example("scheint die sonne morgen", mapOf(WeatherDay.PARAM to WeatherDay.MORGEN.spoken)),
                Example("scheint die sonne heute noch", mapOf(WeatherDay.PARAM to WeatherDay.HEUTE.spoken)),
                Example("ist es morgen sonnig", mapOf(WeatherDay.PARAM to WeatherDay.MORGEN.spoken)),
                Example("wird es übermorgen sonnig", mapOf(WeatherDay.PARAM to WeatherDay.UEBERMORGEN.spoken)),
                Example("gibt es morgen sonne", mapOf(WeatherDay.PARAM to WeatherDay.MORGEN.spoken)),
                Example(
                    "kann ich morgen mit dem kinderwagen in den park",
                    mapOf(WeatherDay.PARAM to WeatherDay.MORGEN.spoken),
                    matchedByTemplates = false,
                ),
                Example(
                    "reißt der himmel heute noch auf",
                    mapOf(WeatherDay.PARAM to WeatherDay.HEUTE.spoken),
                    heldOut = true,
                ),
            ),
        ),
        ExclusiveCommandSpec(
            id = WIND,
            params = listOf(dayParam()),
            templates =
                dayForms("wie (stark|schnell|heftig) (ist|wird) der wind") +
                    dayForms("wie (ist|wird) der wind") +
                    dayForms("(wie viel|wieviel) wind (haben wir|gibt es)?") +
                    dayForms("(ist|wird|ists|wirds) es windig") +
                    dayInfix("(ist|wird|ists|wirds)", "windig") +
                    dayForms("(weht|bläst|blaest) der wind") +
                    dayForms("(gibt|kommt) es (sturm|starken wind)") +
                    dayInfix("(gibt|kommt)", "(sturm|starken wind)"),
            description = "Sagt, wie stark der Wind weht.",
            help = CommandHelp(
                title = "Wind",
                detail = "Sagt erst das Wort — windstill, mäßiger Wind, Sturm — und dann die " +
                    "Kilometer pro Stunde. Die Zahl allein beantwortet die Frage nicht.",
                aliases = listOf("wind", "windig", "sturm"),
            ),
            examples = listOf(
                Example("wie stark ist der wind", mapOf(WeatherDay.PARAM to WeatherDay.HEUTE.spoken)),
                Example("wie stark wird der wind morgen", mapOf(WeatherDay.PARAM to WeatherDay.MORGEN.spoken)),
                Example("wie ist der wind", mapOf(WeatherDay.PARAM to WeatherDay.HEUTE.spoken)),
                Example("ist es windig", mapOf(WeatherDay.PARAM to WeatherDay.HEUTE.spoken)),
                Example("wird es morgen windig", mapOf(WeatherDay.PARAM to WeatherDay.MORGEN.spoken)),
                Example("wie viel wind haben wir", mapOf(WeatherDay.PARAM to WeatherDay.HEUTE.spoken)),
                Example("gibt es morgen sturm", mapOf(WeatherDay.PARAM to WeatherDay.MORGEN.spoken)),
                Example(
                    "kann ich den sonnenschirm draußen stehen lassen",
                    mapOf(WeatherDay.PARAM to WeatherDay.HEUTE.spoken),
                    matchedByTemplates = false,
                ),
                Example(
                    "zieht es morgen draußen ordentlich",
                    mapOf(WeatherDay.PARAM to WeatherDay.MORGEN.spoken),
                    heldOut = true,
                ),
            ),
        ),
        ExclusiveCommandSpec(
            id = UPDATE_LOCATION,
            templates = patterns(
                // Every one of these names "standort". Bare, the word is eight characters at
                // tolerance two, and this is the command that throws the working setup away
                // and asks the phone again — see §7.
                "(aktualisiere|aktualisier|erneuere|erneuer|bestimme|bestimm) (den|meinen)? standort (neu)?",
                "standort (aktualisieren|erneuern|bestimmen|neu bestimmen|neu)",
                "(neuer|neuen) standort",
                "(wetter|wetterstandort) (neu)? (einrichten|einstellen)",
                "(merk|merke) dir (den)? (neuen)? standort",
            ),
            description = "Fragt das Handy neu, wo es steht, und merkt sich den Ort für das Wetter.",
            help = CommandHelp(
                title = "Standort aktualisieren",
                detail = "Fragt das Gerät noch einmal nach seiner Position und merkt sie sich. " +
                    "Nötig, wenn das Panel umgezogen ist — sonst nie.",
                hints = listOf("Auf dem Panel macht das der Standort-Chip in der Wetterkarte."),
                aliases = listOf("standort", "standort aktualisieren", "wetter einrichten"),
            ),
            examples = listOf(
                Example("aktualisiere den standort"),
                Example("standort aktualisieren"),
                Example("bestimme meinen standort neu"),
                Example("neuer standort"),
                Example("wetter neu einrichten"),
                Example("merk dir den neuen standort"),
                Example("ich bin umgezogen stell das wetter neu ein", matchedByTemplates = false),
                Example("wir wohnen jetzt woanders", heldOut = true),
            ),
        ),
    )

    override suspend fun onStart(ctx: SockContext) {
        context = ctx
        watch.restore(ctx)
        // The ten-minute tick (§5). It is a plain loop rather than a scheduled alarm, because
        // a forecast nobody is looking at is worth nothing: this exists so the card is right
        // when somebody walks past it, and the panel is either running or it is not.
        ticker = ctx.scope.launch {
            while (isActive) {
                if (watch.state.value.isSetUp) watch.refresh(ctx)
                republishStatus()
                delay(WeatherConfig(ctx.config).refreshInterval.toMillis())
            }
        }
    }

    override suspend fun onStop() {
        ticker?.cancel()
        ticker = null
        context = null
    }

    override suspend fun handle(invocation: CommandInvocation): SockResult {
        val ctx = context ?: return SockResult.Failed(WeatherSpeech.NOT_READY)
        return when (invocation.commandId) {
            UPDATE_LOCATION -> updateLocation(ctx)
            TEMPERATURE -> answer(ctx, invocation) { report, day, now ->
                temperature(report, day, invocation.textOrNull("bound") ?: NOW, now)
            }

            FORECAST -> answer(ctx, invocation) { report, day, now -> forecast(report, day, now) }
            RAIN -> answer(ctx, invocation) { report, day, now ->
                rain(report, day, invocation.textOrNull("kind") ?: RAINFALL, now)
            }

            SUN -> answer(ctx, invocation) { report, day, now -> sun(report, day, now) }
            WIND -> answer(ctx, invocation) { report, day, now -> wind(report, day, now) }

            // Unreachable in practice: the dispatcher only routes commands this Sock owns.
            else -> SockResult.NotForMe
        }
    }

    /**
     * The check that sits in front of all five questions, so none of them has to repeat it.
     *
     * Three outcomes and three different sentences, which is the whole reason this is not a
     * null check: no location is the user's to fix and names the button, a dead fetch with
     * nothing cached is a plain failure, and a dead fetch with something cached is an answer
     * **with a caveat in front of it** — because twenty-minute-old weather is still weather and
     * refusing it would be a panel throwing away the thing it was asked for.
     *
     * @param build the answer, or null when the report does not reach that far ahead.
     */
    private suspend fun answer(
        ctx: SockContext,
        invocation: CommandInvocation,
        build: (WeatherReport, WeatherDay, LocalDateTime) -> Phrase?,
    ): SockResult {
        val day = WeatherDay.of(invocation.textOrNull(WeatherDay.PARAM))
        val result = when (val lookup = watch.lookup(ctx)) {
            Lookup.NoPlace -> return SockResult.Failed(WeatherSpeech.NO_PLACE)

            is Lookup.Unavailable -> {
                val stale = lookup.stale ?: return SockResult.Failed(WeatherSpeech.failure(lookup.error))
                val phrase = build(stale, day, stale.nowAt(clock.instant()))
                    ?: return SockResult.Spoken(WeatherSpeech.TOO_FAR_OUT)
                WeatherSpeech.stale(watch.age(stale), phrase)
            }

            is Lookup.Ready -> build(lookup.report, day, lookup.report.nowAt(clock.instant()))
                ?: return SockResult.Spoken(WeatherSpeech.TOO_FAR_OUT)
        }
        republishStatus()
        // The panel showing the same forecast the voice just read out is the half of the
        // answer that survives being misheard — and the half somebody checks on the way past.
        ctx.screen.wakeFor(screenWakeSeconds)
        return SockResult.Spoken(result)
    }

    /**
     * `weather.temperature` — §4.
     *
     * **Today is not the same question as tomorrow**, and that is the whole of this method.
     * For a future day the answer is the daily maximum or minimum, which is what a forecast
     * is. For *today* the daily minimum is last night, and answering "wie kalt wird es heute
     * noch" with it is a panel that is confidently wrong at two in the afternoon — so today's
     * bounds are taken over the hours that have not happened yet.
     */
    private fun temperature(
        report: WeatherReport,
        day: WeatherDay,
        bound: String,
        now: LocalDateTime,
    ): Phrase? {
        val forecast = report.day(day) ?: return null
        if (!day.isToday) {
            return when (bound) {
                WARM -> WeatherSpeech.dayWarmest(day, forecast)
                COLD -> WeatherSpeech.dayColdest(day, forecast)
                // "Wie warm ist es morgen" is the present tense used as a figure of speech.
                // Both ends is the honest reading of a question that named neither.
                else -> WeatherSpeech.dayRange(day, forecast)
            }
        }
        val remaining = forecast.hoursFrom(now)
        return when (bound) {
            // Late enough in the evening there are no hours left, and then the thermometer is
            // the only true thing there is to say.
            WARM -> remaining.maxByOrNull { it.temperature }
                ?.let { WeatherSpeech.restOfTodayWarmest(it, report.current.temperature) }
                ?: WeatherSpeech.currentTemperature(report.current)

            COLD -> remaining.minByOrNull { it.temperature }
                ?.let { WeatherSpeech.restOfTodayColdest(it, report.current.temperature) }
                ?: WeatherSpeech.currentTemperature(report.current)

            else -> WeatherSpeech.currentTemperature(report.current)
        }
    }

    /** `weather.forecast` — the overview, which starts with *now* when the day is today. */
    private fun forecast(report: WeatherReport, day: WeatherDay, now: LocalDateTime): Phrase? {
        val forecast = report.day(day) ?: return null
        if (!day.isToday) return WeatherSpeech.dayOverview(day, forecast)
        return WeatherSpeech.todayOverview(
            report.current,
            forecast,
            forecast.hoursFrom(now).maxByOrNull { it.temperature },
        )
    }

    /**
     * `weather.rain` — and `weather.rain` with `kind = schnee`, which is the same question.
     *
     * For today the answer comes out of the hours, because "ab 17 Uhr" is the part somebody
     * standing in a doorway actually needs; a daily probability would say "80 Prozent" about a
     * shower that fell at six this morning.
     */
    private fun rain(
        report: WeatherReport,
        day: WeatherDay,
        kind: String,
        now: LocalDateTime,
    ): Phrase? {
        val forecast = report.day(day) ?: return null
        val remaining = if (day.isToday) forecast.hoursFrom(now) else emptyList()
        if (kind == SNOWFALL) {
            val snowing = if (day.isToday) {
                remaining.any { it.condition.kind.isSnow }
            } else {
                forecast.condition.kind.isSnow
            }
            return if (snowing) WeatherSpeech.snowOnDay(day, forecast) else WeatherSpeech.noSnow(day)
        }
        if (day.isToday) {
            val wet = remaining.firstOrNull {
                it.condition.kind.isPrecipitation ||
                    (it.precipitationProbability ?: 0) >= WeatherSpeech.RAIN_LIKELY
            }
            if (wet != null) return WeatherSpeech.rainToday(wet, wet.precipitationProbability)
            val best = remaining.mapNotNull { it.precipitationProbability }.maxOrNull() ?: 0
            return if (best >= WeatherSpeech.RAIN_WORTH_MENTIONING) {
                WeatherSpeech.rainUnlikely(best)
            } else {
                WeatherSpeech.stayingDry(day)
            }
        }
        val probability = forecast.precipitationProbability ?: 0
        return when {
            forecast.condition.kind.isPrecipitation || probability >= WeatherSpeech.RAIN_LIKELY ->
                WeatherSpeech.rainOnDay(day, forecast)

            probability >= WeatherSpeech.RAIN_WORTH_MENTIONING -> WeatherSpeech.rainUnlikely(probability)
            else -> WeatherSpeech.stayingDry(day)
        }
    }

    /**
     * `weather.sun`.
     *
     * "Gerade nicht, aber ab 15 Uhr" is the answer this command exists for. A flat no on an
     * overcast morning that clears by lunchtime is technically defensible and useless, and the
     * hourly codes are already fetched.
     */
    private fun sun(report: WeatherReport, day: WeatherDay, now: LocalDateTime): Phrase? {
        val forecast = report.day(day) ?: return null
        if (!day.isToday) return WeatherSpeech.sunOnDay(day, forecast)
        // Dark is its own answer, and the hourly weather codes cannot give it: WMO 0 is "klar"
        // at two in the morning as much as at noon, so without the sunset this command
        // cheerfully promises sun for nine in the evening.
        if (!forecast.isDaylight(now)) {
            return WeatherSpeech.sunIsDown(forecast.sunrise, forecast.sunset, now)
        }
        if (report.current.condition.kind.isSunny) return WeatherSpeech.sunNow(report.current)
        val bright = forecast.hoursFrom(now)
            .firstOrNull { it.condition.kind.isSunny && forecast.isDaylight(it.time) }
        return bright?.let { WeatherSpeech.sunLater(it) } ?: WeatherSpeech.noSunToday(report.current)
    }

    /** `weather.wind` — the current gust today, the day's strongest for a future day. */
    private fun wind(report: WeatherReport, day: WeatherDay, now: LocalDateTime): Phrase? {
        val forecast = report.day(day) ?: return null
        if (day.isToday) return WeatherSpeech.windNow(report.current)
        val speed = forecast.maxWindSpeed ?: return WeatherSpeech.NO_WIND_FORECAST
        return WeatherSpeech.windOnDay(day, speed)
    }

    /**
     * `weather.update_location` — the spoken twin of the card's location chip.
     *
     * Both run [WeatherWatch.locate], so a tap and an utterance are one event, exactly as the
     * radio card's picker and "spiele radio ö1" are.
     */
    private suspend fun updateLocation(ctx: SockContext): SockResult {
        val outcome = watch.locate(ctx)
        republishStatus()
        return when (outcome) {
            is LocateOutcome.Located -> {
                // The card is where the rest of this answer lands: the coordinates now, the
                // forecast when the fetch this just started comes back.
                ctx.screen.wakeFor(screenWakeSeconds)
                SockResult.Spoken(WeatherSpeech.LOCATION_SET)
            }

            LocateOutcome.NoFix -> SockResult.Failed(WeatherSpeech.NO_FIX)
        }
    }

    /**
     * The Setup button (§8), and the card's location chip: the Sock's own locate, from a finger.
     *
     * Straight at the Sock rather than through the engine, and for the reason
     * `RadioSock.playFromPanel` gives: there is no utterance here, and inventing one to put a
     * button press back through the matcher would route it through the one part of the system
     * that can misunderstand it.
     */
    suspend fun locateFromPanel(): LocateOutcome {
        val ctx = context ?: return LocateOutcome.NoFix
        return watch.locate(ctx).also { republishStatus() }
    }

    /** The card's own refresh, for a panel somebody is standing in front of right now. */
    suspend fun refreshFromPanel() {
        val ctx = context ?: return
        watch.refresh(ctx)
        republishStatus()
    }

    /**
     * What the settings screen says about this Sock.
     *
     * Degraded rather than unavailable for both of its failures, and deliberately: a panel with
     * no location is one button press from working, and one that cannot reach Open-Meteo will
     * be fine again when the router is. `Unavailable` is for a dependency that is not coming
     * back, which is what it means on the Spotify Sock and what it must keep meaning.
     */
    private fun republishStatus() {
        val current = watch.state.value
        _status.value = when {
            !current.isSetUp -> SockStatus.Degraded("Kein Standort eingerichtet")
            current.error != null -> SockStatus.Degraded(current.error)
            else -> SockStatus.Ready
        }
    }

    companion object {
        const val TEMPERATURE: String = "weather.temperature"
        const val FORECAST: String = "weather.forecast"
        const val RAIN: String = "weather.rain"
        const val SUN: String = "weather.sun"
        const val WIND: String = "weather.wind"
        const val UPDATE_LOCATION: String = "weather.update_location"

        /** `bound` on [TEMPERATURE]: the thermometer now, the day's high, the day's low. */
        const val NOW: String = "jetzt"
        const val WARM: String = "warm"
        const val COLD: String = "kalt"

        /** `kind` on [RAIN]: water, or the frozen kind of it. */
        const val RAINFALL: String = "regen"
        const val SNOWFALL: String = "schnee"

        const val DEFAULT_SCREEN_WAKE_SECONDS: Int = 30

        /** The `day` slot, declared identically by all five questions (§2). */
        private fun dayParam(): ParamSpec = ParamSpec(
            WeatherDay.PARAM,
            ParamType.Enumeration(WeatherDay.SPOKEN),
            required = false,
            default = WeatherDay.HEUTE.spoken,
        )

        /**
         * One question head, in the two word orders German puts a day into.
         *
         * Everything after [head] is the same in every weather command, which is what makes it
         * a function rather than eleven copies of the same tail:
         *
         * - **`(es)?` and `(denn)?` are spelled out, although both are on `Fillers.DE`.** The
         *   matcher skips filler in front of a *keyword* and never in front of a slot
         *   (`socks.specs/README.md` §6), and the thing after them here is the day slot. Drop
         *   them and "regnet es denn morgen" matches nothing at all.
         * - **`(noch)?` appears twice, once on each side of the day.** "Heute noch" and "noch
         *   heute" are both ordinary German and "noch" is not filler — it is a content word
         *   that means *the rest of*, which is exactly the distinction §4 turns into a
         *   different answer.
         * - **The day slot is optional and defaults to today**, so the commonest phrasing of
         *   all — "wie warm ist es" — is also the shortest template.
         */
        private fun dayForms(head: String, vararg params: Pair<String, Any>): List<TemplatePattern> =
            listOf(pattern("$head $DAY_TAIL", *params))

        /**
         * The same tail, with `es` **required** rather than optional.
         *
         * For the two heads — `regnet`, `schneit` — that are one word long, where the optional
         * form would be a bare-keyword template and §6's single-keyword test rejects it: at six
         * and seven characters the tolerance is one, and "regnen", "regnete" and "schneien" are
         * all inside it. Requiring the question form costs the user nothing ("regnet es" is
         * what they say anyway) and keeps "es regnet doch schon wieder" out of the palette.
         *
         * The second template is what a required `es` would otherwise lose: "regnets morgen"
         * has no `es` to require, so the **day** is required there instead. Either way the
         * template needs a second token and cannot be satisfied by the verb alone.
         */
        private fun anchoredDayForms(head: String, vararg params: Pair<String, Any>): List<TemplatePattern> =
            listOf(
                pattern("$head es (denn)? (noch)? ({day:enum})? (noch)?", *params),
                pattern("$head (denn)? (noch)? {day:enum} (noch)?", *params),
            )

        /**
         * The other word order: the day *inside* the question rather than after it.
         *
         * "Wird es morgen regnen" and "regnet es morgen" are the same question, and German puts
         * the day in a different place in each — before the verb that carries the meaning when
         * that verb is at the end, after it when it is not. [dayForms] covers the second; this
         * covers the first, and a Sock that shipped only one of them would be a Sock that hears
         * half of what is said to it.
         */
        private fun dayInfix(
            prefix: String,
            suffix: String,
            vararg params: Pair<String, Any>,
        ): List<TemplatePattern> = listOf(pattern("$prefix $DAY_TAIL $suffix", *params))

        /**
         * Everything between a weather question's verb and its day.
         *
         * `(es)?` and `(denn)?` are spelled out although both are on `Fillers.DE`: the matcher
         * skips filler in front of a *keyword* and never in front of a slot
         * (`socks.specs/README.md` §6), and the thing after them here is the day slot. Drop
         * them and "regnet es denn morgen" matches nothing at all.
         *
         * `(noch)?` appears on both sides of the day because "heute noch" and "noch heute" are
         * both ordinary German, and because `noch` is not filler — it is a content word meaning
         * *the rest of*, which is exactly the distinction §4 turns into a different answer.
         *
         * `(draußen)?` is here rather than on each head because it attaches to every one of
         * them — "wie warm ist es draußen", "scheint die sonne draußen" — and it is a content
         * word the matcher will not skip. Both ASCII spellings are listed beside it for the
         * same reason the Clock lists `maerz`: the recogniser writes the umlaut most of the
         * time and not all of it.
         */
        private const val DAY_TAIL = "(es)? (draußen|draussen|drausen)? (denn)? (noch)? ({day:enum})? (noch)?"

        /** The `Example` param map for a temperature question, so the tables read as tables. */
        private fun temperature(day: WeatherDay, bound: String): Map<String, Any> =
            mapOf(WeatherDay.PARAM to day.spoken, "bound" to bound)

        /** The same, for a rain question. */
        private fun rain(day: WeatherDay, kind: String): Map<String, Any> =
            mapOf(WeatherDay.PARAM to day.spoken, "kind" to kind)
    }
}
