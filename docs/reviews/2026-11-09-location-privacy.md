# 定位与隐私优化记录

- 日期：2026-11-09
- 代码版本：2.7.1 工作区增量；本轮未提升版本号，未创建提交或发布标签。
- 范围：系统定位交付、GPS/GNSS/NMEA、Wi-Fi、基站、蓝牙归属诊断、跨进程配置、Portal 安装包对照。
- 状态：代码修改与本地验证完成；未完成真机验收，不能认定全部入口均已覆盖。

## 已落实的修改

以下文件路径均相对于 `app/src/main/java/app/geoshift/`。

| 问题 | 实现文件 | 最终行为 |
| --- | --- | --- |
| 部分替换失败后返回真实数据 | `SystemHook.java`、`SystemSpoofHooks.java`、`PortalHook.java`、`WifiPrivacyHooks.java` | 已处理路径返回空值或跳过交付，并记录错误；不是对所有未知入口的保证 |
| Wi-Fi 真实身份字段残留 | `WifiPrivacyHooks.java`、`FakeEnvironment.java` | 从空连接对象构建回复；无预设时隐藏 SSID/BSSID，有预设时统一扫描与连接的频率、RSSI和身份 |
| 配置通信失败被当成停止 | `ConfigCache.java`、`PortalHook.java`、`SystemHook.java` | 只有带确认标记的有效回复更新配置；故障保留最近有效状态，确认停止后清除 |
| 固定规律和逐秒跳动 | `FakeLocation.java`、`SimulationService.java`、`PortalStore.java` | 每次 START 生成种子并传到系统侧；平滑有界噪声可按时间重放；额外抖动半径不超过1米 |
| 批量历史点被压成单点 | `LocationPacket.java`、`SystemHook.java` | 保留完整批次，逐点按原单调时钟时间生成模拟位置 |
| 长时间开机后负方位角 | `FakeEnvironment.java` | 方位角归一化到合法范围 |
| 卫星统计与定位不一致 | `FakeEnvironment.java`、`FakeLocation.java` | 信号和仰角按会话平滑变化；按信号强度选择定位卫星；extras 从相同时间的天空数据统计 |
| 小米包装对象污染共享缓存 | `SystemSpoofHooks.java` | 构建独立定位回复，失败阻断返回原缓存 |
| NMEA 自由文本未经验证透传 | `FakeNmea.java` | 不再直接透传 TXT 接收机文本 |
| 回调归属不明缺少提示 | `SystemSpoofHooks.java`、`PortalHook.java` | 对选中应用模式下未知归属进行诊断；不擅自扩大作用域 |
| 探针字段不足 | `tests/probe/.../ProbeActivity.java` | 增加频率、RSSI、Passpoint、MLO及网络能力查询输出 |

## Portal 对照及采用范围

详见 [安装包静态对照](../references/portaldev-2.0.6-static-review.md)。证据来自 DEX 类型、字段和方法表，未取得方法体、未执行原 APK。

借鉴卫星集合和统一数据结构的组织方式，独立实现信号变化和统计一致性。没有复制原包代码；没有据 Oplus 类名添加未经确认的 Hook；没有实现真实星历或 GEO/IGSO/MEO 轨道传播算法。

## 验证快照

`bash tests/run-checks.sh`：共 **1,282** 项通过。

| 检查组 | 数量 |
| --- | ---: |
| 配置 IPC/缓存 | 13 |
| 系统交付及位置对象 | 36 |
| Wi-Fi | 26 |
| 回复包装类型 | 4 |
| 路线、NMEA及卫星一致性 | 52 |
| 生产回调及归属处理 | 16 |
| 坐标算法 | 1135 |

这些是 API 替身与模拟 Hook 调度器测试，不能替代真实 Binder、LSPosed 和厂商 ROM。测试中的 `SystemHook` 是缓存分类替身；完整 `SystemHook` 和 `PortalHook` 通过 Android 编译检查，尚无本轮真机交付证据。

发布构建 `assembleRelease` 和 `lintRelease` 均成功。后续复验建议串行运行：

```bash
bash tests/run-checks.sh
./gradlew assembleRelease
./gradlew lintRelease
```

本轮 Lint：0 项错误、24 项警告。

## 构建产物

- 主程序：`app/build/outputs/apk/release/app-release-unsigned.apk`，未签名，不能作为已发布安装包。
- 探针：`tests/probe/app/build/outputs/apk/debug/app-debug.apk`，调试构建；本轮已成功打包，未安装到设备验收。
- `../releases/` 中已有签名包未被本轮替换；不能仅凭同为2.7.1认定包含这些修改。

主程序 APK SHA-256：`1d358340c4a4884970d0c2beb9ae3b082b3d2fb7f239193ade02ce190257e27b`。后续重建可能变化。

## 剩余限制和后续验证

1. 公网 IP、App 自身历史缓存和服务器历史记录不受 GPS 替换控制。
2. Wi-Fi RTT 等独立测距入口未覆盖；厂商接口仍需设备级检查。
3. 选中应用模式下未知归属回调仍可能未受保护；诊断告警不等于已拦截。
4. 跨进程配置缓存约500ms刷新，开始/停止不是原子切换。通信持续失败时可能继续旧配置，恢复后才同步停止。
5. 更新模块后需要重启相关系统进程，通常重启手机，以避免配置确认协议新旧混用。
6. 卫星模型仍是有限的合成模型，不能证明与真实接收机无法区分。位移、信号变化或清除 mock 标记均不等于设备完整性检测不可见。
7. 尚未完成 Redmi K70/其他目标真机的启动、暂停、熄屏、进程退出、停止恢复、批量历史点及已选/未选应用并行验收。

建议下一步在目标真机运行探针，对照模拟前、模拟中、停止后三个阶段；保存脱敏结果并单独建立验收记录。对尚未复现的接口不按名称猜测修改。
