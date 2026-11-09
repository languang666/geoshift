# UI 与交互优化记录

- 日期：2026-11-09
- 代码版本：2.7.1 工作区增量；本轮未提升版本号，未创建发布标签。
- 范围：地图罗盘指南针、单手缩放、选点规划路线、坐标点击复制、控制台地名与路线 ETA、搜索历史与候选、数值快捷调节 Slider/Chip、收藏多选批量删除、Tab 平滑转场、Material You 动态色彩、触觉反馈。
- 状态：Compose 代码修改、打包构建及回归验证完成。

## 已落实的修改

以下实现文件路径均相对于 `app/src/main/java/app/geoshift/ui/`。

| 模块 / 问题 | 实现文件 | 最终行为 |
| --- | --- | --- |
| 旋转地图无指南针、无法直观回正正北 | `map/MapScreen.kt`、`map/VectorMap.kt`、`map/OsmMap.kt` | 地图右上侧工具条常驻原生 `MapCompass` 立体双色罗盘；双指旋转地图时指针平滑跟手旋转；点击带轻度震动与 500ms 动画平滑回正正北；已正北时轻触提示当前指向 |
| 选点后无法快速发起去往该点的路线 | `map/MapScreen.kt` | 选点卡片（`PreviewCard`）新增「去这里」快捷操作：以当前虚拟坐标为起点、选中点为终点直接进入路线编辑并启动道路吸附规划 |
| 地图缺乏单手缩放方式 | `map/MapScreen.kt` | 悬浮工具组新增独立的放大（`+`）与缩小（`−`）按钮，单手握持或车载使用时无需双指捏合 |
| 搜索地点无历史记录、重复输入繁琐 | `map/MapScreen.kt` | 搜索栏持久化最近 6 条搜索历史记录，在输入框为空且未编辑路线时展示水平滚动的历史标签（`SuggestionChip`），点击即刻发起搜索，支持一键清空历史 |
| 坐标纯文本无法快速复制 | `components/Common.kt`、`map/MapScreen.kt`、`mock/MockScreen.kt` | 新增 `CoordinateRow` 组件：经纬度数字旁带复制图标，轻触自动写入系统剪贴板，伴随触觉振动与 Toast 提示 |
| 底部卡片切换模式时高度瞬时突变 | `map/MapScreen.kt` | `BottomCard` 容器应用 `Modifier.animateContentSize()`，状态卡片、选点卡片与路线编辑卡片切换时高度自适应平滑展开/折叠 |
| 控制台虚拟位置仅有经纬度无地名 | `mock/MockScreen.kt` | `StatusHero` 卡片根据坐标异步逆地理编码，在经纬度下方展示中文地名与区域详细地址 |
| 路线模拟缺少剩余距离与到达时间估算 | `mock/MockScreen.kt` | 路线播放期间结合总里程、剩余百分比和当前时速，实时计算并显示「剩余距离 · 预计剩余时间（ETA）」 |
| 控制台运动方向仅有角度数字 | `mock/MockScreen.kt` | 路线或摇杆移动时，在航向角旁补充标准八卦方位汉字（如 `35°（东北）`、`180°（正南）`） |
| 控制台模拟项目开关与跳转二级页面混淆 | `components/Common.kt`、`mock/MockScreen.kt` | `ToggleTile` 支持 `hasSubpage` 属性：Wi-Fi 与基站右侧展示导航箭头，区分即时开关与详情页跳转 |
| 核心操作缺乏物理按压感 | `map/MapScreen.kt`、`mock/MockScreen.kt`、`components/Common.kt`、`settings/SettingsScreen.kt`、`history/HistoryScreen.kt` | 开始/停止模拟、路线暂停/继续、坐标复制、预设调整与批量删除接入系统轻柔触觉振动反馈（`LocalHapticFeedback`） |
| 参数设置（速度/精度/间隔/海拔）仅支持键盘纯文本输入 | `settings/SettingsScreen.kt` | 重构为交互式 `NumberSettingDialog`：提供常用快捷预设 Chip（如速度 0.5x~20x、精度 3m~50m）、范围连续滑动调节 Slider、步进加减按钮以及重置默认值能力 |
| 收藏条目过多无法批量清理 | `history/HistoryScreen.kt` | 新增「批量管理」多选模式：顶部操作栏支持一键全选/取消全选，列表项提供 Checkbox 多选，底部操作支持批量安全删除，支持系统返回键退出多选 |
| 收藏页面搜索框无法一键清除 | `history/HistoryScreen.kt` | 搜索栏补齐 `Close` 清空按钮，输入长文本时可一键重置筛选 |
| 模拟范围勾选上百个应用时操作困难 | `settings/AppScopeDialog.kt` | 新增「仅看已选 (N)」FilterChip 筛选、新增「全选当前」与「清空」批量操作，补齐搜索清除按钮，优化读取等待转圈动效 |
| Tab 切换界面突变生硬 | `PortalApp.kt` | 底部 4 个主 Tab（地图、控制台、收藏、设置）转场接入 `AnimatedContent` 淡入淡出过渡（180ms/120ms） |
| 外观主题未融入系统动态壁纸 | `theme/Theme.kt`、`settings/SettingsScreen.kt` | Android 12+ (API 31+) 设备支持 Material You「动态色彩」取色方案（`dynamicLightColorScheme` / `dynamicDarkColorScheme`），色彩自适应跟随壁纸调色板 |

