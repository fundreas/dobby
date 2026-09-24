package io.dobby.android.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.dobby.socks.clock.ClockState
import io.dobby.socks.clock.SpokenTime
import io.dobby.socks.clock.TimerState
import java.time.format.DateTimeFormatter

private val HOURS_MINUTES = DateTimeFormatter.ofPattern("HH:mm")

/**
 * The Clock Sock's half of the wall panel (`clock.specs.md` §9).
 *
 * Seconds are deliberately absent from the clock: this panel is on all day, and a digit that
 * redraws once a second is an OLED burning itself in for no information. The countdown is the
 * exception — it is on screen for minutes, not for weeks, and there a second matters.
 */
/**
 * @param onCancelTimer the X on a timer row: [io.dobby.socks.clock.ClockSock.cancelFromPanel],
 *   by id. Defaulted to nothing so a preview and the off-device build can draw the card without
 *   a controller behind it, exactly as the radio card's stop is.
 */
@Composable
fun ClockCard(
    state: ClockState,
    modifier: Modifier = Modifier,
    onCancelTimer: (Long) -> Unit = {},
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                HOURS_MINUTES.format(state.now),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Light,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                SpokenTime.date(state.now.toLocalDate()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.timers.isNotEmpty()) {
            Column(horizontalAlignment = Alignment.End) {
                // Soonest first, and only the ones that fit: a panel that lists eight timers in
                // 6-point type is a list nobody reads across a kitchen. The rest are a count.
                for (timer in state.timers.take(VISIBLE_TIMERS)) {
                    Timer(timer) { onCancelTimer(timer.id) }
                }
                val hidden = state.timers.size - VISIBLE_TIMERS
                if (hidden > 0) {
                    Text(
                        "+$hidden weitere",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun Timer(timer: TimerState, onCancel: () -> Unit) {
    // A ringing timer has to be visible on a muted panel, so it flashes rather than only
    // sounding. A counting one stays still: it is information, not an alarm.
    val flash by rememberInfiniteTransition(label = "chime").animateFloat(
        initialValue = 1f,
        targetValue = if (timer.isRinging) 0.2f else 1f,
        animationSpec = infiniteRepeatable(tween(FLASH_MILLIS), RepeatMode.Reverse),
        label = "flash",
    )

    Row(verticalAlignment = Alignment.CenterVertically) {
        // The readout flashes; the button beside it does not. A control that fades to a fifth
        // of its opacity twice a second is one somebody stabs at, and the whole reason the
        // chime flashes is that it is the row you most want to be able to switch off.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.alpha(flash),
        ) {
            // The label earns its place only once there is something to tell apart: a single
            // default timer is just a countdown, exactly as it was before multi-timer.
            if (timer.numbered || timer.name != null) {
                Text(
                    timer.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                if (timer.isRinging) "${timer.label}!" else SpokenTime.countdown(timer.remainingMs),
                style = MaterialTheme.typography.headlineSmall,
                color = if (timer.isRinging) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onBackground
                },
            )
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { timer.progress },
                    modifier = Modifier.size(28.dp),
                    strokeWidth = 3.dp,
                    color = if (timer.isRinging) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
        }
        // One X per row, and per row is the point: "brich Timer 2 ab" is a sentence somebody
        // has to compose while three countdowns are on the wall in front of them, and pointing
        // at the one they mean skips both the naming and the tie-break the spoken command needs.
        IconButton(onClick = onCancel, modifier = Modifier.size(CANCEL_SIZE)) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "${timer.label} abbrechen",
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val CANCEL_SIZE = 36.dp

private const val FLASH_MILLIS = 450

/** How many timers the card draws before it starts counting the rest. */
private const val VISIBLE_TIMERS = 3
