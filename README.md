# Z8 Transfer

面向 **Nikon Z8** 的 Android 照片与视频传输 App，支持 USB 有线连接和 Wi-Fi 热点直连，可浏览相机文件、全屏预览、选择下载原文件，并通过 USB 删除相机文件。

使用 **Kotlin + Jetpack Compose** 开发，当前优先适配 **小米 17 Pro Max**。

> 当前版本：**0.3.2（开发测试版）**。新版默认采用 Android 系统 MTP 连接。自动化检查已通过，Z8 与目标手机的新版 USB 回归、Wi-Fi 兼容性和实际传输速度仍待实机验证。

## 功能

| 功能 | 当前实现 |
| --- | --- |
| USB 连接 | 默认系统 MTP；可手动选择实验性标准 PTP |
| Wi-Fi 连接 | 相机 AP 热点直连、地址自动推断、手动 IPv4 与端口 |
| 文件浏览 | 存储卡、文件名、日期、文件类型筛选，支持 1 / 2 / 3 列网格 |
| 全屏预览 | 上滑收起工具栏，点按全屏、左右切换，照片双指缩放和双击放大 |
| 批量选择 | 勾选、长按选择、全选当前筛选结果 |
| 原文件下载 | 流式保存、进度与速度显示、后台通知、重复导入检查、失败项重试 |
| 相机文件删除 | USB 下按相机能力开放，逐项复核并二次确认 |
| 连接诊断 | 展示错误详情，可复制设备和连接信息用于排查 |

文件是否可见取决于相机固件及连接模式暴露的内容。App 无法读取相机未提供的文件。

## 当前限制

- **系统 MTP 暂不传输大于等于 4 GiB、大小为 `0xffffffff` 标记或大小未知的文件**，避免生成截断副本；这些文件仍可出现在列表中。
- 标准 PTP 为实验功能。目标手机曾出现 USB 命令发送失败，尚未解决，不作为当前手机的大视频传输方案。
- **不支持 N-RAW / ProRes RAW 原生解码**。能够显示或下载文件，不代表能够在手机上播放。
- Wi-Fi 尚待真机验证；暂不支持电脑 / PIN 配对、STA 专有认证、蓝牙唤醒、自动重连及无线删除。
- 暂无单文件断点续传和进程退出后的队列恢复；点击停止会在当前文件完成后停止后续任务。
- 目前没有真实设备的 USB / Wi-Fi 速度对比数据，也未验证其他机型的兼容性。

## 连接与使用

### USB 有线连接（优先）

1. 用支持数据传输的数据线连接手机与 **Z8 的 USB 数据接口**，相机的 USB 数据连接选择 **MTP/PTP**。
2. 退出其他正在访问相机的 App，避免同时占用设备。
3. 打开 App，保持 **系统 MTP（推荐）**，点击连接并在 Android 弹窗中允许 USB 访问。
4. 连接后浏览文件；点按预览，勾选或长按选择，再下载原文件。
5. 上滑可收起顶部工具栏；向下拉或点击「展开工具栏」恢复。

一次连接只使用选中的驱动，失败后不会自动切换到另一驱动。实验性标准 PTP 只能在未连接时主动选择，新进程启动恢复默认系统 MTP。

### Wi-Fi 热点直连（测试）

1. 在相机打开「**网络菜单 → 连接至智能设备 → Wi-Fi 连接（AP 模式）→ 建立 Wi-Fi 连接**」。菜单名称可能随固件不同。
2. 在手机系统 Wi-Fi 设置中连接相机显示的热点并输入密码；提示无法上网时，选择保持连接。
3. 退出 SnapBridge 等占用相机连接的 App，返回本 App，打开「Wi-Fi 连接」。
4. 优先使用自动地址；失败时填写相机实际局域网 IPv4 地址，默认端口为 **15740**。
5. 连接后即可浏览、预览和选择下载。断开后需要手动重连。

App 将相机通信的 socket 单独绑定到 Wi-Fi，以适应手机同时开启蜂窝数据的情况；当前需要手机与相机地址处于可直接访问的同一网段。

## 格式与预览

