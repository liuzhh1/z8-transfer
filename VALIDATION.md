# Z8 Transfer v0.9.0：监看辅助与选片下载

日期：2026-11-25。基于 v0.8.0 提交 `2a80a64137bd7b7e2645fbcbc79a1b5deadbc51f`。

## 本次行为

- 遥控画面增加亮度直方图、斑马纹、峰值对焦和三分网格，各自独立开关。全屏监看收起参数控件并保留快门，横屏采用紧凑控制区，打开参数时保留画面。
- 预览分析在后台线程每秒最多五次，采样最长边 256 像素，不增加相机请求。直方图为 64 档亮度统计，斑马阈值为 RGB 亮度 235/255，峰值为预览边缘检测。结果不代表 RAW 数据曝光或相机 AF 合焦确认。
- 手机选片标记按原文件来源指纹保存，重连换句柄仍可恢复；卡身份或来源元数据改变后不继承。只改本机标记，最多 20000 项。
- 下载提供仅所选、RAW＋JPEG 成对、只 RAW、只 JPEG。配对核对当前会话、相机、卡编号及卷身份、目录和文件名主体；视频和其他格式保持原选择，缺少目标格式会显示数量。实际下载队列采用该计划；重试只针对失败原文件，删除清单保持明确所选。
- 连接信息按需读取手机、USB 权限、Wi-Fi 地址、VPN、通知和省电状态；针对常见手机系统给出设置提示。不增加联网探测，不自行切换连接或改变权限。

## 自动化验证

JDK 21.0.13.1 / Gradle 8.13 / AGP 8.13.2 / Kotlin 2.2.10 / Android SDK 36。

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

**193 项测试全部通过，无失败、错误或跳过；Lint 0 错误、7 条构建工具/依赖版本更新提示。Debug APK 构建成功。**

新增 17 项覆盖：RAW/JPEG 双向配对及去重、跨相机/卡/卷/目录/会话隔离、缺少目标格式、视频/HEIF 保留、失效选择拒绝、文件名大小写；亮度端点、斑马区域和条纹、峰值边缘、透明叠层及尺寸校验；标记持久化与来源变化；真实控制器的队列扩展；标记筛选、下载数量、全屏监看及横屏参数布局。保留零相册扫描遥控、参数回读、文件完整性和删除复核的全部回归。

Robolectric API 35 的 393×852 dp 竖屏与 852×393 dp 横屏交互通过。已检查新增监看、标记和配对下载界面截图。没有连接真实 Z8 或手机。

## 安装包

- 文件：`Z8Transfer-v0.9.0.apk`
- 包名：`com.liuzh.z8transfer.wifi`；versionName 0.9.0 / versionCode 11。
- 大小：29,301,036 字节。
- SHA-256：`0be2a1d23c3eea50aadcc461d133590dd53996dd193fec3c2e0b53a55e34212a`。
- 签名证书 SHA-256：`513855ef2625e9748888bf597768d9e056b297b80ec592e09d950203c45e398e`。
- apksigner 验签、zipalign 16 KiB 检查、4 个 ABI 原生库 ELF LOAD 16 KiB 对齐检查通过。
- 与实际 v0.8.0 APK 包名、签名一致，版本号递增，可覆盖安装。

## 实机验证边界

当前仍需在 Z8 与目标手机验证取景帧率、叠层延迟、峰值参考效果、标记恢复、配对下载和息屏传输。监看分析不会把预览提升为 RAW 测光仪。保留既有 MTP 大文件、标准 USB PTP 兼容性和按连接能力开放遥控的边界。录像/B 门起止、间隔拍摄、触屏对焦、STA/FTP、LUT 上传等仍属于后续版本范围。

---

# Z8 Transfer v0.8.0：分类遥控设置与拍摄模式

日期：2026-11-21。基于 0.7.0 提交 `01be5f65c02c2bdec28fb04eca58698a378e15c8`。

## 本次范围

