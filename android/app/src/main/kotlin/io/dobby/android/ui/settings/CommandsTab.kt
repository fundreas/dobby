package io.dobby.android.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.dobby.android.SockCommands
import io.dobby.android.SockEntry
import io.dobby.android.data.CommandView
import io.dobby.core.registry.CommandInfo
import io.dobby.core.registry.CommandKind

/**
 * Everything Dobby currently answers to, in one place.
 *
 * Two readings of the same list, because there are two questions people arrive with. *"What
 * was the word for…"* is answered alphabetically and needs no idea which Sock owns it. *"What
 * can the radio do?"* is answered by the grouping. Neither is a filter — the same commands are
 * in both, and which one is showing is remembered in the database, because it is a way of
 * reading and not a passing choice.
 *
 * **Only enabled Socks are here**, and that is not a display rule: this list is generated from
 * the same registry the template matcher searches, so a command that is not in it is a command
 * that would not resolve if it were spoken. The chip row is the shortest way back — the same
 * switches as the Socks tab, on the screen where somebody has just noticed something missing.
 */
@Composable
fun CommandsTab(
    groups: List<SockCommands>,
    socks: List<SockEntry>,
    view: CommandView,
    onView: (CommandView) -> Unit,
    onEnabled: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    // De-duplicated by id: a shared command such as "Stopp" is genuinely part of every Sock
    // that subscribes to it, which is right in the grouped view and would be four identical
    // rows in the flat one.
    val flat = remember(groups) {
        groups.flatMap { group -> group.commands.map { it to group } }
            .distinctBy { (command, _) -> command.id }
            .sortedBy { (command, _) -> command.title.lowercase() }
    }

    LazyColumn(
        modifier.fillMaxSize().navigationBarsPadding(),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "controls") {
            Column {
                ViewSwitch(view, onView)
                SockChips(socks, onEnabled)
                Text(
                    countLine(flat.size, socks),
                    Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (flat.isEmpty()) {
            item(key = "empty") {
                Note("Kein Sock ist an. Dobby hört zu und versteht nichts.")
            }
            return@LazyColumn
        }

        when (view) {
            CommandView.ALPHABETIC -> items(
                count = flat.size,
                key = { flat[it].first.id },
            ) { index ->
                val (command, group) = flat[index]
                CommandCard(command, owner = ownerLabel(command, group))
            }

            // Every Sock, not only the ones contributing rows. A Sock that is off is exactly
            // the thing somebody is looking for when they open this view and cannot find a
            // command, so it keeps its heading and its switch and simply has nothing under it.
            CommandView.BY_SOCK -> for (sock in socks) {
                val group = groups.firstOrNull { it.sockId == sock.id }
                item(key = "group-${sock.id}") {
                    GroupHeader(sock, group?.commands?.size ?: 0, onEnabled)
                }
                if (group == null) continue
                items(
                    count = group.commands.size,
                    key = { "${group.sockId}-${group.commands[it].id}" },
                ) { index ->
                    CommandCard(group.commands[index], owner = null)
                }
            }
        }
    }
}

/**
 * The two readings, as one control.
 *
 * Hand-rolled out of two `Surface`s rather than a segmented button, because the panel needs
 * exactly one shape of two-way choice and this is it — large, flat, unambiguous at a glance,
 * and the same height as everything else somebody taps on this screen.
 */
@Composable
private fun ViewSwitch(view: CommandView, onView: (CommandView) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        for (option in CommandView.entries) {
            val selected = option == view
            Surface(
                color = if (selected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.weight(1f).clickable { onView(option) },
            ) {
                Text(
                    option.label,
                    Modifier.padding(vertical = 10.dp),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

/**
 * One chip per Sock, and the chip *is* the switch.
 *
 * The obvious design here is a filter — chips that hide and show rows of a fixed list. That
 * would be a second, weaker concept sitting next to the real one, and the two would disagree
 * the first time somebody filtered a Sock out and then wondered why Dobby still answered it.
 * So there is no filter: a chip that is off is a Sock that is off, in the database, exactly as
 * if it had been switched off in the Socks tab.
 */
@Composable
private fun SockChips(socks: List<SockEntry>, onEnabled: (String, Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (sock in socks) {
            FilterChip(
                selected = sock.enabled,
                onClick = { onEnabled(sock.id, !sock.enabled) },
                label = { Text(sock.displayName) },
            )
        }
    }
}

/** A Sock's heading in the grouped view, with the same switch the chips and the Socks tab carry. */
@Composable
private fun GroupHeader(sock: SockEntry, commands: Int, onEnabled: (String, Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).alpha(if (sock.enabled) 1f else OFF_ALPHA)) {
            Text(
                sock.displayName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                when {
                    !sock.enabled -> "Aus — ${sock.commandCount} Befehle stumm"
                    commands == 1 -> "1 Befehl"
                    else -> "$commands Befehle"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = sock.enabled, onCheckedChange = { onEnabled(sock.id, it) })
    }
}

/** Faded, not hidden: a switched-off heading is the answer to "where did that command go". */
private const val OFF_ALPHA = 0.45f

/**
 * One command: what it is called, what it does, what to say, and the grammar behind it.
 *
 * Collapsed it shows the two phrasings somebody is most likely to use, which is the question
 * they came with. Expanded it shows every phrasing and every rule — the same thing the
 * terminal's `/commands <sock>` prints, and the same card the help screen draws, because they
 * are the same `CommandInfo` out of the same directory.
 */
@Composable
private fun CommandCard(command: CommandInfo, owner: String?) {
    var expanded by rememberSaveable(command.id) { mutableStateOf(false) }
    val examples = if (expanded) command.examples else command.examples.take(COLLAPSED_LINES)
    val hidden = command.examples.size - examples.size

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clickable { expanded = !expanded },
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    command.title,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                // Only in the flat view: under a Sock's own heading, saying the Sock again on
                // every card is noise. A chain command says "geteilt" in both, because "Stopp"
                // behaving differently depending on what is running is the whole point of it.
                val badge = when {
                    command.kind == CommandKind.SHARED -> "geteilt"
                    else -> owner
                }
                if (badge != null) {
                    Text(
                        badge,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                command.detail,
                Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (examples.isNotEmpty()) {
                Label("So sagst du es")
                examples.forEach { example ->
                    Text(
                        "„$example“",
                        Modifier.padding(top = 2.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            if (!expanded) {
                Text(
                    if (hidden > 0) "Tippen für $hidden weitere und die Syntax" else "Tippen für die Syntax",
                    Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }

            if (command.syntax.isNotEmpty()) {
                Label("Syntax")
                command.syntax.forEach { line ->
                    Text(
                        line,
                        Modifier.padding(top = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "( ) Auswahl · [ ] optional · < > deine Angabe",
                    Modifier.padding(top = 6.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (command.params.isNotEmpty()) {
                Label("Angaben")
                command.params.forEach { param ->
                    Text(
                        param.toString(),
                        Modifier.padding(top = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            command.hints.forEach { hint ->
                Text(
                    hint,
                    Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(
                command.id,
                Modifier.padding(top = 10.dp),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Label(text: String) {
    Spacer(Modifier.size(10.dp))
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private val CommandView.label: String
    get() = when (this) {
        CommandView.ALPHABETIC -> "A–Z"
        CommandView.BY_SOCK -> "Nach Sock"
    }

/** The Sock a command is listed under, or null for a chain nobody owns. */
private fun ownerLabel(command: CommandInfo, group: SockCommands): String? =
    if (command.ownerSockId == null) null else group.displayName

private fun countLine(commands: Int, socks: List<SockEntry>): String {
    val off = socks.count { !it.enabled }
    val base = if (commands == 1) "1 Befehl" else "$commands Befehle"
    return if (off == 0) base else "$base · $off Socks aus"
}

private const val COLLAPSED_LINES = 2
