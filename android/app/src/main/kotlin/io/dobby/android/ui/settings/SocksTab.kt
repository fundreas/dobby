package io.dobby.android.ui.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.dobby.android.SockEntry
import io.dobby.core.sock.SockStatus

/**
 * Which Socks Dobby is wearing.
 *
 * The screen is a **drawer**, and that is not decoration — it is the one arrangement that makes
 * the state readable from across a kitchen. A list of ten rows with ten switches asks somebody
 * to scan ten switch positions; two labelled sections ask them to look at where a row *is*. A
 * Sock somebody has switched off drops out of the list above and lands in the drawer below,
 * with the movement animated, so the tap is confirmed by the thing moving rather than by a
 * toast that has already gone by the time they look up.
 *
 * The pip strip under the heading is the same fact again at a glance: one pip per Sock, lit for
 * the ones that are on. From two metres away it is the only part of this screen that is still
 * legible, which is the distance a wall panel is usually read from.
 *
 * Switching a Sock off is not a mute. Its templates leave the palette, so the words it used to
 * own go back to being words nothing answers to — which is the point, and why the row says how
 * many commands go with it.
 */
@Composable
fun SocksTab(
    socks: List<SockEntry>,
    onEnabled: (String, Boolean) -> Unit,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val worn = socks.filter { it.enabled }
    val drawered = socks.filterNot { it.enabled }

    LazyColumn(
        modifier.fillMaxSize().navigationBarsPadding(),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "header") { Header(socks, onEnabled) }

        item(key = "worn-label") {
            SectionHeading("Angezogen", worn.size, "Dobby hört auf diese Befehle.")
        }

        if (worn.isEmpty()) {
            item(key = "worn-empty") {
                Note(
                    "Alles in der Schublade. Dobby versteht im Moment gar nichts — schalte " +
                        "unten wieder etwas ein.",
                )
            }
        }

        items(worn, key = { it.id }) { sock ->
            SockCard(
                sock = sock,
                modifier = Modifier.animateItem(),
                onEnabled = { onEnabled(sock.id, it) },
                onOpen = { onOpen(sock.id) },
            )
        }

        item(key = "drawer-label") {
            Spacer(Modifier.height(8.dp))
            SectionHeading(
                "In der Schublade",
                drawered.size,
                "Aus. Weder gehört noch vorgeschlagen.",
            )
        }

        if (drawered.isEmpty()) {
            item(key = "drawer-empty") { Note("Leer — alle Socks sind an.") }
        }

        items(drawered, key = { it.id }) { sock ->
            SockCard(
                sock = sock,
                modifier = Modifier.animateItem(),
                onEnabled = { onEnabled(sock.id, it) },
                onOpen = { onOpen(sock.id) },
            )
        }
    }
}

/**
 * How many Socks are on, how many commands that is, and the one button worth having here.
 *
 * "Alle anziehen" appears only when something is off, because a button that does nothing is a
 * button somebody presses once to find out.
 */
