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
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
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
 */
@Composable
fun NowPlayingCard(snapshot: PlayerSnapshot?, artwork: Bitmap?, modifier: Modifier = Modifier) {
    if (snapshot == null) return
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
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
        if (snapshot.isPaused) {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = "pausiert",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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

private const val TICK_MILLIS = 1000L