- 目录扩展到 65 项设置/状态、10 类；52 项提供条件式写入路径、13 项保留只读。目录不等于 Z8 全部菜单或全部真机已验证功能。逐项策略见 [CAMERA_CONTROLS.md](CAMERA_CONTROLS.md)。
- P/A/S/M 模式切换，重新回读当前组更新光圈/快门权限；常用六项使用双列布局，模式与曝光补偿可同时看到，保留实时画面和快门。
- 扩展自动 ISO、测光/步长、对焦/AF 区域、图像品质/尺寸、RAW、色温、降噪、释放/自拍/菜单库、闪光补偿、取景、视频参数和状态读取。未开放或未适配项目显示原因。
- 仅进入参数面板读取当前组，进入遥控仍不枚举照片、不等待参数加载。每次写入复核设备描述符、权限和选项，单次写入后回读所选设置所在组，不自动重发。
- 支持字符串枚举（图像尺寸）和有界大整数范围（色温、张数）；按真实值而非选项下标写入。色温输入校验机身上下限与步长。
- 保留 16 位厂商属性发现，新增 Nikon GetVendorCodes 0x9439 参数 0x0d 的 32 位属性发现，完整保留 0x1dxxx 请求/写入编号。自适应数值项拒绝文本描述符；未知类型禁用。
- 能力报告可查看目录匹配情况、发现失败原因，并复制完整属性编号供后续实机适配。没有扫描或写入未知属性。
- 参数/取景/对焦/快门串行；视频参数类别禁用照片快门，未提供录像开始/停止。面板与输入框按深色背景设置文字颜色。

## 自动化验证

```bash
./gradlew --max-workers=2 :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

**176 项测试全部通过，Lint 0 错误（6 条 GradleDependency 更新提示），Debug APK 构建成功。** APK 签名与 16 KiB 对齐检查通过。

新增覆盖：模式值编码和权限联动、分组只读取已声明字段、扩展 32 位发现与完整写入编号、畸形发现列表回退、色温范围/步长和越界拒绝、精确 UTF-16 字符串选项/截断拒绝、机身选项改变后的写入复核、未适配/状态项目强制只读、自适应数值项拒绝文本类型、分组会话和文本回读，以及模式选择、常用模式/曝光补偿同屏、视频页快门限制、范围编辑和能力报告复制。保留原有零相册扫描、传输、删除和全部照片 UI 回归。

| 测试集 | 用例数 | 结果 |
|---|---:|---|
| CameraNavigationTest | 10 | 全通过 |
| CameraSettingsTest | 12 | 全通过 |
| DeletionEngineTest | 7 | 全通过 |
| NikonFocusMetadataTest | 4 | 全通过 |
| PhotoBrowserTest | 3 | 全通过 |
| PtpIpProtocolTest | 16 | 全通过 |
| PtpProtocolTest | 13 | 全通过 |
| RemoteCameraTest | 10 | 全通过 |
| RemoteSessionTest | 6 | 全通过 |
| ShootingParametersTest | 13 | 全通过 |
| TransferEngineTest | 17 | 全通过 |
| UsbSessionInitializerTest | 20 | 全通过 |
| ScreenTest | 36 | 全通过 |
| WifiDeletionTest | 5 | 全通过 |
| WifiRouteTest | 4 | 全通过 |

## 安装包校验

- 文件：`Z8Transfer-v0.8.0.apk`
- 大小：29,138,804 字节
- 包名：`com.liuzh.z8transfer.wifi`，versionName 0.8.0 / versionCode 10。
- SHA-256：`d6affd1d304934eaeb18d56a46dfd42ce7ca497426858d8c4235b01897c2a748`
- 签名证书 SHA-256：`513855ef2625e9748888bf597768d9e056b297b80ec592e09d950203c45e398e`，与 0.7.0 一致，可覆盖安装。

## 实机边界

本次没有连接真实 Z8/手机，没有实际调参、拍照或删除。52 条写入适配路径不代表当前相机一定全部可写；固件、镜头、P/A/S/M、自动 ISO、照片/视频状态和连接能力会限制可用性。优化校准、主体侦测、部分速度和卡槽策略仍只读，需结合实机数据继续适配。未接入录像/B 门起止、触屏对焦、间隔/焦点变化/像素变化拍摄、HDR 编排、视频分辨率/帧率/编码选择或云创/LUT 上传。系统 MTP 仍不开放遥控参数接口，标准 USB PTP 的既有兼容性边界保留。

协议和功能参考见 CAMERA_CONTROLS.md 中的 Nikon 官方手册及 libgphoto2 源码链接。

---

# Z8 Transfer v0.7.0：遥控拍摄参数调节

日期：2026-11-21。基于 0.6.0 提交 `5454fee531dbc290ee45dc8120ddde8906a79623`。

## 新行为

- 遥控页内调节快门、光圈、ISO、曝光补偿、白平衡。点击「拍摄参数」时读取，进入遥控本身不会额外等待参数、照片列表或缩略图加载。
- 参数面板位于同一页面，限制占用高度，上方实时画面和下方快门仍可见；可返回取景或用系统返回键收起。
- 仅使用设备声明的标准/尼康厂商属性。读取 DevicePropDesc 当前值、类型、读写权限和枚举/范围；不预设镜头光圈，不改 P/A/S/M、自动 ISO、释放模式、白平衡微调或具体色温。
- 尼康快门优先使用 D100 的分子/分母编码，标准曝光时间作为回退。特殊 B 门、Time、闪光同步档不提供选择，保留机身控制。
- 每次写入前重新读取描述符，复核可写和可选值。USB 发送同事务数据容器；PTP/IP 使用 data-phase 2、StartData、EndData。一次写入后回读五项参数；相机拒绝、连接丢失或回读不一致时不显示虚假的成功、不自动重发写入。
- 参数读取/写入与取景、对焦、快门在同一会话串行执行。操作期间快门禁用；正常完成后继续取景。机身改变设置后点「刷新」同步，显示值标记为「已读取」。
- 仍仅显式进入「下载照片」才枚举文件，参数操作与拍照均不扫描相册。保留照片浏览、删除、预览、批选、六列和翻页手柄等既有功能。

## 自动化验证

```bash
./gradlew --max-workers=2 :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

