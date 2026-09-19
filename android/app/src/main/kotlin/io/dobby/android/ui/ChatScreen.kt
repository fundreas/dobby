package io.dobby.android.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.dobby.android.DobbyUiState
import io.dobby.android.Phase
import io.dobby.android.chat.ChatMessage
import io.dobby.android.chat.Voice
import io.dobby.socks.clock.ClockState

/**
 * What Dobby heard and what Dobby answered.
 *
 * The panel's answer is spoken, and speech leaves nothing behind: when the wrong Sock takes an
 * utterance — the failure `dobby-plan.md` §9 calls invisible in review and obvious in use —
 * there is nothing to look at afterwards. This view is that record. Hence the trace line under
 * each answer: it is the terminal harness's `/trace` output, kept for the same reason.
 */
@Composable
fun ChatScreen(
    state: DobbyUiState,
    clock: ClockState,
    onListen: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Header(state, onSettings)
        // The Clock Sock owns the panel's clock, so the panel asks it rather than the system
        // (`clock.specs.md` §7). It is also where a running timer becomes visible.
        ClockCard(clock)
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        Conversation(state.messages, Modifier.weight(1f))
        Composer(state, onListen)
    }
}

@Composable
private fun Header(state: DobbyUiState, onSettings: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Dobby",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.weight(1f))
            StatusPill(state)
            Spacer(Modifier.size(4.dp))
            IconButton(onClick = onSettings) {
                Icon(Icons.Filled.Settings, contentDescription = "Einstellungen")
            }
        }
        Text(
            state.detail.ifEmpty { state.summary },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatusPill(state: DobbyUiState) {
    val label = when (state.phase) {
        Phase.PREPARING -> "startet"
        Phase.READY -> "bereit"
        Phase.WAITING -> "wartet"
        Phase.LISTENING -> "hört zu"
        Phase.THINKING -> "denkt nach"
        Phase.SPEAKING -> "spricht"
        Phase.UNAVAILABLE -> "nicht verfügbar"
    }
    val tint = when (state.phase) {
        Phase.LISTENING, Phase.WAITING -> MaterialTheme.colorScheme.primary
        Phase.UNAVAILABLE -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val color by animateColorAsState(tint, label = "status")

    Row(verticalAlignment = Alignment.CenterVertically) {
        if (state.phase == Phase.PREPARING || state.phase == Phase.THINKING) {
            CircularProgressIndicator(
                Modifier.size(12.dp),
                color = color,
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.size(8.dp))
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

@Composable
private fun Conversation(messages: List<ChatMessage>, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()

    // Follow the conversation the way a person would: the newest line is the one on screen.
    LaunchedEffect(messages.size, messages.lastOrNull()?.text) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    if (messages.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "Sag etwas, oder tippe es.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        state = listState,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(messages, key = { it.id }) { message -> Bubble(message) }
    }
}

@Composable
private fun Bubble(message: ChatMessage) {
    when (message.voice) {
        Voice.SYSTEM -> Text(
            message.text,
            Modifier.fillMaxWidth().padding(vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Voice.USER -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp),
                modifier = Modifier.widthIn(max = 300.dp),
            ) {
                Text(
                    message.text.ifBlank { "…" },
                    Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    // A partial transcript is a guess; it should not look as settled as the
                    // final line it is about to be replaced by.
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                        .copy(alpha = if (message.live) LIVE_ALPHA else 1f),
                )
            }
        }

        Voice.DOBBY -> Column(Modifier.fillMaxWidth()) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp),
                modifier = Modifier.widthIn(max = 320.dp),
            ) {
                Text(
                    message.text,
                    Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (message.failed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
            message.detail?.let { detail ->
                Text(
                    detail,
                    Modifier.padding(start = 6.dp, top = 3.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Composer(state: DobbyUiState, onListen: () -> Unit) {
    val busy = state.phase == Phase.LISTENING || state.phase == Phase.THINKING
    val armed = state.phase == Phase.LISTENING

    Row(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        // One control, sized to be hit from across a room rather than from a thumb's reach —
        // the panel is on a wall, and this is the only thing on it you touch.
        FilledIconButton(
            onClick = onListen,
            modifier = Modifier.size(72.dp),
            enabled = state.canListen && !busy,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = if (armed) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                contentColor = if (armed) Color.Black else MaterialTheme.colorScheme.onSurface,
            ),
        ) {
            Icon(
                Icons.Filled.Mic,
                contentDescription = "Zuhören",
                modifier = Modifier.size(32.dp),
            )
        }
    }
}

private const val LIVE_ALPHA = 0.55f
