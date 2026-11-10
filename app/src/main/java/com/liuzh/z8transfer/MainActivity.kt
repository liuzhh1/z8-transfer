package com.liuzh.z8transfer

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Column
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.liuzh.z8transfer.transfer.TransferService
import com.liuzh.z8transfer.ui.Z8Screen
import com.liuzh.z8transfer.ui.Z8Theme
import com.liuzh.z8transfer.core.DeletionPlan
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : ComponentActivity() {
    private val controller get() = (application as Z8Application).controller
    private val usbManager get() = getSystemService(UsbManager::class.java)
    private val permissionAction get() = "$packageName.USB_PERMISSION"
    private var candidates by mutableStateOf<List<UsbDevice>>(emptyList())
    private var retryAfterPermission = false
    private val notificationsPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        beginDownload(retryAfterPermission)
    }
    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != permissionAction) return
            val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java) ?: return
            val attached = usbManager.deviceList.values.firstOrNull { it.deviceName == device.deviceName && it.deviceId == device.deviceId }
            if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && attached != null && usbManager.hasPermission(attached)) {
                controller.connect(attached)
            } else controller.showError("未获得相机 USB 权限，请点击连接并允许访问")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        retryAfterPermission = savedInstanceState?.getBoolean("retryAfterPermission") ?: false
        enableEdgeToEdge()
        ContextCompat.registerReceiver(this, permissionReceiver, IntentFilter(permissionAction), ContextCompat.RECEIVER_NOT_EXPORTED)
        setContent {
            Z8Theme {
                Z8Screen(controller, onConnect = ::discover, onDownload = ::download,
                    onDelete = ::beginDeletion, onImmersiveChange = ::setViewerImmersive,
                    onWifiSettings = ::openWifiSettings)
                if (candidates.isNotEmpty()) AlertDialog(
                    onDismissRequest = { candidates = emptyList() },
                    title = { Text("选择相机") },
                    text = { Column { candidates.forEach { device ->
                        TextButton(onClick = { candidates = emptyList(); requestConnection(device) }) {
                            Text("${device.productName ?: "Nikon 相机"} · USB ${device.deviceId}")
                        }
                    } } },
                    confirmButton = {},
                )
            }
        }
        handleAttach(intent)
    }

    private fun discover() {
        val devices = usbManager.deviceList.values.filter { it.vendorId == 0x04b0 }
        when (devices.size) {
            0 -> controller.showError("未检测到 Nikon 相机。请使用支持数据传输的 USB 线连接 Z8 的数据接口，并开启手机 OTG（如有此开关）")
            1 -> requestConnection(devices.single())
            else -> candidates = devices
        }
    }

    private fun openWifiSettings() {
        try { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }
        catch (_: Exception) { controller.showError("无法打开系统设置，请手动在手机设置中连接相机 Wi-Fi 后返回") }
    }

    private fun requestConnection(device: UsbDevice) {
        if (controller.state.value.busy) return
        if (usbManager.hasPermission(device)) controller.connect(device) else {
            // Package-scoped mutable intent lets the USB system supply its result extras.
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val result = PendingIntent.getBroadcast(this, device.deviceId, Intent(permissionAction).setPackage(packageName), flags)
            usbManager.requestPermission(device, result)
        }
    }

    private fun download(retryFailures: Boolean) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            retryAfterPermission = retryFailures
            notificationsPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else beginDownload(retryFailures)
    }

    private fun beginDownload(retryFailures: Boolean) {
        if (!controller.queueDownload(retryFailures)) return
        try {
            ContextCompat.startForegroundService(this, Intent(this, TransferService::class.java))
        } catch (error: Exception) {
            controller.failToStart("无法启动下载：${error.message}")
        }
    }

    private fun beginDeletion(plan: DeletionPlan) {
        if (!controller.queueDeletion(plan)) return
        try {
            ContextCompat.startForegroundService(this, Intent(this, TransferService::class.java).setAction(TransferService.ACTION_DELETE))
        } catch (error: Exception) { controller.failToStart("无法启动删除任务：${error.message}") }
    }

    private fun setViewerImmersive(enabled: Boolean) {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (enabled) hide(WindowInsetsCompat.Type.systemBars()) else show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun handleAttach(intent: Intent?) {
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                ?.takeIf { it.vendorId == 0x04b0 }?.let(::requestConnection)
        }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handleAttach(intent) }
    override fun onSaveInstanceState(outState: Bundle) { outState.putBoolean("retryAfterPermission", retryAfterPermission); super.onSaveInstanceState(outState) }
    override fun onDestroy() { unregisterReceiver(permissionReceiver); super.onDestroy() }
}
