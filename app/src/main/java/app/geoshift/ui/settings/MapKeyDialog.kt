package app.geoshift.ui.settings

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import java.security.MessageDigest

/** Public certificate fingerprint; private signing material and saved keys never leave storage. */
@Suppress("DEPRECATION")
private fun signingSha1(context: Context): String = runCatching {
    val signatures = if (Build.VERSION.SDK_INT >= 28) {
        context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            .signingInfo?.apkContentsSigners
    } else {
        context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures
    }
    val certificate = requireNotNull(signatures?.firstOrNull())
    MessageDigest.getInstance("SHA-1").digest(certificate.toByteArray())
        .joinToString(":") { "%02X".format(it.toInt() and 0xff) }
}.getOrDefault("")

const val AMAP_SDK_DISCLOSURE = "高德地图 SDK（北京高德图强科技有限公司）用于高清地图展示和鉴权，可能处理设备与系统信息、应用信息、网络信息及所查看的地图区域。本应用不启用该 SDK 的自动定位功能。"

@Composable
fun MapKeyDialog(
    androidKey: Boolean,
    configured: Boolean,
    onDismiss: () -> Unit,
    onMessage: (String) -> Unit,
    onSave: (String, Boolean) -> Unit,
    onTest: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val uri = LocalUriHandler.current
    val sha1 = remember { signingSha1(context) }
    var value by remember { mutableStateOf("") }
    var agreed by remember { mutableStateOf(false) }
    var tutorial by remember { mutableStateOf(!configured) }
    fun open(url: String) {
        runCatching { uri.openUri(url) }.onFailure { onMessage("无法打开浏览器，请稍后重试") }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (androidKey) "高德高清地图 Android Key" else "地点搜索 Web 服务 Key") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (androidKey) "想要清晰、缩放流畅的高德地图，请申请 Android 平台 Key。已有的 Web 服务 Key 不能替代它。"
                    else "这个 Key 用于搜索地点和显示详细地址，不会改变底图画质。服务平台必须选择「Web 服务」。")
                if (configured) Text("已保存 Key。留空不会覆盖原值；更换时填入新 Key。", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value, onValueChange = { value = it.trim() }, singleLine = true,
                    label = { Text(if (androidKey) "Android Key" else "Web 服务 Key") },
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                TextButton(onClick = { tutorial = !tutorial }) { Text(if (tutorial) "收起申请教程" else "查看申请教程") }
                if (tutorial) {
                    Text("1. 打开高德开放平台，登录并按平台要求完成开发者注册。\n2. 进入「控制台 → 应用管理 → 我的应用」，创建应用，再点击「添加 Key」。")
                    Text(if (androidKey) "3. 服务平台选择「Android 平台 / Android 平台 SDK」。将下面的包名和 SHA1 填入 Package、发布版安全码 SHA1。\n4. 创建后复制 Key，粘贴到本页，阅读隐私说明后保存。\n5. 返回地图，选择「高德地图」，即可使用官方高清地图。"
                        else "3. 服务平台选择「Web 服务」，不是 Android，也不是 Web 端（JS API）。\n4. 复制创建的 Key，粘贴到本页并保存。\n5. 点「验证已保存 Key」，然后返回地图搜索地点。")
                    if (androidKey) {
                        SelectionContainer { Text("包名：${context.packageName}\n发布版 SHA1：${sha1.ifEmpty { "读取失败，请重新安装正式版本后重试" }}", style = MaterialTheme.typography.bodySmall) }
                        Row {
                            TextButton(onClick = { clipboard.setText(AnnotatedString(context.packageName)); onMessage("包名已复制") }) { Text("复制包名") }
                            TextButton(enabled = sha1.isNotEmpty(), onClick = { clipboard.setText(AnnotatedString(sha1)); onMessage("SHA1 已复制") }) { Text("复制 SHA1") }
                        }
                        Text("同一个正式 APK 的包名和签名一致，其他人可按本页信息申请自己的 Key；换签名或重打包后需重新配置。", style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { open(if (androidKey) "https://lbs.amap.com/api/android-sdk/guide/create-project/get-key" else "https://lbs.amap.com/api/webservice/create-project-and-key") }) { Text("打开高德官方申请教程") }
                    Text("常见问题", style = MaterialTheme.typography.titleSmall)
                    Text(if (androidKey) "地图空白或鉴权失败：先核对 Key 平台、包名、发布版 SHA1 和网络。Android Key 与 Web 服务 Key 不能混填。无 Key 时仍可使用默认地图和高德基础图。"
                        else "平台不匹配：重新创建「Web 服务」Key。IP 白名单错误：手机出口 IP 会变化，应按高德支持的移动使用方案配置。配额不足：在高德控制台查看用量和权限。不要把其他人的 Key 发到群里共用。", style = MaterialTheme.typography.bodySmall)
                }
                if (androidKey) {
                    Text(AMAP_SDK_DISCLOSURE, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { open("https://lbs.amap.com/pages/privacy/") }) { Text("阅读高德地图隐私政策") }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = agreed, onCheckedChange = { agreed = it })
                        Text("我已阅读并同意高德地图隐私说明", style = MaterialTheme.typography.bodySmall)
                    }
                } else if (configured && onTest != null) {
                    TextButton(onClick = { onDismiss(); onTest() }) { Text("验证已保存 Key") }
                }
                Text("Key 只保存在本应用私有存储，不进入备份或系统定位配置。", style = MaterialTheme.typography.bodySmall)
                if (configured) TextButton(onClick = { onSave("", false); onDismiss() }) { Text("清除已保存 Key") }
            }
        },
        confirmButton = {
            TextButton(enabled = value.isNotBlank() && (!androidKey || agreed), onClick = { onSave(value.trim(), agreed); onDismiss() }) {
                Text(if (androidKey) "保存并启用" else "保存")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
