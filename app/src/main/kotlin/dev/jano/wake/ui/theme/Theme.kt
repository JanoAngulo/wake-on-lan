package dev.jano.wake.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val scheme = darkColorScheme(
    primary = WakeColors.HeroDisc,
    onPrimary = WakeColors.HeroIcon,
    background = WakeColors.Background,
    onBackground = WakeColors.OnSurface,
    surface = WakeColors.Surface,
    onSurface = WakeColors.OnSurface,
    surfaceVariant = WakeColors.Surface,
    onSurfaceVariant = WakeColors.OnSurfaceMuted,
    surfaceContainer = WakeColors.Surface,
    surfaceContainerHigh = WakeColors.Surface,
    surfaceContainerHighest = WakeColors.Outline,
    outline = WakeColors.Outline,
    outlineVariant = WakeColors.Outline,
    error = WakeColors.Danger,
    errorContainer = WakeColors.DangerContainer,
)

private val typography = Typography(
    titleLarge = WakeType.Title,
    titleMedium = WakeType.Section,
    bodyLarge = WakeType.RowValue,
    bodyMedium = WakeType.Body,
    labelLarge = WakeType.Button,
    labelMedium = WakeType.RowLabel,
)

@Composable
fun WakeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
