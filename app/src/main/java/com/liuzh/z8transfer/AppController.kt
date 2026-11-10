package com.liuzh.z8transfer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.LruCache
import com.liuzh.z8transfer.core.CameraPhoto
import com.liuzh.z8transfer.core.CameraTransport
import com.liuzh.z8transfer.core.DeletionPlan
import com.liuzh.z8transfer.core.DeletionProgress
import com.liuzh.z8transfer.core.DeletionEngine
import com.liuzh.z8transfer.core.PhotoKind
import com.liuzh.z8transfer.core.OriginalDestination
import com.liuzh.z8transfer.core.proxyFrom
import com.liuzh.z8transfer.core.stableHash
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.liuzh.z8transfer.core.OutcomeStatus
import com.liuzh.z8transfer.core.TransferEngine
import com.liuzh.z8transfer.core.TransferProgress
import com.liuzh.z8transfer.storage.MediaImportStore
import com.liuzh.z8transfer.usb.UsbMtpTransport
import com.liuzh.z8transfer.usb.UsbPtpTransport
import com.liuzh.z8transfer.usb.describeUsbConnection
import com.liuzh.z8transfer.core.openSelectedUsb
import com.liuzh.z8transfer.core.UsbConnectionMode
import com.liuzh.z8transfer.wifi.WifiPtpTransport
import com.liuzh.z8transfer.core.WifiEndpoint
import android.os.ParcelFileDescriptor
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

data class AppState(
    val storageReady: Boolean = false,
    val cameraName: String? = null,
    val diagnostics: String = "",
    val connecting: Boolean = false,
    val scanning: Boolean = false,
    val examined: Int = 0,
    val photos: List<CameraPhoto> = emptyList(),
    val selected: Set<String> = emptySet(),
    val downloaded: Set<String> = emptySet(),
    val progress: TransferProgress = TransferProgress(),
    val error: String? = null,
    val deletion: DeletionProgress = DeletionProgress(),
    val supportsDeletion: Boolean = false,
    val connectionLabel: String = "USB",
    val deletionUnavailableReason: String = "当前相机连接未声明支持删除",
    val usbMode: UsbConnectionMode = UsbConnectionMode.SYSTEM_MTP,
) {
    val connected: Boolean get() = cameraName != null
    val busy: Boolean get() = connecting || scanning || progress.running || deletion.running
}

class AppController(context: Context) {
    private val app = context.applicationContext
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(AppState())
    val state = mutableState.asStateFlow()
    private val manager = app.getSystemService(UsbManager::class.java)
    private val store = MediaImportStore(app)
    private val engine = TransferEngine(store)
    @Volatile private var transport: CameraTransport? = null
    private var scanJob: Job? = null
    private var downloadJob: Job? = null
    private var queued: List<CameraPhoto> = emptyList()
    private var queuedDeletion: DeletionPlan? = null
    private var cleanupJob: Job? = null
    private val previewGate = Mutex()
    @Volatile private var scanGeneration = 0
    private val stop = AtomicBoolean(false)
    private val thumbnailCache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    init {
        scope.launch {
            runCatching { store.cleanupIncomplete() }.onFailure {
                showError("未完成副本清理失败：${it.message}。请关闭 App 后重试")
            }.onSuccess { mutableState.update { it.copy(storageReady = true) } }
        }
    }

    fun selectUsbMode(mode: UsbConnectionMode) {
        if (!state.value.busy && !state.value.connected) mutableState.update { it.copy(usbMode = mode) }
    }

    fun connect(device: UsbDevice) {
        val mode = state.value.usbMode
        connectCamera {
            val opened = try { openSelectedUsb<CameraTransport>(mode,
                system = { UsbMtpTransport.open(manager, device) },
                standard = { UsbPtpTransport.open(manager, device) }) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                throw java.io.IOException("USB 连接失败（${mode.label}）\n${error.message ?: error.javaClass.simpleName}\n" +
                    "本次只尝试所选连接方式，未自动切换驱动。\n\n${describeUsbConnection(device)}", error)
            }
            if (manager.deviceList.values.none { it.deviceName == device.deviceName && it.deviceId == device.deviceId }) {
                opened.close(); error("相机已断开，请重新连接")
            }
            opened
        }
    }

