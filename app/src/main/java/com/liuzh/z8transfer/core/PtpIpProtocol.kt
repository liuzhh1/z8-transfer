package com.liuzh.z8transfer.core

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** PTP/IP framing is independent of USB packet boundaries. No complete object is buffered. */
class PtpIpProtocol private constructor(
    private val commandChannel: Channel,
    private val eventChannel: Channel,
    val cameraName: String,
    val cameraGuid: ByteArray,
) : Closeable {
    private val live = AtomicBoolean(true)
    private var transaction = 0
    private var sessionOpen = false
    @Volatile var onDisconnected: (() -> Unit)? = null
    val usable: Boolean get() = live.get()
    private val buffer = ByteArray(128 * 1024)

    init {
        thread(name = "z8-ptpip-events", isDaemon = true) {
            try {
                while (usable) {
                    val packet = eventChannel.control()
                    when (packet.type) {
                        8 -> check(packet.body.size in 6..18 && (packet.body.size - 6) % 4 == 0) { "无效的相机事件" }
                        13 -> { check(packet.body.isEmpty()); eventChannel.send(14) }
                        14 -> check(packet.body.isEmpty())
                        else -> throw IOException("未知的相机事件包 ${packet.type}")
                    }
                }
            } catch (_: Exception) { abort() }
        }
    }

    /** Serialized by the camera transport. A normal PTP error does not corrupt the channel. */
    fun command(operation: Int, parameters: IntArray = intArrayOf(), expected: Long? = null,
        sink: ((ByteArray, Int, Int) -> Unit)? = null): PtpReply {
        check(usable) { "Wi-Fi 会话已断开，请重新连接" }
        require(parameters.size <= 5 && (expected == null || expected >= 0))
        val id = if (sessionOpen) ++transaction else 0
        val request = little(10 + parameters.size * 4).putInt(1).putShort(operation.toShort()).putInt(id)
        parameters.forEach(request::putInt)
        try {
            commandChannel.send(6, request.array())
            val output = if (sink == null) ByteArrayOutputStream() else null
            var count = 0L
            var declared: Long? = null
            var started = false
            var ended = false
            while (true) {
                val header = commandChannel.header()
                when (header.type) {
                    13 -> { check(header.length == 8L); commandChannel.send(14) }
                    14 -> check(header.length == 8L)
                    9 -> {
                        check(!started && header.length == 20L) { "无效的 Wi-Fi 数据起始包" }
                        val p = little(commandChannel.bytes(12))
                        check(p.int == id) { "相机事务编号不匹配" }
                        val size = p.long
                        check(size >= 0 || size == -1L) { "相机数据长度超出支持范围" }
                        declared = size.takeIf { it >= 0 }
                        if (expected != null && declared != null) check(expected == declared) { "文件大小已变化，请刷新列表" }
                        if (sink == null && declared != null) check(declared <= MAX_METADATA) { "相机元数据过大" }
                        started = true
                    }
                    10, 12 -> {
                        check(started && !ended && header.length >= 12) { "相机数据包顺序错误" }
                        check(little(commandChannel.bytes(4)).int == id) { "相机数据事务编号不匹配" }
                        var remaining = header.length - 12
                        val maximum = declared ?: expected
                        if (maximum != null) check(remaining <= maximum - count) { "相机发送的数据超出文件长度" }
                        if (sink == null) check(remaining <= MAX_METADATA - count) { "相机元数据过大" }
                        while (remaining > 0) {
                            val n = minOf(buffer.size.toLong(), remaining).toInt()
                            commandChannel.readFully(buffer, n)
                            if (sink != null) sink(buffer, 0, n) else output!!.write(buffer, 0, n)
                            remaining -= n; count += n
                        }
                        if (header.type == 12) {
                            ended = true
                            if (declared != null) check(count == declared) { "相机数据提前结束" }
                            if (expected != null) check(count == expected) { "相机文件不完整" }
                        }
                    }
                    7 -> {
                        check(header.length in 14L..34L && (header.length - 14) % 4 == 0L) { "无效的 Wi-Fi 响应" }
                        val p = little(commandChannel.bytes((header.length - 8).toInt()))
                        val code = p.short.toInt() and 0xffff
                        check(p.int == id) { "相机响应事务编号不匹配" }
                        check(!started || ended || code != 0x2001) { "相机遗漏数据结束包" }
                        if (code == 0x2001 && expected != null) check(started && ended && count == expected) { "相机没有返回完整文件" }
                        if (operation == 0x1002 && code == 0x2001) { sessionOpen = true; transaction = 0 }
                        if (operation == 0x1003 && code == 0x2001) { sessionOpen = false; transaction = 0 }
                        return PtpReply(code, output?.toByteArray() ?: byteArrayOf(), count)
                    }
                    else -> throw IOException("未知的 Wi-Fi 数据包 ${header.type}")
                }
            }
        } catch (error: Exception) {
            abort()
            if (error is SocketTimeoutException) throw IOException("相机 Wi-Fi 响应超时，请保持连接相机热点并重新连接", error)
            throw error
        }
    }

    /** Close sockets immediately to unblock an in-flight read after Android reports network loss. */
    fun abort() {
        if (live.compareAndSet(true, false)) {
            commandChannel.close(); eventChannel.close()
            onDisconnected?.invoke()
        }
    }
    override fun close() {
        live.set(false)
        commandChannel.close(); eventChannel.close()
    }

    private data class Header(val length: Long, val type: Int)
    private data class Packet(val type: Int, val body: ByteArray)
    private class Channel(private val socket: Socket) : Closeable {
        private val input = DataInputStream(BufferedInputStream(socket.getInputStream(), 128 * 1024))
        private val output: OutputStream = socket.getOutputStream()
        fun header(): Header {
            val p = little(bytes(8))
            val length = p.int.toLong() and 0xffffffffL
            check(length >= 8) { "Wi-Fi 数据包长度无效" }
            return Header(length, p.int)
        }
        fun bytes(n: Int): ByteArray = ByteArray(n).also { input.readFully(it) }
        fun readFully(buffer: ByteArray, n: Int) { input.readFully(buffer, 0, n) }
        fun control(): Packet {
            val h = header()
            check(h.length <= 64 * 1024) { "Wi-Fi 控制包过大" }
            return Packet(h.type, bytes((h.length - 8).toInt()))
        }
        @Synchronized fun send(type: Int, body: ByteArray = byteArrayOf()) {
            output.write(little(8).putInt(8 + body.size).putInt(type).array())
            output.write(body); output.flush()
        }
        override fun close() { runCatching { socket.close() } }
    }

    companion object {
        private const val MAX_METADATA = 16L * 1024 * 1024
        private fun little(n: Int): ByteBuffer = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)
        private fun little(bytes: ByteArray): ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        /** The connector must return a connected, network-bound socket with a read timeout. */
        fun connect(guid: ByteArray, friendlyName: String, openSocket: () -> Socket): PtpIpProtocol {
            require(guid.size == 16)
            require(friendlyName.isNotBlank() && friendlyName.length <= 80 && '\u0000' !in friendlyName)
            var command: Channel? = null
            var events: Channel? = null
            try {
                command = Channel(openSocket())
                val name = (friendlyName + "\u0000").toByteArray(Charsets.UTF_16LE)
                command.send(1, little(16 + name.size + 4).put(guid).put(name).putInt(0x00010000).array())
                val ack = command.control()
                if (ack.type == 5) {
                    val reason = if (ack.body.size == 4) little(ack.body).int else -1
                    throw IOException("相机拒绝 Wi-Fi 握手（$reason）。请使用「连接至智能设备 → Wi-Fi 连接」；若处于电脑连接配置，请切回智能设备模式，并退出其他相机 App")
                }
                check(ack.type == 2 && ack.body.size >= 26 && ack.body.size % 2 == 0) { "相机未确认 Wi-Fi 连接" }
                val p = little(ack.body)
                val connection = p.int
                val cameraGuid = ByteArray(16).also(p::get)
                val chars = StringBuilder()
                var terminated = false
                while (p.remaining() > 4) {
                    val ch = p.char
                    if (ch == '\u0000') { terminated = true; break }
                    chars.append(ch)
                }
                check(terminated && p.remaining() == 4 && p.int == 0x00010000) { "相机 Wi-Fi 协议版本无效" }
                val eventSocket = openSocket()
                events = Channel(eventSocket)
                events.send(3, little(4).putInt(connection).array())
                val eventAck = events.control()
                check(eventAck.type == 4 && eventAck.body.isEmpty()) { "相机未确认 Wi-Fi 事件通道" }
                // An idle camera need not send events. TCP keepalive and Android onLost
                // detect a lost network; active command reads retain their 30 s timeout.
                eventSocket.soTimeout = 0
                return PtpIpProtocol(command, events, chars.toString(), cameraGuid)
            } catch (error: Exception) { command?.close(); events?.close(); throw error }
        }
    }
}
