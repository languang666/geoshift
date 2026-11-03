# 系统定位实现对照

对照日期：2026-10-30。本记录对照的是当时的 2.3.1 实现，后续版本已增加电话服务和目标应用作用域。仅阅读公开源码，未运行第三方模块；本工程新增逻辑独立实现。

| 项目 | 源码中的做法 | 本次采用的方向 |
| --- | --- | --- |
| [HideMockLocation](https://github.com/auag0/HideMockLocation/blob/main/app/src/main/java/io/github/auag0/hidemocklocation/Main.kt) | 隐藏模拟定位标志及相关设置；并不负责生成目标坐标 | 隐藏标志不能代替系统坐标注入；未把它作为坐标修复方案 |
| [AnyDoor](https://github.com/zhaoyuxiangyyds-lab/AnyDoor)（SystemHooks.java） | 系统定位上报与注册请求交付处替换；处理 Wi-Fi 服务的独立类加载器和扫描回复包装类型 | 补充系统末端交付、按参数类型查找入口、兼容 List 和包装列表回复 |
| [XposedFakeLocation](https://github.com/noobexon1/XposedFakeLocation)（SystemServicesHooks.kt / AppWifiHooks.kt） | 系统与应用进程承担不同 hook；扫描过滤通过修改返回结果实现 | 系统 Wi-Fi 回复过滤作为可选项，不修改硬件或全局扫描设置 |
| [GPS Setter](https://github.com/kovag1978-oss/android_gps_setter)（LocationHook.kt） | 包含应用定位读取和系统交付相关处理 | 对照缓存与交付覆盖范围；未复制该项目代码 |

本次保留系统框架定位路径，不新增腾讯 SDK 专用适配，不要求勾选微信。gps、network、fused 均参与替换；按真实返回类型选择定位入口，防止同名 OEM 重载被误认为可用入口。

电话服务、融合定位组件与设置存储是否需要作用域取决于模块实际在哪个进程安装 hook，不能只按名称全部勾选。本次不新增这些作用域，也不要求电话和短信存储。

Wi-Fi 过滤默认关闭，只作用于模拟运行期间普通应用从系统收到的扫描结果与连接 BSSID。它不能清除旧缓存，也不覆盖基站、IP、蓝牙等全部位置线索。应用最终显示的结果仍需实机验证。
