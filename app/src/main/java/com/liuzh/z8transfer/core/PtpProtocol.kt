package com.liuzh.z8transfer.core

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

interface PtpPipe : Closeable {
    fun read(buffer: ByteArray): Int
    fun write(buffer: ByteArray, offset: Int, length: Int): Int
}

data class PtpReply(val code: Int, val data: ByteArray = byteArrayOf(), val transferred: Long = 0) {
    fun requireOk(): PtpReply {
        if (code != 0x2001) throw IOException("相机返回错误 0x${code.toString(16)}")
        return this
    }
}

/** A single session owns the pipe. 64-bit counters, bounded buffers, no hidden Android APIs. */
class PtpProtocol(private val pipe: PtpPipe, private val bufferSize: Int = 128 * 1024,
    sessionAlreadyOpen: Boolean = false) : Closeable {
    private val buffer = ByteArray(bufferSize)
    private var position = 0
    private var limit = 0
    private var shortPacket = false
    private var transaction = 0
    private var sessionOpen = sessionAlreadyOpen
    @Volatile private var broken = false

    fun command(operation: Int, parameters: IntArray = intArrayOf(), expected: Long? = null,
        sink: ((ByteArray, Int, Int) -> Unit)? = null): PtpReply {
        check(!broken) { "USB 会话已失效，请重新连接" }
        require(parameters.size <= 5)
        val id = if (sessionOpen) ++transaction else 0
        val request = ByteBuffer.allocate(12 + 4 * parameters.size).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(12 + parameters.size * 4).putShort(1).putShort(operation.toShort()).putInt(id)
        parameters.forEach(request::putInt)
        try {
            var sent = 0
            while (sent < request.array().size) {
                val n = pipe.write(request.array(), sent, request.array().size - sent)
                if (n <= 0) throw IOException("USB 命令发送失败")
                sent += n
            }
            var header = header()
            verifyId(header, id)
            var count = 0L
            val output = if (sink == null) ByteArrayOutputStream() else null
            if (header.type == 2) {
                check(header.code == operation) { "相机数据操作码不匹配（预期 0x${operation.toString(16)}，实际 0x${header.code.toString(16)}）" }
                val consumer: (ByteArray, Int, Int) -> Unit = sink ?: { bytes, offset, size ->
                    check(output!!.size().toLong() + size <= 16L * 1024 * 1024) { "相机元数据过大" }
                    output.write(bytes, offset, size)
                }
                val length = if (header.length == 0xffffffffL) expected else header.length - 12
                if (expected != null && length != null && length != expected) throw IOException("相机声明的文件长度与列表不一致")
                if (length != null) {
                    var remaining = length
                    while (remaining > 0) {
                        if (position == limit && !fill()) throw IOException("文件数据提前结束")
                        val n = minOf((limit - position).toLong(), remaining).toInt()
                        consumer(buffer, position, n); position += n; remaining -= n; count += n
                    }
                } else {
                    // MTP's 0xffffffff length uses USB short packet/ZLP to end its data phase.
                    // A header-only first packet is not the end of the object.
                    var receivedPayload = false
                    while (true) {
                        if (position < limit) {
                            val n = limit - position
                            consumer(buffer, position, n); count += n; position = limit; receivedPayload = true
                            if (shortPacket) break
                        }
                        if (!fill()) {
                            if (receivedPayload) break else throw IOException("相机返回空的大文件数据")
                        }
                    }
                }
                header = header(); verifyId(header, id)
            }
            check(header.type == 3 && header.length in 12L..32L) { "无效的相机响应" }
            readBytes((header.length - 12).toInt())
            if (operation == 0x1002 && (header.code == 0x2001 || header.code == 0x201e)) { sessionOpen = true; transaction = 0 }
            if (operation == 0x1003 && header.code == 0x2001) { sessionOpen = false; transaction = 0 }
            return PtpReply(header.code, output?.toByteArray() ?: byteArrayOf(), count)
        } catch (error: Exception) {
            broken = true
            runCatching { pipe.close() }
            throw error
        }
    }

    val usable: Boolean get() = !broken
    val hasSession: Boolean get() = sessionOpen
    private data class Header(val length: Long, val type: Int, val code: Int, val transaction: Int)
    private fun header(): Header {
        val bytes = readBytes(12)
        val data = PtpData(bytes)
        val h = Header(data.u32(), data.u16(), data.u16(), data.i32())
        if (h.length < 12) throw IOException("无效的 PTP 数据头")
        return h
    }
    private fun verifyId(header: Header, expected: Int) { check(header.transaction == expected) {
        "相机事务编号不匹配（预期 0x${expected.toUInt().toString(16)}，实际 0x${header.transaction.toUInt().toString(16)}），请重新连接"
    } }
    private fun fill(): Boolean {
        val n = pipe.read(buffer)
        if (n < 0) throw IOException("USB 读取失败或超时，请检查连接")
        position = 0; limit = n; shortPacket = n < buffer.size
        return n > 0
    }
    private fun readBytes(size: Int): ByteArray {
        val result = ByteArray(size)
        var offset = 0
        var emptyPackets = 0
        while (offset < size) {
            if (position == limit && !fill()) {
                if (++emptyPackets > 2) throw IOException("相机响应为空")
                continue
            }
            val n = minOf(size - offset, limit - position)
            buffer.copyInto(result, offset, position, position + n)
            position += n; offset += n
        }
        return result
    }
    override fun close() { broken = true; pipe.close() }
}

class PtpData(bytes: ByteArray) {
    private val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    fun u8(): Int = data.get().toInt() and 0xff
    fun u16(): Int = data.short.toInt() and 0xffff
    fun i32(): Int = data.int
    fun u32(): Long = data.int.toLong() and 0xffffffffL
    fun u64(): Long = data.long.also { require(it >= 0) { "相机文件大小超出支持范围" } }
    fun string(): String {
        val n = u8()
        if (n == 0) return ""
        val chars = CharArray(n) { data.char }
        require(chars.last() == '\u0000') { "PTP 字符串没有结束标记" }
        return String(chars, 0, n - 1)
    }
    fun array16(): Set<Int> { val n = u32(); require(n <= data.remaining() / 2); return (0 until n.toInt()).map { u16() }.toSet() }
    fun array32(): IntArray { val n = u32(); require(n <= data.remaining() / 4); return IntArray(n.toInt()) { i32() } }
}

data class PtpObject(val storage: Int, val format: Int, val protection: Int, val rawSize: Long,
    val thumbnail: Long, val parent: Int, val name: String, val created: Long, val modified: Long) {
    val isDirectory: Boolean get() = format == 0x3001
    fun matches(file: CameraPhoto): Boolean = file.sameObject(name, storage, rawSize, created, modified, parent, isDirectory)
    companion object {
        fun parse(bytes: ByteArray): PtpObject {
            val p = PtpData(bytes)
            val storage = p.i32(); val format = p.u16(); val protect = p.u16(); val size = p.u32()
            p.u16(); val thumb = p.u32(); repeat(5) { p.u32() }
            val parent = p.i32(); p.u16(); p.u32(); p.u32()
            return PtpObject(storage, format, protect, size, thumb, parent, p.string(), ptpTime(p.string()), ptpTime(p.string()))
        }
    }
}

private fun ptpTime(value: String): Long = runCatching {
    LocalDateTime.parse(value.take(15), DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"))
        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
}.getOrDefault(0)
