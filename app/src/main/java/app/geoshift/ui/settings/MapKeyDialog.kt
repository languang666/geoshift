package app.geoshift.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/** Optional AMap Web service key: better search, address and road data in China. */
@Composable
fun MapKeyDialog(
    configured: Boolean,
    onDismiss: () -> Unit,
    onMessage: (String) -> Unit,
    onSave: (String) -> Unit,
    onTest: (() -> Unit)? = null,
) {
    val uri = LocalUriHandler.current
    var value by remember { mutableStateOf("") }
    var tutorial by remember { mutableStateOf(false) }
    fun open(url: String) {
        runCatching { uri.openUri(url) }.onFailure { onMessage("无法打开浏览器，请稍后重试") }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("高德 Web 服务 Key（可选）") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("不填也能搜索地点、显示地址和沿道路规划路线（使用 OpenStreetMap 数据）。填写后这三项改用高德数据，国内小店、新地点和道路更全。服务平台必须选择「Web 服务」。")
                if (configured) Text("已保存 Key。留空不会覆盖原值；更换时填入新 Key。", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value, onValueChange = { value = it.trim() }, singleLine = true,
                    label = { Text("Web 服务 Key") },
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                TextButton(onClick = { tutorial = !tutorial }) { Text(if (tutorial) "收起申请教程" else "查看申请教程") }
                if (tutorial) {
                    Text("1. 打开高德开放平台，登录并按平台要求完成开发者注册。\n2. 进入「控制台 → 应用管理 → 我的应用」，创建应用，再点击「添加 Key」。\n" +
                        "3. 服务平台选择「Web 服务」，不是 Android，也不是 Web 端（JS API）。\n4. 复制创建的 Key，粘贴到本页并保存。\n5. 点「验证已保存 Key」。")
                    TextButton(onClick = { open("https://lbs.amap.com/api/webservice/create-project-and-key") }) { Text("打开高德官方申请教程") }
                    Text("常见问题", style = MaterialTheme.typography.titleSmall)
                    Text("平台不匹配：重新创建「Web 服务」Key。IP 白名单错误：手机出口 IP 会变化，请清空白名单。配额不足：在高德控制台查看用量。不要把其他人的 Key 发到群里共用。",
                        style = MaterialTheme.typography.bodySmall)
                }
                if (configured && onTest != null) TextButton(onClick = { onDismiss(); onTest() }) { Text("验证已保存 Key") }
                Text("Key 只保存在本应用私有存储，不进入备份或系统定位配置。", style = MaterialTheme.typography.bodySmall)
                if (configured) TextButton(onClick = { onSave(""); onDismiss() }) { Text("清除已保存 Key") }
            }
        },
        confirmButton = {
            TextButton(enabled = value.isNotBlank(), onClick = { onSave(value.trim()); onDismiss() }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
