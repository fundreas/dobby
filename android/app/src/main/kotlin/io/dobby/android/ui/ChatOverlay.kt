package io.dobby.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
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

/**
 * What Dobby heard and what Dobby answered — raised over the panel for as long as it matters.
 *
 * The panel's answer is spoken, and speech leaves nothing behind: when the wrong Sock takes an
 * utterance — the failure `dobby-plan.md` §9 calls invisible in review and obvious in use —
 * there is nothing to look at afterwards. This view is that record. Hence the trace line under
 * each answer: it is the terminal harness's `/trace` output, kept for the same reason.
 *
 * A sheet rather than a pane in the column, because a turn is a moment and not a state. While
 * it is up the panel behind it is blurred rather than hidden ([MainScreen] does the blurring):
 * whoever asked for a timer can still see the timer they asked about, which is most of the
 * point of asking at a wall panel rather than into a speaker.
 *
 * Three ways out, and all of them leave the turn alone: the chevron, the panel around it, and
 * the back gesture. Cancelling a turn is the red button at the bottom and only that — closing
 * the record of something is not the same as stopping it.
 */
@Composable
fun ChatOverlay(
    visible: Boolean,
    state: DobbyUiState,
    onDismiss: () -> Unit,
    onListen: () -> Unit,
    onAbort: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(enabled = visible, onBack = onDismiss)

    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(180)),
        exit = fadeOut(tween(200)),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(SCRIM)
                .noRipple(onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            // The sheet rises a little faster than the scrim arrives, so it reads as coming
            // up off the panel rather than being cross-faded over it.
            Sheet(
                state = state,
                onDismiss = onDismiss,
                onListen = onListen,
                onAbort = onAbort,
                modifier = Modifier.animateEnterExit(
                    enter = scaleIn(tween(220), initialScale = 0.94f) + fadeIn(tween(220)),
                    exit = scaleOut(tween(160), targetScale = 0.96f) + fadeOut(tween(160)),
                ),
            )
        }
    }
}

/**
 * The sheet itself: nearly the whole screen, with a hand's width of panel left showing.
 *
 * Milky rather than opaque. On a pure-black theme an opaque sheet is simply a second screen
 * and the popover reads as a navigation, which is the one thing it is not — it is the same
 * screen with something happening on it.
 */
@Composable
private fun Sheet(
    state: DobbyUiState,
    onDismiss: () -> Unit,
    onListen: () -> Unit,
    onAbort: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 14.dp, vertical = 18.dp)
            // Taps inside the sheet are not taps on the scrim: without this, every tap on a
            // bubble would close the thing being read.
            .noRipple {},
        shape = RoundedCornerShape(32.dp),
        color = SHEET_FILL,
        border = BorderStroke(1.dp, SHEET_EDGE),
        shadowElevation = 24.dp,
    ) {
        Column(Modifier.fillMaxSize()) {
            SheetHeader(state, onDismiss)
            HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
            Conversation(state.messages, Modifier.weight(1f))
            SheetControl(state, onListen, onAbort)
        }
    }
}

@Composable
private fun SheetHeader(state: DobbyUiState, onDismiss: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "Gespräch",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            // The same line the header outside carries, because while the sheet is up that
            // header is behind blur and unreadable — and this is the line that says whether
            // the microphone is still open.
            state.detail.takeIf { it.isNotEmpty() }?.let { detail ->
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        StatusPill(state)
        Spacer(Modifier.size(4.dp))
        IconButton(onClick = onDismiss) {
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = "Gespräch schließen",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The microphone, a second time, because the dock is behind the sheet.
 *
 * Same rule as the dock's: while the microphone is open the only thing anybody wants from it
 * is the way out, so the button becomes that and is never greyed out.
 */
@Composable
private fun SheetControl(state: DobbyUiState, onListen: () -> Unit, onAbort: () -> Unit) {
    val listening = state.phase == Phase.LISTENING
    val busy = state.phase == Phase.THINKING

    Row(
        Modifier.fillMaxWidth().padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        FilledIconButton(
            onClick = if (listening) onAbort else onListen,
            modifier = Modifier.size(64.dp),
            enabled = listening || (state.canListen && !busy),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = if (listening) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
                contentColor = if (listening) Color.Black else MaterialTheme.colorScheme.onPrimary,
            ),
        ) {
            Icon(
                if (listening) Icons.Filled.Close else Icons.Filled.Mic,
                contentDescription = if (listening) "Zuhören abbrechen" else "Zuhören",
                modifier = Modifier.size(30.dp),
            )
        }
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
        contentPadding = PaddingValues(16.dp),
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

/** A tap target with nothing to show for itself — a scrim should not ripple. */
@Composable
private fun Modifier.noRipple(onClick: () -> Unit): Modifier {
    val interaction = remember { MutableInteractionSource() }
    return clickable(interactionSource = interaction, indication = null, onClick = onClick)
}

private const val LIVE_ALPHA = 0.55f

/** Half-transparent, so the blurred panel underneath is still legibly *there*. */
private val SCRIM = Color.Black.copy(alpha = 0.55f)
private val SHEET_FILL = Color(0xFF15181D).copy(alpha = 0.90f)
private val SHEET_EDGE = Color.White.copy(alpha = 0.10f)
