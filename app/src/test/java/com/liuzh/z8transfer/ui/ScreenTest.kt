package com.liuzh.z8transfer.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.liuzh.z8transfer.AppState
import com.liuzh.z8transfer.core.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.TimeUnit
import androidx.test.espresso.IdlingPolicies

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Before fun failQuicklyOnLayoutLoops() { IdlingPolicies.setMasterPolicyTimeout(15, TimeUnit.SECONDS) }
    private fun photo(handle: Int = 1, name: String = "DSC_0001.NEF", kind: PhotoKind = PhotoKind.RAW) = CameraPhoto(
        "preview-session", "camera-preview", 1, "card-preview", "CFexpress", handle,
        "DCIM/100NZ_8", name, if (kind == PhotoKind.NEV) 6L * 1024 * 1024 * 1024 else 12_582_912L,
        1_793_930_400_000L, 1_793_930_400_000L, kind,
    )
    private fun connected(files: List<CameraPhoto> = listOf(photo())) = AppState(storageReady = true, cameraName = "Nikon Z8", photos = files, supportsDeletion = true)

    @Test fun disconnectedScreenGuidesConnectionWithoutDestructiveActions() {
        var connections = 0
        compose.setContent { Z8Theme { TransferContent(AppState(storageReady = true), { connections++ }, {}, {}, {}, {}, {}, {}, {}, {}, { null }) } }
        compose.onNodeWithText("删除相机文件").assertDoesNotExist()
        compose.onNodeWithText("连接相机").performClick()
        assertEquals(1, connections)
        snapshot("v02-disconnected")
    }

    @Test fun selectingFileEnablesOriginalDownload() {
        var state by mutableStateOf(connected())
        var downloads = 0
        compose.setContent { Z8Theme { TransferContent(state, {}, {}, {}, { p -> state = state.copy(selected = state.selected + p.key) },
            {}, {}, { downloads++ }, {}, {}, { null }) } }
        compose.onNodeWithContentDescription("选择 DSC_0001.NEF").performClick()
        compose.onNodeWithText("下载 1 个原文件").assertIsEnabled().performClick()
        assertEquals(1, downloads)
        snapshot("v02-selection")
    }

    @Test fun tappingFileOpensFullScreenInsteadOfSmallDialog() {
        compose.setContent { Z8Theme { TransferContent(connected(), {}, {}, {}, {}, {}, {}, {}, {}, {}, { null }) } }
        compose.onNodeWithText("DSC_0001.NEF").performClick()
        val bounds = compose.onNodeWithTag("full-media-viewer").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.height > bounds.width * 1.5f)
        compose.onNodeWithText("选择此文件").assertIsDisplayed()
        compose.onNodeWithText("返回").performClick()
        compose.onNodeWithTag("full-media-viewer").assertDoesNotExist()
    }

    @Test fun upwardSwipeHidesToolsAndExpandsGridAndCanRestore() {
        var immersive = false
        compose.setContent { Z8Theme { TransferContent(connected(), {}, {}, {}, {}, {}, {}, {}, {}, {}, { null }, onImmersiveChange = { immersive = it }) } }
        val before = compose.onNodeWithTag("file-grid").fetchSemanticsNode().boundsInRoot.height
        compose.onNodeWithTag("file-grid").performTouchInput { swipeUp() }
        compose.onNodeWithTag("browser-tools").assertDoesNotExist()
        val after = compose.onNodeWithTag("file-grid").fetchSemanticsNode().boundsInRoot.height
        assertTrue(after > before + 100)
        compose.runOnIdle { assertTrue(immersive) }
        snapshot("v02-fullscreen-grid")
        compose.onNodeWithText("展开工具栏").performClick()
        compose.onNodeWithTag("browser-tools").assertExists()
        compose.runOnIdle { assertFalse(immersive) }
    }

    @Test fun rawVideoAppearsAndUsesExplicitProxyPlayback() {
        val raw = photo(1, "CLIP.NEV", PhotoKind.NEV)
        val proxy = photo(2, "CLIP.MP4", PhotoKind.MP4)
        compose.setContent { Z8Theme { TransferContent(connected(listOf(raw, proxy)), {}, {}, {}, {}, {}, {}, {}, {}, {}, { null }) } }
        compose.onAllNodesWithText("N-RAW")[0].performClick()
        compose.onNodeWithText("CLIP.NEV").assertIsDisplayed().performClick()
        compose.onNodeWithText("N-RAW 原文件可下载；播放使用同目录、同名 MP4 代理").assertIsDisplayed()
        compose.onNodeWithText("播放 MP4 代理").assertIsDisplayed()
        // The filtered viewer contains only N-RAW, but the playback callback resolves
        // proxies against the entire camera list, not just the visible category.
        compose.onNodeWithTag("full-media-viewer").assertIsDisplayed()
    }

    @Test fun deletionDoesNotExecuteUntilIrreversibleActionIsAcknowledged() {
        val file = photo()
        var deletions = 0
        compose.setContent { Z8Theme { DeleteConfirmation(DeletionPlan(file.sessionId, listOf(file)), true, {}, { deletions++ }) } }
        compose.onNodeWithText("永久删除 1 个文件").assertIsNotEnabled()
        assertEquals(0, deletions)
        compose.onNodeWithContentDescription("确认永久删除相机原文件").performClick()
        compose.onNodeWithText("永久删除 1 个文件").assertIsEnabled().performClick()
        assertEquals(1, deletions)
    }

    @Test fun cancellingDeletionDoesNotExecuteTheAction() {
        var deletions = 0
        var dismissed = false
        val p = photo()
        compose.setContent { Z8Theme { DeleteConfirmation(DeletionPlan(p.sessionId, listOf(p)), true, { dismissed = true }, { deletions++ }) } }
        compose.onNodeWithText("取消").performClick()
        assertTrue(dismissed); assertEquals(0, deletions)
    }

    @Test fun disconnectedConfirmationStaysDisabledEvenAfterAcknowledgement() {
        val p = photo()
        compose.setContent { Z8Theme { DeleteConfirmation(DeletionPlan(p.sessionId, listOf(p)), false, {}, { fail("Must not delete") }) } }
        compose.onNodeWithContentDescription("确认永久删除相机原文件").performClick()
        compose.onNodeWithText("永久删除 1 个文件").assertIsNotEnabled()
    }

    @Test fun wifiEntryOpensSetupSettingsAndStartsConnectionWithAutomaticAddress() {
        var settings = 0
        var endpoint: WifiEndpoint? = null
        compose.setContent { Z8Theme { TransferContent(AppState(storageReady = true), {}, {}, {}, {}, {}, {}, {}, {}, {}, { null },
            onWifiConnect = { endpoint = it }, onWifiSettings = { settings++ }) } }
        compose.onNodeWithText("Wi-Fi 连接").performClick()
        compose.onNodeWithText("连接 Z8 Wi-Fi").assertIsDisplayed()
        compose.onNodeWithText("打开手机 Wi-Fi 设置").performScrollTo().performClick()
        assertEquals(1, settings)
        compose.onNodeWithText("连接 Wi-Fi 相机").assertIsEnabled().performClick()
        assertEquals(WifiEndpoint(), endpoint)
        compose.onNodeWithText("连接 Z8 Wi-Fi").assertDoesNotExist()
        snapshot("v03-wifi-entry")
    }

    @Test fun wifiSetupRejectsInvalidAddressOrPortAndAcceptsManualLocalAddress() {
        var endpoint: WifiEndpoint? = null
        compose.setContent { Z8Theme { WifiConnectionDialog(true, WifiEndpoint(), {}, { endpoint = it }, {}) } }
        compose.onNodeWithText("相机 IPv4 地址").performScrollTo().performTextInput("8.8.8.8")
        compose.onNodeWithText("连接 Wi-Fi 相机").assertIsNotEnabled()
        compose.onNodeWithText("相机 IPv4 地址").performTextReplacement("192.168.1.1")
        compose.onNodeWithText("端口").performScrollTo().performTextReplacement("0")
        compose.onNodeWithText("连接 Wi-Fi 相机").assertIsNotEnabled()
        compose.onNodeWithText("端口").performTextReplacement("15740")
        snapshot("v03-wifi-setup")
        compose.onNodeWithText("连接 Wi-Fi 相机").assertIsEnabled().performClick()
        assertEquals(WifiEndpoint("192.168.1.1"), endpoint)
    }

    @Test fun wifiConnectionShowsCorrectTransportAndAllowsDownloadButNoWirelessDeletion() {
        val p = photo()
        val state = connected().copy(connectionLabel = "Wi-Fi", selected = setOf(p.key), supportsDeletion = false,
            deletionUnavailableReason = "Wi-Fi 测试版支持浏览与下载；删除相机文件请使用 USB 连接")
        compose.setContent { Z8Theme { TransferContent(state, {}, {}, {}, {}, {}, {}, {}, {}, {}, { null }) } }
        compose.onNodeWithText("● Wi-Fi 已连接 · 照片与视频").assertIsDisplayed()
        compose.onNodeWithText("下载 1 个原文件").assertIsEnabled()
        compose.onNodeWithText("删除相机文件").assertIsNotEnabled()
    }

    @Test fun wifiSetupCannotStartDuplicateConnectionWhileBusy() {
        compose.setContent { Z8Theme { WifiConnectionDialog(false, WifiEndpoint(), {}, { fail("duplicate connection") }, {}) } }
        compose.onNodeWithText("连接 Wi-Fi 相机").assertIsNotEnabled()
        snapshot("v03-wifi-dialog")
    }

    @Test fun usbFailureShowsBothDriversAndCanCopyCompleteError() {
        val message = "USB 连接失败\n标准 PTP：读取设备信息（0x1001）超时\n系统 MTP：无法读取相机信息\nUSB VID/PID：04b0/0451"
        compose.setContent { Z8Theme { TransferContent(AppState(storageReady = true, error = message), {}, {}, {}, {}, {}, {}, {}, {}, {}, { null }) } }
        compose.onNodeWithText("USB 连接失败").assertIsDisplayed()
        compose.onNodeWithText("查看详情").performClick()
        compose.onNodeWithText("错误详情").assertIsDisplayed()
        compose.onNodeWithText(message).assertExists()
        compose.onNodeWithText("复制信息").performClick()
        val clipboard = compose.activity.getSystemService(android.content.ClipboardManager::class.java)
        assertEquals(message, clipboard.primaryClip!!.getItemAt(0).text.toString())
        compose.onNodeWithText("已复制").assertIsDisplayed()
        snapshot("v031-usb-error-details")
    }

    @Test fun usbDefaultsToSystemMtpAndOnlyChangesAfterExplicitSelection() {
        var state by mutableStateOf(AppState(storageReady = true))
        var attempts = 0
        compose.setContent { Z8Theme { TransferContent(state, { attempts++ }, {}, {}, {}, {}, {}, {}, {}, {}, { null },
            onUsbModeChange = { state = state.copy(usbMode = it) }) } }
        assertEquals(UsbConnectionMode.SYSTEM_MTP, state.usbMode)
        compose.onNodeWithText("系统 MTP（推荐） ▾").assertIsDisplayed()
        snapshot("v032-system-mtp-default")
        compose.onNodeWithTag("usb-mode-selector").performClick()
        compose.onNodeWithText("标准 PTP（实验）").performClick()
        assertEquals(UsbConnectionMode.DIRECT_PTP, state.usbMode)
        assertEquals(0, attempts)
        compose.onNodeWithText("连接相机").performClick()
        assertEquals(1, attempts)
    }

    @Test fun usbModeCannotBeChangedDuringConnectionAndIsHiddenWhenConnected() {
        var state by mutableStateOf(AppState(storageReady = true, connecting = true))
        compose.setContent { Z8Theme { TransferContent(state, {}, {}, {}, {}, {}, {}, {}, {}, {}, { null }) } }
        compose.onNodeWithTag("usb-mode-selector").assertIsNotEnabled()
        compose.runOnIdle { state = connected().copy(connectionLabel = "USB · 系统 MTP") }
        compose.onNodeWithTag("usb-mode-selector").assertDoesNotExist()
        compose.onNodeWithText("● USB · 系统 MTP 已连接 · 照片与视频").assertIsDisplayed()
    }

    private fun snapshot(name: String) {
        val directory = System.getProperty("z8.screenshotDir") ?: return
        val file = File(directory, "$name.png").apply { parentFile?.mkdirs() }
        compose.runOnIdle {
            val view = org.robolectric.shadows.ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }?.window?.decorView
                ?: compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
