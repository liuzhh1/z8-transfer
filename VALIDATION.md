# Z8 Transfer v0.3.2：系统 MTP 默认连接验证记录

日期：2026-11-09。

## 用户实机反馈与本次判断

用户：Xiaomi 2509FPN0BC / Android 17，Nikon Z8 USB VID/PID 04b0/0451；接口 class=6/subclass=1/protocol=1，bulk IN 0x81 / OUT 0x02，maxPacket 1024。v0.3.1 的首个 GetDeviceInfo 0x1001 命令在 USB 发送阶段失败，后续系统 MTP 也无法读取信息和卡；用户确认 0.1.0 test 有线正常。

当前源码确认 v0.3.1 会先打开自写 PTP，失败后自动打开系统 MTP。先前失败是否改变了设备/驱动状态尚属推断，本次没有 USB 总线抓包或真实手机。基于用户版本对照，移除默认连接中的自写 PTP 前置尝试，直接使用 Android MtpDevice。

## 当前行为

- AppState 默认 SYSTEM_MTP，直接 UsbManager.openDevice → MtpDevice.open；不先手动 claim、不先发自写 PTP 命令。
- 一次连接只调用一个已选择的驱动；系统失败不自动尝试标准 PTP，标准发送失败也不立即接着打开系统 MTP。
- 标准 PTP 仅在未连接时手动选取「标准 PTP（实验）」才运行，不宣称已解决这台 Android 17 的底层命令发送失败。
- 默认值不保存为实验偏好；新进程启动恢复系统 MTP。连接/任务进行时不可切换，连接后隐藏选择器并显示实际连接标签。
- 全屏、选择下载、USB 确认后删除、视频/代理显示和 Wi-Fi 实现保留。
- 系统 MTP 原有大于等于 4 GiB/大小未知文件的保守拒绝保留，文件仍可显示，不会自动换驱动或发布截断成功副本。该限制在入口明确显示。

## 自动化检查

JDK 21.0.2 / Gradle 8.13 / AGP 8.13.2 / Kotlin 2.2.10 / compileSdk、targetSdk 36 / minSdk 29。

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

构建和全部 87 项测试通过。Lint 0 错误；3 条固定依赖更新提示（GradleDependency / NewerVersionAvailable），没有忽略功能/API 兼容性错误。

| 测试集 | 用例数 | 结果 |
|---|---:|---|
| DeletionEngineTest | 7 | 全通过 |
| PtpIpProtocolTest | 14 | 全通过 |
| PtpProtocolTest | 11 | 全通过 |
| TransferEngineTest | 17 | 全通过 |
| UsbSessionInitializerTest | 19 | 全通过 |
| ScreenTest | 15 | 全通过 |
| WifiRouteTest | 4 | 全通过 |

关键回归：默认连接绝不执行 raw PTP，实验模式必须主动选择，任一失败/取消都不会换到另一驱动，选择方式不会自动发起连接，忙时无法更改、连接后显示实际系统 MTP 标签。检查了 API 35 / 393×852 dp / Robolectric 原生图形截图，默认系统连接和文件大小限制可见。

其余回归包括文件完整性/回滚、删除保护、USB 合成协议流、16 KiB 数据阶段、PTP/IP 本机 TCP 模拟相机、Wi-Fi 路由模拟和全屏/预览/代理等交互。它们不能代替真实 USB/HyperOS、视频编码和相机测试。

## 安装包校验

应用名 `Z8 Transfer Wi-Fi`，包名 `com.liuzh.z8transfer.wifi`，versionCode 5，versionName 0.3.2。apksigner 校验及与实际 v0.3.1 APK 的签名比对通过，可覆盖 v0.3.0/0.3.1。zipalign 16 KiB 和各 ABI 原生库 ELF LOAD 对齐检查通过。

- APK：`Z8Transfer-v0.3.2-mtp-default.apk`
- 大小：28,374,836 字节
- SHA-256：`b959ad929af2814fbf33bceb96947c0d6421a5abd93b80b109c61dce11ba0625`
- 签名证书 SHA-256：`513855ef2625e9748888bf597768d9e056b297b80ec592e09d950203c45e398e`

工程中的公开测试密钥仅供连续测试更新，不能用作正式发布签名。旧 0.1/0.2 为独立包名/签名，保留即可，测试时退出旧 App，避免同时访问相机。

## 尚待实机验证

- 0.3.2 在用户这台手机上是否恢复连接，及预览/下载/删除、双卡、后台和速度。此次没有真实下载或删除相机文件。
- 同步 raw PTP 发送失败的系统级原因仍未解决；实验模式保留供排查，不作为本机大视频解决方案。
- 系统 MTP 大文件限制、Wi-Fi AP 实际固件兼容性、无 N-RAW/ProRes RAW 解码、无电脑/PIN/STA 专有认证、无线删除、自动重连与单文件断点续传等边界仍在。
