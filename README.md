# Geoshift（2.7.1，原 Portal Compose 版）

原生 Kotlin + Jetpack Compose 编写的虚拟定位工具，使用流程参照 PortalDev，界面在 2.7.0 重新设计。

底部四个标签：地图、控制台、收藏、设置。地图负责选点、搜索（地名或「纬度, 经度」）、画路线和开始/停止；控制台集中显示模拟状态和各项模拟开关（定位、摇杆、Wi-Fi、基站、卫星）、路线播放与系统诊断；Wi-Fi 模拟、基站模拟、权限与模块引导是二级页面。

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

支持 LSPosed 及其后继 Vector（JingMatrix/Vector，兼容旧版 Xposed API）。作用域勾选「系统框架」「电话服务」和「蓝牙」（模块已声明推荐作用域，含 system 与 com.android.providers.telephony 两种写法），不需要勾选目标应用（与原版 PortalDev 的做法一致）。模拟期间所有改写都在系统侧完成：

- 定位：system_server 按上报间隔主动向 gps / network / fused 推送模拟位置，真实上报直接丢弃（替换真实上报会让监听被动位置的融合定位服务反复回环）；发给每个应用的定位回调、单次定位、PendingIntent 和缓存位置都是模拟位置，不带模拟标记。
- 定位数据：每次开始模拟生成一个随机种子，静止时位置在几米内平滑随机漂移，精度、海拔和静止速度随时间轻微波动；随机过程可按时间重放，gps / network / fused 和 NMEA 共用同一份结果。可选的额外抖动不超过 1 米，并在时间上平滑变化，避免逐秒跳点。网络定位精度为 20 米左右，且不带海拔和速度。这是有限的合成误差模型，不等同真实接收机的全部特征。
- 不受 App 休眠影响：App 把配置和一个进程令牌交给系统侧，只要 Geoshift 进程还在，熄屏、休眠或被后台冻结时系统侧都继续模拟；进程被杀才恢复真实位置。
- 路线由系统侧播放：路线点只发送一次，系统侧按时间自己计算当前位置，App 被冻结时路线照样前进。速度在设定值的 80%–120% 之间平滑波动，App 界面与系统侧用同一公式，位置一致。
- 应用开始监听定位时，系统侧立即推送一次模拟位置，不必等下一个上报间隔。
- 停止模拟后立即恢复：系统各定位来源缓存里残留的模拟位置不会再发给应用（返回该来源最近一次真实定位，没有则为空），直到真实定位到来。
- 批量回调（运动类应用常用）里的每个位置按各自时间计算，系统末端交付保留完整批次与历史时间戳，路线上的批量点是连续移动的。
- 卫星：应用收到的 GNSS 状态为模拟卫星（GPS、北斗、Galileo、GLONASS，各用自己的载波频率，位置随时间缓慢移动，信号强度随仰角变化）；NMEA 语句逐条替换为同类型的模拟语句（GGA、RMC、GSA、GSV 等，坐标和卫星与模拟数据一致，保留芯片原有节奏）；GNSS 原始测量和导航电文（足以反算真实位置）不再下发。
- Wi-Fi：扫描结果只含 Wi-Fi 预设（未设置则为空），连接信息从空对象构建，未设置预设时同时隐藏 SSID/BSSID，不沿用真实连接的 Passpoint、MLO 等身份字段。设置预设时，扫描结果与连接信息使用相同的 SSID、BSSID、频率和 RSSI；旧预设没有频率/RSSI 时使用 2437 MHz / -48 dBm。通过 ConnectivityManager 网络回调读到的 Wi-Fi 信息同样替换。
- 小米 HyperOS / MIUI：系统自带的「模糊定位」服务（MiuiBlurLocationManager）返回的位置与基站同样替换为模拟值，并忽略其 GPS 刷新。
- 蓝牙：模拟期间应用扫描不到蓝牙 LE 设备（回调和 PendingIntent 两种方式），防止定位 SDK 用附近信标反查位置；已连接的设备不受影响，系统设置里配对新设备（经典蓝牙发现）不受影响。需勾选「蓝牙」作用域。
- Google Play 服务融合定位（FusedLocationProviderClient）：其输入来自上述系统数据，模拟期间返回模拟位置，已用探针验证。
- 无法伪装：公网 IP（定位 SDK 可据此判断城市，需要代理 / VPN）、运营商代码。「控制台 → 系统诊断」新增「出口 IP 校验」，手动比对当前公网 IP 定位与模拟点，差异大时提醒改用对应地区的代理 / VPN；仅在点击时联网，需先同意隐私说明。
- 不伪造传感器：加速度计、陀螺仪、计步由系统 native 直接送入各 App 自己的进程，系统侧无法改写；要改写必须注入目标 App 进程，会丧失「零注入」隐蔽性，因此不做。运动 / 考勤类应用可能用传感器核对——手机静止但定位在移动即为不一致。建议：静止打卡用静止模拟（速度≈0，与放桌上的手机一致）；需要轨迹时让手机实际随身移动或降到步行速度，车速级静止模拟有被识别风险。模拟速度达车速级时「控制台 → 系统诊断」会给出提醒。
- 基站：电话服务返回基站预设（未设置则为空），包括 getAllCellInfo、getCellLocation、requestCellInfoUpdate、getServiceState 和基站 / 网络状态变化回调。运营商代码（getNetworkOperator）来自系统属性，在系统侧无法修改，仍是真实 SIM 卡的值。

