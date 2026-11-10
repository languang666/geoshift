# 2026-11-11：传感器交叉校验——已知局限 + 速度提醒（不伪造）

## 背景

达达、钉钉都带传感器入口（`SensorManager`、`TYPE_ACCELEROMETER`/`TYPE_GYROSCOPE`、`elapsedRealtimeNanos`，见 [检测方对照](../references/detection-review-2026-11-11.md)）。可据此做物理一致性核对：手机静止（加速度计只有重力+噪声、计步不增）但定位在路线上移动 = 作弊信号。

## 为什么不伪造（架构结论）

定位回调能在系统侧改，是因为它走 `ILocationListener$Stub$Proxy` 这种 system_server 里的 Java Binder 接口。**传感器样本不走这条路**：由 native 的 sensorservice 经共享内存/socket 直接送入 App 进程，在 App 自己的进程里由 `SystemSensorManager.dispatchSensorEvent` 分发。system_server 内没有可 hook 的逐样本 Java 回调点。

因此合成"与路线一致的加速度/计步"必须把模块注入目标 App 进程（把 App 加进作用域）——这恰好重新暴露给检测方用得最多的**进程内栈扫描 + `/proc/self/maps`**，交出 Geoshift 当前最大的隐蔽优势（零注入）。且合成得不像真实信号本身又是新特征。权衡后：**不伪造传感器**，保留零注入设计。当前作用域仍只含 `android/system/com.android.phone/telephony/bluetooth`，无普通 App。

## 改动（只提醒，不改架构）

`控制台 → 系统诊断`：正在模拟且速度 ≥ 2.3 m/s（约 8 km/h，明显非步行）时，显示一条警告横幅，说明若手机静止，运动/考勤类应用可能用加速度计或计步发现与定位不符，建议改静止模拟或让手机实际移动。纯前端由 `RuntimeState.speed` 计算，零网络、零注入。步行级（< 2.3 m/s）不弹横幅以免唠叨，由 README 说明覆盖。静止模拟（速度≈0）与静止手机一致，无此问题。

## 使用建议（已写入主 README）

- 静止打卡 / 固定位置：用静止模拟（速度≈0），与放在桌上的手机一致，传感器核对不出问题。
- 需要移动轨迹且目标应用会核对传感器：让手机实际随身移动，或把速度降到步行级；车速级静止模拟有被识别风险。
- 这是本工具不覆盖的残余面，不随版本消除。

## 仍未解决

传感器伪造本身（需以隐蔽性为代价，见上）。公网 IP 见 [出口 IP 校验](2026-11-11-exit-ip-check.md)。
