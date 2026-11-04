package io.github.jman9700.quartermaster.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import io.github.jman9700.quartermaster.domain.FreshnessLevel

// The alpha's palette (Catppuccin Mocha) for dark mode, Catppuccin Latte for light mode.
private val DarkColors = darkColorScheme(
    primary = Color(0xFF89B4FA),
    onPrimary = Color(0xFF1E1E2E),
    primaryContainer = Color(0xFF3B4A6B),
    onPrimaryContainer = Color(0xFFCDD6F4),
    secondaryContainer = Color(0xFF3B4A6B),
    onSecondaryContainer = Color(0xFFCDD6F4),
    tertiaryContainer = Color(0xFF45475A),
    onTertiaryContainer = Color(0xFFCDD6F4),
    secondary = Color(0xFFA6ADC8),
    background = Color(0xFF1E1E2E),
    onBackground = Color(0xFFCDD6F4),
    surface = Color(0xFF1E1E2E),
    onSurface = Color(0xFFCDD6F4),
    surfaceVariant = Color(0xFF2A2A3E),
    onSurfaceVariant = Color(0xFFA6ADC8),
    surfaceContainer = Color(0xFF2A2A3E),
    surfaceContainerLow = Color(0xFF242436),
    surfaceContainerHigh = Color(0xFF313147),
    outline = Color(0xFF585B70),
    error = Color(0xFFF38BA8),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF1E66F5),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD5E1FB),
    onPrimaryContainer = Color(0xFF0B3A99),
    secondaryContainer = Color(0xFFD5E1FB),
    onSecondaryContainer = Color(0xFF0B3A99),
    tertiaryContainer = Color(0xFFDCE0E8),
    onTertiaryContainer = Color(0xFF4C4F69),
    secondary = Color(0xFF6C6F85),
    background = Color(0xFFEFF1F5),
    onBackground = Color(0xFF4C4F69),
    surface = Color(0xFFEFF1F5),
    onSurface = Color(0xFF4C4F69),
    surfaceVariant = Color(0xFFE6E9EF),
    onSurfaceVariant = Color(0xFF6C6F85),
    surfaceContainer = Color(0xFFE6E9EF),
    surfaceContainerLow = Color(0xFFEAEDF2),
    surfaceContainerHigh = Color(0xFFDCE0E8),
    outline = Color(0xFF9CA0B0),
    error = Color(0xFFD20F39),
)

@Immutable
data class FreshnessColors(val good: Color, val warning: Color, val critical: Color, val track: Color) {
    fun of(level: FreshnessLevel) = when (level) {
        FreshnessLevel.GOOD -> good
        FreshnessLevel.WARNING -> warning
        FreshnessLevel.CRITICAL -> critical
    }
}

private val DarkFreshness = FreshnessColors(Color(0xFFA6E3A1), Color(0xFFF9E2AF), Color(0xFFF38BA8), Color(0xFF585B70))
private val LightFreshness = FreshnessColors(Color(0xFF40A02B), Color(0xFFDF8E1D), Color(0xFFD20F39), Color(0xFFCCD0DA))

val LocalFreshnessColors = staticCompositionLocalOf { DarkFreshness }

@Composable
fun QuarterMasterTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalFreshnessColors provides if (dark) DarkFreshness else LightFreshness) {
        MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, content = content)
    }
}
