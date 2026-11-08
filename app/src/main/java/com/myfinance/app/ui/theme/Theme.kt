package com.myfinance.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

val IncomeGreen = Color(0xFF12B76A)
val ExpenseRed = Color(0xFFF04438)

// Vibrant brand colours.
val Indigo = Color(0xFF5B4CF0)
val Violet = Color(0xFF8B5CF6)
val Pink = Color(0xFFEC4899)
val Coral = Color(0xFFFF6B6B)
val Amber = Color(0xFFF59E0B)
val Teal = Color(0xFF14B8A6)
val Sky = Color(0xFF0EA5E9)
val Lime = Color(0xFF84CC16)

/** Distinct, bright colours for chart series that have no colour of their own (people, payment modes). */
val ChartPalette = listOf(Indigo, Pink, Teal, Amber, Sky, Coral, Violet, Lime)

/** Header gradients. */
val HeroGradient = Brush.linearGradient(listOf(Color(0xFF4F46E5), Color(0xFF9333EA), Color(0xFFEC4899)))
val IncomeGradient = Brush.linearGradient(listOf(Color(0xFF10B981), Color(0xFF14B8A6)))
val SpendGradient = Brush.linearGradient(listOf(Color(0xFFF97316), Color(0xFFEF4444)))

private val Light = lightColorScheme(
    primary = Indigo,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE4E1FF),
    onPrimaryContainer = Color(0xFF1A1170),
    secondary = Pink,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFD9E8),
    onSecondaryContainer = Color(0xFF5C0A30),
    tertiary = Teal,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFC8F5EE),
    onTertiaryContainer = Color(0xFF00382F),
    background = Color(0xFFF7F6FF),
    surface = Color(0xFFF7F6FF),
    surfaceVariant = Color(0xFFE6E3F5),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFF0EEFF),
    surfaceContainerHigh = Color(0xFFFFFFFF),
    surfaceContainerHighest = Color(0xFFFFFFFF),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFA5A0FF),
    onPrimary = Color(0xFF1E1580),
    primaryContainer = Color(0xFF3A2FB8),
    onPrimaryContainer = Color(0xFFE4E1FF),
    secondary = Color(0xFFFF8AC0),
    onSecondary = Color(0xFF5C0A30),
    secondaryContainer = Color(0xFF8A1D52),
    onSecondaryContainer = Color(0xFFFFD9E8),
    tertiary = Color(0xFF5EEAD4),
    onTertiary = Color(0xFF00382F),
    tertiaryContainer = Color(0xFF005046),
    onTertiaryContainer = Color(0xFFC8F5EE),
    background = Color(0xFF12111C),
    surface = Color(0xFF12111C),
    surfaceVariant = Color(0xFF2B2940),
    surfaceContainerLowest = Color(0xFF0D0C15),
    surfaceContainerLow = Color(0xFF1A1928),
    surfaceContainer = Color(0xFF1E1D2E),
    surfaceContainerHigh = Color(0xFF242338),
    surfaceContainerHighest = Color(0xFF2B2940),
)

/**
 * A fixed, colourful scheme. Wallpaper-based dynamic colours are deliberately not used:
 * they made the app look muted and different on every family phone.
 */
@Composable
fun MyFinanceTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
}
