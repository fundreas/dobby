package io.dobby.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.StickyNote2
import androidx.compose.material.icons.filled.Check
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
import io.dobby.socks.memo.Memo
import io.dobby.socks.memo.MemoState

/**
 * The open memos, on the wall (`memo.specs.md` §8).
 *
 * The half of this Sock that a speaker cannot do. A spoken list is serial — one memo, then the
 * next, and you have to ask for each one — while four lines on a panel are read in the time it
 * takes to walk past, which is the whole reason a memo gets written down instead of remembered.
 *
 * Drawn whenever anything is open — which is the one way this card is unlike the three above
 * it. Those appear while something is *running*; an open memo is open until somebody closes
 * it, and a list that is visible only while you are asking about it is a list nobody is
 * reminded by. A panel nobody has dictated to draws no card at all.
 *
 * The memo Dobby last read out is marked, because it is the one "Memo erledigt" would close. On
 * a panel that is a readout; in the room it is the difference between saying one word and
 * saying the memo back.
 *
 * @param onClose the check mark on a memo row: [io.dobby.socks.memo.MemoSock.closeFromPanel],
 *   by id. Defaulted to nothing so a preview and the off-device build can draw the card without
 *   a controller behind it, exactly as the clock card's X is.
 */
@Composable
fun MemoCard(state: MemoState, modifier: Modifier = Modifier, onClose: (Long) -> Unit = {}) {
    if (state.memos.isEmpty()) return
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            Icons.AutoMirrored.Filled.StickyNote2,
            contentDescription = null,
            modifier = Modifier.size(ICON_SIZE).padding(top = 4.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(Modifier.weight(1f)) {
            // Newest first, and only the ones that fit: a wall panel listing fifty notes in
            // 6-point type is a list nobody reads. The rest are a count, exactly as the
            // timers are.
            for (memo in state.memos.take(VISIBLE_MEMOS)) {
                MemoRow(memo, spotlit = memo.id == state.spotlight) { onClose(memo.id) }
            }
            val hidden = state.memos.size - VISIBLE_MEMOS
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

@Composable
private fun MemoRow(memo: Memo, spotlit: Boolean, onClose: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            memo.display,
            style = MaterialTheme.typography.bodyLarge,
            // The one Dobby just read out, and therefore the one a bare "erledigt" acts on.
            fontWeight = if (spotlit) FontWeight.SemiBold else FontWeight.Normal,
            color = if (spotlit) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onBackground
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        // A check rather than an X, and the icon is the whole product argument: closing a memo
        // is finishing something, not cancelling it. It runs the identical close the voice
        // command runs — and by id, so it needs no memo session and cannot close the wrong one.
        IconButton(onClick = onClose, modifier = Modifier.size(CLOSE_SIZE)) {
            Icon(
                Icons.Filled.Check,
                contentDescription = "${memo.display} erledigt",
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val ICON_SIZE = 28.dp

private val CLOSE_SIZE = 36.dp

/** How many memos the card draws before it starts counting the rest. */
private const val VISIBLE_MEMOS = 4
