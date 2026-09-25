package io.dobby.android.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.dobby.socks.weather.DayForecast
import io.dobby.socks.weather.HourSlot
import io.dobby.socks.weather.WeatherDay
import io.dobby.socks.weather.WeatherReport
import io.dobby.socks.weather.WeatherSpeech
import io.dobby.socks.weather.WeatherState
import io.dobby.socks.weather.degrees
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime

/**
 * The weather, on the wall (`weather.specs.md` §8).
 *
 * Three cards in one composable, because they are three states of one thing and a panel that
 * showed a different *component* for each would move everything below it around:
 *
 * 1. **Un-set-up.** One line and a Setup button. This is the only thing in the whole panel that
 *    asks the user for something, and it asks once — the forecast has to be *somewhere*, and
 *    there is no defensible default (`weather.specs.md` §6).
 * 2. **Set up, nothing fetched.** The location, and a spinner.
 * 3. **A forecast.** Which is the card this Sock exists for, below.
 *
 * Drawn **whenever the Sock is set up**, unlike the two music cards and like [MemoCard]: the
 * weather is not something the panel is *doing*, it is something that is true, and a forecast
 * that appears only while you are asking about it is a forecast you have to ask about. A panel
 * nobody has pressed Setup on draws the first state and nothing else.
 *
 * **Three days across the top, and the rest of the selected one underneath.** Today opens with
 * what is outside right now, because that is the thing somebody walking past is checking against
 * the window; tomorrow and the day after open with the day's own summary, because there is no
 * "now" in them. The hour strip below is the same data the voice reads out of — "heute wird es
 * noch bis zu 21 Grad" is the warmest column of it — so the screen and the answer can never
 * disagree.
 *
 * @param onSetUp the Setup button and the location chip: the Sock's own locate, by finger. The
 *   caller grants `ACCESS_COARSE_LOCATION` first — see `MainActivity`.
 * @param onRefresh a tap on the freshness line. Defaulted to nothing so a preview and the
 *   off-device build can draw the card without a controller behind it, exactly as the clock
 *   card's X is.
 */
@Composable
fun WeatherCard(
    state: WeatherState,
    now: Instant,
    modifier: Modifier = Modifier,
    onSetUp: () -> Unit = {},
    onRefresh: () -> Unit = {},
) {
    if (!state.isSetUp) {
        SetupRow(locating = state.locating, onSetUp = onSetUp)
        return
    }
    // Survives recomposition and rotation, and deliberately not the trip in and out of the
    // settings screen: somebody who looked at Thursday wants today back when they walk past
    // again, because today is what a glance at a wall panel is asking about.
    var selected by remember { mutableStateOf(WeatherDay.HEUTE) }
    val report = state.report

    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (report == null) {
            Waiting(state)
        } else {
            Headline(report, selected, LocalDateTime.ofInstant(now, report.zone))
            DayPicker(report, selected) { selected = it }
            HourStrip(report, selected, LocalDateTime.ofInstant(now, report.zone))
        }
        Footer(
            state = state,
            age = report?.let { Duration.between(it.fetchedAt, now) },
            onSetUp = onSetUp,
            onRefresh = onRefresh,
        )
    }
}

/**
 * The first screen, and the only place in Dobby that asks for a permission after the microphone.
 *
 * A button and one sentence saying what it is for. No map, no search field, no list of cities:
 * the panel is on a wall in one flat, the phone already knows which flat, and a place picker
 * would be a keyboard on a device that does not have one.
 */
@Composable
private fun SetupRow(locating: Boolean, onSetUp: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "🌡",
            style = MaterialTheme.typography.headlineSmall,
        )
        Column(Modifier.weight(1f)) {
            Text(
                "Wetter",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                "Einmal den Standort festlegen, dann weiß ich, wie das Wetter hier wird.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Button(onClick = onSetUp, enabled = !locating) {
            if (locating) {
                CircularProgressIndicator(
                    Modifier.size(SPINNER_SIZE),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp,
                )
            } else {
                Text("Standort einrichten")
            }
        }
    }
}

/** Set up, nothing fetched yet — the few seconds after Setup, and after every cold start. */
@Composable
private fun Waiting(state: WeatherState) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(
            Modifier.size(SPINNER_SIZE),
            color = MaterialTheme.colorScheme.primary,
            strokeWidth = 2.dp,
        )
        Text(
            state.error ?: "Wetter wird geholt…",
            style = MaterialTheme.typography.bodyMedium,
            color = if (state.error == null) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.error
            },
        )
    }
}

