# 2026-11-11：系统进程内 getter 的反内联加固（deoptimize）

## 背景

对照达达骑士、钉钉及两款开源模块（见 [检测方对照](../references/detection-review-2026-11-11.md)）。两款开源模块里，HLocation 用了 `XposedBridge.deoptimizeMethod` 防止 ART 把被 hook 的短 getter 内联绕过；Geoshift 此前未使用。

## 问题

主路径 `SystemSpoofHooks`（system_server 里对 Binder 回调代理 `ILocationListener$Stub$Proxy.onLocationChanged` 等的改写）已由探针验证未被绕过，无需处理。但 `PortalHook` 与 `WifiPrivacyHooks` 只在系统进程（uid < 10000：system_server、com.android.phone 等）里安装，其中**直接 hook 的取值 getter** 在同进程内被框架本地调用时，ART 可能内联调用方、走原始方法体而跳过 hook。这部分未被探针覆盖。

受影响的 getter：

- `PortalHook`：`LocationManager.getLastKnownLocation` / `getLastLocation`、`WifiInfo.getSSID` / `getBSSID`、`WifiManager.getScanResults`、`TelephonyManager.getAllCellInfo`。
- `WifiPrivacyHooks`：服务端 `getScanResults`、`getConnectionInfo`。

注册类方法（`requestLocationUpdates`、`registerTelephonyCallback`、`listen` 等）不是取值点，不处理。

## 改动

- 新增 `HookUtil.java`：`deoptimize(Member)` / `deoptimize(Set<Unhook>)` / `deoptimizeN(Set<Unhook>)`。通过反射解析 `XposedBridge.deoptimizeMethod(Member)`——LSPosed/Vector 有，经典 Xposed 无则返回 null 并跳过，保持旧 API 兼容。解析只做一次并缓存。
- 在上述 getter 的 `hookAllMethods(...)` 返回值上调用 `HookUtil.deoptimize(...)`，强制其调用方走解释器路径。`WifiPrivacyHooks` 需要命中数的一处用 `deoptimizeN` 返回 size。

性质：可靠性加固，只强制慢路径，不改变任何返回结果。deoptimize 在进程生命周期内不可逆，但只作用于这几个低频 getter，开销可忽略。主路径 `SystemSpoofHooks` 未改。

## 验证

- `./gradlew :app:compileDebugJavaWithJavac --offline` 通过。
- 运行时效果（内联绕过是否真实发生、deoptimize 后是否消除）尚未用探针验证，需在装有 LSPosed/Vector 的真机或模拟器上复测。

## 未解决

公网 IP 反查、传感器物理一致性、框架自身隐藏——均不在本次范围，见对照文档"未解决"一节。
