# 检测方对照：达达骑士 / 钉钉，及两款开源模块

日期：2026-11-11。路径以仓库 `source/` 为基准。

方法：对四个 APK 解包后读取 `classes*.dex` 的字符串、`lib/*/` 的 `.so` 名称和 `AndroidManifest.xml`，用特征词统计。**没有执行这些 APK，也没有取得方法体或反混淆结果**；以下字符串命中代表"存在该入口的可能"，不代表其算法、触发条件或实际效果已被验证。两款商业 App 均带商业加固与原生安全 SDK，Java 层字符串只是冰山一角。

参考文件与 SHA-256：

- 达达骑士 12.32.1：`dada_knight-…-20260903211137500.apk`
  `8224c1754ae2caf674d2c93d5ed6731545946f1543454762f1e678b971391d14`
- 钉钉：`rimet_36180121811227.apk`
  `e47311f5087e5241608d62ef0f1c320b47cbaaf129a019185a5e3cf5ccc06a3d`
- HLocation 1.7.0：`HLocation-v1.7.0.apk`
  `73f496dd6a98e3ef8eeb8fa6d6f6fad1e175aa7d722eabf050851cfa09ca3fc2`
- XposedFakeLocation：`XposedFakeLocation.apk`
  `cb8336adff4f4c8c7be9311d84bd09d1f267bdab4b34154df9adca1d1dad8251`

## 一、达达 / 钉钉的检测面（事实：字符串/so 命中）

两者都挂了商业安全 SDK：钉钉是阿里系（`com.alibaba.wireless.security` / JAQ / mPaaS，`libAPSE_7.1.0.so`、`libap_sec_store.so`、`libHolmesJni.so`），达达带独立的 `libAntiCheat.so` + `libdada_secure_1.0.3.so` + `libzxprotect.so`。命中的检测类别：

| 类别 | 命中（dex 字符串 / so 名） | Geoshift 现状（推断） |
| --- | --- | --- |
| 模拟标记 | `isFromMockProvider`、`isMock`、`mock_location` 两侧各十余处 | 已挡——系统侧上报不带 mock 标记 |
| 找 Hook 框架本身 | `de.robv.android.xposed.*`、`XposedBridge.jar`、扫包 `getInstalledPackages`（找 LSPosed/LSPatch/Magisk 安装器），`com.topjohnwu.magisk`、`lspatch` | 框架层问题，模块无法解决；靠 LSPosed 隐藏作用域 + Magisk DenyList/Shamiko |
| 栈扫描 | `getStackTrace` / `StackTraceElement`，钉钉约 97 处 | 架构优势——Geoshift 不注入 App 进程，目标进程栈内无本模块帧 |
| Native 自检 | `/proc/self/maps`、`/proc/self/status`（TracerPid）、`/proc/self/task`、`app_process` | 从 native 读 /proc，Java 模块藏不住框架痕迹；同属框架层 |
| IP 反查城市 | 钉钉 `libipdetector.so` + `libipstack-detect.so`；达达 aliyun 定位 | 未覆盖——公网 IP 无法在模块内伪装，需代理/VPN |
| 传感器/物理一致性 | `SensorManager`、`TYPE_ACCELEROMETER`/`TYPE_GYROSCOPE`、`elapsedRealtimeNanos` | 部分——已做漂移/抖动与时间重放，但未伪造加速度计；静止设备配路线移动可被融合识别 |
| ROM/Root 泛化信号 | `test-keys`、`Build.FINGERPRINT`、`selinux` | 框架层，Magisk 用户通病 |

结论（推断）：Geoshift 的数据层（定位/卫星/NMEA/批量回调/基站一致性）大概率不会在"数据"上露馅；真正可据以判定的是**框架指纹（/proc、扫包）、公网 IP、传感器物理**——前者不属于本模块职责，后两者是模块侧仍可改进的方向。

## 二、两款开源模块（事实：命中）

两者都是**经典的注入目标 App 进程**的 Hook 模块，直接 hook `Location.getLatitude/getLongitude`、`getLastKnownLocation`、`setIsFromMockProvider`。这种进程内注入恰好会被上面的栈扫描 + `/proc/self/maps` 看见——在隐蔽架构上**弱于** Geoshift 的"仅系统侧改写、不进普通 App 进程"。

- HLocation 1.7.0：`classes.dex` 命中 `deoptimize` 7 处（即 `XposedBridge.deoptimizeMethod`），用于防止 ART 把被 hook 的短 getter 内联绕过。它还内置了高德地图 SDK（assets 里的 amap 资源与 `Sensor`/`Proxy` 字符串来自该 SDK，非反检测逻辑）。
- XposedFakeLocation：更精简的纯模块，无 `deoptimize`，同样直接 hook getter。

两者对 **IP、传感器都未做任何处理**，这两块无现成实现可借鉴。

## 三、据此对 Geoshift 的改动

唯一可直接借鉴的技术点是 `deoptimizeMethod`。Geoshift 主路径（`SystemSpoofHooks` 的 Binder 回调代理改写）已由探针验证未被绕过，不动；但 `PortalHook` 与 `WifiPrivacyHooks` 在系统/电话进程内**直接 hook 的取值 getter**（`LocationManager.getLastKnownLocation`、`WifiInfo.getSSID/getBSSID`、`WifiManager.getScanResults`、`TelephonyManager.getAllCellInfo`、服务端 `getConnectionInfo`），在同进程内被本地调用时存在被 ART 内联绕过的风险，未经探针覆盖。

已新增 `HookUtil.deoptimize(...)`（反射调用 `XposedBridge.deoptimizeMethod`，LSPosed/Vector 有、经典 Xposed 无则跳过，保持兼容），只对上述 getter 生效，强制其调用方走解释器路径。属可靠性加固，不改变返回结果。

未解决（不在本次改动范围）：
- 公网 IP 反查——模块内无法伪装，需在 App 侧引导代理/VPN，或校验出口 IP 城市与模拟点是否一致。
- 传感器物理一致性——"沿路线移动"时未伪造加速度计/计步，运动类 App 的融合仍可识别。
- 框架自身隐藏（/proc、扫包、栈扫描抓的是 LSPosed/Magisk 本体）——靠 LSPosed 隐藏作用域与 Magisk DenyList/Shamiko，非 Geoshift 职责。

关联：[优化记录](../reviews/2026-11-11-anti-inline-deoptimize.md)。