## 重点功能设计细节

### 1. 原生立体罗盘（MapCompass）
- **视觉**：48dp 细边框圆形悬浮卡片；Canvas 绘制经典 3D 斜角双色指针（北极暗红+亮红阴影，南极深灰+浅灰阴影），中心金属同心转轴，顶部固定微型红色“N”字标。
- **动效**：基于 Compose `animateFloatAsState(spring)` 物理弹簧阻尼，消除逐帧抖动。
- **交互**：
  - 地图转歪时点击：相机执行 `animateCamera(bearing = 0.0, 500)` 平滑回正，Toast 提示 `已回正正北`。
  - 已指向正北时点击：轻触振动，提示 `当前已指向正北（0°）`。
- **底层调整**：屏蔽了 MapLibre 原生罗盘（原位于左上角且与 Compose 搜索栏有重叠风险），由原生 Compose 顶层组件接管交互。

### 2. 「去这里」即时路线生成
- 点击地图任意位置或搜索选中后，在 `PreviewCard` 点击「去这里」：
  - 自动构建 `[当前虚拟位置, 选点目标]` 两个基准点；
  - 自动保留用户上次选择的出行方式（步行/骑行/驾车）；
  - 触发 `RoadRouter` 异步道路吸附并直接切换至 `RouteEditorCard`，用户可继续微调途经点或保存出发。

### 3. 数值参数弹窗（NumberSettingDialog）与滑动微调
- 速度倍率支持 `0.5x`、`1.0x`、`1.5x`、`2.0x`、`3.0x`、`5.0x`、`10.0x`、`20.0x` 快捷预设；
- 精度支持 `3m`、`5m`、`10m`、`15m`、`20m`、`50m` 常用档位；
- 整合了直观的 `Slider` 滑条与 `+` / `−` 步进微调按钮，大幅减少必须调起系统软键盘输入的繁琐操作。

### 4. 收藏列表多选批量删除
- 在收藏页面点选右上角「批量管理」进入多选模式；
- 支持一键「全选」与「取消全选」，点选条目或复选框即时统计数量；
- 点击「删除选中」弹出二次确认模态窗，并在后台统一批处理提交，操作伴随长振动提示。

### 5. 模拟范围（AppScopeDialog）批量管理
- 在手机安装大量应用时，通过「仅看已选」可在 1 秒内核对当前生效白名单；
- 配合关键词搜索与「全选当前」，可针对特定类别的应用（如测试包、银行包、社交包）实现一键批量勾选。

## 验证快照

- `bash tests/run-checks.sh`：共 **1,282** 项回归测试全部通过。
  - 配置 IPC/缓存：13 项
  - 系统交付及位置对象：36 项
  - Wi-Fi：26 项
  - 回复包装类型：4 项
  - 路线、NMEA及卫星一致性：52 项
  - 生产回调及归属处理：16 项
  - 坐标算法：1,135 项
- 构建测试：`./gradlew assembleRelease` 顺利完成，无编译错误，成功产出 release APK。
- 静态检查：Compose 规范与生命周期检查通过。

## 剩余限制

1. osmdroid 默认图层（纯 OSM 瓦片）采用屏幕固定正北绘制，不支持双指旋转地图视角；罗盘在 OSM 模式下保持指北，点击提示已指向正北。矢量地图、高德地图和卫星影像均支持全自由旋转与一键回正。
2. 逆地理编码受公共服务速率限制（Nominatim 限制约 1 次/秒），在高速移动时暂停逆地理请求，静止或低速移动时自动恢复展示。
