# Z8 Transfer v0.5.0：遥控拍照、实时取景与单张预览删除

日期：2026-11-20。基于已完成的 0.4.0 分支提交 `6803060b6c5d6346f78cf06b0d6e186e335b2dfe`，保留全部照片浏览改造。

## 新增实现

- 能力按相机当前连接的操作码开放；系统 MTP 提供说明，Wi-Fi PTP/IP 与标准 USB PTP 接入同一 Nikon 遥控引擎。
- 遥控页面自动开启可用取景，显示实际帧数/帧率，可暂停、恢复、自动对焦、拍照；帧只用于显示。
- 遥控、对焦、快门、帧拉取串行执行；快门只发送一次，不因相机忙、错误、响应丢失自动重发。未知结果时阻止继续拍照。
- 相机存储卡为拍照目标，不切换为仅 SDRAM；退出遥控后重扫列表并保留有效选择，可手动下载原片。
- 退出、后台停止或断连时结束取景、释放本会话取得的控制；清理失败则提示重连。画面超时显示停更提示，不将旧帧伪装为新帧。
- 单张预览提供删除按钮及确认，支持 RAW 配套 JPEG；删除后跳到剩余文件，最后一张删除后关闭预览。

## 协议依据

操作码与参数含义核对 [libgphoto2 ptp.h](https://github.com/gphoto/libgphoto2/blob/master/camlibs/ptp2/ptp.h) 及其 Nikon 取景/拍照实现；本项目自行实现 Kotlin 控制状态与会话管理。

| 指令 | 用途 / 参数 |
|---|---|
| 0x90C2 | 切换遥控模式；1 进入，0 释放本 App 成功取得的模式 |
| 0x90C8 | DeviceReady；只轮询相机状态，不触发拍照 |
| 0x9201 / 0x9202 / 0x9203 | 开始 / 结束 / 拉取实时取景；三项均声明支持才开放取景 |
| 0x90C1 | 相机声明支持时提供 AF Drive |
| 0x9207 | 优先卡内拍照，参数 0xffffffff（使用当前焦点）、0（相机存储卡）；需要时先独立对焦 |
| 0x100E | 未声明 0x9207 且声明标准拍照时使用，参数 0、0；发出快门后不切换其他指令重试 |

取景数据跳过 Nikon 帧头并提取完整 JPEG；限制协议数据、JPEG 大小与解码尺寸。收到忙状态可等待后继续读取画面，取景被相机中断时最多自动恢复一次；不自动重发拍照。

## 自动化验证

```bash
./gradlew --max-workers=2 :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

**123 项测试全部通过，Lint 0 错误、0 警告，Debug APK 构建成功**。apksigner 验签及 zipalign 16 KiB 对齐检查通过。

| 测试集 | 用例数 | 结果 |
|---|---:|---|
| DeletionEngineTest | 7 | 全通过 |
| NikonFocusMetadataTest | 4 | 全通过 |
| PhotoBrowserTest | 3 | 全通过 |
| PtpIpProtocolTest | 14 | 全通过 |
| PtpProtocolTest | 11 | 全通过 |
| RemoteCameraTest | 10 | 全通过 |
| RemoteSessionTest | 3 | 全通过 |
| TransferEngineTest | 17 | 全通过 |
| UsbSessionInitializerTest | 19 | 全通过 |
| ScreenTest | 27 | 全通过 |
| WifiDeletionTest | 4 | 全通过 |
| WifiRouteTest | 4 | 全通过 |

## 安装包校验

- 文件：`Z8Transfer-v0.5.0.apk`
- 大小：28,809,532 字节
- 包名：`com.liuzh.z8transfer.wifi`，versionName 0.5.0 / versionCode 7。
- SHA-256：`71ef991f194fed9e16e0bd213780d2d2b5c041673f77061e291237fb6d9979d9`
- 签名证书 SHA-256：`513855ef2625e9748888bf597768d9e056b297b80ec592e09d950203c45e398e`，与 0.4.0 相同，可覆盖更新。

新增测试覆盖模式释放、实际 USB / PTP-IP 容器中的操作码与卡目标参数、快门不重发、忙/对焦失败/结果不明、JPEG 边界、暂停恢复和取消清理，以及 Compose 遥控按钮、后台停止、预览删除、RAW 配套删除和翻页后的删除目标。

## 实机边界

没有连接真实 Z8/手机，也没有实际触发拍摄或删除。需要在 Z8 + 小米手机验证当前固件是否开放控制指令、Wi-Fi 控制认证、标准 USB PTP 的已知发送问题、取景帧头/方向/帧率/延迟、AF 与快门、卡内新文件发现及退出清理。不能将模拟协议流或界面截图视为真机画面。

0.5.0 不提供曝光/ISO/光圈等参数修改、指定对焦点、录像或 Bulb 控制；相机应选择普通照片单张模式。默认系统 MTP 浏览/下载保留。

---

# Z8 Transfer v0.4.0：照片浏览与删除改造

日期：2026-11-20。基于仓库 main 的 `f7a7465a378944d59ed7d61bda06696f8e77b35b`。

## 本次实现

| 需求 | 行为 |
|---|---|
| 排序按钮 | 时间、名称、大小各支持升降序，保留原有选择 |
| 长按滑动批量选择 | 连续范围选择/取消、反向缩回恢复原始选择、边缘自动滚动 |
| RAW / JPEG 筛选 | 独立格式入口，与存储卡、日期、文件名筛选叠加 |
| Wi-Fi 删除 | 按 DeleteObject 能力开放；逐文件复核会话、存储卡卷、元数据、保护状态；无通配、无自动重试 |
| 对焦框开关 | 读取 Nikon AFInfo2 0400 明确坐标，按 EXIF 方向变换并随图片缩放/平移；无坐标时显示提示 |
| 六列 | 1–6 列菜单，4–6 列紧凑展示 |
| 翻页手柄 | 网格右侧拖动定位，提供无障碍进度操作和拖动序号 |
| RAW 配套 JPEG 删除 | 确认框可选，限定同卡卷/同目录/同主文件名，大小写和 .jpg/.jpeg 兼容；改变选项后重新确认 |

NEF 优先使用配套 JPEG；没有配套时尝试读取内嵌 JPEG。此功能读取内嵌预览，未实现 RAW 像素解码。JPEG/HEIF 原图缓存上限 64 MiB，NEF 192 MiB，内嵌 JPEG 32 MiB。对焦坐标与预览使用相同来源和方向。

## 自动化验证

JDK 21.0.13.1 / Gradle 8.13 / AGP 8.13.2 / Kotlin 2.2.10 / compileSdk、targetSdk 36 / minSdk 29。

```bash
./gradlew --max-workers=2 :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

完整检查通过：**103 项测试全部通过，Lint 0 错误、0 警告，Debug APK 构建成功**。apksigner 验签与 zipalign 16 KiB 对齐检查通过。

| 测试集 | 用例数 | 结果 |
|---|---:|---|
| DeletionEngineTest | 7 | 全通过 |
| NikonFocusMetadataTest | 4 | 全通过 |
| PhotoBrowserTest | 3 | 全通过 |
| PtpIpProtocolTest | 14 | 全通过 |
| PtpProtocolTest | 11 | 全通过 |
| TransferEngineTest | 17 | 全通过 |
| UsbSessionInitializerTest | 19 | 全通过 |
| ScreenTest | 21 | 全通过 |
| WifiDeletionTest | 3 | 全通过 |
| WifiRouteTest | 4 | 全通过 |

## 安装包校验

- 文件：`Z8Transfer-v0.4.0.apk`
- 大小：28,614,364 字节
- SHA-256：`fc398a7ce59d656bf05b73c7556e836851a8e380817522de1803a98637d1b51c`
- 签名证书 SHA-256：`513855ef2625e9748888bf597768d9e056b297b80ec592e09d950203c45e398e`，与仓库原 0.3.2 记录一致，可覆盖更新。

测试使用本地合成 EXIF/TIFF、PTP/IP 字节流、Robolectric API 35（393×852 dp）和 Compose 触控注入。
Wi-Fi 删除验证实际经过 WifiPtpTransport 和 PtpIpProtocol，检查 DeleteObject 的句柄和参数；拒绝、不支持、受保护、卡卷变化、元数据变化、旧会话均有覆盖。

## 实机验证边界

本次没有连接真实 Z8 或手机，没有实际删除相机文件。需在用户设备验证 Wi-Fi 固件是否开放删除及保护状态反馈、长按拖选与边缘滚动、1–6 列布局、手柄定位，以及不同方向的 NEF/JPEG 对焦框位置。

对焦框只支持 AFInfo2 0400 的明确矩形坐标；没有坐标的 AF 点掩码、其他布局、HEIF、视频或无法解码的内嵌预览不承诺显示。无线删除被相机拒绝时显示错误，用户可在相机中删除。RAW 和 JPEG 独立逐项执行，部分失败会分别报告。

公开测试签名与 0.3.2 相同，包名继续使用 `com.liuzh.z8transfer.wifi`，versionName 0.4.0 / versionCode 6。

---

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
