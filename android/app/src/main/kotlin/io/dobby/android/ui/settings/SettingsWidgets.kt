package io.dobby.android.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * The rows every settings page is built from.
 *
 * Shared rather than copied into each Sock's page for the reason the panel has a theme at all:
 * nine pages that each invent their own row spacing is nine pages that look like nine apps. A
 * Sock decides *what* it offers; the panel decides what a choice looks like.
 *
 * All of them are sized for a finger at arm's length with something else in the other hand —
 * the same brief as the radio card's station picker, and the reason there are no dropdowns
 * anywhere in here.
 */

/** A heading over a group of rows. */
@Composable
fun SectionLabel(text: String) {
    Text(
        text,
        Modifier.padding(start = 20.dp, top = 18.dp, bottom = 6.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Explanatory prose under a heading or at the foot of a group. */
@Composable
fun Note(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** One option out of several, big enough to hit from a step back with a wet hand. */
@Composable
fun ChoiceRow(title: String, subtitle: String?, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.size(4.dp))
    }
}

/** A titled switch, for the settings that are genuinely two-valued. */
@Composable
fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * A number with a minus and a plus, and no keyboard.
 *
 * Every numeric Sock setting in the panel is a small count with a real range — three seconds
 * between chimes, fifty memos, twenty-five percent a step — and the two ways of editing one on
 * a device with no keyboard are this and a text field that brings the IME up over the list.
 * Two taps beats an IME, and a stepper cannot produce a value outside the range, which is why
 * the pages hand the range in rather than validating afterwards.
 *
 * [unit] is appended to the value, and [render] overrides it outright for a value whose reading
 * is not "number space unit" — "1 Minute" against "2 Minuten", say.
 */
@Composable
fun StepperRow(
    title: String,
    subtitle: String,
    value: Int,
    range: IntRange,
    onChange: (Int) -> Unit,
    step: Int = 1,
    unit: String = "",
    render: (Int) -> String = { if (unit.isEmpty()) "$it" else "$it $unit" },
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FilledTonalIconButton(
            onClick = { onChange((value - step).coerceIn(range)) },
            enabled = value > range.first,
        ) {
            Icon(Icons.Filled.Remove, contentDescription = "Weniger")
        }
        Text(
            render(value),
            Modifier.widthIn(min = 76.dp).padding(horizontal = 4.dp),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground,
        )
        FilledTonalIconButton(
            onClick = { onChange((value + step).coerceIn(range)) },
            enabled = value < range.last,
        ) {
            Icon(Icons.Filled.Add, contentDescription = "Mehr")
        }
    }
}