    fun connectWifi(endpoint: WifiEndpoint) = connectCamera { WifiPtpTransport.open(app, endpoint) }

    fun lastWifiEndpoint(): WifiEndpoint {
        val prefs = app.getSharedPreferences("wifi-connection", Context.MODE_PRIVATE)
        return runCatching { WifiEndpoint(prefs.getString("last-host", "") ?: "", prefs.getInt("last-port", 15740)) }.getOrDefault(WifiEndpoint())
    }

    private fun connectCamera(open: suspend () -> CameraTransport) {
        if (state.value.busy) return
        mutableState.update { it.copy(connecting = true, error = null) }
        scope.launch {
            try {
                scanJob?.cancelAndJoin()
                cleanupJob?.join()
                transport?.close()
                transport = null
                thumbnailCache.evictAll()
                mutableState.update { it.copy(cameraName = null, photos = emptyList(), selected = emptySet(), downloaded = emptySet(), supportsDeletion = false) }
                val opened = open()
                transport = opened
                if (opened is WifiPtpTransport) opened.onDisconnected = { scope.launch {
                    if (transport === opened) onConnectionLost(opened, "相机 Wi-Fi 已断开。已完成的下载已保留；重连后可重试未完成文件")
                } }
                if (!opened.attached) {
                    transport = null; opened.close(); error("相机连接已中断，请重新连接")
                }
                mutableState.update { it.copy(cameraName = opened.displayName, diagnostics = opened.diagnostics, connecting = false,
                    supportsDeletion = opened.supportsDeletion, connectionLabel = opened.connectionLabel,
                    deletionUnavailableReason = opened.deletionUnavailableReason, error = null) }
                refresh()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                transport?.let { runCatching { it.close() } }; transport = null
                mutableState.update { it.copy(connecting = false, cameraName = null, supportsDeletion = false, error = error.message ?: "相机连接失败") }
            }
        }
    }