@Composable
private fun Header(socks: List<SockEntry>, onEnabled: (String, Boolean) -> Unit) {
    val on = socks.count { it.enabled }
    val audible = socks.filter { it.enabled }.sumOf { it.commandCount }
    val silent = socks.filterNot { it.enabled }.sumOf { it.commandCount }

    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                "$on",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                " von ${socks.size} Socks an",
                Modifier.padding(bottom = 3.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            if (on < socks.size) {
                TextButton(onClick = {
                    socks.filterNot { it.enabled }.forEach { onEnabled(it.id, true) }
                }) {
                    Text("Alle anziehen")
                }
            }
        }

        PipStrip(socks)

        Text(
            if (silent == 0) {
                "$audible Befehle hörbar."
            } else {
                "$audible Befehle hörbar · $silent stumm."
            },
            Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * One pip per Sock, lit for the ones that are on, in the same order as the list.
 *
 * The cheapest possible summary and the only thing on this screen that survives being read from
 * across the room. Not interactive: at this size a finger would hit the neighbour, and there is
 * a full-width row for every one of them a thumb-scroll away.
 */
@Composable
private fun PipStrip(socks: List<SockEntry>) {
    Row(
        Modifier.padding(top = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        for (sock in socks) {
            val color by animateColorAsState(
                if (sock.enabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                label = "pip",
            )
            Box(
                Modifier
                    .size(width = 18.dp, height = 6.dp)
                    .clip(CircleShape)
                    .background(color),
            )
        }
    }
}

@Composable
private fun SectionHeading(title: String, count: Int, subtitle: String) {
    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "  $count",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * One Sock: its name, what is behind it, and the switch.
 *
 * Two targets in one row and they are deliberately different shapes. The **body** opens the
 * Sock's own settings and is the whole width of the card minus the switch, because it is the
 * thing somebody came here to do most often. The **switch** is the switch. A card with no
 * settings page has no chevron and no click on the body, so there is nothing to tap that does
 * nothing.
 *
 * The card carries its own state rather than announcing it: filled and full-contrast on,
 * outlined and faded off, with an accent bar down the left edge that is the same colour as
 * its pip in the strip above.
 */
@Composable
private fun SockCard(
    sock: SockEntry,
    onEnabled: (Boolean) -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val configurable = SockSettingsPages.has(sock.id)
    val container by animateColorAsState(
        if (sock.enabled) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.background,
        label = "sock-container",
    )
    val contentAlpha by animateFloatAsState(
        if (sock.enabled) 1f else DRAWER_ALPHA,
        label = "sock-alpha",
    )

    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(container)
            .border(
                width = 1.dp,
                color = if (sock.enabled) {
                    Color.Transparent
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                shape = RoundedCornerShape(16.dp),
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The same colour as this Sock's pip in the strip, so the two readings of the screen
        // agree without a legend.
        Box(
            Modifier
                .padding(vertical = 14.dp)
                .size(width = 3.dp, height = 28.dp)
                .clip(CircleShape)
                .background(
                    if (sock.enabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                ),
        )

        Row(
            Modifier
                .weight(1f)
                .then(if (configurable) Modifier.clickable(onClick = onOpen) else Modifier)
                .padding(start = 12.dp, end = 4.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).alpha(contentAlpha)) {
                Text(
                    sock.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    subtitle(sock, configurable),
                    style = MaterialTheme.typography.bodySmall,
                    // A degraded Sock stays in the list and keeps its row — knowing Spotify
                    // exists but is broken is more useful than it vanishing (`help.specs.md`
                    // §10) — so the reason is what changes colour, not the row.
                    color = if (trouble(sock) == null) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
            if (configurable) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "Einstellungen von ${sock.displayName}",
                    modifier = Modifier.alpha(contentAlpha),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Switch(
            checked = sock.enabled,
            onCheckedChange = onEnabled,
            modifier = Modifier.padding(end = 12.dp),
        )
    }
}

/** What the row says under the name: the trouble if there is any, otherwise what is behind it. */
private fun subtitle(sock: SockEntry, configurable: Boolean): String {
    trouble(sock)?.let { return it }
    val commands = if (sock.commandCount == 1) "1 Befehl" else "${sock.commandCount} Befehle"
    return when {
        !sock.enabled -> "$commands · stumm"
        configurable -> "$commands · Einstellungen"
        else -> "$commands · nichts einzustellen"
    }
}

private fun trouble(sock: SockEntry): String? = when (val status = sock.status) {
    is SockStatus.Ready -> null
    is SockStatus.Degraded -> "Eingeschränkt: ${status.reason}"
    is SockStatus.Unavailable -> "Nicht verfügbar: ${status.reason}"
}

/** Faded, not hidden: a drawered Sock has to stay readable, it is about to be switched back on. */
private const val DRAWER_ALPHA = 0.45f