| 文件类型 | 列表与下载 | 预览方式 |
| --- | --- | --- |
| JPEG、HEIF / HIF / HEIC | 支持，受连接方式及大小限制 | 缩略图；不超过 64 MiB 时尝试缓存原文件，解码最长边约 2560 像素 |
| NEF 照片 RAW | 支持，受连接方式及大小限制 | 优先使用同卡、同目录、同名 JPEG，否则使用相机缩略图；无完整 NEF 解码 |
| NEV（N-RAW） | 支持显示；下载受大文件限制 | 可使用同卡、同目录、同名 MP4 代理；无 N-RAW 解码 |
| MOV、MP4 | 支持，受连接方式及大小限制 | 优先使用同名 MP4 代理；通过 Android 播放器尝试播放，受实际编码支持限制 |
| 其他普通文件 | 相机暴露时可列出与下载 | 不承诺预览支持 |

视频临时预览的单文件上限为 **512 MiB**。MOV 是容器格式，不能仅凭扩展名判断是否为 RAW 或是否可播放。预览缓存不会替代正式下载。

### 保存位置与完整性

通过 Android MediaStore 保存到手机共享存储，目录保留相机、存储卡和原始文件夹的区分：

| 文件 | 保存目录 |
| --- | --- |
| JPEG、NEF、HEIF 照片 | `Pictures/Z8Transfer/<相机标识>/<存储卡>/<原始目录>/` |
| MOV、MP4 及其他已识别的视频 | `Movies/Z8Transfer/<相机标识>/<存储卡>/<原始目录>/` |
| NEV 及其他文件 | `Download/Z8Transfer/<相机标识>/<存储卡>/<原始目录>/` |

- 以原始字节流传输，不压缩、不转码。
- 已知大小的文件需通过实际字节数和协议 / 驱动结果检查后才发布；失败时回滚未完成副本。
- 支持根据来源元数据及本地副本的存在与长度检查重复导入；这不等同于源文件与副本的内容哈希校验。
- 对支持未知长度流的实验连接，会标记未独立验证源文件大小，并且不自动去重。
- 使用前台服务显示后台传输通知；实际后台表现仍受手机系统策略影响。

### 删除相机文件

仅在 USB 连接且相机声明支持删除时启用。确认界面列出固定文件清单，需要明确确认永久删除；执行前复核连接、存储卡、文件元数据及保护状态。

只删除所选相机文件，不自动连带删除同名 RAW、JPEG 或代理文件，也不删除手机已有的下载副本。不支持目录或通配删除，失败不会自动重试。**相机原文件删除不可撤销，测试前请先备份。**

## 开发与构建

### 环境

| 项目 | 配置 |
| --- | --- |
| 语言 / UI | Kotlin 2.2.10 / Jetpack Compose |
| Gradle / Android Gradle Plugin | 8.13 / 8.13.2 |
| JDK | 17 或 21，JVM 目标为 17 |
| Android SDK | compileSdk 36 / targetSdk 36 |
| 最低系统版本 | Android 10（API 29） |
| Compose BOM | 2025.08.00 |

用 Android Studio 打开包含 `settings.gradle.kts` 的项目根目录，安装 Android SDK Platform 36 及构建工具，并配置本机 SDK 路径。也可通过 `ANDROID_HOME` 或不提交到 Git 的 `local.properties` 指定 SDK。

首次构建需要联网下载 Gradle 和 Maven 依赖。工程已包含 Gradle Wrapper，无需单独安装 Gradle。

### 构建测试安装包

macOS / Linux，在项目根目录执行：

```bash
chmod +x gradlew
./gradlew :app:assembleDebug
```

Windows PowerShell：

```powershell
.\gradlew.bat :app:assembleDebug
```

生成文件：`app/build/outputs/apk/debug/app-debug.apk`。

连接已开启 USB 调试的手机后，可通过 Android Studio 安装，或执行：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 版本与签名

| 项目 | 当前 Debug 构建 |
| --- | --- |
| 应用名称 | Z8 Transfer Wi-Fi（同时支持 USB） |
| applicationId | `com.liuzh.z8transfer.wifi` |
| versionName / versionCode | 0.3.2 / 5 |

