package io.dobby.android.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.dobby.socks.radio.RadioState
import io.dobby.socks.radio.Station
import io.dobby.socks.radio.Stations
import io.dobby.socks.radio.stationOrNull

/**
 * What is on the radio, on the wall (`radio.specs.md` §7).
 *
 * The third user of the path `ClockCard` opened, and it stayed the simplest for a while:
 * everything drawn here is already in `:socks:radio`, so the card takes a [RadioState] and a
 * pair of callbacks and nothing else. What it has since grown is the station's own logo — from
 * [StationLogos], bundled in the APK because the table it indexes is a constant — and, behind
 * a tap on that logo, the list of the other four.
 *
 * Drawn only while the Sock holds playback, so a panel nobody has asked for radio keeps the
 * clock at the top of the screen where it belongs. `Error` is drawn too, briefly: a stream that
 * has just been given up on is exactly the moment somebody looks at the panel to find out why
 * the kitchen went quiet.
 *
 * The X and the picker are the two things on this card that are not readouts, and they are
 * here for the same reason: the room is playing music, which is the worst possible moment to
 * make somebody say "Dobby" twice over it. Both run the identical Sock call the spoken command
 * runs, so a tap and an utterance are one event.
 *
 * @param onStop the Sock's stop. Defaulted to nothing so a preview and the off-device build
 *   can draw the card without one.
 * @param onPickStation tune to a station chosen from the dropdown — the Sock's own `tuneTo`,
 *   the same one "spiele radio ö1" reaches.
 */
@Composable
fun RadioCard(
    state: RadioState,
    modifier: Modifier = Modifier,
    onStop: () -> Unit = {},
    onPickStation: (Station) -> Unit = {},
) {
    if (state is RadioState.Idle) return
    var picking by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // The logo and the two lines are one target, and the X is deliberately outside it:
            // "switch station" and "turn it off" are opposite intentions, and a tap that could
            // be either is one that will be wrong half the time.
            Row(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { picking = true }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StationLogo(state.stationOrNull, LOGO_SIZE)
                Column(Modifier.weight(1f)) {
                    Text(
                        // The one thing that is always right, whatever the stream says about
                        // itself.
                        station(state),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Light,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // Only when there is one. Never a placeholder — §7 is explicit, and a line
                    // reading "Unbekannter Titel" across a kitchen is worse than no line at all.
                    second(state)?.let { line ->
                        Text(
                            line,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            // Buffering covers both the first connect and every rung of the reconnect ladder,
            // which is the whole of what §7's "buffering / reconnecting indicator" has to say.
            if (state is RadioState.Buffering) {
                CircularProgressIndicator(
                    modifier = Modifier.size(INDICATOR_SIZE),
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 2.dp,
                )
            }
            // Beside the spinner rather than in place of it: a stream that is still connecting
            // is exactly one a person may want to give up on, and a button that appears only
            // once the sound does would be missing whenever it is most wanted.
            IconButton(onClick = onStop) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Radio ausschalten",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        StationPicker(
            expanded = picking,
            current = state.stationOrNull,
            onDismiss = { picking = false },
            onPick = {
                picking = false
                onPickStation(it)
            },
        )
    }
}

/**
 * The whole station table, with each station's own logo beside its name.
 *
 * **Every station, including the one already playing.** A list that hides the current row makes
 * the four remaining ones move depending on what is on, and a panel on a wall is operated from
 * muscle memory at arm's length; the check mark says which one it is instead. Tapping it is a
 * re-tune, which is the cheapest way to restart a stream that has gone strange and is a
 * reasonable thing to want.
 *
 * `Stations.ALL` is read straight from the Sock's module — the same list the settings screen's
 * default-station dropdown enumerates, and the same order, because it is `radio.specs.md` §3.5's
 * "a `List` rather than a `Map` because the dropdown wants a stable order" being taken up on.
 */
@Composable
private fun StationPicker(
    expanded: Boolean,
    current: Station?,
    onDismiss: () -> Unit,
    onPick: (Station) -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.padding(horizontal = 20.dp),
    ) {
        for (station in Stations.ALL) {
            DropdownMenuItem(
                text = {
                    Text(
                        station.displayName,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                leadingIcon = { StationLogo(station, MENU_LOGO_SIZE) },
                trailingIcon = {
                    if (station.id == current?.id) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = "Läuft gerade",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
                onClick = { onPick(station) },
            )
        }
    }
}

/**
 * A station's logo, or the generic glyph for one that has none.
 *
 * Square, clipped to the same rounded corner the tap target uses, and `ContentScale.Fit` rather
 * than `Crop`: these are wordmarks — "radio FM4" is *text* — and a crop that shaves a letter
 * off is worse than a little empty space beside a round one.
 */
@Composable
private fun StationLogo(station: Station?, size: Dp) {
    val logo = station?.let { StationLogos.of(it) }
    if (logo == null) {
        Icon(
            Icons.Filled.Radio,
            contentDescription = null,
            modifier = Modifier.size(size),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Image(
        painter = painterResource(logo),
        // The station name is right next to it in both places this is drawn, so the logo is
        // decoration as far as a screen reader is concerned.
        contentDescription = null,
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(6.dp)),
        contentScale = ContentScale.Fit,
    )
}

private fun station(state: RadioState): String = when (state) {
    is RadioState.Idle -> ""
    is RadioState.Buffering -> state.station.displayName
    is RadioState.Playing -> state.station.displayName
    is RadioState.Error -> state.station.displayName
}

private fun second(state: RadioState): String? = when (state) {
    is RadioState.Playing -> state.nowPlaying
    is RadioState.Error -> state.reason
    is RadioState.Buffering, is RadioState.Idle -> null
}

private val LOGO_SIZE = 40.dp

private val MENU_LOGO_SIZE = 28.dp

private val INDICATOR_SIZE = 18.dp
