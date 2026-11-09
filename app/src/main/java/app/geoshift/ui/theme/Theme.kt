package app.geoshift.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Brand blue: actions and selection. */
val Indigo = Color(0xFF2F62F5)
/** "Simulating" green: every place that shows the simulation is running uses it. */
val Success = Color(0xFF14A06F)
val Warning = Color(0xFFE08A12)
val Danger = Color(0xFFDC4A5A)

/** Gradient for the running state (status panel, console header). */
val LiveStart = Color(0xFF0FA47A)
val LiveEnd = Color(0xFF1C7FD8)

private val Light = lightColorScheme(
    primary = Indigo,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE3EAFF),
    onPrimaryContainer = Color(0xFF0F2A7A),
    secondary = Color(0xFF4F5B76),
    secondaryContainer = Color(0xFFE8ECF4),
    onSecondaryContainer = Color(0xFF18213A),
    tertiary = Success,
    tertiaryContainer = Color(0xFFDDF5EB),
    onTertiaryContainer = Color(0xFF07432E),
    background = Color(0xFFF3F5F9),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFEEF1F6),
    surfaceContainer = Color(0xFFF0F2F7),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFE9ECF3),
    surfaceContainerHighest = Color(0xFFE2E6EE),
    onSurface = Color(0xFF151A26),
    onSurfaceVariant = Color(0xFF636B80),
    outline = Color(0xFFC5CBD8),
    outlineVariant = Color(0xFFE3E7EF),
    error = Danger,
)

private val Dark = darkColorScheme(
    primary = Color(0xFF8EAEFF),
    onPrimary = Color(0xFF082269),
    primaryContainer = Color(0xFF22408F),
    onPrimaryContainer = Color(0xFFDDE5FF),
    secondary = Color(0xFFBCC5DA),
    secondaryContainer = Color(0xFF2A3245),
    onSecondaryContainer = Color(0xFFDCE2F2),
    tertiary = Color(0xFF5BD6A6),
    tertiaryContainer = Color(0xFF0D4A35),
    onTertiaryContainer = Color(0xFFC8F5E2),
    background = Color(0xFF0E1117),
    surface = Color(0xFF171B23),
    surfaceVariant = Color(0xFF212633),
    surfaceContainer = Color(0xFF1A1E27),
    surfaceContainerLow = Color(0xFF171B23),
    surfaceContainerHigh = Color(0xFF222734),
    surfaceContainerHighest = Color(0xFF2A303E),
    onSurface = Color(0xFFE6E9F1),
    onSurfaceVariant = Color(0xFF99A1B5),
    outline = Color(0xFF465065),
    outlineVariant = Color(0xFF272D3A),
    error = Color(0xFFFF8A97),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

private val AppType = Typography().let { t ->
    t.copy(
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.Bold, fontSize = 24.sp),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = t.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

@Composable
fun isDark(theme: String): Boolean = when (theme) {
    "dark" -> true
    "light" -> false
    else -> isSystemInDarkTheme()
}

@Composable
fun PortalTheme(theme: String, content: @Composable () -> Unit) {
    val dark = isDark(theme)
    val context = LocalContext.current
    val colorScheme = when {
        theme == "dynamic" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = colorScheme, shapes = AppShapes, typography = AppType, content = content)
}
