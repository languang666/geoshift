package app.geoshift.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.Dp
import app.geoshift.ui.theme.palette
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.geoshift.ui.theme.Danger
import app.geoshift.ui.theme.Success
import app.geoshift.ui.theme.Warning
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Inset grouped card: rounded, subtle border, on the background. */
@Composable
fun PortalCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val shape = MaterialTheme.shapes.large
    Surface(
        modifier = modifier.fillMaxWidth().clip(shape).let { if (onClick != null) it.clickable(onClick = onClick) else it },
        shape = shape,
        border = BorderStroke(0.6.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

/** Large title, as at the top of iOS system apps, with optional actions on the right. */
@Composable
fun PageHeader(title: String, subtitle: String? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 8.dp, top = 18.dp, bottom = 8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        actions()
    }
}

/** Section header above a grouped list. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 36.dp, end = 36.dp, top = 22.dp, bottom = 7.dp))
}

/** Footer text under a grouped list. */
@Composable
fun SectionFooter(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 36.dp, end = 36.dp, top = 7.dp))
}

/** Solid rounded-square icon tile with a white glyph, as in iOS Settings. */
@Composable
fun IconBadge(icon: ImageVector, tint: Color = MaterialTheme.colorScheme.primary, size: Int = 30) {
    Box(Modifier.size(size.dp).clip(RoundedCornerShape((size * 0.24f).dp)).background(tint), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size((size * 0.62f).dp))
    }
}

/** Settings row colour for an icon, picked from the system palette by its position so groups look varied. */
@Composable
fun rowTint(icon: ImageVector?): Color {
    val p = palette
    val colors = listOf(p.blue, p.green, p.orange, p.indigo, p.red, p.teal, p.purple, p.pink, p.gray)
    return colors[((icon?.name?.hashCode() ?: 0) and 0x7fffffff) % colors.size]
}

/** iOS-style switch: green capsule track, white knob. */
@Composable
fun IosSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, enabled: Boolean = true, modifier: Modifier = Modifier) {
    val p = palette
    val track by animateColorAsState(if (checked) p.green else if (p.dark) Color(0xFF39393D) else Color(0xFFE3E3E8), label = "track")
    val offset by animateDpAsState(if (checked) 20.dp else 0.dp, label = "knob")
    Box(
        modifier.size(51.dp, 31.dp).clip(CircleShape).background(track)
            .let { if (onCheckedChange != null && enabled) it.toggleable(checked, onValueChange = onCheckedChange) else it }
            .alpha(if (enabled) 1f else 0.5f).padding(2.dp),
    ) {
        Box(Modifier.offset(x = offset).size(27.dp).shadow(2.dp, CircleShape).clip(CircleShape).background(Color.White))
    }
}

/** iOS segmented control: a filled track with a raised white segment. */
@Composable
fun Segmented(options: List<String>, selected: Int, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    val p = palette
    Row(
        modifier.fillMaxWidth().height(34.dp).clip(RoundedCornerShape(17.dp)).background(p.fill).padding(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier.weight(1f).fillMaxHeight()
                    .let { if (on) it.shadow(1.5.dp, RoundedCornerShape(15.dp)) else it }
                    .clip(RoundedCornerShape(15.dp))
                    .background(if (on) (if (p.dark) Color(0xFF636366) else Color.White) else Color.Transparent)
                    .clickable { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = MaterialTheme.typography.bodySmall, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

/**
 * Liquid-glass surface for controls floating over content (tab bar, map buttons): translucent fill,
 * a light rim and a soft shadow.
 */
fun Modifier.glass(shape: Shape, fill: Color, edge: Color, elevation: Dp = 10.dp): Modifier =
    this.shadow(elevation, shape, ambientColor = Color.Black.copy(alpha = 0.18f), spotColor = Color.Black.copy(alpha = 0.18f))
        .clip(shape).background(fill).border(0.6.dp, edge, shape)

@Composable
fun Modifier.glass(shape: Shape = CircleShape, elevation: Dp = 10.dp): Modifier {
    val p = palette
    return glass(shape, p.glass, p.glassEdge, elevation)
}

/** Settings-style row: icon tile, title, subtitle, and a trailing value / chevron / switch. */
@Composable
fun SettingRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    value: String? = null,
    checked: Boolean? = null,
    enabled: Boolean = true,
    onCheckedChange: ((Boolean) -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    tint: Color? = null,
) {
    val click = onClick ?: if (checked != null && onCheckedChange != null) ({ onCheckedChange(!checked) }) else null
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).let { if (click != null && enabled) it.clickable(onClick = click) else it }
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(Modifier.alpha(if (enabled) 1f else 0.45f)) { IconBadge(icon, tint ?: rowTint(icon)) }
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            if (!subtitle.isNullOrEmpty()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (value != null) {
            Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp).widthIn(max = 160.dp))
        }
        when {
            checked != null -> IosSwitch(checked, onCheckedChange, enabled, Modifier.padding(start = 10.dp))
            onClick != null -> Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(start = 4.dp).size(22.dp))
        }
    }
}

