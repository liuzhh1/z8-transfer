package com.liuzh.z8transfer.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.mtp.MtpConstants
import android.mtp.MtpDevice
import android.os.ParcelFileDescriptor
import com.liuzh.z8transfer.core.CameraPhoto
import com.liuzh.z8transfer.core.CameraTransport
import com.liuzh.z8transfer.core.OriginalDestination
import com.liuzh.z8transfer.core.PhotoKind
import com.liuzh.z8transfer.core.stableHash
import com.liuzh.z8transfer.core.sameObject
import com.liuzh.z8transfer.core.UNKNOWN_FILE_SIZE
import com.liuzh.z8transfer.core.probeMtpConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class UsbMtpTransport private constructor(
    val usbDevice: UsbDevice,
    private val mtp: MtpDevice,
    override val displayName: String,
    private val cameraIdentity: String,
    override val diagnostics: String,
    override val supportsDeletion: Boolean,
) : CameraTransport {
    override val connectionLabel: String = "USB · 系统 MTP"
    override val sessionId: String = UUID.randomUUID().toString()
    override val usbDeviceId: Int get() = usbDevice.deviceId
    private val isAttached = AtomicBoolean(true)
    private val closed = AtomicBoolean(false)
    private val gate = Mutex()
    override val attached: Boolean get() = isAttached.get() && !closed.get()

    override suspend fun scan(onBatch: (List<CameraPhoto>) -> Unit, onExamined: (Int) -> Unit) =
        withContext(Dispatchers.IO) {
            gate.withLock {
                requireAttached()
                val storageIds = mtp.storageIds ?: throw IOException("无法读取存储卡，请检查相机状态")
                var examined = 0
                val batch = mutableListOf<CameraPhoto>()
                for (storageId in storageIds) {
                    val storage = mtp.getStorageInfo(storageId)
                        ?: throw IOException("无法读取存储卡信息：${storageId.toUInt().toString(16)}")
                    val label = storage.description.ifBlank { "存储卡" }
                    // If the camera doesn't identify its volume, scope duplicate detection
                    // to this connection rather than risk confusing a replacement card.
                    val volume = storage.volumeIdentifier.takeIf { it.isNotBlank() }
                        ?: "session:$sessionId"
                    val storageIdentity = "$volume:${storage.maxCapacity}"
                    val pending = ArrayDeque<Pair<Int, String>>()
                    val seen = mutableSetOf<Int>()
                    pending.add(-1 to "")
                    while (pending.isNotEmpty()) {
                        currentCoroutineContext().ensureActive()
                        requireAttached()
                        val (parent, folder) = pending.removeFirst()
                        val handles = mtp.getObjectHandles(storageId, 0, parent)
                            ?: throw IOException("无法读取 $label/$folder，请重连后刷新")
                        for (handle in handles) {
                            currentCoroutineContext().ensureActive()
                            requireAttached()
                            if (!seen.add(handle)) continue
                            val info = mtp.getObjectInfo(handle)
                                ?: throw IOException("读取文件信息失败，请刷新列表后重试")
                            examined++
                            if (info.format == MtpConstants.FORMAT_ASSOCIATION) {
                                val nested = listOf(folder, info.name).filter { it.isNotEmpty() }.joinToString("/")
                                pending.add(handle to nested)
                            } else {
                                val kind = PhotoKind.fromName(info.name)
                                if (kind != null) {
                                    batch += CameraPhoto(
                                        sessionId, cameraIdentity, storageId, storageIdentity,
                                        label, handle, folder, info.name, if (info.compressedSizeLong == 0xffffffffL) UNKNOWN_FILE_SIZE else info.compressedSizeLong,
                                        info.dateCreated, info.dateModified, kind, info.thumbCompressedSizeLong,
                                        info.compressedSizeLong, info.parent,
                                    )
                                }
                            }
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

    override suspend fun thumbnail(photo: CameraPhoto): ByteArray? = withContext(Dispatchers.IO) {
        gate.withLock {
            requirePhoto(photo)
            // Only request advertised thumbnails. Never download an entire NEF for a grid cell.
            if (photo.thumbnailBytes !in 1..2_097_152L) return@withLock null
            mtp.getThumbnail(photo.handle)
        }
    }

    override suspend fun copyOriginal(
        photo: CameraPhoto,
        destination: OriginalDestination,
        progress: (Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        gate.withLock {
            requirePhoto(photo)
            check(photo.size in 1..0xfffffffeL) { "系统兼容模式无法安全传输此大文件，请使用标准 PTP 模式重连" }
            val current = mtp.getObjectInfo(photo.handle)
                ?: throw IOException("照片已不可读取，请刷新列表")
            // Handles can be reused after an on-camera delete/format. Verify the selected object.
            check(current.storageId == photo.storageId && current.name == photo.name &&
                current.compressedSizeLong == photo.size && current.dateModified == photo.modifiedAt &&
                current.dateCreated == photo.takenAt) { "相机中的文件已变化，请刷新照片列表" }
            coroutineScope {
                val monitor = launch(Dispatchers.IO) {
                    while (isActive) {
                        progress(runCatching { destination.size() }.getOrDefault(0L).coerceAtLeast(0L))
                        delay(250)
                    }
                }
                try {
                    val success = ParcelFileDescriptor.dup(destination.fileDescriptor).use { descriptor ->
                        mtp.importFile(photo.handle, descriptor)
                    }
                    if (!success) throw IOException("相机传输失败，请检查数据线、相机电量及连接状态")
                    requireAttached()
                } finally {
                    monitor.cancelAndJoin()
                }
                progress(destination.size())
            }
        }
    }

    override fun markDetached() { isAttached.set(false) }

    override suspend fun deleteOriginal(photo: CameraPhoto) = withContext(Dispatchers.IO) {
        gate.withLock {
            requirePhoto(photo)
            check(supportsDeletion && photo.handle != 0 && photo.handle != -1) { "相机不支持删除或文件句柄无效" }
            val storage = mtp.getStorageInfo(photo.storageId) ?: error("存储卡不可读取，未执行删除")
            val volume = storage.volumeIdentifier.ifBlank { "session:$sessionId" }
            check(photo.storageIdentity == "$volume:${storage.maxCapacity}") { "存储卡已变化，未执行删除" }
            val info = mtp.getObjectInfo(photo.handle) ?: error("文件已不存在，请刷新列表")
            check(photo.sameObject(info.name, info.storageId, info.compressedSizeLong, info.dateCreated, info.dateModified,
                info.parent, info.format == MtpConstants.FORMAT_ASSOCIATION)) { "文件已变化，未执行删除" }
            check(info.protectionStatus == 0) { "文件受保护，请先在相机中解除保护" }
            check(mtp.deleteObject(photo.handle)) { "相机拒绝删除或结果未确认，请刷新核实" }
        }
    }

    override suspend fun close() = withContext(Dispatchers.IO) {
        isAttached.set(false)
        gate.withLock {
            if (closed.compareAndSet(false, true)) mtp.close()
        }
    }

    private fun requireAttached() { check(attached) { "相机已断开，请重新连接" } }
    private fun requirePhoto(photo: CameraPhoto) {
        requireAttached()
        check(photo.sessionId == sessionId) { "照片列表来自旧连接，请刷新后重试" }
    }

    companion object {
        suspend fun open(manager: UsbManager, device: UsbDevice): UsbMtpTransport = withContext(Dispatchers.IO) {
            check(manager.hasPermission(device)) { "尚未获得相机 USB 访问权限" }
            val connection = manager.openDevice(device) ?: throw IOException("无法打开 USB 设备，请关闭其他相机导入 App 后重试")
            val mtp = MtpDevice(device)
            // MtpDevice takes ownership of the connection, including on open failure.
            if (!mtp.open(connection)) throw IOException("无法建立 MTP/PTP 会话，请将 Z8 的「USB 数据连接」设为「MTP/PTP」")
            try {
                val info = probeMtpConnection({ mtp.deviceInfo }, { mtp.storageIds })
                val manufacturer = info?.manufacturer ?: device.manufacturerName.orEmpty()
                val model = info?.model ?: device.productName.orEmpty()
                val name = listOf(manufacturer, model).filter { it.isNotBlank() }.joinToString(" ")
                val serial = (info?.serialNumber ?: device.serialNumber)?.takeIf { it.isNotBlank() }
                val identity = stableHash("${if (info == null) "usb-descriptor:" else ""}$manufacturer:$model:${serial ?: UUID.randomUUID()}")
                val details = buildString {
                    appendLine("设备：$name")
                    appendLine("设备版本：${info?.version ?: "设备信息未提供"}")
                    appendLine("USB VID/PID：%04x / %04x".format(device.vendorId, device.productId))
                    appendLine("USB 描述符版本：${device.version}（不是协商后的传输速度）")
                    appendLine("连接：USB · MTP/PTP")
                    appendLine("可跨连接识别相机：${if (serial != null) "是" else "否"}")
                    if (info == null) appendLine("设备信息未提供，但存储卡访问成功；使用 USB 描述符识别，禁用删除")
                    append("手机：${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} / Android ${android.os.Build.VERSION.RELEASE}")
                }
                UsbMtpTransport(device, mtp, name.ifBlank { "Nikon 相机" }, identity,
                    details + "\n模式：系统 MTP 默认连接；直接打开安卓 MtpDevice，未先发送自写 PTP 命令\n" +
                    "大于等于 4 GiB 或大小未知的文件不会以此模式传输，以免输出不完整副本",
                    info?.operationsSupported?.contains(0x100b) == true)
            } catch (error: Exception) {
                mtp.close()
                throw error
            }
        }
    }
}