工程包含公开测试密钥 `signing/wifi-test.keystore`，别名为 `z8wifi`，store / key 密码均为 `android`。它只用于测试版连续更新，**正式发布必须配置自己的私有签名密钥**。

当前测试包与 0.3.0 / 0.3.1 使用相同签名，可覆盖安装；0.1 / 0.2 的包名和签名不同，会作为独立应用共存。测试时请退出旧版，避免争用相机。

Release 使用基础 applicationId `com.liuzh.z8transfer`，当前没有配置发布签名。不要将测试 Debug APK 当作正式发行版。

### 自动化检查

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

截至 **2026-11-09** 的记录：**87 项测试通过，Lint 0 错误、3 条依赖更新提示，Debug 构建成功**。覆盖传输完整性、删除复核、USB 协议与驱动选择、PTP/IP 模拟连接、Wi-Fi 路由及 Compose 交互。

这些检查使用模拟协议流、本机 TCP 和 Robolectric，不能替代真实相机、手机及视频编码测试。具体用例数量、APK 校验和待验证项目见 [VALIDATION.md](VALIDATION.md)。

## 项目结构

以下路径均相对于 `app/src/main/java/com/liuzh/z8transfer/`：

| 路径 | 职责 |
| --- | --- |
| [AppController.kt](app/src/main/java/com/liuzh/z8transfer/AppController.kt) | 会话、文件选择、预览缓存和任务状态 |
| [core/](app/src/main/java/com/liuzh/z8transfer/core/) | 传输接口、下载 / 删除引擎、USB 模式、PTP 与 PTP/IP 协议 |
| [usb/](app/src/main/java/com/liuzh/z8transfer/usb/) | 系统 MTP、实验标准 PTP 与 USB 诊断 |
| [wifi/](app/src/main/java/com/liuzh/z8transfer/wifi/) | Wi-Fi 路由绑定及 PTP/IP 相机连接 |
| [ui/](app/src/main/java/com/liuzh/z8transfer/ui/) | 文件网格、筛选、全屏预览和连接对话框 |
| [storage/](app/src/main/java/com/liuzh/z8transfer/storage/) | MediaStore 导入、重复检查和未完成文件清理 |
| [transfer/](app/src/main/java/com/liuzh/z8transfer/transfer/) | 前台传输服务 |

测试位于 [app/src/test/](app/src/test/)，Android 权限与组件声明见 [AndroidManifest.xml](app/src/main/AndroidManifest.xml)。App 需要 USB 访问授权及相应的网络 / 前台服务权限，通知权限用于展示传输状态；写入自身导入文件使用 MediaStore，无需相册全量读取权限。

## 问题排查与反馈

| 现象 | 建议 |
| --- | --- |
| USB 无法读取设备信息或存储卡 | 确认数据线、USB 数据接口及 MTP/PTP 模式，退出其他相机 App，拔线重连并保留默认系统 MTP |
| 标准 PTP 提示命令发送失败 | 属于已知待排查问题，重新建立连接后使用默认系统 MTP；切换驱动不会自动修复底层 USB 问题 |
| Wi-Fi 超时或拒绝连接 | 确认保持连接相机热点、使用智能设备 AP 模式，检查实际 IP / 端口并退出其他相机 App |
| RAW 视频有文件但无法播放 | 使用兼容播放器或同名 MP4 代理；本 App 未实现 N-RAW / ProRes RAW 解码 |
| 大视频无法下载 | 先确认当前连接的大文件限制；系统 MTP 不支持本版限制范围之外的文件 |

反馈时请附上 App 版本、相机固件、手机型号与 Android 版本、连接方式、复现步骤及「查看详情 → 复制信息」的错误内容。请勿公开 Wi-Fi 密码、设备序列号等个人信息。

## 后续方向

- 优先完成 Z8 + 小米 17 Pro Max 的 USB 实机回归与大文件传输验证。
- 验证不同相机固件下的 Wi-Fi 热点连接与断线处理。
- 改进 RAW / 视频代理预览及传输任务恢复。

## 许可证

当前尚未指定许可证，仓库未包含 `LICENSE` 文件。

本项目独立开发，与 Nikon、小米官方无隶属关系。