以上环境伪装由「设置 → 隐藏真实信号」控制，默认开启；不改变真实网络连接和硬件。

已确认属于模拟范围的系统定位、Wi-Fi 和电话服务主路径，替换失败时返回空结果或跳过该次回调，并记录诊断，避免错误时退回真实数据。Wi-Fi 网络回调尽量保留网络能力，只移除无法构建的 Wi-Fi 详情。「仅选中的应用」模式下无法识别归属的回调会记录警告，仍不扩大模拟范围，因此未知归属入口不代表已受保护。Wi-Fi RTT 等独立测距入口尚未覆盖。

电话和蓝牙进程读取系统配置时，只有带确认标记的成功回复才更新状态。IPC 失败、未被模块处理的命令回复或配置损坏，会保留最近一次有效配置并记录限速日志，避免临时通信故障放行真实信号；系统确认已停止后才清除缓存。通信持续失败时，这些进程可能继续使用旧配置，直至通信恢复；配置刷新间隔为 500 ms，不能保证开始/停止在各进程间同时生效。更新模块后需重启相关系统进程（通常重启手机），避免新旧协议混用。

模拟范围（「设置 → 模拟范围」）：默认所有应用；可改为「仅选中的应用」，其他应用（如导航）继续拿到真实位置、Wi-Fi、基站和卫星。系统侧按每个回调所属的应用分别处理；使用谷歌融合定位的应用需同时选中「Google Play 服务」。

隐蔽性：App 与系统侧的命令通道只对 Geoshift 自己和系统进程开放；其他任何应用（包括选中的应用）调用时得到的是原生系统的行为，无法据此发现 Geoshift。与 PortalDev 一样，不在普通应用进程内注入代码，作用域里不要勾选普通应用。

「控制台 → 系统诊断」显示真实上报拦截数、推送成功／失败、应用回调替换计数、Wi-Fi 与电话服务报告。

2.5.1 已在 Android 15 模拟器（Magisk 31.1 + Vector v2.2，勾系统框架、电话服务和蓝牙；2.4.3 在 LSPosed 1.11.0 上同样验证过）上用独立探针应用（tests/probe）验证：定位 8 种读取方式、Google 融合定位 4 种读取方式、卫星、NMEA、GNSS 原始测量、Wi-Fi 3 种读取方式、基站 6 种读取方式在未设预设和设了预设时均为模拟值，停止后恢复真实值。蓝牙伪装入口已在蓝牙进程安装，但模拟器周围没有蓝牙设备，拦截效果需在真机验证；小米模糊定位服务只存在于小米 ROM，模拟器无法验证；尚未在 Redmi K70 实机验证。实现参考见 [开源项目对照](tests/OPEN_SOURCE_REVIEW.md)。

