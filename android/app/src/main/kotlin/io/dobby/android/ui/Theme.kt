package io.dobby.android.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Pure black, always dark.
 *
 * Not a style preference: the panel is an OLED phone that will show the same layout for years,
 * and black pixels are off pixels (`dobby-plan.md` §7.2). There is no light theme because
 * there is no daylight case — the thing lives on a wall.
 */
private val DobbyColors = darkColorScheme(
    background = Color.Black,
    surface = Color.Black,
    surfaceVariant = Color(0xFF16181C),
    onBackground = Color(0xFFE8EAED),
    onSurface = Color(0xFFE8EAED),
    onSurfaceVariant = Color(0xFF9AA0A6),
    primary = Color(0xFF7FC7FF),
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF12344D),
    onPrimaryContainer = Color(0xFFD6EBFF),
    error = Color(0xFFFF8A80),
)

@Composable
fun DobbyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DobbyColors,
        typography = Typography(),
        content = content,
    )
}
