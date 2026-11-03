package app.geoshift.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

val Indigo = Color(0xFF5266E8)
val Success = Color(0xFF1FA971)
val Warning = Color(0xFFE59A1A)
val Danger = Color(0xFFD9485F)

private val Light = lightColorScheme(
    primary = Indigo,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE2E6FF),
    onPrimaryContainer = Color(0xFF14206B),
    secondary = Color(0xFF5B6178),
    secondaryContainer = Color(0xFFE6E8F2),
    onSecondaryContainer = Color(0xFF1A1E2E),
    background = Color(0xFFF7F8FC),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFF0F2F8),
    surfaceContainer = Color(0xFFF2F3F9),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFECEEF6),
    surfaceContainerHighest = Color(0xFFE6E8F1),
    onSurface = Color(0xFF181B26),
    onSurfaceVariant = Color(0xFF6A7085),
    outline = Color(0xFFC9CDDB),
    outlineVariant = Color(0xFFE4E7F0),
    error = Danger,
)

private val Dark = darkColorScheme(
    primary = Color(0xFF9AA8FF),
    onPrimary = Color(0xFF0F1A63),
    primaryContainer = Color(0xFF2C3A9C),
    onPrimaryContainer = Color(0xFFDEE2FF),
    secondary = Color(0xFFC0C5DC),
    secondaryContainer = Color(0xFF2C3142),
    onSecondaryContainer = Color(0xFFDDE1F2),
    background = Color(0xFF11131A),
    surface = Color(0xFF181B24),
    surfaceVariant = Color(0xFF232735),
    surfaceContainer = Color(0xFF1C1F29),
    surfaceContainerLow = Color(0xFF181B24),
    surfaceContainerHigh = Color(0xFF242836),
    surfaceContainerHighest = Color(0xFF2C3040),
    onSurface = Color(0xFFE5E7F0),
    onSurfaceVariant = Color(0xFF9EA4B8),
    outline = Color(0xFF4A5064),
    outlineVariant = Color(0xFF2E3242),
    error = Color(0xFFFF8A9B),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun isDark(theme: String): Boolean = when (theme) {
    "dark" -> true
    "light" -> false
    else -> isSystemInDarkTheme()
}

@Composable
fun PortalTheme(theme: String, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isDark(theme)) Dark else Light, shapes = AppShapes, content = content)
}
