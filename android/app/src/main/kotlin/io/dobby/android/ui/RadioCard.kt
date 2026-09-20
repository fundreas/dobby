package io.dobby.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.dobby.socks.radio.RadioState

/**
 * What is on the radio, on the wall (`radio.specs.md` §7).
 *
 * The third user of the path `ClockCard` opened, and the simplest: unlike Spotify there is no
 * artwork and so no second source to combine — everything drawn here is already in
 * `:socks:radio`, so the card takes a [RadioState] and nothing else.
 *
 * Drawn only while the Sock holds playback, so a panel nobody has asked for radio keeps the
 * clock at the top of the screen where it belongs. `Error` is drawn too, briefly: a stream that
 * has just been given up on is exactly the moment somebody looks at the panel to find out why
 * the kitchen went quiet.
 *
 * The X is the one thing on this card that is not a readout, and it is here for the same
 * reason the one under the microphone is: the room is playing music, which is the worst
 * possible moment to make somebody say "Dobby" twice over it. It runs the identical stop the
 * voice command runs, so the card closing and the stream ending are one event — including
 * from `Error`, where the X is how a card nobody can do anything about gets dismissed.
 *
 * @param onStop the Sock's stop. Defaulted to nothing so a preview and the off-device build
 *   can draw the card without one.
 */
@Composable
fun RadioCard(state: RadioState, modifier: Modifier = Modifier, onStop: () -> Unit = {}) {
    if (state is RadioState.Idle) return
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            Icons.Filled.Radio,
            contentDescription = null,
            modifier = Modifier.size(ICON_SIZE),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(Modifier.weight(1f)) {
            Text(
                // The one thing that is always right, whatever the stream says about itself.
                station(state),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Light,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Only when there is one. Never a placeholder — §7 is explicit, and a line reading
            // "Unbekannter Titel" across a kitchen is worse than no line at all.
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
        // Buffering covers both the first connect and every rung of the reconnect ladder, which
        // is the whole of what §7's "buffering / reconnecting indicator" has to say.
        if (state is RadioState.Buffering) {
            CircularProgressIndicator(
                modifier = Modifier.size(INDICATOR_SIZE),
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 2.dp,
            )
        }
        // Beside the spinner rather than in place of it: a stream that is still connecting is
        // exactly one a person may want to give up on, and a button that appears only once the
        // sound does would be missing whenever it is most wanted.
        IconButton(onClick = onStop) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Radio ausschalten",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
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

private val ICON_SIZE = 32.dp

private val INDICATOR_SIZE = 18.dp
