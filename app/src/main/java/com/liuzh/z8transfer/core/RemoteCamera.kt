package com.liuzh.z8transfer.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/** Camera operations advertised by this connection; capabilities are never inferred from model name. */
data class RemoteCapabilities(val liveView: Boolean = false, val capture: Boolean = false, val autofocus: Boolean = false) {
    val available: Boolean get() = liveView || capture
}
data class CaptureReceipt(val ready: Boolean, val message: String)
interface RemoteCamera {
    val capabilities: RemoteCapabilities
    suspend fun startLiveView()
    suspend fun stopLiveView()
    suspend fun frame(): ByteArray?
    suspend fun focus()
    suspend fun capture(autofocus: Boolean, onAccepted: () -> Unit = {}): CaptureReceipt
    suspend fun close()
}

/** Nikon command numbers/parameter meanings checked against libgphoto2 camlibs/ptp2/ptp.h.
 * This implements control sequences only; the transport owns serialization and network/USB framing.
 * Capture always targets the memory card and is issued exactly once, including on ambiguous I/O errors.
 */
class NikonRemoteCamera(private val operations: Set<Int>,
    private val command: suspend (Int, IntArray) -> PtpReply) : RemoteCamera {
    override val capabilities = RemoteCapabilities(
        liveView = setOf(0x9201, 0x9202, 0x9203).all(operations::contains),
        capture = 0x9207 in operations || 0x100e in operations,
        autofocus = 0x90c1 in operations,
    )
    private val gate = Mutex()
    private var controlOwned = false
    private var live = false
    private var startAttempted = false
    private var recovering = false
    private suspend fun send(op: Int, vararg args: Int) = command(op, args)
    private fun PtpReply.ok(action: String): PtpReply {
        if (code != 0x2001) throw IOException("$action：${remoteError(code)}")
        return this
    }
    private suspend fun prepare() {
        if (!controlOwned && 0x90c2 in operations) {
            val reply = send(0x90c2, 1)
            // Some Nikon bodies reject changing mode while already under control.
            if (reply.code != 0xa003) reply.ok("进入遥控模式")
            controlOwned = reply.code == 0x2001
        }
    }
    private suspend fun ready(attempts: Int = 20) {
        if (0x90c8 !in operations) return
        repeat(attempts) {
            val reply = send(0x90c8)
            if (reply.code == 0x2001) return
            if (reply.code != 0x2019) reply.ok("检查相机状态")
            delay(150)
        }
        throw IOException("相机仍在处理，请检查曝光时间、对焦及存储卡状态")
    }
    private suspend fun start() {
        check(capabilities.liveView) { "当前连接未开放实时取景" }
        prepare(); ready()
        startAttempted = true
        val reply = send(0x9201)
        if (reply.code != 0x2019) reply.ok("开启实时取景")
        ready()
        live = true
    }
    override suspend fun startLiveView() = gate.withLock { if (!live) start(); recovering = false }
    override suspend fun stopLiveView() = gate.withLock { stop() }
    private suspend fun stop() {
        if (!startAttempted) return
        // NotLiveView means there is already nothing to stop.
        val reply = send(0x9202)
        if (reply.code != 0xa00b) reply.ok("结束实时取景")
        live = false; startAttempted = false; recovering = false
    }
    override suspend fun frame(): ByteArray? = gate.withLock {
        check(live) { "请先开启实时取景" }
        val reply = send(0x9203)
        when (reply.code) {
            0x2019, 0xa004 -> null // Completed transaction, no image ready yet.
            0xa00b -> {
                check(!recovering) { "相机已结束实时取景，请重新开启" }
                // A shutter release may stop live view. Restart the stream once, never the shutter.
                recovering = true; live = false; start(); null
            }
            else -> {
                reply.ok("读取实时画面")
                val jpeg = liveViewJpeg(reply.data)
                recovering = false
                jpeg
            }
        }
    }
    private suspend fun autofocus() {
        check(capabilities.autofocus) { "当前连接未开放自动对焦，请在相机上对焦" }
        prepare(); ready()
        send(0x90c1).ok("自动对焦")
        ready(40)
    }
    override suspend fun focus() = gate.withLock { autofocus() }
    override suspend fun capture(autofocus: Boolean, onAccepted: () -> Unit): CaptureReceipt = gate.withLock {
        check(capabilities.capture) { "当前连接未开放遥控拍照" }
        prepare(); ready()
        if (autofocus) autofocus()
        // 0xffffffff: use existing focus. Destination 0: camera memory card.
        // No retry or fallback after issuing this operation: timeouts may follow a real exposure.
        val reply = try {
            if (0x9207 in operations) send(0x9207, -1, 0) else send(0x100e, 0, 0)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            throw CaptureUncertainException("拍照结果未确认，请先检查相机或退出遥控刷新列表，避免重复拍摄", error)
        }
        reply.ok("拍照指令")
        onAccepted()
        try {
            ready(300)
            CaptureReceipt(true, "拍照指令已接受，原片保存目标为相机存储卡")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            CaptureReceipt(false, "拍照指令已接受，完成状态未确认：${error.message}。请先检查相机，再退出遥控刷新列表")
        }
    }
    override suspend fun close() = gate.withLock {
        var failure: Exception? = null
        try { stop() } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { failure = error }
        if (controlOwned) {
            try { send(0x90c2, 0).ok("释放相机控制") ; controlOwned = false }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (failure == null) failure = error }
        }
        failure?.let { throw it }
        Unit
    }
}

fun remoteError(code: Int): String = when (code) {
    0x2019 -> "相机忙，请稍后重试"
    0x2005 -> "当前连接不支持此功能"
    0x200c -> "存储卡已满"
    0x200f -> "访问被拒绝，请检查相机连接及控制模式"
    0xa002 -> "未能合焦，请检查镜头与对焦模式"
    0xa004 -> "当前相机状态不允许此操作，请检查照片/视频开关、菜单和连接模式"
    0xa00b -> "相机未处于实时取景状态"
    else -> "相机返回 0x${code.toString(16)}"
}

/** Nikon live-view data includes a model-specific header before the JPEG. */
fun liveViewJpeg(payload: ByteArray): ByteArray {
    require(payload.size in 4..8 * 1024 * 1024) { "实时画面长度无效或超过 8 MiB" }
    val start = (0..minOf(payload.size - 4, 65535)).firstOrNull {
        payload[it] == 0xff.toByte() && payload[it + 1] == 0xd8.toByte() && payload[it + 2] == 0xff.toByte()
    } ?: throw IOException("相机实时数据中没有可识别的 JPEG")
    var end = payload.lastIndex - 1
    while (end > start + 2 && !(payload[end] == 0xff.toByte() && payload[end + 1] == 0xd9.toByte())) end--
    require(end > start + 2) { "实时画面不完整，请重新开启取景" }
    return payload.copyOfRange(start, end + 2)
}
