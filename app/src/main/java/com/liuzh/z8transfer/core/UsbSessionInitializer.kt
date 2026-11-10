package com.liuzh.z8transfer.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.IOException

data class PtpDeviceInfo(val manufacturer: String, val model: String, val version: String,
    val serial: String, val operations: Set<Int>) {
    companion object {
        fun parse(bytes: ByteArray): PtpDeviceInfo {
            val p = PtpData(bytes)
            p.u16(); p.u32(); p.u16(); p.string(); p.u16()
            val operations = p.array16(); repeat(4) { p.array16() }
            return PtpDeviceInfo(p.string(), p.string(), p.string(), p.string(), operations)
        }
    }
}

data class UsbSessionInfo(val deviceInfo: PtpDeviceInfo?, val storageIds: IntArray, val notes: List<String>)

/** Only initialization/read-only commands may be retried. Never used for DeleteObject. */
class UsbSessionInitializer(
    private val command: (Int, IntArray) -> PtpReply,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    suspend fun initialize(): UsbSessionInfo {
        val notes = mutableListOf<String>()
        suspend fun request(code: Int, label: String, parameters: IntArray = intArrayOf()): PtpReply {
            repeat(3) { attempt ->
                val reply = try { command(code, parameters) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { throw IOException("$label（0x${code.toString(16)}）：${error.message ?: error.javaClass.simpleName}", error) }
                if (reply.code != 0x2019 || attempt == 2) return reply
                notes += "$label：相机忙，等待后重试 ${attempt + 1}/2"
                pause(250L * (attempt + 1))
            }
            error("unreachable")
        }
        fun decode(reply: PtpReply): PtpDeviceInfo? {
            if (reply.code != 0x2001) {
                notes += "设备信息返回 0x${reply.code.toString(16)}"
                return null
            }
            return try { PtpDeviceInfo.parse(reply.data) }
            catch (error: Exception) {
                notes += "设备信息字段不可解析（${error.javaClass.simpleName}）；验证存储卡后使用 USB 名称"
                null
            }
        }

        // GetDeviceInfo is valid outside a PTP session. Query it before claiming
        // a session so devices that need this ordering can initialize normally.
        val before = request(0x1001, "读取设备信息")
        var info = decode(before)
        val opened = request(0x1002, "打开 PTP 会话", intArrayOf(1))
        if (opened.code != 0x2001 && opened.code != 0x201e) {
            throw IOException("打开 PTP 会话（0x1002）返回 0x${opened.code.toString(16)}")
        }
        // An exclusively claimed USB interface can retain a camera-side session
        // from an earlier app process. Follow Android's behavior and adopt it.
        if (opened.code == 0x201e) notes += "相机已有 PTP 会话，沿用会话，未发送关闭/重置命令"
        if (info == null) info = decode(request(0x1001, "会话内读取设备信息"))
        val stores = request(0x1004, "验证存储卡访问")
        if (stores.code != 0x2001) throw IOException("验证存储卡访问（0x1004）返回 0x${stores.code.toString(16)}")
        val ids = try { PtpData(stores.data).array32() }
        catch (error: Exception) { throw IOException("存储卡列表数据不完整", error) }
        if (info == null) notes += "设备信息不可用，但存储卡访问成功；使用 USB 描述符识别相机，禁用删除"
        return UsbSessionInfo(info, ids, notes.toList())
    }
}

/** Missing cosmetic MTP metadata must not block a camera whose storage is readable. */
internal suspend fun <T> probeMtpConnection(readInfo: () -> T?, readStorages: () -> IntArray?,
    pause: suspend (Long) -> Unit = { delay(it) }): T? {
    var info = readInfo()
    if (info == null) { pause(300); info = readInfo() }
    if (readStorages() == null) throw IOException(
        if (info == null) "系统 MTP 无法读取设备信息和存储卡。请退出其他相机 App，拔线后重新连接，并确认相机为 MTP/PTP 模式"
        else "系统 MTP 已识别设备，但无法访问存储卡。请等相机写卡完成后重新连接")
    return info
}

