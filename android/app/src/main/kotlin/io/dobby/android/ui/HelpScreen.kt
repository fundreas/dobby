package io.dobby.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.dobby.core.registry.CommandInfo
import io.dobby.core.registry.CommandKind
import io.dobby.core.registry.Introspection
import io.dobby.core.registry.SockInfo
import io.dobby.core.sock.SockStatus

/**
 * What Dobby can do, on the panel: every area, and inside each one every command with the
 * sentences that reach it.
 *
 * The spoken help says three things and stops, which is right for a speaker and useless when
 * somebody wants to know what else there is (`help.specs.md` §3). This is the other half of
 * the same answer, and it reads from the same [Introspection] the Help Sock answers out of —
 * including the syntax, which is rendered from the templates the matcher actually runs. There
 * is no second list of commands anywhere to fall out of date.
 *
 * Two levels and no more. A wall panel is not a documentation site: areas, then commands.
 */
@Composable
fun HelpScreen(
    introspection: Introspection?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var openSock by rememberSaveable { mutableStateOf<String?>(null) }

    // One handler for both levels, so the back gesture retraces the way in.
    BackHandler { if (openSock != null) openSock = null else onBack() }

    val socks = remember(introspection) { introspection?.socks().orEmpty() }
    val sock = socks.firstOrNull { it.id == openSock }
    val commands = remember(introspection, sock?.id) {
        sock?.let { introspection?.commands(it.id) }.orEmpty()
    }

    Column(modifier.fillMaxSize()) {
        Header(
            title = sock?.displayName ?: "Hilfe",
            onBack = { if (openSock != null) openSock = null else onBack() },
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

        when {
            introspection == null -> Note("Dobby kann seine Befehle gerade nicht nachschlagen.")

            sock == null -> SockList(socks) { openSock = it.id }

            commands.isEmpty() -> Note("${sock.displayName} kann im Moment nichts.")

            else -> CommandList(sock, commands)
        }
    }
}

@Composable
private fun Header(title: String, onBack: () -> Unit) {
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
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

@Composable
private fun SockList(socks: List<SockInfo>, onOpen: (SockInfo) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().navigationBarsPadding()) {
        item {
            Text(
                "Tippe auf einen Bereich. Oder frag mich: „Erkläre das Kommando Timer stellen“.",
                Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        }

        items(socks, key = { it.id }) { sock ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(sock) }
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        sock.displayName,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        listOfNotNull(count(sock.commandCount), trouble(sock)).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        // A degraded area stays in the list — knowing Spotify exists but is
                        // broken is more useful than it vanishing (`help.specs.md` §10) — so the
                        // reason is what changes colour, not the row.
                        color = if (trouble(sock) == null) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        }
    }
}

@Composable
private fun CommandList(sock: SockInfo, commands: List<CommandInfo>) {
    LazyColumn(
        Modifier.fillMaxSize().navigationBarsPadding(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        trouble(sock)?.let { reason ->
            item {
                Text(
                    reason,
                    Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        items(commands, key = { it.id }) { command -> CommandCard(command) }
    }
}

/**
 * One command: what it is called, what it does, what to say, and the grammar behind it.
 *
 * Collapsed it shows the two phrasings somebody is most likely to use, which is the question
 * they came with. Expanded it shows every phrasing and every rule — the same thing the
 * terminal's `/commands <sock>` prints, for whoever is standing at the panel instead.
 */
@Composable
private fun CommandCard(command: CommandInfo) {
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
                if (command.kind == CommandKind.SHARED) {
                    // A chain command is part of this area without belonging to it, and that is
                    // worth saying: "Stopp" behaves differently depending on what is running.
                    Text(
                        "geteilt",
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

@Composable
private fun Note(text: String) {
    Text(
        text,
        Modifier.fillMaxWidth().padding(20.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun count(commands: Int): String = if (commands == 1) "1 Befehl" else "$commands Befehle"

private fun trouble(sock: SockInfo): String? = when (val status = sock.status) {
    is SockStatus.Ready -> null
    is SockStatus.Degraded -> "Eingeschränkt: ${status.reason}"
    is SockStatus.Unavailable -> "Nicht verfügbar: ${status.reason}"
}

private const val COLLAPSED_LINES = 2
