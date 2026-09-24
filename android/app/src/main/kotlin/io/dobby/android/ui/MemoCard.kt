package io.dobby.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.StickyNote2
import androidx.compose.material.icons.filled.Close
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
import io.dobby.socks.memo.MemoSpeech
import io.dobby.socks.memo.MemoState
import java.time.Instant
import java.time.ZoneId

/**
 * One memo, on the wall (`memo.specs.md` §8).
 *
 * **The memo that is current**: the one the memo session is sitting on — which is the one a
 * spoken "Memo erledigt" would close — or the newest one when no walk is open. One and not a
 * list, because this card sits above the conversation on a panel that is mostly showing a
 * clock, and the job here is to say *there is something*, in a line that is readable while
 * walking past. The whole list is one tap away, in [MemoScreen].
 *
 * Drawn whenever anything is open, which is the one way this card is unlike the three above
 * it. Those appear while something is *running*; an open memo is open until somebody closes
 * it, and a list that is visible only while you are asking about it is a list nobody is
 * reminded by. A panel nobody has dictated to draws no card at all.
 *
 * @param onClose the X: [io.dobby.socks.memo.MemoSock.closeFromPanel], by id. It closes the
 *   memo on the card and nothing else.
 * @param onOpen a tap anywhere else on the card, which opens the full list. Both default to
 *   nothing so a preview and the off-device build can draw the card without a controller
 *   behind it, exactly as the clock card's X does.
 */
@Composable
fun MemoCard(
    state: MemoState,
    modifier: Modifier = Modifier,
    onClose: (Long) -> Unit = {},
    onOpen: () -> Unit = {},
) {
    // The spotlight first: after "welche Memos sind offen" the card and the voice are then
    // talking about the same memo, and the X closes what "erledigt" would have closed.
    val memo = state.memos.firstOrNull { it.id == state.spotlight } ?: state.memos.firstOrNull()
    if (memo == null) return
    Row(
        modifier
            .fillMaxWidth()
            // The row is the button. A memo is a short line of text with a lot of space beside
            // it, and a panel is operated with a thumb from a metre away.
            .clickable(onClick = onOpen)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            Icons.AutoMirrored.Filled.StickyNote2,
            contentDescription = null,
            modifier = Modifier.size(ICON_SIZE),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(Modifier.weight(1f)) {
            Text(
                memo.display,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Light,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                // The stamp the voice speaks, and the count that tells somebody whether the
                // tap is worth making.
                listOfNotNull(
                    MemoSpeech.panel(memo.createdAt, Instant.now(), ZoneId.systemDefault()),
                    more(state),
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // An X, and only for the memo on the card. Closing from here is by id, so it needs
        // neither the memo session nor the five minutes it lives for — pointing at a memo says
        // which one, and a button press must not be routed through the one part of the system
        // that can misunderstand it.
        IconButton(onClick = { onClose(memo.id) }) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "${memo.display} erledigt",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** "noch 2 weitere" — left out entirely when this is the only memo there is. */
private fun more(state: MemoState): String? = when (state.memos.size) {
    0, 1 -> null
    2 -> "noch 1 weiteres"
    else -> "noch ${state.memos.size - 1} weitere"
}

private val ICON_SIZE = 32.dp
