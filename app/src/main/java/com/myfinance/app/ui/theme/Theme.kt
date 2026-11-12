package com.myfinance.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val IncomeGreen = Color(0xFF16A34A)
val ExpenseRed = Color(0xFFE5484D)

// Soft brand colours: a calm purple accent with pastel companions.
val Indigo = Color(0xFF7A5AF8)
val Violet = Color(0xFF9B87F5)
val Pink = Color(0xFFE879A6)
val Coral = Color(0xFFF2877E)
val Amber = Color(0xFFE9A23B)
val Teal = Color(0xFF3DB8A5)
val Sky = Color(0xFF5B9BF0)
val Lime = Color(0xFF8BC34A)

/** Distinct colours for chart series that have no colour of their own (people, payment modes). */
val ChartPalette = listOf(Indigo, Pink, Teal, Amber, Sky, Coral, Violet, Lime)

/** Header gradients, kept soft. */
val HeroGradient = Brush.linearGradient(listOf(Color(0xFF8B74F9), Color(0xFFB59CFA)))
val IncomeGradient = Brush.linearGradient(listOf(Color(0xFF34C38F), Color(0xFF5CCFB9)))
val SpendGradient = Brush.linearGradient(listOf(Color(0xFFF59E7B), Color(0xFFF2877E)))

/** The light grey page behind the white cards. */
private val PageGrey = Color(0xFFF2F2F5)

private val Light = lightColorScheme(
    primary = Indigo,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFECE8FE),
    onPrimaryContainer = Color(0xFF2B1C7A),
    secondary = Color(0xFF6B6880),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE9E7F3),
    onSecondaryContainer = Color(0xFF23212F),
    tertiary = Teal,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFDDF3E4),
    onTertiaryContainer = Color(0xFF0E3B22),
    error = Color(0xFFD93A3F),
    errorContainer = Color(0xFFFCE3E2),
    onErrorContainer = Color(0xFF5C1013),
    background = PageGrey,
    onBackground = Color(0xFF1B1B21),
    surface = PageGrey,
    onSurface = Color(0xFF1B1B21),
    onSurfaceVariant = Color(0xFF6E6E78),
    surfaceVariant = Color(0xFFEBEBEF),
    outline = Color(0xFFB4B4BD),
    outlineVariant = Color(0xFFE6E6EB),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color.White,
    surfaceContainerHighest = Color.White,
)

private val Dark = darkColorScheme(
    primary = Color(0xFFB7A8FF),
    onPrimary = Color(0xFF26186E),
    primaryContainer = Color(0xFF3A2F7A),
    onPrimaryContainer = Color(0xFFECE8FE),
    secondary = Color(0xFFC9C6D8),
    onSecondary = Color(0xFF2B2938),
    secondaryContainer = Color(0xFF34323F),
    onSecondaryContainer = Color(0xFFE9E7F3),
    tertiary = Color(0xFF7FD9C5),
    onTertiary = Color(0xFF00382F),
    tertiaryContainer = Color(0xFF1F4433),
    onTertiaryContainer = Color(0xFFDDF3E4),
    errorContainer = Color(0xFF5A2224),
    onErrorContainer = Color(0xFFFCE3E2),
    background = Color(0xFF111114),
    onBackground = Color(0xFFEDEDF2),
    surface = Color(0xFF111114),
    onSurface = Color(0xFFEDEDF2),
    onSurfaceVariant = Color(0xFFA3A3AE),
    surfaceVariant = Color(0xFF2A2A31),
    outline = Color(0xFF5E5E68),
    outlineVariant = Color(0xFF2E2E35),
    surfaceContainerLowest = Color(0xFF16161A),
    surfaceContainerLow = Color(0xFF1C1C21),
    surfaceContainer = Color(0xFF1E1E23),
    surfaceContainerHigh = Color(0xFF212126),
    surfaceContainerHighest = Color(0xFF232329),
)

/** Large titles in a serif face, everything else in the phone's clean sans-serif. */
private val AppTypography = Typography().let { t ->
    val serif = FontFamily.Serif
    t.copy(
        displayLarge = t.displayLarge.copy(fontFamily = serif),
        displayMedium = t.displayMedium.copy(fontFamily = serif),
        displaySmall = t.displaySmall.copy(fontFamily = serif, fontSize = 34.sp, lineHeight = 40.sp),
        headlineLarge = t.headlineLarge.copy(fontFamily = serif),
        headlineMedium = t.headlineMedium.copy(fontFamily = serif, fontSize = 28.sp, lineHeight = 34.sp),
        headlineSmall = t.headlineSmall.copy(fontFamily = serif, fontSize = 24.sp, lineHeight = 30.sp),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = t.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    )
}

/** Rounded everywhere: pill chips, soft cards, round buttons. */
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(50),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/**
 * A fixed, calm scheme: light grey pages, white rounded cards, a purple accent and pastel highlights.
 * Wallpaper-based dynamic colours are deliberately not used: they made the app look different on every family phone.
 */
@Composable
fun MyFinanceTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) Dark else Light,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
