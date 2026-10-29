package com.lscardinali.pocketwow.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/** Always dark, never dynamic: the app sits next to a game client and keeps one look. */
private val ColorScheme = darkColorScheme(
    primary = Gold,
    onPrimary = GoldInk,
    primaryContainer = GoldDeep,
    onPrimaryContainer = Parchment,
    secondary = Frost,
    onSecondary = FrostInk,
    tertiary = Ember,
    onTertiary = GoldInk,
    error = Blood,
    background = Night,
    onBackground = Parchment,
    surface = Slate,
    onSurface = Parchment,
    surfaceVariant = SlateRaised,
    onSurfaceVariant = Faded,
    surfaceContainer = Slate,
    surfaceContainerLow = Slate,
    surfaceContainerHigh = SlateRaised,
    surfaceContainerHighest = SlateHigh,
    outline = GoldDeep,
    outlineVariant = Rim,
    secondaryContainer = SlateHigh,
    onSecondaryContainer = Gold,
)

@Composable
fun PocketWowTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ColorScheme, typography = Typography, content = content)
}