/**
 * The big line: symbol, temperature, and what the sky is doing.
 *
 * **Today shows the thermometer; the other two show the day's high.** They are different
 * numbers answering different questions, and printing tomorrow's maximum in the same slot as
 * today's current reading would be the card quietly changing what it means. The second line
 * says which it is.
 */
@Composable
private fun Headline(report: WeatherReport, day: WeatherDay, now: LocalDateTime) {
    val forecast = report.day(day)
    val symbol = if (day.isToday) report.current.condition.symbol else forecast?.condition?.symbol
    val temperature = if (day.isToday) {
        report.current.temperature
    } else {
        forecast?.maxTemperature
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(symbol.orEmpty(), style = MaterialTheme.typography.displaySmall)
        Column(Modifier.weight(1f)) {
            Text(
                temperature?.let { WeatherSpeech.panelDegrees(it) } ?: "–",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Light,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                secondLine(report, day, forecast, now),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Everything the big number leaves out, in one line of German.
 *
 * The panel's own UI is German whatever voice is selected (`socks.specs/README.md` §2a), and
 * the wording deliberately echoes what the Sock says out loud — "gefühlt 12°" here and "gefühlt
 * 12 Grad" spoken, from the same rounded number, so a card and an answer read as one panel.
 */
private fun secondLine(
    report: WeatherReport,
    day: WeatherDay,
    forecast: DayForecast?,
    now: LocalDateTime,
): String {
    if (forecast == null) return "Keine Vorhersage für diesen Tag."
    val parts = mutableListOf<String>()
    if (day.isToday) {
        parts += report.current.condition.de
        val felt = report.current.apparentTemperature
        if (kotlin.math.abs(felt - report.current.temperature) >= 1.0) {
            parts += "gefühlt ${WeatherSpeech.panelDegrees(felt)}"
        }
        val peak = forecast.hoursFrom(now).maxByOrNull { it.temperature }
        if (peak != null && peak.temperature > report.current.temperature + 1.0) {
            parts += "noch bis ${WeatherSpeech.panelDegrees(peak.temperature)}"
        }
    } else {
        parts += forecast.condition.de
        parts += "${WeatherSpeech.panelDegrees(forecast.minTemperature)} bis " +
            WeatherSpeech.panelDegrees(forecast.maxTemperature)
    }
    forecast.precipitationProbability
        ?.takeIf { it >= WeatherSpeech.RAIN_WORTH_MENTIONING }
        ?.let { parts += "Regen $it %" }
    return parts.joinToString(" · ")
}

/**
 * Heute · Morgen · Übermorgen.
 *
 * Every day, including the one already selected, for the reason the radio card's station picker
 * shows every station: a row that disappears when chosen makes the others move, and a panel on
 * a wall is operated from muscle memory at arm's length. Days the forecast does not reach are
 * drawn disabled rather than hidden, for the same reason.
 */
@Composable
private fun DayPicker(report: WeatherReport, selected: WeatherDay, onSelect: (WeatherDay) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (day in PICKABLE) {
            val forecast = report.day(day)
            val active = day == selected
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = if (active) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                modifier = Modifier.clickable(enabled = forecast != null) { onSelect(day) },
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(forecast?.condition?.symbol.orEmpty(), style = MaterialTheme.typography.bodySmall)
                    Text(
                        label(day),
                        style = MaterialTheme.typography.labelLarge,
                        color = when {
                            forecast == null -> MaterialTheme.colorScheme.onSurfaceVariant
                            active -> MaterialTheme.colorScheme.onPrimaryContainer
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                    )
                    forecast?.let {
                        Text(
                            WeatherSpeech.panelDegrees(it.maxTemperature),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = if (active) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * How the selected day goes on: one column per hour, temperature over a rain bar.
 *
 * For **today this starts at the current hour**, which is the whole point of it — the half of
 * the day that has already happened is not a forecast, and a strip that opened at midnight
 * would put six columns of last night in front of the thing somebody is looking for. For the
 * other two days it is the whole day, because all of it is still ahead.
 *
 * The bar is precipitation probability, drawn as height rather than printed as a number: on a
 * strip of twelve columns the *shape* is the information — a wet afternoon is a hump, and you
 * can see it from across the room, which twelve percentages cannot be.
 */
@Composable
private fun HourStrip(report: WeatherReport, day: WeatherDay, now: LocalDateTime) {
    val forecast = report.day(day) ?: return
    val hours = if (day.isToday) forecast.hoursFrom(now) else forecast.hours
    if (hours.isEmpty()) return
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // Every second hour: a wall panel is read at a glance and twenty-four columns on a
        // phone-width screen is a barcode.
        for (hour in hours.filterIndexed { index, _ -> index % EVERY_NTH_HOUR == 0 }) {
            HourColumn(hour)
        }
    }
}

@Composable
private fun HourColumn(hour: HourSlot) {
    Column(
        Modifier.width(HOUR_COLUMN_WIDTH),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            "${hour.temperature.degrees()}°",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(hour.condition.symbol, style = MaterialTheme.typography.labelSmall)
        RainBar(hour.precipitationProbability ?: 0)
        Text(
            "${hour.time.hour}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A probability as a column of ink. Zero draws the track alone, so the row keeps its rhythm. */
@Composable
private fun RainBar(probability: Int) {
    Column(
        Modifier
            .height(RAIN_BAR_HEIGHT)
            .width(RAIN_BAR_WIDTH)
            .clip(RoundedCornerShape(2.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        verticalArrangement = Arrangement.Bottom,
    ) {
        val filled = RAIN_BAR_HEIGHT * (probability.coerceIn(0, PERCENT) / PERCENT.toFloat())
        Spacer(
            Modifier
                .height(filled)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}

/**
 * The location chip, and the freshness line beside it — **the two smallest controls on the card
 * and the only way to move the panel.**
 *
 * This is the answer to "how does somebody update the location without a settings screen": the
 * coordinates the forecast is for are drawn where a caption belongs, and the caption is a
 * button. It reads as information until you want it to be a control, which is exactly the right
 * weight for something done once a year — a "Standort ändern" row in settings would be one more
 * row to read past every time somebody goes looking for the voice, and a long-press would be a
 * gesture nobody discovers.
 *
 * The freshness line is the other tap target, and it is the honest half of a cached card: "vor 4
 * Minuten" most of the time, and the last error in red when the fetch failed — tapping it tries
 * again rather than making somebody wait out the ten-minute tick.
 */
@Composable
private fun Footer(
    state: WeatherState,
    age: Duration?,
    onSetUp: () -> Unit,
    onRefresh: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(enabled = !state.locating, onClick = onSetUp)
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (state.locating) {
                CircularProgressIndicator(
                    Modifier.size(CHIP_ICON_SIZE),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(
                    Icons.Filled.MyLocation,
                    contentDescription = "Standort aktualisieren",
                    modifier = Modifier.size(CHIP_ICON_SIZE),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                if (state.locating) "Standort wird bestimmt…" else state.place?.display.orEmpty(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.weight(1f))
        Text(
            freshness(state, age),
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(enabled = !state.refreshing, onClick = onRefresh)
                .padding(horizontal = 6.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = if (state.error != null) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
        )
    }
}

private fun freshness(state: WeatherState, age: Duration?): String {
    if (state.refreshing) return "wird geholt…"
    val error = state.error
    return when {
        // Both, when there is both: the error is why the card has stopped moving, and the age
        // is how much that costs — a caveat, not a blank.
        error != null && age != null -> "$error · Stand ${WeatherSpeech.panelAge(age)}"
        error != null -> error
        age != null -> WeatherSpeech.panelAge(age)
        else -> ""
    }
}

private fun label(day: WeatherDay): String = when (day) {
    WeatherDay.HEUTE -> "Heute"
    WeatherDay.MORGEN -> "Morgen"
    else -> "Übermorgen"
}

/**
 * The three the picker offers.
 *
 * [WeatherDay] has a fourth entry — the umlaut-less spelling of the third day — and it exists
 * for the speech recogniser, not for a button. Enumerating the enum here would draw
 * "Übermorgen" twice.
 */
private val PICKABLE = listOf(WeatherDay.HEUTE, WeatherDay.MORGEN, WeatherDay.UEBERMORGEN)

private const val EVERY_NTH_HOUR = 2

private const val PERCENT = 100

private val SPINNER_SIZE = 18.dp

private val CHIP_ICON_SIZE = 14.dp

private val HOUR_COLUMN_WIDTH = 34.dp

private val RAIN_BAR_HEIGHT = 20.dp

private val RAIN_BAR_WIDTH = 6.dp
