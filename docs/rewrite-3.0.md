# Geoshift 3.0 重写设计

目标：功能对齐 2.7.6，代码全部用 Kotlin 重写，Xposed 部分改用 libxposed API 102（现代 API）。
不兼容 2.x 的数据格式；旧数据只能通过 2.x 导出、3.0 导入（导入兼容 `format=geoshift` v1）。

## 框架要求

- 需要支持 libxposed API 101+ 的框架（Vector 等）。原版 LSPosed 1.x 只支持旧 API，3.0 不再支持。
- `module.prop`：`minApiVersion=101`、`targetApiVersion=102`、`staticScope=true`。
- 作用域（`scope.list`）：`system`（系统框架）、`com.android.phone`、`com.android.bluetooth`、
  `com.google.android.bluetooth`。

## 分层

```
core/      纯 Kotlin，不依赖 Android：坐标、噪声、模拟定位点、卫星天空、NMEA、路线回放、配置模型。
           全部可在 JVM 上做单元测试（app/src/test）。
xposed/    模块入口与 hook。Hook DSL（before/after + 失败即阻断）、反射查找（记录缺失入口）、
           配置读取（RemotePreferences）、诊断上报；system/、phone/、bluetooth/ 三个进程各自的 hook。
data/      App 侧：记录存储（JSON 文件）、设置/运行状态（StateFlow）、与框架的桥（XposedService）、
           网络（高德、OSM 搜索/反查、路线规划、出口 IP）、真实 Wi-Fi/基站扫描。
service/   SimulationService（前台服务：Android 模拟位置后端的时钟）、JoystickService（悬浮摇杆）。
ui/        Compose 界面，视觉沿用 2.7 设计，状态改为直接订阅 data 层 StateFlow。
```

## 配置通道

| 方向 | 2.x | 3.0 |
| --- | --- | --- |
| App → 所有被 hook 进程：模拟配置 | `sendExtraCommand SET`，电话/蓝牙进程每 500 ms `GET` 轮询 | RemotePreferences 组 `geoshift`：键 `config`（SimConfig JSON），框架推送变更，进程内缓存 |
| App → system_server：路线点 | `sendExtraCommand ROUTE` | 远程文件 `route-<id>.json`，config 里带 `routeId` |
| 摇杆高频位置 | 每 100 ms 走 SET | `sendExtraCommand("geoshift","MOVE")`，仅本应用 uid |
| 诊断 | `PING` 取 system_server 汇总；电话/蓝牙进程 `REPORT` | 不变（仅本应用 / 系统 uid 可用，其他调用者得到原生行为） |

配置有效性：
- `config.boot` 必须等于当前 `Settings.Global.BOOT_COUNT`，重启后自动失效（2.x 的「进程重启即停止」）。
- 不再与 App 进程存活绑定（与 PortalDev 一致）：App 被冻结或杀掉，模拟继续，路线由 system_server 自行回放。
  需要停止时打开 App 停止，或重启手机。

## 失败即阻断

libxposed 默认 PROTECTIVE 模式下 hook 抛异常会当作没 hook，等于泄露真实数据。
所以每个 hook 体都在 DSL 内部捕获异常，并按该入口的阻断策略返回空/默认值，同时上报诊断。

## 反射入口登记

所有通过名字查找的系统类、方法、字段都经 `Reflect` 查找；找不到时记录到诊断「缺失入口」。
2.7.2 的 `getLocations` 问题会直接显示在诊断里，而不是在运行时才以异常形式出现。

## 测试

- JVM 单元测试（`./gradlew testDebugUnitTest`）：core 全部逻辑。
- 模拟器（Vector）+ GeoProbe：系统层效果。
