# Geoshift（2.4.2，原 Portal Compose 版）

原生 Kotlin + Jetpack Compose 重写的虚拟定位工具，界面布局和使用流程参照 PortalDev：
底部四个标签：地图、历史、模拟、设置；Wi-Fi 模拟、基站模拟、权限与模块引导是二级页面。

- 包名 `app.geoshift`。1.x 网页版（`local.portal.rebuild`）是另一个应用，不能覆盖安装；记录用「设置 → 导出备份 / 导入备份」迁移。2.x 各版本之间可以直接覆盖升级。
- Java 底层包含模拟服务、摇杆、LSPosed 系统注入、设备扫描、地图网络、坐标算法和本地存储；界面与业务操作使用 Kotlin。
- 基础地图：osmdroid 原生控件，可通过地图右侧图层按钮或「设置 → 地图图层」切换默认 OpenStreetMap 与高德道路地图，选择会保存。基础地图显示无需 Key，深色模式会对瓦片做反色处理。
- 全球卫星影像：可在图层菜单选择 Esri World Imagery，影像源为 Esri、Maxar、Earthstar Geographics 及 GIS 用户社区，覆盖和分辨率因地区而异。使用受 Esri 服务许可和网络可用性约束，影像不会随深色模式反色。
- 官方高清地图：在「设置 → 高德高清地图 Android Key」配置 Android 平台 Key 并同意 SDK 说明后，高德图层使用官方 3D 矢量地图 SDK 11.3.100，支持清晰文字、平滑缩放、旋转、倾斜和原生夜间地图。未配置时继续使用基础瓦片地图。
- 两类 Key 分开保存在应用私有存储：Android Key 用于官方地图；Web 服务 Key 用于搜索与地址。两处填写页均附申请教程和官方链接，Android 教程自动显示并支持复制当前安装包的包名与 SHA1。Key 不进入备份或系统定位配置。
- 高德图层使用在线道路瓦片接口；标记、路线和相机转换为 GCJ-02 显示，点击选点转换回 WGS-84。切换时保留相同地理中心和缩放，存储和模拟定位始终使用 WGS-84。瓦片可用性取决于网络和地图服务。
- 地点搜索 / 地址显示：可选，填写高德 Web 服务 Key 后使用；坐标按 GCJ-02 ↔ WGS-84 换算。
- 不包含：原版的许可证服务、Root 检测、"网络降级到 2G"（会改动手机真实网络）、广告和统计 SDK。

## 系统定位

LSPosed 作用域只需勾选「系统框架」和「电话服务」，不需要勾选目标应用（与原版 PortalDev 的做法一致）。模拟期间所有改写都在系统侧完成：

- 定位：system_server 按上报间隔主动向 gps / network / fused 推送模拟位置，真实上报直接丢弃（替换真实上报会让监听被动位置的融合定位服务反复回环）；发给每个应用的定位回调、单次定位、PendingIntent 和缓存位置都是模拟位置，不带模拟标记。
- 定位数据模仿真实接收机：静止时位置在几米内缓慢漂移，精度、海拔和速度随时间轻微波动；网络定位精度为 20 米左右，且不带海拔和速度。
- 卫星：应用收到的 GNSS 状态为模拟卫星（GPS、北斗、Galileo、GLONASS，各用自己的载波频率，位置随时间缓慢移动，信号强度随仰角变化）；NMEA 语句（含真实坐标）不再下发。
- Wi-Fi：扫描结果只含 Wi-Fi 预设（未设置则为空），连接信息为预设 SSID/BSSID 或隐藏 BSSID；通过 ConnectivityManager 网络回调读到的 Wi-Fi 信息同样替换。
- 基站：电话服务返回基站预设（未设置则为空），包括 getAllCellInfo、getCellLocation、requestCellInfoUpdate、getServiceState 和基站 / 网络状态变化回调。运营商代码（getNetworkOperator）来自系统属性，在系统侧无法修改，仍是真实 SIM 卡的值。

以上环境伪装由「设置 → 伪装 Wi-Fi、基站和卫星」控制，默认开启；不改变真实网络连接和硬件。目标应用的 hook 仍保留，只在勾选目标应用时额外生效。

「模拟 → 系统定位诊断」显示真实上报拦截数、推送成功／失败、应用回调替换计数、Wi-Fi 与电话服务报告。

2.4.2 已在 Android 15 模拟器（Magisk 31.1 + LSPosed 1.11.0，只勾系统框架和电话服务）上用独立探针应用（tests/probe）验证：定位 8 种读取方式、卫星、NMEA、Wi-Fi 3 种读取方式、基站 6 种读取方式在未设预设和设了预设时均为模拟值，停止后恢复真实值。尚未在 Redmi K70 实机验证。实现参考见 [开源项目对照](tests/OPEN_SOURCE_REVIEW.md)。

## 构建与验证

需要 JDK 17+、Android SDK（platform 35、build-tools 35.0.0）。

```
./build-release.sh        # 产出 Geoshift-<版本>.apk，使用 ~/projects/portal-rebuild/private-signing 中的私钥签名
```

只编译不签名：`./gradlew assembleRelease`。

## 目录

```
app/src/main/java/app/geoshift/
  *.java                 底层：模拟服务、摇杆、LSPosed hook、扫描、地图网络、坐标算法
  MainActivity.kt        Compose 入口、权限申请、备份导入导出
  data/                  PortalState（类型化状态）、PortalActions（全部业务操作与校验）
  ui/                    PortalApp（导航）、map/ history/ mock/ settings/ setup/ 各页面、theme/、components/
tests/GeoChecks.java     坐标算法与地图切换坐标回归测试
```

坐标回归测试（无需 Android 或网络）：

```sh
javac -d /tmp/geoshift-map-checks app/src/main/java/app/geoshift/Geo.java app/src/main/java/app/geoshift/MapSource.java tests/GeoChecks.java
java -cp /tmp/geoshift-map-checks GeoChecks
```

Android 静态检查：`./gradlew lintRelease`。请与发布构建串行执行，避免共享编译目录发生冲突。

系统辅助逻辑回归：`bash tests/run-bridge-checks.sh`。这些测试使用 API 替身与模拟 hook 调度器，不能替代真实 Android 16 / LSPosed 验证。

## 许可

本项目代码以 Apache License 2.0 发布，见 [LICENSE](LICENSE)。

osmdroid、AndroidX、Kotlin、Xposed API（仅编译期）均为 Apache License 2.0；地图数据 © OpenStreetMap contributors（ODbL）。
高德地图 SDK 为高德开放平台专有 SDK，使用受其 SDK 许可及服务条款约束；隐私政策：https://lbs.amap.com/pages/privacy/ 。仅在用户配置 Android Key 并单独同意 SDK 说明后初始化，不启动 SDK 自动定位。官方地图使用 ARM 原生库，安装包因此比基础图版本大。
界面流程参照 fuqiuluo/Portal（Apache License 2.0）与 PortalDev；代码为本工程独立实现，未复制原版 bundle、DEX 或原生库。
