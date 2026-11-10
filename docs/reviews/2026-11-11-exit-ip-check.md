# 2026-11-11：出口 IP 校验（提示性，非伪装）

## 背景

对照发现钉钉带 `libipdetector.so` + `libipstack-detect.so`，达达走 aliyun 定位——两者都会用公网 IP 反查城市，与定位交叉核对（见 [检测方对照](../references/detection-review-2026-11-11.md)）。模块**无法在进程内伪装出口 IP**，只能提示用户。

## 改动

新增一个**手动触发**的校验，比对当前公网出口 IP 的定位与模拟点，差异大时提醒用户需要对应地区的代理/VPN。

- `IpLocation.java`：`lookup(Context)` 查出口 IP 定位。
  - 填了高德 Web Key → 高德 `/v3/ip`（按请求源 IP 定位，国内准）；高德对海外 IP 返回空 `rectangle`，此时回退。
  - 无 Key 或海外 → `https://ipwho.is/`（HTTPS、无 Key、全球可用，返回 WGS-84）。
  - 坐标统一转 WGS-84（高德结果从 GCJ-02 转换），响应上限 256 KB，超时 7/8s。
- `PortalActions.checkExitIp()`：先过 `requireOnline()`（未同意隐私说明则拒绝），再 `IpLocation.lookup`，读运行态 `enabled/lat/lon`，用 `Geo.distance` 算与模拟点距离，分三档返回一句话：< 50 km 基本一致、< 300 km 偏差较大、否则明显不一致并提示用代理/VPN；未在模拟或拿不到坐标时只显示定位供参考。
- UI：`控制台 → 系统诊断` 展开后新增「出口 IP 校验」行，点「检查」触发，结果走 toast。复用现有 `vm.run<String>(onDone = vm::toast){ checkExitIp() }`。

## 隐私

**不自动外发**：只有用户点「检查」才会把出口 IP 发给定位服务，且需先同意隐私说明（`requireOnline`）。与现有地图/搜索遵循同一前置条件。关 VPN 时查到的是真实 IP，开 VPN 时是 VPN 出口 IP——后者正是要校验的对象。

## 验证

- `./gradlew :app:compileDebugJavaWithJavac :app:compileDebugKotlin --offline` 通过。
- 运行时（高德国内定位、ipwho.is 海外回退、距离分档文案）尚未真机联网验证。

## 仍未解决

传感器物理一致性、框架自身隐藏，见对照文档。IP 本身仍无法伪装，本功能只是提示。
