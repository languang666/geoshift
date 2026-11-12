# 2026-11-12：NR 基站模拟与预设保存

范围：Geoshift 3.0.7，基站扫描、预设、配置和系统侧模拟。尚未完成真机验证。

## 问题和修改

原实现只模拟 LTE 和 GSM，NR 仅能扫描；选中 NR 后编辑器按 LTE 保存，并沿用可能超出 LTE 范围的 NR 参数。表单提交后立即关闭，即使保存失败也不能保留内容。

- `core/SimConfig.kt`：增加 `CellType.NR`，小区标识继续使用 `Long`，兼容原有 LTE/GSM 配置。
- `data/Models.kt`：NR 扫描数据可转换为预设，用于环境记录载入；不完整或不合法数据仍被过滤。
- `data/Validate.kt`：NR 的 NCI、TAC、PCI、NR-ARFCN 分别限制在 0–68719476735、0–16777215、0–1007、0–3279165；SS-RSRP 为 -156 至 -31 dBm。未知值需要用户填写，不推算 LTE 参数。
- `ui/mock/SignalScreens.kt`：增加 NR 选择、PCI 编辑及提示，保存成功后关闭表单，失败时保留输入并显示错误；扫描 NR 默认保留其类型。
- `data/Geoshift.kt`：Android 10 以下拒绝新建 NR 预设。
- `xposed/Fakes.kt`：构造 `CellInfoNr`，设置 NCI、TAC、PCI、NR-ARFCN 和 SS-RSRP，复用现有电话服务和系统回调路径。使用反射读取 NCI 核对，失败沿用现有阻止真实数据返回的逻辑。

Android API 29 起提供 NR 类型，字段范围依据 [Android CellIdentityNr 文档](https://developer.android.com/reference/android/telephony/CellIdentityNr)。系统内部字段是非公开接口，须针对 Android 版本及厂商 ROM 实测；网络注册制式、运营商代码与真实网络可能不一致，本次没有改变真实网络。

## 验证与后续

新增 `SimConfigTest.nrRoundTripPreserves36BitIdentity` 检查 36 位 NCI 的配置序列化及边界。运行 `bash tests/run-checks.sh`，并在安装模块、重启设备后用 GeoProbe 检查 `getAllCellInfo`、刷新回调、监听回调和 ServiceState；核对 NCI、TAC、PCI、频点与信号，同时检查 LTE/GSM 回归及停止后的真实数据恢复。Android 模拟器不等于 5G 真机，Redmi K70 / HyperOS 的 NR 扫描、反射字段和实际应用结果仍需实测。

本次 `./gradlew assembleRelease -Pemulator` 构建通过；`bash tests/run-checks.sh` 共 29 项通过。补充设备验证：Android 15 x86_64 模拟器采用 Magisk + Vector（历史 KernelSU 环境测试后已还原为 Magisk）；通过 `adb root` 而非普通 shell `su` 取得权限。测试包安装并重启后，Vector 日志显示模块加载成功。界面创建并应用 NR 预设，NCI 45678912345、TAC 12345；GeoProbe 的 getAllCellInfo、requestCellInfoUpdate、CellInfo 回调、ServiceState 查询及回调均返回该 NR 身份。旧式 getCellLocation 为 [12345,-1,0]；运营商代码仍是真实值。此结果只证明模拟器上的指定路径，信号强度、扫描导入、LTE/GSM 回归及真机 ROM 仍需验证。模拟器测试后修正了 NR 编辑页漏显示 PCI 的问题，未重新进行设备测试。

测试包：`../releases/Geoshift-3.0.6-NR-test.apk`（包含 arm64-v8a / x86_64），SHA-256：`8fc604cc716cb165786bbb43ceee3bbe2dbce2fcf65067a544daa0e5ad8463f4`。签名证书与既有 3.0.6 一致，zipalign 检查通过。该包用于测试，未发布。不要把编译成功写成设备验证成功；私钥和真实小区信息不要写入记录。

正式版构建：`../releases/Geoshift-3.0.7.apk`，SHA-256 `720aecdd8abf530640250451c22fd6d4c55b4766d8313bb4359a68cab2c12928`。版本号 47，仅包含 arm64-v8a；签名证书与 3.0.6 一致，zipalign 验证通过。正式构建通过，PCI 显示修复后 29 项单元测试通过。

## 复查修复（3.0.7 之后）

- `xposed/Fakes.kt`：Android 10+ 的 `CellSignalStrength*` 把信号格数缓存在 `mLevel`，只改 dBm 时 `getLevel()` 一直是 0（LTE/GSM 同样受影响）。现按 AOSP 默认门限写入 `mLevel`（NR SS-RSRP −110/−90/−80/−65，LTE RSRP −115/−105/−95/−85，GSM RSSI −107/−103/−97/−89）；改完字段后调用 `updateGlobalCellId` 刷新全局小区 ID。
- `data/DeviceScanner.kt`：NR 扫描的 SS-RSRP 不可用时改用 CSI-RSRP。
- `data/Models.kt`：环境记录里的小区信号不可用（`Int.MAX_VALUE`）时按 −85 dBm 载入，不再整条丢弃。
- `ui/mock/SignalScreens.kt`：信号强度留空按 −85 dBm 保存（3.0.7 会报“超出范围”）；填了非数字仍报错。
- GeoProbe 输出补充每个小区的 dBm 和格数。

验证：`bash tests/run-checks.sh` 30 项通过（新增 `scannedCellWithoutSignalKeepsIdentity`）。Android 15 x86_64 模拟器 + Magisk + Vector 2.2：
- NR（NCI 45678912345、TAC 12345、PCI 500、NR-ARFCN 627264、信号留空）：getAllCellInfo、requestCellInfoUpdate、CellInfo 回调、ServiceState 查询/回调都返回该身份，dbm=−85、level=2；getCellLocation [12345,-1,0]。编辑页 PCI 正常显示。
- LTE（CI 123456789、TAC 2222、PCI 100、EARFCN 1850、−100）：各路径一致，level=2；getCellLocation [2222,123456789,0]。
- GSM（LAC 3333、CID 4444、ARFCN 60、−95）：各路径一致，level=3。
- 必填项缺失时保存失败、表单保留输入。关闭基站模拟后各路径恢复为模拟器真实小区（310/260）。

测试备注：
- 用 adb 安装时，若 APK 旁有 `.idsig` 会走增量安装，开机早期 APK 内容未就绪，Vector 无法加载到 system_server（只加载电话/蓝牙进程）。要用 `adb install --no-incremental`。
- 测试中出现一次 system_server watchdog 重启：android.fg 持有 GNSS 监听锁同步调用 HAL `GnssMeasurementInterface.close`，HAL 回调线程在 `reportMeasurementData` 等同一把锁。死锁环里没有 Geoshift 帧，由反复强停 GeoProbe（注册了 GNSS 原始测量）触发，判断为 AOSP/模拟器 GNSS HAL 问题，未在真机复现。
- 扫描导入 NR（模拟器无 NR 小区）和 Redmi K70 真机仍未验证。
