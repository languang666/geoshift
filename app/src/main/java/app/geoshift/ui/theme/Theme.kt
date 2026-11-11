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
 * A system-app look in the style of current iOS: grouped backgrounds, the system tint colours,
 * large titles and generously rounded, borderless cards. Colours follow the iOS system palette.
 */

/** Tint colours (system palette, light / dark variants picked by [Palette]). */
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
    blue = Color(0xFF007AFF), green = Color(0xFF34C759), red = Color(0xFFFF3B30), orange = Color(0xFFFF9500),
    yellow = Color(0xFFFFCC00), teal = Color(0xFF30B0C7), indigo = Color(0xFF5856D6), purple = Color(0xFFAF52DE),
    pink = Color(0xFFFF2D55), gray = Color(0xFF8E8E93),
    separator = Color(0x4A3C3C43), fill = Color(0x1F767680),
    glass = Color(0xEDFCFCFD), glassEdge = Color(0xB3FFFFFF), dark = false,
)

private val DarkPalette = Palette(
    blue = Color(0xFF0A84FF), green = Color(0xFF30D158), red = Color(0xFFFF453A), orange = Color(0xFFFF9F0A),
    yellow = Color(0xFFFFD60A), teal = Color(0xFF40C8E0), indigo = Color(0xFF5E5CE6), purple = Color(0xFFBF5AF2),
    pink = Color(0xFFFF375F), gray = Color(0xFF8E8E93),
    separator = Color(0x99545458), fill = Color(0x3D767680),
    glass = Color(0xE62C2C2E), glassEdge = Color(0x24FFFFFF), dark = true,
)

val LocalPalette = staticCompositionLocalOf { LightPalette }

/** Brand / action colour. */
val Indigo = Color(0xFF007AFF)
/** "Simulating" green: everything that shows the simulation is running uses it. */
val Success = Color(0xFF34C759)
val Warning = Color(0xFFFF9500)
val Danger = Color(0xFFFF3B30)

/** Running-state gradient (status card). */
val LiveStart = Color(0xFF30D158)
val LiveEnd = Color(0xFF00A86B)

private val Light = lightColorScheme(
    primary = LightPalette.blue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE5F1FF),
    onPrimaryContainer = Color(0xFF00408A),
    secondary = Color(0xFF8E8E93),
    secondaryContainer = Color(0xFFE9E9EB),
    onSecondaryContainer = Color(0xFF1C1C1E),
    tertiary = LightPalette.green,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE3F8E8),
    onTertiaryContainer = Color(0xFF0B5A21),
    background = Color(0xFFF2F2F7),
    onBackground = Color.Black,
    surface = Color.White,
    onSurface = Color.Black,
    surfaceVariant = Color(0xFFF2F2F7),
    onSurfaceVariant = Color(0xFF8A8A8E),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color(0xFFF2F2F7),
    surfaceContainerHigh = Color(0xFFE9E9EB),
    surfaceContainerHighest = Color(0xFFE3E3E8),
    outline = Color(0xFFC4C4C7),
    outlineVariant = Color(0xFFE5E5EA),
    error = LightPalette.red,
)

private val Dark = darkColorScheme(
    primary = DarkPalette.blue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF0A2A4D),
    onPrimaryContainer = Color(0xFFBFDCFF),
    secondary = Color(0xFF98989F),
    secondaryContainer = Color(0xFF2C2C2E),
    onSecondaryContainer = Color(0xFFEBEBF5),
    tertiary = DarkPalette.green,
    onTertiary = Color.Black,
    tertiaryContainer = Color(0xFF0E3A1C),
    onTertiaryContainer = Color(0xFFB4F0C4),
    background = Color.Black,
    onBackground = Color.White,
    surface = Color(0xFF1C1C1E),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF1C1C1E),
    onSurfaceVariant = Color(0xFF98989F),
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF1C1C1E),
    surfaceContainer = Color(0xFF1C1C1E),
    surfaceContainerHigh = Color(0xFF2C2C2E),
    surfaceContainerHighest = Color(0xFF3A3A3C),
    outline = Color(0xFF5A5A5E),
    outlineVariant = Color(0xFF38383A),
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
