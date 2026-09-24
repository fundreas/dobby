package io.dobby.android.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.dobby.socks.spotify.PlayerSnapshot
import kotlinx.coroutines.delay

/**
 * What is playing, on the wall (`spotify.specs.md` §7).
 *
 * The second user of the path `ClockCard` opened — Sock state, straight through the controller,
 * into a composable — and it needed nothing new on the way.
 *
 * Black background and no animation: the panel's screen is meant to be dark
 * (`dobby-plan.md` §7.2). The one moving part is the progress bar, and even that redraws once a
 * second rather than once a frame.
 *
 * The transport row is here for the same reason the radio card's X is: the room is playing
 * music, which is the worst possible moment to make somebody say "Dobby" twice over it. Every
 * button runs the identical player call the spoken command runs, so a tap and an utterance are
 * one event.
 *
 * @param onPrevious `spotify.skip_previous`, from a finger.
 * @param onPlayPause `spotify.pause` or `spotify.resume`, whichever the snapshot says is next.
 * @param onNext `spotify.skip_next`.
 * @param onClose pause and let go of the App Remote, which is what takes this card off screen.
 *   Defaulted to nothing, like the radio card's, so a preview and the off-device build can draw
 *   the card without a controller behind it.
 */
@Composable
fun NowPlayingCard(
    snapshot: PlayerSnapshot?,
    artwork: Bitmap?,
    modifier: Modifier = Modifier,
    onPrevious: () -> Unit = {},
    onPlayPause: () -> Unit = {},
    onNext: () -> Unit = {},
    onClose: () -> Unit = {},
) {
    if (snapshot == null) return
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Cover(artwork)
            Column(Modifier.weight(1f)) {
                Text(
                    snapshot.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    snapshot.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.size(6.dp))
                // Read here rather than inside the lambda below: the lambda is a plain function
                // the indicator calls while drawing, and `remember` has to happen in composition.
                val progress = progressOf(snapshot)
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            }
            // The X on the right-hand side, on the title row rather than down among the
            // transport buttons: closing the card is not a thing you do to the music, and a
            // finger reaching for "next" must not land on it.
            IconButton(onClick = onClose) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Musik beenden",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // Their own row, centred and full width: three targets at the size a wall panel is
        // pressed at do not fit beside a cover and two lines of text on a phone-width screen.
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Transport(Icons.Filled.SkipPrevious, "Vorheriger Titel", onPrevious)
            // The one that changes shape, because it is the one whose meaning depends on what
            // the player is doing — and the paused marker this replaces said the same thing
            // without being pressable.
            Transport(
                if (snapshot.isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                if (snapshot.isPaused) "Weiter" else "Pause",
                onPlayPause,
                size = PLAY_ICON_SIZE,
            )
            Transport(Icons.Filled.SkipNext, "Nächster Titel", onNext)
        }
    }
}

@Composable
private fun Transport(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    size: Dp = TRANSPORT_ICON_SIZE,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(TRANSPORT_BUTTON_SIZE)) {
        Icon(
            icon,
            contentDescription = label,
            modifier = Modifier.size(size),
            tint = MaterialTheme.colorScheme.onBackground,
        )
    }
}

@Composable
private fun Cover(artwork: Bitmap?) {
    val shape = RoundedCornerShape(4.dp)
    if (artwork == null) {
        Icon(
            Icons.Filled.MusicNote,
            contentDescription = null,
            modifier = Modifier.size(COVER_SIZE),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Image(
        bitmap = artwork.asImageBitmap(),
        contentDescription = null,
        modifier = Modifier.size(COVER_SIZE).clip(shape),
        contentScale = ContentScale.Crop,
    )
}

/**
 * Where in the track we are, interpolated rather than polled.
 *
 * The position in a `PlayerState` update is a **snapshot**, not a ticker: the App Remote pushes
 * a new state on a change, not once a second. So the bar advances from the last known position
 * plus elapsed wall time while the track is running, and the App Remote is never asked again —
 * the same trick `ClockSock.tick` uses to align a countdown to the second boundary.
 */
@Composable
private fun progressOf(snapshot: PlayerSnapshot): Float {
    // No early return above these two: a `remember` that is sometimes called and sometimes not
    // changes the shape of the composition, and a track whose duration arrives one update late
    // is exactly the case that would do it.
    var elapsed by remember(snapshot.uri, snapshot.positionMs, snapshot.isPaused) {
        mutableLongStateOf(0L)
    }
    LaunchedEffect(snapshot.uri, snapshot.positionMs, snapshot.isPaused) {
        if (snapshot.isPaused) return@LaunchedEffect
        while (true) {
            delay(TICK_MILLIS)
            elapsed += TICK_MILLIS
        }
    }
    if (snapshot.durationMs <= 0) return 0f
    return ((snapshot.positionMs + elapsed).toFloat() / snapshot.durationMs).coerceIn(0f, 1f)
}

private val COVER_SIZE = 44.dp

/** Sized to be hit from across a room rather than from a thumb's reach, like the microphone. */
private val TRANSPORT_BUTTON_SIZE = 56.dp

private val TRANSPORT_ICON_SIZE = 30.dp

/** Play/pause is the one that gets pressed most, so it is the one that is easiest to hit. */
private val PLAY_ICON_SIZE = 38.dp

private const val TICK_MILLIS = 1000L
