package app.geoshift.ui.settings

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.geoshift.map.MapSource
import app.geoshift.ui.AppState
import app.geoshift.ui.AppViewModel
import app.geoshift.ui.components.ConfirmDialog
import app.geoshift.ui.components.PortalCard
import app.geoshift.ui.theme.Success
import java.security.MessageDigest

private const val CONSOLE_URL = "https://console.amap.com/dev/key/app"
private const val GUIDE_URL = "https://lbs.amap.com/api/android-sdk/guide/create-project/get-key"
private const val PRIVACY_URL = "https://lbs.amap.com/pages/privacy/"

/** SHA1 of the certificate this APK is signed with, as AMap's console expects it. Public information. */
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

/** Step-by-step guide to the user's own AMap Android key, and where it is entered. */
@Composable
fun AmapKeyScreen(s: AppState, vm: AppViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val uri = LocalUriHandler.current
    val sha1 = remember { signingSha1(context) }
    val packageName = context.packageName
    var value by rememberSaveable { mutableStateOf("") }
    var agreed by rememberSaveable { mutableStateOf(false) }
    var faq by rememberSaveable { mutableStateOf(false) }
    var webKey by remember { mutableStateOf(false) }
    var clear by remember { mutableStateOf(false) }
    val configured = s.sdkKeyConfigured

    fun open(url: String) = runCatching { uri.openUri(url) }.onFailure { vm.toast("无法打开浏览器，请稍后重试") }
    fun copy(label: String, text: String) {
        clipboard.setText(AnnotatedString(text))
        vm.toast("${label}已复制")
    }
    fun verify() = vm.run<String>(onDone = vm::toast) { checkMapKey() }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()
            .verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 32.dp),
    ) {
        Row(Modifier.padding(start = 8.dp, top = 8.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") }
        }
        Column(Modifier.padding(horizontal = 24.dp)) {
            Text("高德 Key", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("可选。不填也能正常使用全部功能", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.size(16.dp))

        // ---- Status ----
        PortalCard(Modifier.padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (configured) Icons.Rounded.CheckCircle else Icons.Rounded.Info, null,
                    tint = if (configured) Success else MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text(if (configured) "已填写 Android Key" else "未填写，正在使用免 Key 地图", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.size(6.dp))
            Text(
                if (configured) "「高德地图」图层已使用高德官方地图；搜索、地址和沿道路路线使用高德数据（海外自动改用 OpenStreetMap）。"
                else "填写自己的高德 Android Key 后：\n· 「高德地图」图层变成高德官方地图：矢量清晰、可旋转倾斜、有 3D 楼块\n" +
                    "· 搜索、地址显示、沿道路路线改用高德数据，国内小店和新地点更全\n· 免费申请，大约 10 分钟，用你自己的免费额度",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (configured) {
                Spacer(Modifier.size(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilledTonalButton(onClick = ::verify) { Text("验证 Key") }
                    OutlinedButton(onClick = { clear = true }) { Text("清除 Key") }
                }
            }
        }

        // ---- Steps ----
        Step(1, "注册高德开发者", "免费，只需一次")
        PortalCard(Modifier.padding(horizontal = 16.dp)) {
            Body("打开高德开放平台控制台，用支付宝 / 淘宝扫码或手机号登录。首次使用会要求「成为开发者」，按页面提示选择个人开发者并完成实名认证（支付宝登录一般可一步完成）。")
            Spacer(Modifier.size(10.dp))
            FilledTonalButton(onClick = { open(CONSOLE_URL) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("打开高德控制台")
            }
        }

        Step(2, "创建应用", "名称随意")
        PortalCard(Modifier.padding(horizontal = 16.dp)) {
            Body("进入「应用管理 → 我的应用」，点右上角「创建新应用」。应用名称填 Geoshift（随意），应用类型选任意一项，点「新建」。")
        }

        Step(3, "添加 Key", "关键步骤：平台和两项信息要填对")
        PortalCard(Modifier.padding(horizontal = 16.dp)) {
            Body("在刚创建的应用右侧点「添加 Key」：")
            Spacer(Modifier.size(6.dp))
            Body("· Key 名称：随意，例如 手机\n· 服务平台：选「Android 平台」（不要选 Web 服务、Web 端）\n· 发布版安全码 SHA1：复制下方 SHA1 粘贴\n· 调试版安全码 SHA1：同样粘贴这个 SHA1\n· PackageName：复制下方包名粘贴")
            Spacer(Modifier.size(12.dp))
            CopyField("PackageName（包名）", packageName) { copy("包名", packageName) }
            Spacer(Modifier.size(10.dp))
            CopyField("SHA1（本安装包的签名）", sha1.ifEmpty { "读取失败，请重新安装后再试" }, enabled = sha1.isNotEmpty()) { copy("SHA1", sha1) }
            Spacer(Modifier.size(10.dp))
            Text("勾选同意服务条款后点「提交」，列表里会出现一串 32 位的 Key。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Step(4, "填入 Key", if (configured) "更换时填入新 Key" else "复制控制台里的 Key 粘贴到这里")
        PortalCard(Modifier.padding(horizontal = 16.dp)) {
            OutlinedTextField(value, onValueChange = { value = it.trim() }, singleLine = true,
                label = { Text("高德 Android Key") }, placeholder = { Text("32 位字母和数字") },
                visualTransformation = if (configured) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.size(8.dp))
            Row(Modifier.fillMaxWidth().clickable { agreed = !agreed }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(agreed, onCheckedChange = { agreed = it })
                Text("我已阅读并同意启用高德地图 SDK", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            }
            Text(
                "高德地图 SDK（北京高德图强科技有限公司）用于显示官方地图、搜索地点、查询地址和规划路线，可能处理设备与系统信息、应用信息、网络信息、" +
                    "所查看的地图区域以及搜索关键词和坐标。本应用不启用其定位功能。Key 只保存在本机，不进入导出的备份。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { open(PRIVACY_URL) }, contentPadding = PaddingValues(0.dp)) { Text("查看高德隐私政策") }
            Button(
                enabled = value.isNotBlank() && agreed,
                onClick = {
                    val key = value.trim()
                    vm.run<Unit>("高德 Key 已保存，地图已切换到高德官方地图") {
                        setSdkKey(key)
                        updateSettings { it.copy(mapSource = MapSource.AMAP) }
                    }
                    value = ""
                },
                modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 12.dp),
            ) { Text("保存") }
        }

        Step(5, "验证", "确认 Key 可用")
        PortalCard(Modifier.padding(horizontal = 16.dp)) {
            Body("保存后点「验证 Key」。通过后回到地图，图层选「高德地图」即可看到官方地图（保存时已自动切换）。")
            Spacer(Modifier.size(10.dp))
            FilledTonalButton(enabled = configured, onClick = ::verify, modifier = Modifier.fillMaxWidth()) { Text("验证 Key") }
        }

        // ---- FAQ ----
        Row(
            Modifier.padding(start = 20.dp, top = 22.dp, end = 16.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { faq = !faq }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("常见问题", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Icon(if (faq) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, if (faq) "收起" else "展开")
        }
        if (faq) PortalCard(Modifier.padding(horizontal = 16.dp)) {
            Faq("验证提示「包名或 SHA1 不匹配」（错误 1008 / 1001）",
                "控制台里填的 SHA1 或包名和本页不一致。在控制台点 Key 右侧的「设置」核对，改成本页显示的值。从其他地方下载或自己重新编译的安装包签名不同，要以本页显示的 SHA1 为准。")
            Faq("提示「平台不匹配」（错误 1009）", "创建 Key 时服务平台选错了。重新添加一个「Android 平台」的 Key。")
            Faq("地图一片空白或只有网格", "先点「验证 Key」看具体原因；再检查网络。高德在海外基本没有数据，海外地区请切换到「矢量地图」或「全球卫星影像」。")
            Faq("要花钱吗？", "个人开发者有免费额度，自己使用足够。在控制台「流量分析」可以看用量。")
            Faq("能和别人共用一个 Key 吗？", "不建议。每个人用自己的 Key，额度互不影响，也不会因为别人滥用被封。")
            Faq("想换回原来的地图", "点上方「清除 Key」，所有功能回到免 Key 方式；或者只在地图图层里换成其他图层。")
            Text("高德官方说明：", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { open(GUIDE_URL) }, contentPadding = PaddingValues(0.dp)) { Text("获取 Android Key（高德文档）") }
        }

        // ---- Web service key (older, optional) ----
        Row(
            Modifier.padding(start = 20.dp, top = 14.dp, end = 16.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { webKey = true }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Web 服务 Key（旧方式）", style = MaterialTheme.typography.titleSmall)
                Text(if (s.mapKeyConfigured) "已设置。填了 Android Key 后不再需要" else "只改善搜索和路线，不改变地图。一般不需要",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.outline)
        }
    }

    if (webKey) MapKeyDialog(configured = s.mapKeyConfigured, onDismiss = { webKey = false }, onMessage = vm::toast,
        onTest = ::verify,
        onSave = { key -> vm.run(if (key.isEmpty()) "已清除 Web 服务 Key" else "Web 服务 Key 已保存") { setMapKey(key) } })
    if (clear) ConfirmDialog("清除高德 Key", "清除后「高德地图」图层改回瓦片地图，搜索和路线改用免 Key 服务。", confirm = "清除", danger = true,
        onDismiss = { clear = false }) {
        vm.run("已清除高德 Key") { setSdkKey("") }
    }
}

@Composable
private fun Body(text: String) =
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)

@Composable
private fun Step(n: Int, title: String, subtitle: String) {
    Row(Modifier.padding(start = 20.dp, top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(24.dp).background(MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) {
            Text("$n", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A value to paste into AMap's console, with a copy button. */
@Composable
private fun CopyField(label: String, value: String, enabled: Boolean = true, onCopy: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
            .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SelectionContainer {
                Text(value, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace))
            }
        }
        IconButton(enabled = enabled, onClick = onCopy) { Icon(Icons.Rounded.ContentCopy, "复制") }
    }
}

@Composable
private fun Faq(question: String, answer: String) {
    Column(Modifier.padding(bottom = 12.dp)) {
        Text(question, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.size(2.dp))
        Text(answer, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