/** Inset grouped list of [SettingRow]s. */
@Composable
fun SettingGroup(content: @Composable ColumnScope.() -> Unit) {
    val shape = MaterialTheme.shapes.large
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = shape,
        border = BorderStroke(0.6.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(content = content)
    }
}

/** Hairline between rows of a grouped list, starting where the row text starts. */
@Composable
fun RowDivider(icon: Boolean = true) =
    HorizontalDivider(Modifier.padding(start = if (icon) 60.dp else 16.dp), thickness = 0.5.dp, color = palette.separator)

/**
 * Control Center-style tile: icon, title and state. When on, the icon disc fills with the tint.
 */
@Composable
fun ToggleTile(
    icon: ImageVector,
    title: String,
    state: String,
    on: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    hasSubpage: Boolean = false,
    accentColor: Color? = null,
    onClick: () -> Unit,
) {
    val accent = accentColor ?: palette.green
    val shape = MaterialTheme.shapes.large
    Surface(
        modifier = modifier.clip(shape).clickable(enabled = enabled, onClick = onClick),
        shape = shape,
        border = BorderStroke(0.6.dp, if (on) accent.copy(alpha = 0.35f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(if (on) accent else palette.fill),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, Modifier.size(21.dp),
                    tint = when {
                        on -> Color.White
                        enabled -> accentColor ?: MaterialTheme.colorScheme.onSurface
                        else -> MaterialTheme.colorScheme.outline
                    })
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                Text(state, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (on) accent else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (hasSubpage) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(20.dp))
        }
    }
}

/** Clickable coordinate row with haptic feedback and tap-to-copy. */
@Composable
fun CoordinateRow(
    lat: Double,
    lon: Double,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.titleMedium,
    color: Color = Color.Unspecified,
    onCopied: (() -> Unit)? = null,
) {
    val clipboard = LocalClipboardManager.current
    val haptic = LocalHapticFeedback.current
    val text = formatCoord(lat, lon)
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable {
                clipboard.setText(AnnotatedString(text))
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onCopied?.invoke()
            }
            .padding(vertical = 1.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(text, style = style.copy(fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum"), color = color)
        Icon(
            Icons.Rounded.ContentCopy,
            contentDescription = "复制坐标",
            tint = if (color != Color.Unspecified) color.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(15.dp),
        )
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, detail: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = 56.dp, horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(64.dp))
        Spacer(Modifier.size(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.size(6.dp))
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String = "确认", danger: Boolean = false, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = { onConfirm(); onDismiss() }) {
                Text(confirm, color = if (danger) Danger else MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** Single text / number input dialog with an optional hint ("提示") line. */
@Composable
fun InputDialog(
    title: String,
    initial: String,
    label: String = "",
    hint: String? = null,
    number: Boolean = false,
    singleLine: Boolean = true,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, label = { if (label.isNotEmpty()) Text(label) },
                    singleLine = singleLine, minLines = if (singleLine) 1 else 3,
                    keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Decimal) else KeyboardOptions.Default,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(text.trim()); onDismiss() }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

fun formatTime(time: Long, pattern: String = "yyyy-MM-dd HH:mm"): String =
    if (time <= 0) "" else SimpleDateFormat(pattern, Locale.CHINA).format(Date(time))

fun formatCoord(lat: Double, lon: Double) = String.format(Locale.ROOT, "%.6f, %.6f", lat, lon)

fun formatDistance(metres: Double): String =
    if (metres < 1000) String.format(Locale.ROOT, "%.0f 米", metres) else String.format(Locale.ROOT, "%.2f 公里", metres / 1000)

fun formatNumber(n: Double): String = if (n == Math.floor(n) && !n.isInfinite()) n.toLong().toString() else n.toString()
