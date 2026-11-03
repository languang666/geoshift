package app.geoshift.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.geoshift.ui.theme.Danger
import app.geoshift.ui.theme.Success
import app.geoshift.ui.theme.Warning
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Rounded white card used across all screens. */
@Composable
fun PortalCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Surface(
        modifier = modifier.fillMaxWidth().clip(shape).let { if (onClick != null) it.clickable(onClick = onClick) else it },
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 0.dp,
        shadowElevation = 1.dp,
    ) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 20.dp, top = 20.dp, bottom = 8.dp))
}

/** Tinted rounded icon square. */
@Composable
fun IconBadge(icon: ImageVector, tint: Color = MaterialTheme.colorScheme.primary, size: Int = 40) {
    Box(Modifier.size(size.dp).clip(RoundedCornerShape(12.dp)).background(tint.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint, modifier = Modifier.size((size * 0.55f).dp))
    }
}

/** Settings-style row: icon, title, subtitle, and a trailing value / chevron / switch. */
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
) {
    val click = onClick ?: if (checked != null && onCheckedChange != null) ({ onCheckedChange(!checked) }) else null
    Row(
        Modifier.fillMaxWidth().let { if (click != null && enabled) it.clickable(onClick = click) else it }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            IconBadge(icon, size = 36)
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            if (!subtitle.isNullOrEmpty()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (value != null) {
            Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp))
        }
        when {
            checked != null -> Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange, modifier = Modifier.padding(start = 8.dp))
            onClick != null -> Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Card containing a group of [SettingRow]s. */
@Composable
fun SettingGroup(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shadowElevation = 1.dp,
    ) {
        Column(Modifier.padding(vertical = 4.dp), content = content)
    }
}

enum class Tone(val color: Color) { OK(Success), WARN(Warning), ERROR(Danger) }

@Composable
fun StatusPill(text: String, tone: Tone) {
    Row(
        Modifier.clip(CircleShape).background(tone.color.copy(alpha = 0.13f)).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(tone.color))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = tone.color, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, detail: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = 48.dp, horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        IconBadge(icon, tint = MaterialTheme.colorScheme.onSurfaceVariant, size = 64)
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
