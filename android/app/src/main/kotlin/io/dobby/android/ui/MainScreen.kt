package io.dobby.android.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import android.graphics.Bitmap
import io.dobby.android.DobbyUiState
import io.dobby.android.Phase
import io.dobby.socks.clock.ClockState
import io.dobby.socks.departures.DeparturesState
import io.dobby.socks.memo.MemoState
import io.dobby.socks.radio.RadioState
import io.dobby.socks.radio.Station
import io.dobby.socks.spotify.PlayerSnapshot
import io.dobby.socks.weather.WeatherState
import kotlinx.coroutines.delay
import java.time.Instant

/**
 * The panel at rest: what is true right now, stacked and scrollable, with the dock underneath.
 *
 * Two things are deliberate here and both come from the wall.
 *
 * **The stack scrolls.** It used to be a fixed column that divided whatever was left between
 * the cards and the conversation, which worked while there were three cards and stopped
 * working at six: a running timer, music, a memo, the weather and a departure board do not fit
 * on a phone-sized panel at once, and a layout that squeezes them all to fit is a layout where
 * nothing can be read from across the room. So the cards keep their natural height and the
 * screen scrolls past them, newest concern first.
 *
 * **The conversation is not in the stack.** A chat log is the record of a turn
 * (`dobby-plan.md` §9) and a turn is a moment, not a state — so it belongs on top of the panel
 * while it is happening and nowhere at all when it is not. See [ChatOverlay], which this
 * raises whenever Dobby is in a turn, and [rememberChatVisibility] for exactly when.
 */
@Composable
fun MainScreen(
    state: DobbyUiState,
    clock: ClockState,
    nowPlaying: PlayerSnapshot?,
    artwork: Bitmap?,
    radio: RadioState,
    memos: MemoState,
    weather: WeatherState,
    departures: DeparturesState,
    muted: Boolean,
    onSetUpWeather: () -> Unit,
    onRefreshWeather: () -> Unit,
    onRefreshDepartures: () -> Unit,
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
    onCommands: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val chat = rememberChatVisibility(state.phase)
    // Measured rather than guessed: the dock grows a button when something is playing, and a
    // constant tuned to the other case leaves the last card half-swallowed.
    val density = LocalDensity.current
    var dockHeight by remember { mutableStateOf(0.dp) }

    // The whole panel goes soft behind the sheet rather than merely dark: a scrim alone over a
    // pure-black theme is indistinguishable from the panel having switched off, and the point
    // of a popover is that the thing behind it is still there.
    val softness by animateDpAsState(
        targetValue = if (chat.visible) PANEL_BLUR else 0.dp,
        animationSpec = tween(durationMillis = 220),
        label = "panel-blur",
    )

    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(Modifier.fillMaxSize().blur(softness)) {
            Column(Modifier.fillMaxSize()) {
                // Tapping the status line opens the record — the conversation is no longer on
                // this screen, and "what did it just say?" needs a way back that is not
                // waiting for the next turn.
                Header(state, onSettings, onOpenChat = chat::open)
                CardStack(
                    clock = clock,
                    nowPlaying = nowPlaying,
                    artwork = artwork,
                    radio = radio,
                    memos = memos,
                    weather = weather,
                    departures = departures,
                    onSetUpWeather = onSetUpWeather,
                    onRefreshWeather = onRefreshWeather,
                    onRefreshDepartures = onRefreshDepartures,
                    onStopRadio = onStopRadio,
                    onPickStation = onPickStation,
                    onSpotifyPrevious = onSpotifyPrevious,
                    onSpotifyPlayPause = onSpotifyPlayPause,
                    onSpotifyNext = onSpotifyNext,
                    onCloseSpotify = onCloseSpotify,
                    onCancelTimer = onCancelTimer,
                    onCloseMemo = onCloseMemo,
                    onOpenMemos = onOpenMemos,
                    onSettings = onSettings,
                    dockClearance = dockHeight,
                    modifier = Modifier.weight(1f),
                )
            }

            Dock(
                state = state,
                // The speaker button exists only while there is something to silence.
                playing = playing(nowPlaying, radio),
                muted = muted,
                onToggleMute = onToggleMute,
                onRecord = {
                    chat.open()
                    onListen()
                },
                onAbort = onAbort,
                onHelp = onHelp,
                onCommands = onCommands,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .onSizeChanged {
                        dockHeight = with(density) { it.height.toDp() }
                    },
            )
        }

        ChatOverlay(
            visible = chat.visible,
            state = state,
            onDismiss = chat::dismiss,
            onListen = onListen,
            onAbort = onAbort,
        )
    }
}

