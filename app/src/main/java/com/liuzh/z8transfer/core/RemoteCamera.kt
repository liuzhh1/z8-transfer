package com.liuzh.z8transfer.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/** Camera operations advertised by this connection; capabilities are never inferred from model name. */
data class RemoteCapabilities(val liveView: Boolean = false, val capture: Boolean = false, val autofocus: Boolean = false,
    val parameters: Boolean = false) {
    val available: Boolean get() = liveView || capture
}
data class CaptureReceipt(val ready: Boolean, val message: String)
interface RemoteCamera {
    val capabilities: RemoteCapabilities
    val parameterCoverage: String? get() = null
    suspend fun startLiveView()
    suspend fun stopLiveView()
    suspend fun frame(): ByteArray?
    suspend fun focus()
    suspend fun capture(autofocus: Boolean, onAccepted: () -> Unit = {}): CaptureReceipt
    suspend fun readParameters(): List<ShootingParameter> = emptyList()
    suspend fun readParameterGroup(group: ShootingGroup): List<ShootingParameter> = readParameters().filter { it.setting.group == group }
    suspend fun setParameter(setting: ShootingSetting, value: Long): List<ShootingParameter> = error("当前连接未开放参数调节")
    suspend fun setTextParameter(setting: ShootingSetting, value: String): List<ShootingParameter> = error("当前连接未开放文本参数调节")
    suspend fun close()
}

/** Nikon command numbers/parameter meanings checked against libgphoto2 camlibs/ptp2/ptp.h.
 * This implements control sequences only; the transport owns serialization and network/USB framing.
 * Capture always targets the memory card and is issued exactly once, including on ambiguous I/O errors.
 */
