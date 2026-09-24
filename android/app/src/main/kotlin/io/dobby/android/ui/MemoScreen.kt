package io.dobby.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.dobby.socks.memo.Memo
import io.dobby.socks.memo.MemoSpeech
import io.dobby.socks.memo.MemoState
import java.time.Instant
import java.time.ZoneId

/**
 * Every open memo, by the date it was dictated (`memo.specs.md` §8).
 *
 * The card above the conversation shows one memo because that is what fits over a clock; this
 * is where the rest of them are. It is the drawn half of `memo.latest_memo` and its walk — the
 * same list, the same order, read with the eyes instead of one memo per sentence — and it is
 * reached by tapping the card rather than by a button in the header, because the card is the
 * thing somebody is already looking at when they want more of it.
 *
 * **Sorted by creation, both ways.** Newest first is the order everything else uses: the card,
 * the state flow, and what "welche Memos sind offen" reads out. Oldest first is the other end
 * of the same walk — `memo.oldest_memo` exists for exactly the case where something has been
 * lying around too long, and a list that could not be turned round would make the voice better
 * at this than the screen.
 *
 * There is no sort by text and no filter. Fifty memos is the cap (`memo.specs.md` §9), the
 * screen holds a dozen at a time, and a wall panel with a sort menu is a spreadsheet.
 *
 * @param onClose the X on a row: the Sock's own close, by id.
 */
@Composable
fun MemoScreen(
    state: MemoState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onClose: (Long) -> Unit = {},
) {
    // Survives rotation and the panel's own recompositions, and deliberately not the trip in
    // and out: somebody who turned the list round to find an old memo wants the newest ones
    // first the next time they open it, which is what the card is showing them anyway.
    var newestFirst by rememberSaveable { mutableStateOf(true) }
    val memos = if (newestFirst) state.memos else state.memos.asReversed()

    BackHandler(onBack = onBack)

    Column(modifier.fillMaxSize()) {
        Header(
            count = state.memos.size,
            newestFirst = newestFirst,
            onBack = onBack,
            onToggleSort = { newestFirst = !newestFirst },
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

        if (memos.isEmpty()) {
            // Reachable by closing the last memo from inside this screen, which is the one way
            // in. Saying so beats an empty page that looks like something failed to load.
            Text(
                "Es sind keine Memos offen.",
                Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        LazyColumn(Modifier.fillMaxSize().navigationBarsPadding()) {
            items(memos, key = { it.id }) { memo ->
                MemoRow(memo, spotlit = memo.id == state.spotlight) { onClose(memo.id) }
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            }
        }
    }
}

/**
 * The back arrow, what is open, and the one control on this screen.
 *
 * The sort is a labelled row rather than an icon alone: an arrow on its own is a guess about
 * which end of the list it means, and this is read from across a kitchen.
 */
@Composable
private fun Header(count: Int, newestFirst: Boolean, onBack: () -> Unit, onToggleSort: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
        }
        Column {
            Text(
                "Memos",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                if (count == 1) "1 Memo offen" else "$count Memos offen",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.weight(1f))
        Row(
            Modifier
                .clickable(onClick = onToggleSort)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (newestFirst) "Neueste zuerst" else "Älteste zuerst",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Icon(
                if (newestFirst) Icons.Filled.ArrowDownward else Icons.Filled.ArrowUpward,
                contentDescription = "Sortierung umdrehen",
                modifier = Modifier.size(18.dp).padding(start = 4.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** One memo: what it says, when it was dictated, and the X that closes it. */
@Composable
private fun MemoRow(memo: Memo, spotlit: Boolean, onClose: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                memo.display,
                style = MaterialTheme.typography.bodyLarge,
                // The memo Dobby last read out, and therefore the one a spoken "erledigt" acts
                // on. Marked here for the same reason it is marked on the card: the voice and
                // the screen must agree about which memo "das Memo" is.
                fontWeight = if (spotlit) FontWeight.SemiBold else FontWeight.Normal,
                color = if (spotlit) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onBackground
                },
            )
            Text(
                MemoSpeech.panel(memo.createdAt, Instant.now(), ZoneId.systemDefault()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onClose) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "${memo.display} erledigt",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
