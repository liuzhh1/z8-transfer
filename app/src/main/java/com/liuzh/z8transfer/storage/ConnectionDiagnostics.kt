package com.liuzh.z8transfer.storage

import android.content.Context
import android.hardware.usb.UsbManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import java.net.Inet4Address
import java.util.Locale

/** Read local status on demand; do not probe or modify any camera connection. */
@Suppress("DEPRECATION")
fun connectionDiagnostics(context: Context, sessionReport: String): String = buildString {
    val power = context.getSystemService(PowerManager::class.java)
    val usb = context.getSystemService(UsbManager::class.java)
    val connectivity = context.getSystemService(ConnectivityManager::class.java)
    appendLine("Z8 Transfer ${context.packageManager.getPackageInfo(context.packageName, 0).versionName}")
    appendLine("手机：${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
    appendLine("通知：${if (NotificationManagerCompat.from(context).areNotificationsEnabled()) "已允许" else "未允许，后台下载通知可能不可见"}")
    appendLine("省电模式：${if (power?.isPowerSaveMode == true) "开启" else "关闭"}")
    appendLine("电池优化：${if (power?.isIgnoringBatteryOptimizations(context.packageName) == true) "已豁免" else "未豁免；息屏后下载可能受系统限制"}")
    val devices = usb?.deviceList?.values.orEmpty().filter { it.vendorId == 0x04b0 }
    appendLine("Nikon USB 设备：${devices.size}")
    devices.forEach { device ->
        appendLine(String.format(Locale.ROOT, "  VID/PID %04x/%04x · 权限 %s · 接口 %d", device.vendorId,
            device.productId, if (usb?.hasPermission(device) == true) "已允许" else "未允许", device.interfaceCount))
    }
    val networks = connectivity?.allNetworks.orEmpty()
    var wifi = false
    var vpn = false
    networks.forEach { network ->
        val capabilities = connectivity?.getNetworkCapabilities(network) ?: return@forEach
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) vpn = true
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            wifi = true
            val links = connectivity.getLinkProperties(network)
            val addresses = links?.linkAddresses.orEmpty().filter { it.address is Inet4Address }
                .joinToString { "${it.address.hostAddress}/${it.prefixLength}" }
            appendLine("Wi-Fi：${links?.interfaceName ?: "未知接口"} · ${addresses.ifBlank { "尚无 IPv4 地址" }}")
        }
    }
    if (!wifi) appendLine("Wi-Fi：当前未检测到已连接网络")
    appendLine("VPN：${if (vpn) "检测到 VPN；相机连接异常时检查其局域网规则" else "未检测到"}")
    val manufacturer = Build.MANUFACTURER.lowercase(Locale.ROOT)
    when {
        manufacturer.contains("xiaomi") || manufacturer.contains("redmi") ->
            appendLine("小米/红米：在应用省电策略中允许后台运行；相机热点没有互联网时选择保持连接，检查智能网络切换设置。")
        manufacturer.contains("samsung") ->
            appendLine("三星：检查应用是否进入休眠/深度休眠列表；相机热点连接不稳定时检查智能 Wi-Fi 切换设置。")
        manufacturer.contains("huawei") || manufacturer.contains("honor") ->
            appendLine("华为/荣耀：检查应用启动管理与后台运行权限。")
    }
    appendLine("\n当前会话")
    append(sessionReport.ifBlank { "尚未连接相机。USB 请确认数据线、数据接口与访问权限；Wi-Fi 请先连接相机热点。" })
}