class NikonRemoteCamera(private val operations: Set<Int>, private val properties: Set<Int>? = null,
    private val dataCommand: (suspend (Int, IntArray, ByteArray) -> PtpReply)? = null,
    private val command: suspend (Int, IntArray) -> PtpReply) : RemoteCamera {
    override val capabilities = RemoteCapabilities(
        liveView = setOf(0x9201, 0x9202, 0x9203).all(operations::contains),
        capture = 0x9207 in operations || 0x100e in operations,
        autofocus = 0x90c1 in operations,
        parameters = 0x1014 in operations,
    )
    private val gate = Mutex()
    private var controlOwned = false
    private var live = false
    private var startAttempted = false
    private var recovering = false
    private var vendorProperties: Set<Int> = emptySet()
    private var vendorPropertiesRead = false
    private var discoveryNotes = emptyList<String>()
    override val parameterCoverage: String get() {
        val known = properties.orEmpty() + vendorProperties
        return buildString {
            append("本版接入 ${ShootingSetting.entries.size} 项设置/状态；机身声明其中 ${ShootingSetting.entries.count { it.codes.any(known::contains) }} 项。")
            append("发现 ${known.size} 个相机属性，未列出的属性不写入。\n")
            ShootingGroup.entries.forEach { group ->
                val items = ShootingSetting.entries.filter { it.group == group && it.codes.any(known::contains) }
                append(group.title + "：" + items.joinToString("、") { it.title } + "\n")
            }
            if (discoveryNotes.isNotEmpty()) append(discoveryNotes.joinToString("\n") + "\n")
            append("属性编号：" + known.sorted().joinToString(",") { "0x${it.toString(16)}" })
        }
    }
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

    private suspend fun parameter(setting: ShootingSetting): ShootingParameter {
        var lastReason = "当前连接未开放此参数"
        var readOnly: ShootingParameter? = null
        for (code in setting.codes) {
            if (properties != null && code !in properties && code !in vendorProperties) continue
            val reply = send(0x1014, code)
            if (reply.code != 0x2001) { lastReason = remoteError(reply.code); continue }
            val property = try { DeviceProperty.parse(reply.data, code) }
                catch (error: Exception) { lastReason = error.message ?: "相机参数数据不可识别"; continue }
            if (setting.type == 0 && property.type !in 1..6 || setting.type != 0 && property.type != setting.type) {
                lastReason = "相机返回的参数类型暂不支持"; continue
            }
            val item = ShootingParameter(setting, property,
                if (0x1016 !in operations || dataCommand == null) "当前连接未开放参数写入" else null)
            if (item.editable) return item
            if (readOnly == null) readOnly = item
        }
        return readOnly ?: ShootingParameter(setting, reason = lastReason)
    }
    private suspend fun discoverProperties() {
        if (vendorPropertiesRead) return
        val notes = mutableListOf<String>()
        for (op in listOf(0x90ca,0x9439).filter(operations::contains)) {
            val reply = if (op == 0x9439) send(op,0xd) else send(op)
            if (reply.code != 0x2001) { notes += "扩展属性发现：${remoteError(reply.code)}"; continue }
            val codes = try {
                val p = java.nio.ByteBuffer.wrap(reply.data).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                val count = p.int.toLong() and 0xffffffffL
                val width = if (op == 0x9439) 4 else 2
                require(count <= 8192 && count * width == p.remaining().toLong()) { "扩展属性列表长度无效" }
                List(count.toInt()) { if (width == 4) p.int else p.short.toInt() and 0xffff }.toSet().also {
                    require(it.all { code -> code > 0 }) { "扩展属性编号无效" }
                }
            } catch (_: Exception) { notes += "扩展属性列表不可解析，保留标准属性"; emptySet() }
            vendorProperties = vendorProperties + codes
        }
        discoveryNotes = notes; vendorPropertiesRead = true
    }
    private suspend fun parameters(group: ShootingGroup): List<ShootingParameter> {
        discoverProperties()
        return ShootingSetting.entries.filter { it.group == group }.map { parameter(it) }
    }
    override suspend fun readParameters(): List<ShootingParameter> = readParameterGroup(ShootingGroup.COMMON)
    override suspend fun readParameterGroup(group: ShootingGroup): List<ShootingParameter> = gate.withLock {
        check(capabilities.parameters) { "当前连接未开放拍摄参数读取" }
        prepare(); ready()
        parameters(group)
    }
    override suspend fun setParameter(setting: ShootingSetting, value: Long): List<ShootingParameter> = gate.withLock {
        setValue(setting,value,null)
    }
    override suspend fun setTextParameter(setting: ShootingSetting, value: String): List<ShootingParameter> = gate.withLock {
        setValue(setting,null,value)
    }
    private suspend fun setValue(setting: ShootingSetting, value: Long?, text: String?): List<ShootingParameter> {
        check(capabilities.parameters && 0x1016 in operations && dataCommand != null) { "当前连接未开放参数调节" }
        prepare(); ready()
        discoverProperties()
        // Revalidate mode, lens, data type, access and supported values immediately before writing.
        val item = parameter(setting)
        check(item.editable && if (text != null) text in item.property?.textChoices.orEmpty() else value != null && item.accepts(value)) {
            item.explanation ?: "参数选项已变化，请刷新后重新选择"
        }
        val property = checkNotNull(item.property)
        if (if (text != null) property.currentText != text else property.current != value) {
            val data = if (text != null) property.encodeText(text) else property.encode(checkNotNull(value))
            val reply = try { checkNotNull(dataCommand).invoke(0x1016, intArrayOf(property.code), data) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { throw IOException("参数写入结果未确认，请刷新参数或重新连接后核对机身；不会自动重发", error) }
            reply.ok("设置${setting.title}")
            ready()
        }
        val actual = parameters(setting.group)
        val confirmed = actual.first { it.setting == setting }.property
        if (confirmed?.code != property.code || confirmed.type != property.type ||
            (if (text != null) confirmed.currentText != text else confirmed.current != value))
            throw ParameterVerificationException(actual, "相机尚未确认所选${setting.title}，已显示重新读取的当前值；请检查模式和参数锁定")
        return actual
    }
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
    0x200a -> "相机未开放此参数"
    0x201b -> "相机不接受此参数的数据格式，请刷新或重新连接"
    0x201c -> "相机不接受此参数值，请刷新可用选项"
    0x201d -> "相机拒绝了请求参数，请刷新并检查机身模式和镜头"
    0x200c -> "存储卡已满"
    0x200f -> "访问被拒绝，请检查相机连接及控制模式"
    0xa002 -> "未能合焦，请检查镜头与对焦模式"
    0xa004 -> "当前相机状态不允许此操作，请检查照片/视频开关、菜单和连接模式"
    0xa00b -> "相机未处于实时取景状态"
    else -> "相机返回 0x${code.toString(16)}"
}

class ParameterVerificationException(val actual: List<ShootingParameter>, message: String) : IOException(message)

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
