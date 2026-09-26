package io.dobby.android.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.dobby.socks.departures.DepartureBoard
import io.dobby.socks.departures.DeparturesSpeech
import io.dobby.socks.departures.DeparturesState
import io.dobby.socks.departures.DirectionBoard
import io.dobby.socks.departures.LineKind
import io.dobby.socks.departures.Station
import java.time.Duration
import java.time.Instant

/**
 * The departure board, on the wall (`departures.specs.md` §5).
 *
 * Two states of one thing, and they are not the Weather card's three: there is no "set up and
 * waiting" worth its own layout, because a board with no data is a board with no rows and the
 * footer already says why.
 *
 * 1. **No station configured.** One line that says where to fix it, and tapping it goes there.
 *    Unlike the weather's Setup button this cannot be a button: setting this up means finding
 *    a station, which is a keyboard's job and not a card's.
 * 2. **A board**, which is what this Sock exists for: one page per station, rows sorted by
 *    countdown, and the attribution that the licence requires.
 *
 * **One station at a time, swiped.** Stacking every station cost the card its whole height
 * budget — three stops at four rows each pushed the conversation off a panel that has one
 * screen for all of it. Sideways is the axis nothing else on this screen uses, so a page per
 * station costs no vertical space at all, and the station whose stop is outside the door is
 * page one because that is the order it was configured in.
 *
 * **Rows are departures, not lines.** The same line twice in six minutes is two rows, which is
 * what a real departure board looks like and what somebody deciding whether to run needs to
 * see. Four per station: it is the next one and the one after it that decide whether to run,
 * and the fifth is already a different journey.
 *
 * **Stale is dimmed, never blanked.** A refresh in flight or a failed one dims the rows and
 * says so in the footer; it does not empty the card. A board that disappears every time a
 * kitchen's Wi-Fi hiccups is a card that looks broken twice a day, and four-minute-old
 * departures are still departures.
 *
 * @param onOpenSettings where the station list is typed in — the whole of this Sock's setup.
 * @param onRefresh a tap on the freshness line. It may do nothing: the 30-second floor has no
 *   bypass, not even for a finger (§6.2).
 */
@Composable
fun DeparturesCard(
    state: DeparturesState,
    now: Instant,
    modifier: Modifier = Modifier,
    onOpenSettings: () -> Unit = {},
    onRefresh: () -> Unit = {},
) {
    if (!state.isSetUp) {
        SetupHint(onOpenSettings)
        return
    }
    val board = state.board
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        StationPages(
            stations = state.stations,
            board = board,
            dimmed = state.refreshing || state.error != null,
        )
        Footer(
            state = state,
            age = board?.let { Duration.between(it.fetchedAt, now) },
            onRefresh = onRefresh,
        )
    }
}

/**
 * Nothing is configured yet.
 *
 * Drawn rather than hidden, because a Sock nobody knows about is a Sock nobody sets up — and
 * this is the one line of the panel that can say "there is a departure board in here". It
 * disappears for good the moment a station exists.
 */
@Composable
private fun SetupHint(onOpenSettings: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenSettings)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("🚏", style = MaterialTheme.typography.titleMedium)
        Column(Modifier.weight(1f)) {
            Text(
                "Abfahrten",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                "In den Einstellungen eine Haltestelle eintragen.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The stations, one per page.
 *
 * **Every page is as tall as the tallest**, which is the whole reason [StationBlock] takes a
 * `padTo` at all. A pager is exactly as high as the page it is showing, so
 * swiping from a stop with four departures to one with none would shrink the card by three
 * rows and shunt the conversation underneath it up the screen — the panel jumping about under
 * a finger that was only reading. Blank rows are cheaper than that.
 *
 * One station is drawn without a pager and without dots. A pager over one page swipes
 * nowhere, and a single dot is an indicator that indicates nothing.
 */
@Composable
private fun StationPages(stations: List<Station>, board: DepartureBoard?, dimmed: Boolean) {
    if (stations.size == 1) {
        StationBlock(stations.first(), board, dimmed, padTo = 0)
        return
    }
    val pager = rememberPagerState(pageCount = { stations.size })
    val padTo = stations.maxOf { rows(board, it).size }

    HorizontalPager(
        state = pager,
        // Top, not centre: the station's name is the thing that has to line up between two
        // pages, and it is the first row of both.
        verticalAlignment = Alignment.Top,
    ) { page ->
        StationBlock(stations[page], board, dimmed, padTo = padTo)
    }
    Dots(count = stations.size, current = pager.currentPage)
}

/** Which of the stations is showing, and how many there are. */
@Composable
private fun Dots(count: Int, current: Int) {
    Row(
        Modifier.fillMaxWidth().padding(top = 2.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { index ->
            Box(
                Modifier
                    .padding(horizontal = 3.dp)
                    .size(if (index == current) DOT_ON else DOT_OFF)
                    .clip(CircleShape)
                    .background(
                        if (index == current) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = DOT_ALPHA)
                        },
                    ),
            )
        }
    }
}

/**
 * The next few departures from one station, soonest first.
 *
 * Flattened out of the direction boards rather than read off them: a [DirectionBoard] is one
 * line going one way with several countdowns behind it, and what somebody standing in a
 * kitchen wants is the next four *vehicles*, whichever lines they happen to belong to.
 */
private fun rows(board: DepartureBoard?, station: Station): List<Pair<DirectionBoard, Int>> =
    board?.at(station).orEmpty()
        .flatMap { direction -> direction.countdowns.map { direction to it } }
        .sortedBy { (_, countdown) -> countdown }
        .take(MAX_ROWS)

/**
 * One configured station: its name, and the next few things leaving it.
 *
 * @param padTo how many departure rows this page must occupy whether it has them or not, so
 *   that swiping between stations does not resize the card. Zero for the station that is on
 *   its own and has nothing to line up with.
 */
@Composable
private fun StationBlock(
    station: Station,
    board: DepartureBoard?,
    dimmed: Boolean,
    padTo: Int,
) {
    val rows = rows(board, station)

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            station.display,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (rows.isEmpty()) {
            Text(
                if (board == null) "Wird geholt…" else "Keine Abfahrten gemeldet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        for ((direction, countdown) in rows) {
            DepartureRow(direction, countdown, dimmed)
        }
        // The message above already took one row's worth of height, so a station with nothing
        // to report pads one row less than an empty one would.
        val drawn = if (rows.isEmpty()) 1 else rows.size
        repeat((padTo - drawn).coerceAtLeast(0)) { EmptyRow() }
    }
}

/**
 * A departure row with nothing in it.
 *
 * Built out of the same two texts as [DepartureRow] rather than out of a `Spacer` of some
 * measured height: the row is as tall as its type and its badge padding make it, and a
 * hard-coded dp would be right until somebody changed the type scale.
 */
@Composable
private fun EmptyRow() {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            " ",
            Modifier.widthIn(min = BADGE_MIN_WIDTH).padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
        )
        Text(
            " ",
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
        )
    }
}

