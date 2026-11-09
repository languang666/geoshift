package app.geoshift.ui.settings

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class AppEntry(val label: String, val pkg: String)

/**
 * Chooses which apps see the simulation. "All apps" is the default; "only chosen apps" leaves every
 * other app on its real location, Wi-Fi, cells and satellites.
 */
@Composable
fun AppScopeDialog(scope: String, chosen: List<String>, onDismiss: () -> Unit, onSave: (String, List<String>) -> Unit) {
    val context = LocalContext.current
    var mode by remember { mutableStateOf(scope) }
    val picked = remember { mutableStateListOf<String>().apply { addAll(chosen) } }
    var query by remember { mutableStateOf("") }
    var onlySelected by remember { mutableStateOf(false) }
    var apps by remember { mutableStateOf<List<AppEntry>?>(null) }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(launcher, 0)
                .map { AppEntry(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
                .filter { it.pkg != context.packageName }
                .distinctBy { it.pkg }
                .sortedWith(compareBy({ it.pkg !in chosen }, { it.label.lowercase() }))
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Text("模拟范围", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                ScopeOption("所有应用", "所有应用都看到模拟的位置、Wi-Fi、基站和卫星", mode == "all") { mode = "all" }
                ScopeOption("仅选中的应用", "只有下面选中的应用看到模拟数据，其他应用（如导航）保持真实位置", mode == "selected") { mode = "selected" }
                Text(
                    "使用谷歌定位服务的应用，其定位经由「Google Play 服务」获取，仅选中模式下需要同时选中它。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("搜索应用名或包名（已选 ${picked.size} 个）") },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "清除") }
                        }
                    },
                )
                Spacer(Modifier.height(8.dp))
                val list = apps
                if (list == null) {
                    Column(
                        Modifier.weight(1f).fillMaxWidth().padding(top = 40.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                        Text("正在读取应用列表…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    val q = query.trim().lowercase()
                    val filtered = list.filter {
                        (q.isEmpty() || q in it.label.lowercase() || q in it.pkg.lowercase()) &&
                                (!onlySelected || it.pkg in picked)
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FilterChip(
                            selected = onlySelected,
                            onClick = { onlySelected = !onlySelected },
                            label = { Text("仅看已选 (${picked.size})") },
                        )
                        TextButton(onClick = {
                            filtered.forEach { if (it.pkg !in picked) picked.add(it.pkg) }
                        }) { Text("全选当前") }
                        TextButton(onClick = { picked.clear() }) { Text("清空") }
                    }
                    LazyColumn(Modifier.weight(1f)) {
                        items(filtered, key = { it.pkg }) { app ->
                            val on = app.pkg in picked
                            Row(
                                Modifier.fillMaxWidth().clickable { if (on) picked.remove(app.pkg) else picked.add(app.pkg) }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(on, onCheckedChange = { if (it) picked.add(app.pkg) else picked.remove(app.pkg) })
                                Column(Modifier.weight(1f)) {
                                    Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(app.pkg, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                                        overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    Button(
                        onClick = { onSave(mode, picked.toList()) },
                        enabled = mode == "all" || picked.isNotEmpty(),
                    ) { Text("保存") }
                }
            }
        }
    }
}

@Composable
private fun ScopeOption(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick = onClick)
        Column {
            Text(title)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
