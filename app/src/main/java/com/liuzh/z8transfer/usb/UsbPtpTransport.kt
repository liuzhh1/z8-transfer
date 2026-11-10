package com.liuzh.z8transfer.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.ParcelFileDescriptor
import com.liuzh.z8transfer.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class UsbPtpTransport private constructor(
    private val device: UsbDevice,
    private val protocol: PtpProtocol,
    override val displayName: String,
    private val identity: String,
    private val operations: Set<Int>,
    override val diagnostics: String,
) : CameraTransport {
    override val connectionLabel: String = "USB · 标准 PTP"
    override val sessionId: String = UUID.randomUUID().toString()
    override val usbDeviceId: Int get() = device.deviceId
    private val live = AtomicBoolean(true)
    private val gate = Mutex()
    override val attached: Boolean get() = live.get() && protocol.usable
    override val supportsDeletion: Boolean get() = 0x100b in operations

    override suspend fun scan(onBatch: (List<CameraPhoto>) -> Unit, onExamined: (Int) -> Unit) = withContext(Dispatchers.IO) {
        gate.withLock {
            checkLive()
            val stores = PtpData(protocol.command(0x1004).requireOk().data).array32()
            var count = 0
            val batch = mutableListOf<CameraPhoto>()
            for (id in stores) {
                val p = PtpData(protocol.command(0x1005, intArrayOf(id)).requireOk().data)
                p.u16(); p.u16(); p.u16()
                val capacity = p.u64(); p.u64(); p.u32()
                val label = p.string().ifBlank { "存储卡" }
                val volume = p.string().ifBlank { "session:$sessionId" }
                val directories = ArrayDeque<Pair<Int, String>>()
                val seen = mutableSetOf<Int>()
                directories.add(-1 to "")
                while (directories.isNotEmpty()) {
                    val (parent, folder) = directories.removeFirst()
                    val handles = PtpData(protocol.command(0x1007, intArrayOf(id, 0, parent)).requireOk().data).array32()
                    for (handle in handles) {
                        currentCoroutineContext().ensureActive(); checkLive()
                        if (!seen.add(handle)) continue
                        val item = objectInfo(handle)
                        count++
                        if (item.isDirectory) directories.add(handle to listOf(folder, item.name).filter { it.isNotEmpty() }.joinToString("/"))
                        else {
                            val size = objectSize(handle, item.rawSize)
                            batch += CameraPhoto(sessionId, identity, id, "$volume:$capacity", label, handle,
                                folder, item.name, size, item.created, item.modified, PhotoKind.fromName(item.name)!!,
                                item.thumbnail, item.rawSize, item.parent)
                        }
                        if (count % 32 == 0) {
                            if (batch.isNotEmpty()) { onBatch(batch.toList()); batch.clear() }
                            onExamined(count)
                        }
                    }
                }
            }
            if (batch.isNotEmpty()) onBatch(batch.toList())
            onExamined(count)
        }
    }

    private fun objectInfo(handle: Int): PtpObject = PtpObject.parse(protocol.command(0x1008, intArrayOf(handle)).requireOk().data)

    private fun objectSize(handle: Int, raw: Long): Long {
        if (raw != 0xffffffffL) return raw
        if (0x9803 in operations) {
            val reply = protocol.command(0x9803, intArrayOf(handle, 0xdc04))
            if (reply.code == 0x2001 && reply.data.size == 8) return PtpData(reply.data).u64()
        }
        if (0x9805 in operations) {
            val reply = protocol.command(0x9805, intArrayOf(handle, 0, 0xdc04, 0, 0))
            if (reply.code == 0x2001 && reply.data.size >= 20) {
                val p = PtpData(reply.data)
                if (p.u32() == 1L && p.i32() == handle && p.u16() == 0xdc04 && p.u16() == 0x0008) return p.u64()
            }
        }
        // Keep the file visible and stream until the protocol's data-phase boundary.
        return UNKNOWN_FILE_SIZE
    }

    override suspend fun thumbnail(photo: CameraPhoto): ByteArray? = withContext(Dispatchers.IO) {
        gate.withLock {
            checkPhoto(photo)
            if (photo.thumbnailBytes !in 1..2_097_152L) return@withLock null
            val result = protocol.command(0x100a, intArrayOf(photo.handle))
            result.data.takeIf { result.code == 0x2001 }
        }
    }

    override suspend fun copyOriginal(photo: CameraPhoto, destination: OriginalDestination, progress: (Long) -> Unit) = withContext(Dispatchers.IO) {
        gate.withLock {
            checkPhoto(photo)
            val info = objectInfo(photo.handle)
            check(info.matches(photo)) { "相机文件已变化，请刷新列表" }
            if (photo.size > 0 && info.rawSize == 0xffffffffL) check(objectSize(photo.handle, info.rawSize) == photo.size) { "视频大小已变化，请刷新列表" }
            var bytes = 0L
            var lastUpdate = 0L
            ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.dup(destination.fileDescriptor)).use { stream ->
                    protocol.command(0x1009, intArrayOf(photo.handle), photo.size.takeIf { it > 0 }) { buffer, offset, size ->
                        stream.write(buffer, offset, size); bytes += size
                        val now = System.nanoTime()
                        if (now - lastUpdate >= 200_000_000L) { progress(bytes); lastUpdate = now }
                    }.requireOk()
            }
            progress(bytes)
        }
    }

    override suspend fun deleteOriginal(photo: CameraPhoto) = withContext(Dispatchers.IO) {
        gate.withLock {
            checkPhoto(photo)
            check(supportsDeletion) { "相机未声明支持 USB 删除" }
            val card = PtpData(protocol.command(0x1005, intArrayOf(photo.storageId)).requireOk().data)
            card.u16(); card.u16(); card.u16()
            val capacity = card.u64(); card.u64(); card.u32(); card.string()
            val volume = card.string().ifBlank { "session:$sessionId" }
            check(photo.storageIdentity == "$volume:$capacity") { "存储卡已变化，未执行删除；请刷新列表" }
            val info = objectInfo(photo.handle)
            check(info.matches(photo)) { "文件已变化或句柄已复用，未执行删除；请刷新列表" }
            check(info.protection == 0) { "文件受到相机保护，请先在相机中解除保护" }
            if (photo.size > 0 && info.rawSize == 0xffffffffL) check(objectSize(photo.handle, info.rawSize) == photo.size) { "视频大小已变化，未执行删除" }
            protocol.command(0x100b, intArrayOf(photo.handle, 0)).requireOk()
            Unit
        }
    }

    private fun checkLive() { check(attached) { "USB 会话已断开，请重新连接" } }
    private fun checkPhoto(photo: CameraPhoto) {
        checkLive()
        check(photo.sessionId == sessionId && photo.handle != 0 && photo.handle != -1) { "文件属于旧连接或句柄无效，请刷新" }
    }
    override fun markDetached() { live.set(false) }
    override suspend fun close() = withContext(Dispatchers.IO) {
        live.set(false)
        gate.withLock {
            if (protocol.usable) runCatching { protocol.command(0x1003).requireOk() }
            protocol.close()
        }
    }

    companion object {
        suspend fun open(manager: UsbManager, device: UsbDevice): UsbPtpTransport = withContext(Dispatchers.IO) {
            check(manager.hasPermission(device)) { "尚未获得相机 USB 权限" }
            val intf = (0 until device.interfaceCount).map(device::getInterface)
                .firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE }
                ?: error("未找到相机 PTP 接口，请在相机设置中选择 MTP/PTP")
            val endpoints = (0 until intf.endpointCount).map(intf::getEndpoint).filter { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK }
            val input = endpoints.firstOrNull { it.direction == UsbConstants.USB_DIR_IN } ?: error("相机没有数据输入端点")
            val output = endpoints.firstOrNull { it.direction == UsbConstants.USB_DIR_OUT } ?: error("相机没有数据输出端点")
            val connection = manager.openDevice(device) ?: error("无法打开 USB 设备")
            if (!connection.claimInterface(intf, false) && !connection.claimInterface(intf, true)) {
                connection.close(); error("无法取得相机 USB 接口，请退出旧版 App 和其他导入程序后拔线重连")
            }
            // 16 KiB is packet-aligned for USB 2/3 still-image endpoints and
            // avoids oversized synchronous bulk requests on vendor USB stacks.
            // Protocol and pipe must share this size for short-packet detection.
            val protocol = PtpProtocol(UsbPipe(connection, intf, input, output), bufferSize = 16 * 1024)
            try {
                val initialized = UsbSessionInitializer({ op, parameters -> protocol.command(op, parameters) }).initialize()
                val info = initialized.deviceInfo
                val manufacturer = info?.manufacturer ?: device.manufacturerName.orEmpty()
                val model = info?.model ?: device.productName.orEmpty()
                val serial = info?.serial?.takeIf { it.isNotBlank() } ?: device.serialNumber.orEmpty()
                val operations = info?.operations.orEmpty()
                val name = "$manufacturer $model".trim().ifBlank { "Nikon 相机" }
                val identity = stableHash("${if (info == null) "usb-descriptor:" else ""}$manufacturer:$model:${serial.ifBlank { UUID.randomUUID().toString() }}")
                val diagnostic = "设备：$name\n设备版本：${info?.version ?: "设备信息未提供"}\n" +
                    "连接：USB PTP · 64 位流式传输 · 16 KiB 兼容读取\n" +
                    "设备信息来源：${if (info != null) "PTP DeviceInfo" else "USB 描述符（已验证存储卡可访问）"}\n" +
                    "USB 删除：${if (0x100b in operations) "已声明支持" else "未声明支持，禁用删除"}\n" +
                    "初始化可见存储卡：${initialized.storageIds.size}\n" + initialized.notes.joinToString("\n") + "\n" + describeUsbConnection(device)
                UsbPtpTransport(device, protocol, name, identity, operations, diagnostic)
            } catch (error: Exception) {
                if (protocol.usable && protocol.hasSession) runCatching { protocol.command(0x1003) }
                protocol.close(); throw error
            }
        }
    }
}

private class UsbPipe(private val connection: UsbDeviceConnection, private val intf: UsbInterface,
    private val input: UsbEndpoint, private val output: UsbEndpoint) : PtpPipe {
    private var closed = false
    override fun read(buffer: ByteArray): Int = connection.bulkTransfer(input, buffer, buffer.size, 30_000)
    override fun write(buffer: ByteArray, offset: Int, length: Int): Int = connection.bulkTransfer(output, buffer, offset, length, 30_000)
    override fun close() { if (!closed) { closed = true; runCatching { connection.releaseInterface(intf) }; connection.close() } }
}