/**
 * Everything the panel is currently holding, in one scroll.
 *
 * The order is the one the cards were written in and it is an order of urgency, not of
 * arrival: the time and a running timer first, then whatever is making noise, then the things
 * that are simply true — a memo somebody left, the weather, the next tram. A card that has
 * nothing to say draws nothing at all, so the stack is usually short and the scroll is the
 * exception rather than the rule.
 */
@Composable
private fun CardStack(
    clock: ClockState,
    nowPlaying: PlayerSnapshot?,
    artwork: Bitmap?,
    radio: RadioState,
    memos: MemoState,
    weather: WeatherState,
    departures: DeparturesState,
    onSetUpWeather: () -> Unit,
    onRefreshWeather: () -> Unit,
    onRefreshDepartures: () -> Unit,
    onStopRadio: () -> Unit,
    onPickStation: (Station) -> Unit,
    onSpotifyPrevious: () -> Unit,
    onSpotifyPlayPause: () -> Unit,
    onSpotifyNext: () -> Unit,
    onCloseSpotify: () -> Unit,
    onCancelTimer: (Long) -> Unit,
    onCloseMemo: (Long) -> Unit,
    onOpenMemos: () -> Unit,
    onSettings: () -> Unit,
    dockClearance: Dp,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
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
        // Below the weather, and drawn on the same terms: a departure board is something that
        // is true rather than something the panel is doing, and one that only appears while
        // you are asking about it is one you have to ask about (`departures.specs.md` §5).
        // `Instant.now()` per recomposition for the same reason the weather card takes one —
        // the freshness line is supposed to be the truth at the moment of drawing.
        DeparturesCard(
            departures,
            now = Instant.now(),
            onOpenSettings = onSettings,
            onRefresh = onRefreshDepartures,
        )
        // The dock floats over this column, so the last card has to be able to scroll out from
        // under it — otherwise the bottom of the departure board is permanently behind glass.
        // The dock measures itself and its height arrives here; it already carries the
        // navigation bar's padding, so this is the whole of what is covered.
        Spacer(Modifier.height(dockClearance))
    }
}

/**
 * The three things you touch, floating over the stack.
 *
 * A bar rather than a row at the end of the column, because the column scrolls now and the
 * microphone must not scroll away — the panel is on a wall and this is the only part of it a
 * hand ever reaches for. Floating rather than docked to the edge for the same reason the cards
 * are cards: the thing under it stays visible, so it reads as being on top of the panel rather
 * than as the bottom of it.
 *
 * Help and Befehle flank the microphone because "what can I say" is a question somebody has
 * while standing in front of it, with their hand already here.
 */
@Composable
private fun Dock(
    state: DobbyUiState,
    playing: Boolean,
    muted: Boolean,
    onToggleMute: () -> Unit,
    onRecord: () -> Unit,
    onAbort: () -> Unit,
    onHelp: () -> Unit,
    onCommands: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        shape = RoundedCornerShape(32.dp),
        color = DOCK_FILL,
        border = BorderStroke(1.dp, DOCK_EDGE),
        shadowElevation = 12.dp,
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DockButton(
                icon = Icons.AutoMirrored.Filled.HelpOutline,
                label = "Hilfe",
                onClick = onHelp,
            )
            DockButton(
                icon = Icons.AutoMirrored.Filled.FormatListBulleted,
                label = "Befehle",
                onClick = onCommands,
            )
            RecordButton(state, onRecord = onRecord, onAbort = onAbort)
            // Appears with the sound and goes away with it, so the panel carries a speaker
            // button only in the one situation it means anything. Muting leaves playback alone
            // (`system.specs.md` §4) — it does not pause Spotify, does not stop the stream and
            // does not stop a timer counting. That is the difference between this and the X on
            // the cards above it, and it is the whole reason both exist.
            if (playing) {
                DockButton(
                    icon = if (muted) {
                        Icons.AutoMirrored.Filled.VolumeOff
                    } else {
                        Icons.AutoMirrored.Filled.VolumeUp
                    },
                    label = if (muted) "Ton an" else "Stumm",
                    onClick = onToggleMute,
                    tint = if (muted) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

/** One of the dock's quiet buttons: icon over a word, sized to be hit without looking. */
@Composable
private fun DockButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Column(
        Modifier
            .clip(RoundedCornerShape(22.dp))
            .clickable(onClick = onClick)
            .width(72.dp)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, contentDescription = label, Modifier.size(26.dp), tint = tint)
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
        )
    }
}