    fun refresh() {
        val source = transport ?: return
        if (state.value.busy || !source.attached) return
        scanJob?.cancel()
        scanGeneration++
        thumbnailCache.evictAll()
        mutableState.update { it.copy(scanning = true, examined = 0, photos = emptyList(), selected = emptySet(), downloaded = emptySet(), error = null) }
        scanJob = scope.launch {
            try {
                source.scan(onBatch = { batch ->
                    mutableState.update { old ->
                        old.copy(photos = (old.photos + batch).sortedWith(compareByDescending<CameraPhoto> { it.sortTime }.thenBy { it.name }))
                    }
                }, onExamined = { count -> mutableState.update { it.copy(examined = count) } })
                mutableState.update { it.copy(scanning = false) }
                // Check the local copy before displaying a downloaded badge.
                for (photo in state.value.photos) {
                    ensureActive()
                    if (source !== transport || !source.attached) break
                    if (store.existing(photo) != null) mutableState.update { it.copy(downloaded = it.downloaded + photo.key) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableState.update { it.copy(scanning = false, error = error.message ?: "读取照片列表失败") }
                if (!source.attached) invalidateConnection(source)
            }
        }
    }

    fun disconnect() {
        if (state.value.progress.running || state.value.deletion.running || state.value.connecting) return
        val previous = transport
        previous?.markDetached()
        transport = null
        scanJob?.cancel()
        thumbnailCache.evictAll()
        mutableState.update { it.copy(cameraName = null, scanning = false, photos = emptyList(), selected = emptySet(), downloaded = emptySet()) }
        cleanupJob = scope.launch { previous?.close() }
    }

    fun onDetached(device: UsbDevice) {
        val previous = transport ?: return
        if (previous.usbDeviceId != device.deviceId) return
        onConnectionLost(previous, "相机已断开。已完成的照片已保留；未完成照片可在重连后重新下载")
    }

    private fun onConnectionLost(previous: CameraTransport, message: String) {
        if (previous !== transport) return
        previous.markDetached()
        stop.set(true)
        transport = null
        scanJob?.cancel()
        mutableState.update { it.copy(cameraName = null, scanning = false, photos = emptyList(), selected = emptySet(), downloaded = emptySet(),
            supportsDeletion = false, error = message) }
        cleanupJob = scope.launch { previous.close() }
    }

    fun toggle(photo: CameraPhoto) {
        if (state.value.busy) return
        mutableState.update { it.copy(selected = if (photo.key in it.selected) it.selected - photo.key else it.selected + photo.key) }
    }

    fun selectVisible(photos: List<CameraPhoto>) {
        if (state.value.busy) return
        val keys = photos.map { it.key }.toSet()
        mutableState.update { it.copy(selected = if (keys.all(it.selected::contains)) it.selected - keys else it.selected + keys) }
    }

    fun clearSelection() { if (!state.value.busy) mutableState.update { it.copy(selected = emptySet()) } }

    /** Called from the visible activity before starting the foreground service. */
    fun queueDownload(retryFailures: Boolean = false): Boolean {
        if (state.value.busy || !state.value.storageReady || transport?.attached != true) return false
        val failures = state.value.progress.outcomes.filter { it.status == OutcomeStatus.FAILED }.map { it.photo.importKey }.toSet()
        queued = if (retryFailures) state.value.photos.filter { it.importKey in failures }
            else state.value.photos.filter { it.key in state.value.selected }
        if (queued.isEmpty()) return false
        stop.set(false)
        mutableState.update { it.copy(progress = TransferProgress(running = true, totalFiles = queued.size), error = null) }
        return true
    }

    fun startQueuedTransfer() {
        if (downloadJob?.isActive == true) return
        val source = transport
        val batch = queued
        queued = emptyList()
        if (source?.attached != true || batch.isEmpty()) {
            failToStart("连接已失效，请重新选择照片")
            return
        }
        downloadJob = scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    engine.run(source, batch, shouldStop = { stop.get() }) { progress ->
                        // Keep the service alive until result bookkeeping is complete.
                        mutableState.update { it.copy(progress = progress.copy(running = true)) }
                    }
                }
                val savedKeys = result.outcomes.filter { it.status != OutcomeStatus.FAILED }.map { it.photo.key }.toSet()
                downloadJob = null
                mutableState.update { it.copy(selected = it.selected - savedKeys, downloaded = it.downloaded + savedKeys, progress = result) }
                if (!source.attached) invalidateConnection(source)
            } catch (error: Exception) {
                downloadJob = null
                mutableState.update { it.copy(progress = it.progress.copy(running = false), error = error.message ?: "下载任务中断") }
            }
        }
    }

    fun failToStart(message: String) {
        queued = emptyList()
        queuedDeletion = null
        mutableState.update { it.copy(progress = it.progress.copy(running = false), deletion = it.deletion.copy(running = false), error = message) }
    }

    fun requestStop() {
        stop.set(true)
        mutableState.update { it.copy(progress = it.progress.copy(stopRequested = true)) }
    }

    fun queueDeletion(plan: DeletionPlan): Boolean {
        val source = transport ?: return false
        if (state.value.busy || !source.attached || !source.supportsDeletion) return false
        if (plan.sessionId != source.sessionId || plan.files.any { selected -> state.value.photos.none { it.key == selected.key } }) {
            showError("文件列表已变化，请重新选择文件并确认删除"); return false
        }
        queuedDeletion = plan; stop.set(false)
        mutableState.update { it.copy(deletion = DeletionProgress(running = true, total = plan.files.size), error = null) }
        return true
    }

    fun startQueuedDeletion() {
        val source = transport
        val plan = queuedDeletion
        queuedDeletion = null
        if (source == null || plan == null) { failToStart("删除请求已失效，请重新确认"); return }
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    DeletionEngine().run(source, plan, { stop.get() }) { p -> mutableState.update { it.copy(deletion = p.copy(running = true)) } }
                }
                val removed = result.outcomes.filter { it.deleted }.map { it.file.key }.toSet()
                removed.forEach(thumbnailCache::remove)
                mutableState.update { it.copy(deletion = result, photos = it.photos.filterNot { p -> p.key in removed },
                    selected = it.selected - removed, downloaded = it.downloaded - removed) }
                if (!source.attached) invalidateConnection(source)
            } catch (error: Exception) { mutableState.update { it.copy(deletion = it.deletion.copy(running = false), error = error.message) } }
        }
    }

    private fun invalidateConnection(source: CameraTransport) {
        onConnectionLost(source, "${source.connectionLabel} 会话已中断，请重新连接。已完成的下载已保留")
    }

    suspend fun preview(photo: CameraPhoto): Bitmap? {
        val target = if (photo.kind == PhotoKind.RAW) state.value.photos.firstOrNull {
            it.sessionId == photo.sessionId && it.storageId == photo.storageId && it.folder == photo.folder && it.kind == PhotoKind.JPEG &&
                it.name.substringBeforeLast('.').equals(photo.name.substringBeforeLast('.'), true)
        } ?: photo else photo
        if (target.kind != PhotoKind.JPEG && target.kind != PhotoKind.HEIF) return thumbnail(photo)
        if (target.size !in 1..64L * 1024 * 1024 || state.value.busy) return thumbnail(photo)
        return withContext(Dispatchers.IO) {
            runCatching {
                val file = cacheOriginal(target)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                var scale = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / scale > 2560) scale *= 2
                BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = scale })
            }.getOrNull()
        } ?: thumbnail(photo)
    }

    suspend fun videoPreview(photo: CameraPhoto): String = withContext(Dispatchers.IO) {
        check(!state.value.busy) { "请等待相机任务完成后播放" }
        val target = photo.proxyFrom(state.value.photos) ?: photo
        check(target.kind.canPlayOnAndroid) { "此格式不支持手机直接播放；可下载原文件，或使用同名 MP4 代理预览" }
        check(target.size in 1..512L * 1024 * 1024) { "此视频较大或大小未知，请先下载到手机后使用播放器打开" }
        cacheOriginal(target).absolutePath
    }

    private suspend fun cacheOriginal(photo: CameraPhoto): File = previewGate.withLock {
        val source = transport ?: error("相机已断开")
        val folder = File(app.cacheDir, "previews").apply { mkdirs() }
        val file = File(folder, stableHash(photo.key) + "." + photo.name.substringAfterLast('.'))
        if (file.exists() && file.length() == photo.size) return@withLock file
        // Bound disk cache. A preview is a disposable copy, never a completed import record.
        folder.listFiles()?.filter { it != file }?.sortedBy { it.lastModified() }?.let { files ->
            var total = files.sumOf { it.length() }
            for (old in files) if (total > 512L * 1024 * 1024) { total -= old.length(); old.delete() }
        }
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_READ_WRITE)
        val destination = object : OriginalDestination {
            override val fileDescriptor get() = descriptor.fileDescriptor
            override fun size(): Long = descriptor.statSize
            override suspend fun commit(): String = file.absolutePath
            override suspend fun rollback() { file.delete() }
            override fun close() { descriptor.close() }
        }
        try {
            source.copyOriginal(photo, destination) {}
            check(destination.size() == photo.size) { "预览文件不完整" }
            return@withLock file
        } catch (error: Exception) { file.delete(); throw error }
        finally { destination.close() }
    }

    suspend fun thumbnail(photo: CameraPhoto): Bitmap? {
        thumbnailCache.get(photo.key)?.let { return it }
        val current = state.value
        if (current.scanning || current.progress.running || current.deletion.running) return null
        val source = transport ?: return null
        val generation = scanGeneration
        return withContext(Dispatchers.IO) {
            runCatching {
                val bytes = source.thumbnail(photo) ?: return@runCatching null
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
                var scale = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / scale > 640) scale *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = scale })
                    ?.takeIf { generation == scanGeneration && source === transport }
                    ?.also { thumbnailCache.put(photo.key, it) }
            }.getOrNull()
        }
    }

    fun showError(message: String) { mutableState.update { it.copy(error = message) } }
    fun dismissError() { mutableState.update { it.copy(error = null) } }
}
