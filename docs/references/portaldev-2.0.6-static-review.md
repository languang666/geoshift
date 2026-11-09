# PortalDev 2.0.6 安装包静态对照

日期：2026-11-09。路径以仓库 `source/` 为基准。


参考文件：`../reference/PortalDev_2.0.6.r86.8ed0978.apk`，SHA-256：
`4415b8137ceee26c4208b5d74bd4e4a80fdc616115a3d5ea03a8cdb8b67cb7c9`。
REA 的 JADX 引擎未配置，本次直接读取 `classes2.dex` 的字符串、类型、字段和方法表；没有执行原 APK，也没有取得方法体。以下不代表其算法或运行效果已被验证。

- `GnssSimulator.allSatellites`：卫星集合字段。
- `MockGnssData`：`azimuths`、`elevations`、`cn0s`、`carrierFreqs`、`svCount`、`svidWithFlags`。
- `OrbitType`：`minCn0` / `maxCn0`，并存在 GEO / IGSO / MEO 类型。
- `OplusLocationHandler.hookOplusLocationMethods` 与小米对应方法存在，但目标入口、参数及回退行为尚未确认，未照类名添加 Hook。

本工程独立实现的改进：卫星信号与仰角加入按会话种子生成的平滑有界变化；按信号强度选择参与定位的卫星；定位 extras 从同一份天空快照统计使用数量、平均及最大信号强度。GNSS 与 NMEA 继续消费该模型。没有复制 Portal 二进制或算法，也未声称具备真实星历计算能力。

关联：[优化记录](../reviews/2026-11-09-location-privacy.md)。