**160 项测试全部通过，Lint 0 错误（6 条 GradleDependency 更新提示），Debug APK 构建成功。** APK 签名与 16 KiB 对齐检查通过。

新增/扩展检查涵盖有符号与无符号属性、精确快门档位、范围展开上限、只读和畸形描述符、尼康厂商属性发现、修改前权限变化、写入一次与真实回读、不确定结果无重发、USB/PTP-IP 出站容器与事务编号、Wi-Fi 完整调参链路、参数与取景/快门串行、控制器零相册扫描，以及参数面板的选择、只读提示、失败刷新和可见快门。

| 测试集 | 用例数 | 结果 |
|---|---:|---|
| CameraNavigationTest | 10 | 全通过 |
| DeletionEngineTest | 7 | 全通过 |
| NikonFocusMetadataTest | 4 | 全通过 |
| PhotoBrowserTest | 3 | 全通过 |
| PtpIpProtocolTest | 16 | 全通过 |
| PtpProtocolTest | 13 | 全通过 |
| RemoteCameraTest | 10 | 全通过 |
| RemoteSessionTest | 5 | 全通过 |
| ShootingParametersTest | 13 | 全通过 |
| TransferEngineTest | 17 | 全通过 |
| UsbSessionInitializerTest | 20 | 全通过 |
| ScreenTest | 33 | 全通过 |
| WifiDeletionTest | 5 | 全通过 |
| WifiRouteTest | 4 | 全通过 |

## 安装包校验

- 文件：`Z8Transfer-v0.7.0.apk`
- 大小：29,009,784 字节
- 包名：`com.liuzh.z8transfer.wifi`，versionName 0.7.0 / versionCode 9。
- SHA-256：`61eec3b77bc58c726ccb867cef2ef9ee4529cdcd66a442c3927fa3aed2862967`
- 签名证书 SHA-256：`513855ef2625e9748888bf597768d9e056b297b80ec592e09d950203c45e398e`，沿用 0.6.0 测试签名，可覆盖安装。

## 实机边界与协议依据

本次没有连接真实 Z8 或手机，没有实际修改机身参数、触发快门或删除文件。选项、可写性和实时生效效果取决于固件、P/A/S/M、镜头、自动 ISO、机身锁定及当前连接模式；设备拒绝时显示原因，请先在机身检查。调参期间取景请求会短暂停顿，未承诺零延迟或固定帧率。Android 系统 MTP 继续不提供遥控与参数控制，请使用相机开放能力的 Wi-Fi / 标准 USB PTP；标准 PTP 的既有手机兼容性问题保留。

