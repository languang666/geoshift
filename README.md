# Geoshift（2.5.4，原 Portal Compose 版）

原生 Kotlin + Jetpack Compose 重写的虚拟定位工具，界面布局和使用流程参照 PortalDev：
底部四个标签：地图、历史、模拟、设置；Wi-Fi 模拟、基站模拟、权限与模块引导是二级页面。

- 包名 `app.geoshift`。1.x 网页版（`local.portal.rebuild`）是另一个应用，不能覆盖安装；记录用「设置 → 导出备份 / 导入备份」迁移。2.x 各版本之间可以直接覆盖升级。
- Java 底层包含模拟服务、摇杆、LSPosed 系统注入、设备扫描、地图网络、坐标算法和本地存储；界面与业务操作使用 Kotlin。
- **全部功能无需任何 Key**。高德 Web 服务 Key 是可选项，只用来提升国内搜索、地址和道路数据。2.5.0 起不再内置高德地图 SDK，也不再需要 Android Key（升级时会清除旧版保存的 Android Key）。
- 地图图层（默认高德地图；地图右侧图层按钮或「设置 → 地图图层」切换，选择会保存，切换时保留中心和缩放。地图显示不出来时，搜索栏下方有可关闭的小提示，可一键换图层或填写高德 Key）：
  - 默认地图：OpenStreetMap 瓦片（osmdroid），深色模式对瓦片反色。OSM 官方瓦片没有高清版，需要清晰显示时请用矢量地图。
  - 矢量地图：MapLibre Native + OpenFreeMap 矢量瓦片，文字清晰、可旋转倾斜，深色模式使用深色样式；地名优先显示中文（name:zh-Hans / name:zh）。路线画在文字下方，不遮挡路名。
  - 高德地图：在线道路瓦片，由 MapLibre 以「下一级瓦片按 128 dp 显示」的方式绘制（与高德网页版在高清屏上的做法相同），高分辨率屏上文字和道路清晰，可旋转；深色模式反色显示。标记、路线和相机转换为 GCJ-02 显示，点击选点转换回 WGS-84。
  - 全球卫星影像：Esri World Imagery（Esri、Maxar、Earthstar Geographics 及 GIS 用户社区），同样取下一级影像显示，清晰度约为普通瓦片的两倍；覆盖和分辨率因地区而异，不随深色模式反色。
  - 存储和模拟定位始终使用 WGS-84。
- 地点搜索 / 地址显示：未填 Key 时用 Photon（搜索，按当前地图中心就近排序）和 Nominatim（地址，限速每秒 1 次），均为 OpenStreetMap 数据；填了高德 Web 服务 Key 时改用高德，坐标按 GCJ-02 ↔ WGS-84 换算。Key 只保存在应用私有存储，不进入备份或系统定位配置。
- 沿道路路线：路线编辑时可选「直线 / 步行 / 骑行 / 驾车」。选道路方式后，相邻两个途经点之间按真实道路连接：填了高德 Web 服务 Key 时用高德路径规划，否则用 FOSSGIS 提供的公共 OSRM 服务（OpenStreetMap 数据）。保存的路线含完整道路折线（最多 10,000 点），播放时沿道路移动、不再联网；同时记录途经点和出行方式。
- 不包含：原版的许可证服务、Root 检测、"网络降级到 2G"（会改动手机真实网络）、广告和统计 SDK。

## 系统定位

支持 LSPosed 及其后继 Vector（JingMatrix/Vector，兼容旧版 Xposed API）。作用域勾选「系统框架」「电话服务」和「蓝牙」，不需要勾选目标应用（与原版 PortalDev 的做法一致）。模拟期间所有改写都在系统侧完成：

