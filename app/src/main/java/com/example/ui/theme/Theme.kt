package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val FlacdlDarkColorScheme = darkColorScheme(
    primary = CyanAccent,
    onPrimary = ObsidianBackground,
    primaryContainer = CyanMutedContainer,
    onPrimaryContainer = CyanSoft,
    secondary = BlueAccent,
    onSecondary = ObsidianBackground,
    secondaryContainer = BlueMutedContainer,
    onSecondaryContainer = TextPrimary,
    tertiary = StatusConnected,
    onTertiary = ObsidianBackground,
    background = ObsidianBackground,
    onBackground = TextPrimary,
    surface = CharcoalSurface,
    onSurface = TextPrimary,
    surfaceVariant = CharcoalSurfaceVariant,
    onSurfaceVariant = TextSecondary,
    surfaceContainerLowest = ObsidianBackground,
    surfaceContainerLow = CharcoalSurface,
    surfaceContainer = CharcoalSurfaceVariant,
    surfaceContainerHigh = CharcoalElevated,
    outline = HairlineBorder,
    outlineVariant = HairlineBorder,
    error = StatusDisconnected,
    onError = TextPrimary,
    errorContainer = StatusDisconnectedContainer,
    onErrorContainer = TextPrimary
)

private val FlacdlShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

@Composable
fun FlacdlTesterTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = FlacdlDarkColorScheme,
        typography = AppTypography,
        shapes = FlacdlShapes,
        content = content
    )
}
