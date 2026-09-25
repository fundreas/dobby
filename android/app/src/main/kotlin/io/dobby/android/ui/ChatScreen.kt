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
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
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
import android.graphics.Bitmap
import io.dobby.android.chat.Voice
import io.dobby.socks.clock.ClockState
import io.dobby.socks.memo.MemoState
import io.dobby.socks.radio.RadioState
import io.dobby.socks.radio.Station
import io.dobby.socks.spotify.PlayerSnapshot
import io.dobby.socks.weather.WeatherState
import java.time.Instant

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
    nowPlaying: PlayerSnapshot?,
    artwork: Bitmap?,
    radio: RadioState,
    memos: MemoState,
    weather: WeatherState,
    muted: Boolean,
    onSetUpWeather: () -> Unit,
    onRefreshWeather: () -> Unit,
    onStopRadio: () -> Unit,
    onPickStation: (Station) -> Unit,
    onSpotifyPrevious: () -> Unit,
    onSpotifyPlayPause: () -> Unit,
    onSpotifyNext: () -> Unit,
    onCloseSpotify: () -> Unit,
    onCancelTimer: (Long) -> Unit,
    onCloseMemo: (Long) -> Unit,
    onOpenMemos: () -> Unit,
    onToggleMute: () -> Unit,
    onListen: () -> Unit,
    onAbort: () -> Unit,
    onSettings: () -> Unit,
    onHelp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Header(state, onSettings)
        // The Clock Sock owns the panel's clock, so the panel asks it rather than the system
        // (`clock.specs.md` §7). It is also where a running timer becomes visible.
        ClockCard(clock, onCancelTimer = onCancelTimer)
        // Drawn only while something is loaded, so a panel nobody has asked for music keeps
        // the clock at the top of the screen where it belongs (`spotify.specs.md` §7).
        NowPlayingCard(
            nowPlaying,
            artwork,
            onPrevious = onSpotifyPrevious,
            onPlayPause = onSpotifyPlayPause,
            onNext = onSpotifyNext,
            onClose = onCloseSpotify,
        )
        // Same rule, and the coordinator guarantees these two are never both drawn: only one
        // Sock can hold the channel (`radio.specs.md` §7).
        RadioCard(radio, onStop = onStopRadio, onPickStation = onPickStation)
        // Not "only while something is running", unlike the three above: an open memo is open
        // until somebody closes it, and a list that is only visible while you are asking about
        // it would be a list nobody is reminded by (`memo.specs.md` §8).
        MemoCard(memos, onClose = onCloseMemo, onOpen = onOpenMemos)
        // Same rule as the memo card, and for the same reason: the weather is not something
        // the panel is *doing*, it is something that is true, and a forecast that appears only
        // while you are asking about it is a forecast you have to ask about
        // (`weather.specs.md` §8). Below the memo rather than above it — a memo is a thing
        // somebody has to act on, the weather is a thing they walk past.
        //
        // `Instant.now()` on every recomposition is deliberate: what it feeds is the "vor 4
        // Minuten" line and the boundary between the hours that have happened and the ones
        // that have not, both of which are supposed to be the truth at the moment of drawing.
        WeatherCard(
            weather,
            now = Instant.now(),
            onSetUp = onSetUpWeather,
            onRefresh = onRefreshWeather,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        Conversation(state.messages, Modifier.weight(1f))
        // The speaker button exists only while there is something to silence — see [playing].
        Composer(state, playing(nowPlaying, radio), muted, onToggleMute, onListen, onAbort, onHelp)
    }
}

/**
 * Whether the room is making a noise Dobby put there (`system.specs.md` §4).
 *
 * The same two cards the screen already draws, asked a second question: mute is a control for
 * sound that is happening, and a speaker button on a silent panel is one more thing to read
 * past. `Buffering` counts — a stream connecting is about to be loud, and the moment before it
 * is, is exactly when somebody reaches for the button. `Error` does not: that card is a card
 * about silence, and it has its own X.
 */
private fun playing(nowPlaying: PlayerSnapshot?, radio: RadioState): Boolean =
    (nowPlaying != null && !nowPlaying.isPaused) ||
        radio is RadioState.Playing ||
        radio is RadioState.Buffering

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
private fun Composer(
    state: DobbyUiState,
    playing: Boolean,
    muted: Boolean,
    onToggleMute: () -> Unit,
    onListen: () -> Unit,
    onAbort: () -> Unit,
    onHelp: () -> Unit,
) {
    // The one control is two controls, because while the microphone is open the only thing
    // anybody wants from it is the way out: a wake word the television said, or a question
    // asked by mistake, otherwise costs ten seconds of standing there being listened to.
    val listening = state.phase == Phase.LISTENING
    val busy = state.phase == Phase.THINKING

    Row(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        // Here rather than in the header, and for the same reason the help button is: this row
        // is where a hand already is. It appears with the sound and goes away with it, so the
        // panel carries a speaker button only in the one situation it means anything.
        //
        // Muting leaves playback alone (`system.specs.md` §4) — it does not pause Spotify, does
        // not stop the stream and does not stop a timer counting. That is the difference between
        // this and the X on the cards above it, and it is the whole reason both exist.
        if (playing) {
            IconButton(onClick = onToggleMute, modifier = Modifier.size(72.dp)) {
                val speaker = if (muted) {
                    Icons.AutoMirrored.Filled.VolumeOff
                } else {
                    Icons.AutoMirrored.Filled.VolumeUp
                }
                Icon(
                    speaker,
                    contentDescription = if (muted) "Ton wieder an" else "Stummschalten",
                    modifier = Modifier.size(28.dp),
                    tint = if (muted) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }

        // One control, sized to be hit from across a room rather than from a thumb's reach —
        // the panel is on a wall, and this is the only thing on it you touch.
        FilledIconButton(
            onClick = if (listening) onAbort else onListen,
            modifier = Modifier.size(72.dp),
            // Abort is never greyed out: it is the button for a turn that is already running,
            // and there is no state in which somebody may start listening but not stop.
            enabled = listening || (state.canListen && !busy),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = if (listening) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                contentColor = if (listening) Color.Black else MaterialTheme.colorScheme.onSurface,
            ),
        ) {
            Icon(
                if (listening) Icons.Filled.Close else Icons.Filled.Mic,
                contentDescription = if (listening) "Zuhören abbrechen" else "Zuhören",
                modifier = Modifier.size(32.dp),
            )
        }

        // Beside it rather than in the header: "what can I say" is a question somebody has
        // while standing in front of the microphone, with their hand already here.
        IconButton(onClick = onHelp, modifier = Modifier.size(72.dp)) {
            Icon(
                Icons.AutoMirrored.Filled.HelpOutline,
                contentDescription = "Hilfe",
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private const val LIVE_ALPHA = 0.55f