/**
 * The microphone, and the way out of it.
 *
 * One control that is two controls, because while the microphone is open the only thing
 * anybody wants from it is the way out: a wake word the television said, or a question asked
 * by mistake, otherwise costs ten seconds of standing there being listened to.
 */
@Composable
private fun RecordButton(state: DobbyUiState, onRecord: () -> Unit, onAbort: () -> Unit) {
    val listening = state.phase == Phase.LISTENING
    val busy = state.phase == Phase.THINKING

    Column(
        Modifier.width(88.dp).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        FilledIconButton(
            onClick = if (listening) onAbort else onRecord,
            modifier = Modifier.size(56.dp),
            // Abort is never greyed out: it is the button for a turn that is already running,
            // and there is no state in which somebody may start listening but not stop.
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
                modifier = Modifier.size(28.dp),
            )
        }
        Text(
            if (listening) "Stopp" else "Record",
            style = MaterialTheme.typography.labelSmall,
            color = if (listening) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
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
private fun Header(state: DobbyUiState, onSettings: () -> Unit, onOpenChat: () -> Unit) {
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
            Row(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onOpenChat)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusPill(state)
            }
            Spacer(Modifier.size(4.dp))
            IconButton(onClick = onSettings) {
                Icon(Icons.Filled.Settings, contentDescription = "Einstellungen")
            }
        }
        Text(
            state.detail.ifEmpty { state.summary },
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onOpenChat),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun StatusPill(state: DobbyUiState) {
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

/** Open, dismissed, or waiting to close itself — see [rememberChatVisibility]. */
@Stable
internal class ChatVisibility internal constructor() {
    var visible by mutableStateOf(false)
        private set

    /** Whether Dobby is mid-turn, kept in step by [rememberChatVisibility]. */
    internal var inTurn by mutableStateOf(false)

    /** Set when a turn that is still running has already been waved away. */
    internal var suppressed by mutableStateOf(false)

    fun open() {
        suppressed = false
        visible = true
    }

    fun dismiss() {
        visible = false
        // Suppression is scoped to the turn being dismissed and to nothing else. Without it,
        // the next phase change inside the same turn — LISTENING to THINKING — would raise the
        // sheet again over somebody who has just put it away; with it left standing afterwards,
        // the *next* turn would come up silent because of a tap made during the last one.
        suppressed = inTurn
    }

    internal fun close() {
        visible = false
    }
}

/**
 * When the conversation is on screen, and — the harder half — when it goes away again.
 *
 * It arrives with the turn: the moment the microphone opens, whatever raised it. It does not
 * leave with the turn, because the last thing in it is the answer and an answer that vanishes
 * the instant it has finished being spoken is an answer nobody standing two metres away gets
 * to read. So it lingers for [CHAT_LINGER] once Dobby is idle again and then lowers itself,
 * which is the behaviour a wall panel needs: it returns to being a clock without being asked.
 *
 * Dismissing by hand wins over all of it, and stays won until the turn is over — see
 * [ChatVisibility.dismiss].
 */
@Composable
private fun rememberChatVisibility(phase: Phase): ChatVisibility {
    val chat = remember { ChatVisibility() }
    val inTurn = phase == Phase.LISTENING || phase == Phase.THINKING || phase == Phase.SPEAKING

    LaunchedEffect(inTurn) {
        chat.inTurn = inTurn
        if (inTurn) {
            if (!chat.suppressed) chat.open()
            return@LaunchedEffect
        }
        chat.suppressed = false
        if (chat.visible) {
            delay(CHAT_LINGER)
            chat.close()
        }
    }

    return chat
}

/** How long the conversation stays up after Dobby has stopped talking. */
private const val CHAT_LINGER = 9_000L

private val PANEL_BLUR = 18.dp

/** Milky rather than opaque: the card the dock is sitting on stays faintly readable through it. */
private val DOCK_FILL = Color(0xFF16181C).copy(alpha = 0.88f)
private val DOCK_EDGE = Color.White.copy(alpha = 0.07f)