## 构建与验证

需要 JDK 17+、Android SDK（platform 37、build-tools 37.0.0）。构建工具链：Gradle 9.8.2、AGP 9.4.1（内置 Kotlin 2.4.21）、Compose BOM 2026.09.00。

本地工作区布局（仓库只是其中的 `source/`）：

```
geoshift/
  source/      本仓库
  releases/    构建产物 Geoshift-<版本>.apk、GeoProbe.apk
  reference/   对照用的原版 PortalDev 安装包（不入库）
  android/     sdk/（Android SDK）、avd/（测试模拟器 portaltest）
  signing/     签名私钥（仅 root 可读，不入库）
```

```
./build-release.sh              # 产出 ../releases/Geoshift-<版本>.apk（仅 ARM），用 ../signing 中的私钥签名（可用 PORTAL_KEYS 指定）
EMULATOR=1 ./build-release.sh   # 额外打包 x86_64 原生库，产出 -emulator.apk，仅用于模拟器测试
```

SDK 默认取 `../android/sdk`（可用 ANDROID_HOME 覆盖）。

只编译不签名：`./gradlew assembleRelease`。

## 开发记录

检查与优化记录统一存放在 [docs/](docs/README.md)。本轮详情见 [2026-11-09 定位与隐私优化记录](docs/reviews/2026-11-09-location-privacy.md)，其中区分已完成修改、验证结果和剩余限制。

## 目录

```
app/src/main/java/app/geoshift/
  *.java                 底层：模拟服务、摇杆、LSPosed hook（系统框架 / 电话服务 / 蓝牙）、扫描、地图网络、坐标算法
  MainActivity.kt        Compose 入口、权限申请、备份导入导出
  data/                  PortalState（类型化状态）、PortalActions（全部业务操作与校验）、
                         RoadRouter（沿道路路线）、OpenGeocoder（免 Key 搜索与地址）
  ui/                    PortalApp（导航）、map/（VectorMap：MapLibre 图层；OsmMap：OSM 默认图层）、
                         history/ mock/ settings/ setup/ 各页面、theme/、components/
tests/run-checks.sh      一键运行下面两组回归测试
tests/GeoChecks.java     坐标算法与地图切换坐标回归测试
tests/bridge/            系统 hook、路线播放、NMEA 逻辑回归（API 替身）
tests/probe/             探针应用 GeoProbe：以普通应用身份读取全部定位线索，logcat 标签 GEOPROBE
```

回归测试（无需真机或网络）：`bash tests/run-checks.sh`。这些测试使用 API 替身与模拟 hook 调度器，不能替代真机 / LSPosed 验证。

回归包含生产 `SystemSpoofHooks`、`CallbackOwners` 和 `AppScope` 的回调替换、归属解析与异常阻断检查，以及配置读取失败/确认停止、Wi-Fi 构建/包装失败、字段一致性、停止恢复、长时间开机的卫星角度边界、小米模糊定位回复不污染共享缓存。测试中的 `SystemHook` 仅为缓存分类替身；完整的 `SystemHook` / `PortalHook` 仍需 Android 构建和实机验证。探针同时输出 Wi-Fi 扫描、连接、网络能力查询与网络回调的频率/RSSI、Passpoint 和 MLO 字段，便于真机对照。

Android 静态检查：`./gradlew lintRelease`。请与发布构建串行执行，避免共享编译目录发生冲突。

## 许可

本项目代码以 Apache License 2.0 发布，见 [LICENSE](LICENSE)。

osmdroid、AndroidX、Kotlin、Xposed API（仅编译期）均为 Apache License 2.0；地图数据 © OpenStreetMap contributors（ODbL）。
MapLibre Native 为 BSD 2-Clause License；矢量瓦片 © OpenFreeMap © OpenMapTiles。搜索、地址和道路路线分别使用 Photon（Komoot）、Nominatim 和 FOSSGIS OSRM 公共服务，请遵守其使用政策。
界面流程参照 fuqiuluo/Portal（Apache License 2.0）与 PortalDev；代码为本工程独立实现，未复制原版 bundle、DEX 或原生库。
