package com.liuzh.z8transfer.wifi

import android.content.Context
import android.annotation.SuppressLint
import android.os.ParcelFileDescriptor
import androidx.core.content.edit
import com.liuzh.z8transfer.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

class WifiPtpTransport private constructor(
    private val route: WifiRoute,
    private val protocol: PtpIpProtocol,
    override val displayName: String,
    private val identity: String,
    private val operations: Set<Int>,
    override val diagnostics: String,
) : CameraTransport {
    override val sessionId: String = UUID.randomUUID().toString()
    override val connectionLabel: String = "Wi-Fi"
    private val live = AtomicBoolean(true)
    private val gate = Mutex()
    override val attached: Boolean get() = live.get() && protocol.usable && route.available
    // This first wireless build deliberately exposes no destructive Wi-Fi operation.
    // USB retains its checked DeleteObject implementation.
    override val deletionUnavailableReason: String = "Wi-Fi 测试版支持浏览与下载；删除相机文件请使用 USB 连接"
    var onDisconnected: (() -> Unit)?
        get() = protocol.onDisconnected
        set(value) { protocol.onDisconnected = value }

    override suspend fun scan(onBatch: (List<CameraPhoto>) -> Unit, onExamined: (Int) -> Unit) = withContext(Dispatchers.IO) {
        gate.withLock {
            checkLive()
            val stores = PtpData(protocol.command(0x1004).requireOk().data).array32()
            var examined = 0
            val batch = mutableListOf<CameraPhoto>()
            for (storage in stores) {
                val p = PtpData(protocol.command(0x1005, intArrayOf(storage)).requireOk().data)
                p.u16(); p.u16(); p.u16()
                val capacity = p.u64(); p.u64(); p.u32()
                val label = p.string().ifBlank { "存储卡" }
                val volume = p.string().ifBlank { "session:$sessionId" }
                val directories = ArrayDeque<Pair<Int, String>>()
                val seen = mutableSetOf<Int>()
                directories.add(-1 to "")
                while (directories.isNotEmpty()) {
                    currentCoroutineContext().ensureActive(); checkLive()
                    val (parent, folder) = directories.removeFirst()
                    val handles = PtpData(protocol.command(0x1007, intArrayOf(storage, 0, parent)).requireOk().data).array32()
                    for (handle in handles) {
                        currentCoroutineContext().ensureActive(); checkLive()
                        if (handle == 0 || handle == -1 || !seen.add(handle)) continue
                        val info = objectInfo(handle)
                        check(info.storage == storage) { "相机返回了其他存储卡的文件，请重连" }
                        examined++
                        if (info.isDirectory) directories.add(handle to listOf(folder, info.name).filter(String::isNotEmpty).joinToString("/"))
                        else batch += CameraPhoto(sessionId, identity, storage, "$volume:$capacity", label, handle,
                            folder, info.name, objectSize(handle, info.rawSize), info.created, info.modified,
                            PhotoKind.fromName(info.name)!!, info.thumbnail, info.rawSize, info.parent)
                        if (examined % 32 == 0) {
                            if (batch.isNotEmpty()) { onBatch(batch.toList()); batch.clear() }
                            onExamined(examined)
                        }
                    }
                }
            }
            if (batch.isNotEmpty()) onBatch(batch.toList())
            onExamined(examined)
        }
    }

    private fun objectInfo(handle: Int): PtpObject = PtpObject.parse(protocol.command(0x1008, intArrayOf(handle)).requireOk().data)
    private fun objectSize(handle: Int, reported: Long): Long {
        if (reported != 0xffffffffL) return reported
        if (0x9803 in operations) {
            val result = protocol.command(0x9803, intArrayOf(handle, 0xdc04))
            if (result.code == 0x2001 && result.data.size == 8) return PtpData(result.data).u64()
        }
        if (0x9805 in operations) {
            val result = protocol.command(0x9805, intArrayOf(handle, 0, 0xdc04, 0, 0))
            if (result.code == 0x2001 && result.data.size >= 20) {
                val p = PtpData(result.data)
                if (p.u32() == 1L && p.i32() == handle && p.u16() == 0xdc04 && p.u16() == 0x0008) return p.u64()
            }
        }
        return UNKNOWN_FILE_SIZE
    }

    override suspend fun thumbnail(photo: CameraPhoto): ByteArray? = withContext(Dispatchers.IO) {
        gate.withLock {
            checkPhoto(photo)
            if (0x100a !in operations || photo.thumbnailBytes !in 1..2_097_152L) return@withLock null
            val reply = protocol.command(0x100a, intArrayOf(photo.handle))
            reply.data.takeIf { reply.code == 0x2001 && it.size <= 2_097_152 }
        }
    }

    override suspend fun copyOriginal(photo: CameraPhoto, destination: OriginalDestination, progress: (Long) -> Unit) = withContext(Dispatchers.IO) {
        gate.withLock {
            checkPhoto(photo)
            val info = objectInfo(photo.handle)
            check(info.matches(photo)) { "相机文件已变化，请刷新列表" }
            if (photo.size > 0 && info.rawSize == 0xffffffffL) check(objectSize(photo.handle, info.rawSize) == photo.size) { "文件大小已变化，请刷新列表" }
            var bytes = 0L
            var lastUpdate = 0L
            ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.dup(destination.fileDescriptor)).use { stream ->
                protocol.command(0x1009, intArrayOf(photo.handle), photo.size.takeIf { it > 0 }) { buffer, offset, size ->
                    // Finish the current data phase if a preview's UI coroutine is
                    // cancelled. Leaving unread bytes would invalidate the session.
                    // Android network loss still closes both sockets immediately.
                    checkLive()
                    stream.write(buffer, offset, size); bytes += size
                    val now = System.nanoTime()
                    if (now - lastUpdate >= 200_000_000L) { progress(bytes); lastUpdate = now }
                }.requireOk()
            }
            progress(bytes)
        }
    }

    private fun checkLive() { check(attached) { "相机 Wi-Fi 已断开，请重新连接" } }
    private fun checkPhoto(photo: CameraPhoto) {
        checkLive()
        check(photo.sessionId == sessionId && photo.handle != 0 && photo.handle != -1) { "文件来自旧连接，请刷新列表" }
    }
    override fun markDetached() { live.set(false); protocol.close(); route.close() }
    override suspend fun close() = withContext(Dispatchers.IO) {
        live.set(false)
        protocol.onDisconnected = null
        route.onLost = null
        gate.withLock {
            if (protocol.usable) runCatching { protocol.command(0x1003) }
            protocol.close(); route.close()
        }
    }

    companion object {
        suspend fun open(context: Context, endpoint: WifiEndpoint): WifiPtpTransport = withContext(Dispatchers.IO) {
            val route = WifiRoute.acquire(context, endpoint)
            var protocol: PtpIpProtocol? = null
            try {
                val prefs = context.getSharedPreferences("wifi-connection", Context.MODE_PRIVATE)
                // Preserve the Boolean commit result: a failed durable GUID write
                // must stop pairing rather than changing identity on every attempt.
                @SuppressLint("UseKtx")
                val hex = prefs.getString("initiator-guid", null) ?: UUID.randomUUID().toString().replace("-", "").also {
                    check(prefs.edit().putString("initiator-guid", it).commit()) { "无法保存无线连接身份，请检查手机可用空间" }
                }
                val guid = ByteArray(16) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
                val opened = PtpIpProtocol.connect(guid, "Z8 Transfer Android") { route.socket() }
                protocol = opened
                route.onLost = opened::abort
                check(route.available && opened.usable) { "相机 Wi-Fi 已断开" }
                opened.command(0x1002, intArrayOf(1)).requireOk()
                val p = PtpData(opened.command(0x1001).requireOk().data)
                p.u16(); p.u32(); p.u16(); p.string(); p.u16()
                val operations = p.array16(); repeat(4) { p.array16() }
                val manufacturer = p.string(); val model = p.string(); val version = p.string(); val serial = p.string()
                check(setOf(0x1004, 0x1005, 0x1007, 0x1008, 0x1009).all(operations::contains)) {
                    "相机当前无线模式未开放文件浏览。请使用「连接至智能设备 → Wi-Fi 连接（AP 模式）」，退出 SnapBridge 后重试。电脑连接/PIN 配对模式暂不支持"
                }
                val name = "$manufacturer $model".trim().ifBlank { opened.cameraName.ifBlank { "Nikon Z8" } }
                val cameraGuid = opened.cameraGuid.joinToString("") { "%02x".format(it.toInt() and 0xff) }
                val identity = stableHash("$manufacturer:$model:${serial.ifBlank { "ptpip:$cameraGuid" }}")
                val diagnostic = "设备：$name\n设备版本：$version\n连接：Wi-Fi PTP/IP · 64 位流式传输\n地址：${route.endpoint.host}:${route.endpoint.port}\n路由：独立绑定 Wi-Fi，不经过蜂窝数据\n无线删除：测试版暂未开放\n手机：${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} / Android ${android.os.Build.VERSION.RELEASE}"
                check(opened.usable && route.available) { "相机 Wi-Fi 已断开" }
                prefs.edit { putString("last-host", endpoint.host); putInt("last-port", endpoint.port) }
                WifiPtpTransport(route, opened, name, identity, operations, diagnostic)
            } catch (error: Exception) {
                protocol?.close(); route.close()
                if (error is SocketTimeoutException) throw IOException("Wi-Fi 连接超时。请保持连接 Z8 热点、退出其他相机 App，并检查相机地址", error)
                if (error is ConnectException) throw IOException("相机未接受无线连接。请在相机开启「连接至智能设备 → Wi-Fi 连接」，并检查地址和端口", error)
                throw error
            }
        }
    }
}