编码和指令核对来源：[libgphoto2 ptp.h](https://github.com/gphoto/libgphoto2/blob/master/camlibs/ptp2/ptp.h)、[config.c](https://github.com/gphoto/libgphoto2/blob/master/camlibs/ptp2/config.c)、[ptp.c](https://github.com/gphoto/libgphoto2/blob/master/camlibs/ptp2/ptp.c)、[ptpip.c](https://github.com/gphoto/libgphoto2/blob/master/camlibs/ptp2/ptpip.c)。没有引入该项目的代码依赖。

---

# Z8 Transfer v0.6.0：遥控与下载独立页面，按需加载相册

日期：2026-11-21。基于 0.5.0 提交 `b365cecc3c38a630774bdd59a5450ad6a2f81027`。

## 问题与新行为

旧版连接成功立即扫描全卡；扫描状态禁用遥控入口；退出遥控拍照后又自动扫描，导致拍摄依赖相册加载完成。

- 初次连接后停留在功能首页，不读取照片列表。
- 「遥控拍摄」是独立页面，直接建立取景/拍照会话，保留明确的快门按钮。
- 仅进入「下载照片」后扫描相册。先选择下载页再连接也视为显式请求；先选择遥控页则在前台连接完成后直接开启遥控。
- 下载页扫描未结束时，遥控入口仍可用；取消扫描、等待当前相机事务结束后再开启取景，保留同一连接。
- 退出遥控、返回首页或后台停止不触发相册扫描；拍摄后的新文件在用户进入下载页时读取。
- 新一轮下载页访问更新列表，当前页重复点击不重复扫描；重新加载仍保留有效选择。旧扫描回调按连接、页面和扫描代次过滤。
- 真实原片下载、删除任务继续保持任务期间的切换限制，避免打断写入或删除。
- 相机连接在后台完成时不自动启动遥控；回到前台后需显式开启。

## 验证范围

CameraNavigationTest 使用真实 AppController 的连接与页面切换路径，注入可长时间保持未完成扫描的模拟相机，验证：连接时零扫描、遥控与快门零扫描、先选择页面再连接、取消未完成扫描并忽略迟到回调、拍后退出不扫描、下载页显式更新、原有选择保留、读取相册失败不妨碍遥控，以及后台连接不启动取景。

Compose 检查首页不显示相册、扫描期间遥控按钮可用、遥控全页没有列表控件、可直接切到下载页；同时回归单张预览删除、RAW 配套删除和所有既有协议/传输测试。

```bash
./gradlew --max-workers=2 :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

**135 项测试全部通过，Lint 0 错误，Debug APK 构建成功**。Lint 有 6 条固定依赖版本更新提示，未升级依赖、未忽略功能/API 检查。apksigner 验签与 zipalign 16 KiB 对齐检查通过。

| 测试集 | 用例数 | 结果 |
|---|---:|---|
| CameraNavigationTest | 9 | 全通过 |
| DeletionEngineTest | 7 | 全通过 |
| NikonFocusMetadataTest | 4 | 全通过 |
| PhotoBrowserTest | 3 | 全通过 |
| PtpIpProtocolTest | 14 | 全通过 |
| PtpProtocolTest | 11 | 全通过 |
| RemoteCameraTest | 10 | 全通过 |
| RemoteSessionTest | 3 | 全通过 |
| TransferEngineTest | 17 | 全通过 |
| UsbSessionInitializerTest | 19 | 全通过 |
| ScreenTest | 30 | 全通过 |
| WifiDeletionTest | 4 | 全通过 |
| WifiRouteTest | 4 | 全通过 |

## 安装包校验

- 文件：`Z8Transfer-v0.6.0.apk`
- 大小：28,862,768 字节
- 包名：`com.liuzh.z8transfer.wifi`，versionName 0.6.0 / versionCode 8。
- SHA-256：`643831b7710604755cd11f4e8e1f9ee2cbe7f10c4e420bb0c7e0d7ce8a90cdd8`
- 签名证书 SHA-256：`513855ef2625e9748888bf597768d9e056b297b80ec592e09d950203c45e398e`，与 0.5.0 相同，可覆盖更新。

## 实机边界

本次未连接真实 Z8 或手机，未实际触发快门或删除文件。扫描取消需要当前正在读取的相机响应先完成；不会强行中断协议数据流，也不能消除相机自身的连接、开取景或响应延迟。默认系统 MTP 的遥控限制及标准 USB PTP 的已知兼容性边界保留。

---

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