- 定位：system_server 按上报间隔主动向 gps / network / fused 推送模拟位置，真实上报直接丢弃（替换真实上报会让监听被动位置的融合定位服务反复回环）；发给每个应用的定位回调、单次定位、PendingIntent 和缓存位置都是模拟位置，不带模拟标记。
- 定位数据模仿真实接收机：静止时位置在几米内缓慢漂移，精度、海拔和速度随时间轻微波动；网络定位精度为 20 米左右，且不带海拔和速度。
- 卫星：应用收到的 GNSS 状态为模拟卫星（GPS、北斗、Galileo、GLONASS，各用自己的载波频率，位置随时间缓慢移动，信号强度随仰角变化）；NMEA 语句（含真实坐标）、GNSS 原始测量和导航电文（足以反算真实位置）不再下发。
- Wi-Fi：扫描结果只含 Wi-Fi 预设（未设置则为空），连接信息为预设 SSID/BSSID 或隐藏 BSSID；通过 ConnectivityManager 网络回调读到的 Wi-Fi 信息同样替换。
- 小米 HyperOS / MIUI：系统自带的「模糊定位」服务（MiuiBlurLocationManager）返回的位置与基站同样替换为模拟值，并忽略其 GPS 刷新。
- 蓝牙：模拟期间应用扫描不到蓝牙 LE 设备（回调和 PendingIntent 两种方式），防止定位 SDK 用附近信标反查位置；已连接的设备不受影响，系统设置里配对新设备（经典蓝牙发现）不受影响。需勾选「蓝牙」作用域。
- Google Play 服务融合定位（FusedLocationProviderClient）：其输入来自上述系统数据，模拟期间返回模拟位置，已用探针验证。
- 无法伪装：公网 IP（定位 SDK 可据此判断城市，需要代理 / VPN）、运营商代码。
- 基站：电话服务返回基站预设（未设置则为空），包括 getAllCellInfo、getCellLocation、requestCellInfoUpdate、getServiceState 和基站 / 网络状态变化回调。运营商代码（getNetworkOperator）来自系统属性，在系统侧无法修改，仍是真实 SIM 卡的值。

以上环境伪装由「设置 → 伪装 Wi-Fi、基站、卫星和蓝牙」控制，默认开启；不改变真实网络连接和硬件。目标应用的 hook 仍保留，只在勾选目标应用时额外生效。

「模拟 → 系统定位诊断」显示真实上报拦截数、推送成功／失败、应用回调替换计数、Wi-Fi 与电话服务报告。

2.5.1 已在 Android 15 模拟器（Magisk 31.1 + Vector v2.2，勾系统框架、电话服务和蓝牙；2.4.3 在 LSPosed 1.11.0 上同样验证过）上用独立探针应用（tests/probe）验证：定位 8 种读取方式、Google 融合定位 4 种读取方式、卫星、NMEA、GNSS 原始测量、Wi-Fi 3 种读取方式、基站 6 种读取方式在未设预设和设了预设时均为模拟值，停止后恢复真实值。蓝牙伪装入口已在蓝牙进程安装，但模拟器周围没有蓝牙设备，拦截效果需在真机验证；小米模糊定位服务只存在于小米 ROM，模拟器无法验证；尚未在 Redmi K70 实机验证。实现参考见 [开源项目对照](tests/OPEN_SOURCE_REVIEW.md)。

## 构建与验证

需要 JDK 17+、Android SDK（platform 37、build-tools 37.0.0）。构建工具链：Gradle 9.8.2、AGP 9.4.1（内置 Kotlin 2.4.21）、Compose BOM 2026.09.00。

```
EMULATOR=1 ./build-release.sh   # 额外打包 x86_64 原生库，产出 Geoshift-<版本>-emulator.apk，仅用于模拟器测试
./build-release.sh        # 产出 Geoshift-<版本>.apk（仅 ARM），使用 ~/.geoshift-signing 中的私钥签名（可用 PORTAL_KEYS 指定）
```

只编译不签名：`./gradlew assembleRelease`。

## 目录

```
app/src/main/java/app/geoshift/
  *.java                 底层：模拟服务、摇杆、LSPosed hook（系统框架 / 电话服务 / 蓝牙）、扫描、地图网络、坐标算法
  MainActivity.kt        Compose 入口、权限申请、备份导入导出
  data/                  PortalState（类型化状态）、PortalActions（全部业务操作与校验）、
                         RoadRouter（沿道路路线）、OpenGeocoder（免 Key 搜索与地址）
  ui/                    PortalApp（导航）、map/（VectorMap：MapLibre 图层；OsmMap：OSM 默认图层）、
                         history/ mock/ settings/ setup/ 各页面、theme/、components/
tests/GeoChecks.java     坐标算法与地图切换坐标回归测试
tests/bridge/            系统 hook 逻辑回归（API 替身，run-bridge-checks.sh）
tests/probe/             探针应用 GeoProbe：以普通应用身份读取全部定位线索，logcat 标签 GEOPROBE
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
MapLibre Native 为 BSD 2-Clause License；矢量瓦片 © OpenFreeMap © OpenMapTiles。搜索、地址和道路路线分别使用 Photon（Komoot）、Nominatim 和 FOSSGIS OSRM 公共服务，请遵守其使用政策。
界面流程参照 fuqiuluo/Portal（Apache License 2.0）与 PortalDev；代码为本工程独立实现，未复制原版 bundle、DEX 或原生库。
