package app.geoshift.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.geoshift.data.ThemeMode

/*
 * Fresh & Energetic (轻氧活力风) design theme:
 * Clean, translucent glass surfaces, energetic pastel accents (mint green, periwinkle, coral pink, sunshine gold),
 * rounded cards with subtle depth, and vibrant status gradients.
 */

/** Tint colours (Fresh & Energetic palette, light / dark variants picked by [Palette]). */
class Palette(
    val blue: Color, val green: Color, val red: Color, val orange: Color, val yellow: Color,
    val teal: Color, val indigo: Color, val purple: Color, val pink: Color, val gray: Color,
    /** Hairline separators inside grouped lists. */
    val separator: Color,
    /** Fill behind controls (segmented control track, search field). */
    val fill: Color,
    /** Glass surfaces floating over content (tab bar, map controls). */
    val glass: Color,
    val glassEdge: Color,
    val dark: Boolean,
)

private val LightPalette = Palette(
    blue = Color(0xFF5B67EA), green = Color(0xFF00C897), red = Color(0xFFFF5252), orange = Color(0xFFFF9F43),
    yellow = Color(0xFFFFD166), teal = Color(0xFF06D6A0), indigo = Color(0xFF6C5CE7), purple = Color(0xFFA29BFE),
    pink = Color(0xFFFF6584), gray = Color(0xFF8C8CA1),
    separator = Color(0x1F5B67EA), fill = Color(0x126C5CE7),
    glass = Color(0xF2F8F9FE), glassEdge = Color(0xD9FFFFFF), dark = false,
)

private val DarkPalette = Palette(
    blue = Color(0xFF7C88FF), green = Color(0xFF26E7A6), red = Color(0xFFFF6B6B), orange = Color(0xFFFFA94D),
    yellow = Color(0xFFFFE066), teal = Color(0xFF38D9A9), indigo = Color(0xFF9775FA), purple = Color(0xFFB197FC),
    pink = Color(0xFFFF8787), gray = Color(0xFFA6A7B5),
    separator = Color(0x33A29BFE), fill = Color(0x2E7C88FF),
    glass = Color(0xE61B192A), glassEdge = Color(0x33A29BFE), dark = true,
)

val LocalPalette = staticCompositionLocalOf { LightPalette }

/** Brand / action colour (Energetic Periwinkle). */
val Indigo = Color(0xFF5B67EA)
/** "Simulating" green: vibrant fresh mint green. */
val Success = Color(0xFF00C897)
val Warning = Color(0xFFFF9F43)
val Danger = Color(0xFFFF5252)

/** Running-state gradient (status card): Fresh Mint to Ocean Cyan. */
val LiveStart = Color(0xFF00D2A0)
val LiveEnd = Color(0xFF05B6D4)

private val Light = lightColorScheme(
    primary = LightPalette.blue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEEF0FE),
    onPrimaryContainer = Color(0xFF2C3575),
    secondary = Color(0xFF8C8CA1),
    secondaryContainer = Color(0xFFEFF1F8),
    onSecondaryContainer = Color(0xFF2D2E3A),
    tertiary = LightPalette.green,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE2F9F3),
    onTertiaryContainer = Color(0xFF005B44),
    background = Color(0xFFF7F8FC),
    onBackground = Color(0xFF1E1F29),
    surface = Color.White,
    onSurface = Color(0xFF1E1F29),
    surfaceVariant = Color(0xFFF0F2FA),
    onSurfaceVariant = Color(0xFF787A91),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color(0xFFF7F8FC),
    surfaceContainerHigh = Color(0xFFEFF1F8),
    surfaceContainerHighest = Color(0xFFE5E8F3),
    outline = Color(0xFFC7CBDD),
    outlineVariant = Color(0xFFE4E7F2),
    error = LightPalette.red,
)

private val Dark = darkColorScheme(
    primary = DarkPalette.blue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF28264A),
    onPrimaryContainer = Color(0xFFD8DCFF),
    secondary = Color(0xFFA6A7B5),
    secondaryContainer = Color(0xFF262438),
    onSecondaryContainer = Color(0xFFEDEDF5),
    tertiary = DarkPalette.green,
    onTertiary = Color.Black,
    tertiaryContainer = Color(0xFF0E382D),
    onTertiaryContainer = Color(0xFFB8F3E2),
    background = Color(0xFF12111D),
    onBackground = Color.White,
    surface = Color(0xFF1D1B2D),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF26233B),
    onSurfaceVariant = Color(0xFFA7A8BA),
    surfaceContainerLowest = Color(0xFF100F1A),
    surfaceContainerLow = Color(0xFF181626),
    surfaceContainer = Color(0xFF1D1B2D),
    surfaceContainerHigh = Color(0xFF27243C),
    surfaceContainerHighest = Color(0xFF353150),
    outline = Color(0xFF5A567D),
    outlineVariant = Color(0xFF36334D),
    error = DarkPalette.red,
)

/** iOS 26 rounds everything generously: cards ~26, sheets ~34. */
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(34.dp),
)

/** iOS text styles: Large Title 34, Title 28/22/20, Headline 17 semibold, Body 17, Subhead 15, Footnote 13, Caption 12. */
private val AppType = Typography().let { t ->
    fun s(size: Int, line: Int, weight: FontWeight = FontWeight.Normal, track: Double = 0.0) =
        TextStyle(fontSize = size.sp, lineHeight = line.sp, fontWeight = weight, letterSpacing = track.sp)
    t.copy(
        displaySmall = s(34, 41, FontWeight.Bold, 0.4),
        headlineLarge = s(34, 41, FontWeight.Bold, 0.4),
        headlineMedium = s(28, 34, FontWeight.Bold, 0.36),
        headlineSmall = s(34, 41, FontWeight.Bold, 0.37),
        titleLarge = s(22, 28, FontWeight.Bold, 0.35),
        titleMedium = s(17, 22, FontWeight.SemiBold, -0.41),
        titleSmall = s(15, 20, FontWeight.SemiBold, -0.24),
        bodyLarge = s(17, 22, track = -0.41),
        bodyMedium = s(15, 20, track = -0.24),
        bodySmall = s(13, 18, track = -0.08),
        labelLarge = s(15, 20, FontWeight.SemiBold, -0.24),
        labelMedium = s(13, 18, FontWeight.Normal, -0.08),
        labelSmall = s(11, 13, FontWeight.Medium, 0.07),
    )
}

@Composable
fun isDark(theme: ThemeMode): Boolean = when (theme) {
    ThemeMode.DARK -> true
    ThemeMode.LIGHT -> false
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
}

@Composable
fun GeoshiftTheme(theme: ThemeMode, content: @Composable () -> Unit) {
    val dark = isDark(theme)
    CompositionLocalProvider(LocalPalette provides if (dark) DarkPalette else LightPalette) {
        MaterialTheme(colorScheme = if (dark) Dark else Light, shapes = AppShapes, typography = AppType, content = content)
    }
}

/** The current system palette. */
val palette: Palette @Composable get() = LocalPalette.current