/** Line badge, destination, countdown — read left to right, in that order of importance. */
@Composable
private fun DepartureRow(direction: DirectionBoard, countdown: Int, dimmed: Boolean) {
    val alpha = if (dimmed) DIMMED else 1f
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LineBadge(direction, alpha)
        Text(
            direction.towards,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = alpha),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            DeparturesSpeech.panelCountdown(countdown),
            style = MaterialTheme.typography.bodyMedium,
            // „jetzt" is the one countdown that means something different in kind, and the
            // weight is how a board says so without a second column.
            fontWeight = if (countdown <= 0) FontWeight.SemiBold else FontWeight.Normal,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = alpha),
            textAlign = TextAlign.End,
        )
    }
}

/**
 * The line, in its own colour.
 *
 * The five U-Bahn lines in the city's own colours, tram red, bus blue, everything else grey —
 * at arm's length from across a room the colour is read before the text, and Vienna's line
 * colours are the thing every one of these passengers already knows by heart.
 */
@Composable
private fun LineBadge(direction: DirectionBoard, alpha: Float) {
    Text(
        direction.line,
        Modifier
            .widthIn(min = BADGE_MIN_WIDTH)
            .clip(RoundedCornerShape(4.dp))
            .background(badgeColor(direction).copy(alpha = alpha))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = Color.White.copy(alpha = alpha),
        textAlign = TextAlign.Center,
        maxLines = 1,
    )
}

private fun badgeColor(direction: DirectionBoard): Color = when (direction.kind) {
    LineKind.METRO -> METRO_COLORS[direction.line.uppercase()] ?: METRO_FALLBACK
    LineKind.TRAM -> TRAM
    LineKind.BUS -> BUS
    LineKind.TRAIN -> TRAIN
    LineKind.OTHER -> OTHER
}

/**
 * The freshness line, and the attribution that is not optional.
 *
 * `Datenquelle: Stadt Wien – data.wien.gv.at` is a **licence term** (CC BY 4.0), not a credit
 * somebody might like to see, so it is drawn unconditionally and in the same type as the rest
 * of the footer — not in an about screen, not behind a long-press, and not only when the data
 * arrived. Tapping the freshness half refreshes, within the limits of §6.
 */
@Composable
private fun Footer(state: DeparturesState, age: Duration?, onRefresh: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Datenquelle: Stadt Wien – data.wien.gv.at",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.weight(1f))
        Text(
            freshness(state, age),
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(enabled = !state.refreshing, onClick = onRefresh)
                .padding(horizontal = 6.dp, vertical = 2.dp),
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

private fun freshness(state: DeparturesState, age: Duration?): String {
    if (state.refreshing) return "wird geholt…"
    val error = state.error
    return when {
        // Both, when there is both: the error is why the board has stopped moving, and the age
        // is how much that costs — a caveat, not a blank.
        error != null && age != null -> "$error · Stand ${DeparturesSpeech.panelAge(age)}"
        error != null -> error
        age != null -> "Stand ${DeparturesSpeech.panelAge(age)}"
        else -> ""
    }
}

/** Vienna's own line colours. The U5 is in here for the day it opens. */
private val METRO_COLORS = mapOf(
    "U1" to Color(0xFFE20B14),
    "U2" to Color(0xFF7A3B93),
    "U3" to Color(0xFFF47B20),
    "U4" to Color(0xFF009640),
    "U5" to Color(0xFF17B5B0),
    "U6" to Color(0xFFA87E2B),
)

private val METRO_FALLBACK = Color(0xFF505050)

private val TRAM = Color(0xFFDA291C)

private val BUS = Color(0xFF0069B4)

private val TRAIN = Color(0xFF1B5E9E)

private val OTHER = Color(0xFF505050)

/**
 * Four per station.
 *
 * Six fitted while the stations were stacked and the card could be as tall as it liked. A
 * page is read at a glance from across a kitchen, and past the fourth row the question has
 * stopped being „laufe ich" and started being „welche Verbindung nehme ich" — which is a
 * question for a phone, not for a wall.
 */
private const val MAX_ROWS = 4

private const val DIMMED = 0.45f

private val DOT_ON = 7.dp

private val DOT_OFF = 5.dp

private const val DOT_ALPHA = 0.35f

private val BADGE_MIN_WIDTH = 34.dp
