package com.liuzh.z8transfer.core

import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class PtpIpProtocolTest {
    private fun le(n: Int) = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)
    private fun frame(type: Int, body: ByteArray = byteArrayOf(), length: Long = body.size + 8L) =
        le(8 + body.size).putInt(length.toInt()).putInt(type).put(body).array()
    private fun response(id: Int, code: Int = 0x2001) = frame(7, le(6).putShort(code.toShort()).putInt(id).array())
    private fun start(id: Int, size: Long) = frame(9, le(12).putInt(id).putLong(size).array())
    private fun data(id: Int, bytes: ByteArray, end: Boolean = true) = frame(if (end) 12 else 10, le(4 + bytes.size).putInt(id).put(bytes).array())
    private val guid = ByteArray(16) { (it + 3).toByte() }
    private fun ack() = frame(2, le(4).putInt(77).array() + guid + "尼康 Z8\u0000".toByteArray(Charsets.UTF_16LE) + le(4).putInt(0x10000).array())
    private fun chain(vararg streams: InputStream) = SequenceInputStream(Collections.enumeration(streams.toList()))

    private class Idle : InputStream() {
        private val closed = CountDownLatch(1)
        override fun read(): Int { closed.await(); return -1 }
        override fun close() { closed.countDown() }
    }
    private class MemorySocket(private val bytes: InputStream) : Socket() {
        val sent = ByteArrayOutputStream()
        var wasClosed = false
        override fun getInputStream(): InputStream = bytes
        override fun getOutputStream(): OutputStream = sent
        override fun setSoTimeout(timeout: Int) {}
        override fun close() { wasClosed = true; bytes.close() }
    }
    private fun connection(commands: InputStream, events: ByteArray = frame(4)): Triple<PtpIpProtocol, MemorySocket, MemorySocket> {
        val command = MemorySocket(chain(ByteArrayInputStream(ack()), commands))
        val event = MemorySocket(chain(ByteArrayInputStream(events), Idle()))
        val sockets = ArrayDeque(listOf(command, event))
        return Triple(PtpIpProtocol.connect(guid, "测试手机") { sockets.removeFirst() }, command, event)
    }
    private fun connection(commands: ByteArray) = connection(ByteArrayInputStream(commands))
    private fun assertBroken(bytes: ByteArray, action: (PtpIpProtocol) -> Unit) {
        val (p, command, events) = connection(bytes)
        p.use {
            assertThrows(Exception::class.java) { action(p) }
            assertFalse(p.usable); assertTrue(command.wasClosed); assertTrue(events.wasClosed)
        }
    }

    @Test fun twoSocketHandshakeCarriesStableGuidUnicodeNameAndConnectionNumber() {
        val (p, command, event) = connection(response(0) + response(1) + response(2))
        p.use {
            assertEquals("尼康 Z8", p.cameraName)
            assertArrayEquals(guid, p.cameraGuid)
            p.command(0x1002, intArrayOf(1)).requireOk()
            p.command(0x1001).requireOk()
            p.command(0x1003).requireOk()
            val raw = ByteBuffer.wrap(command.sent.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
            val length = raw.int; assertEquals(1, raw.int)
            val actualGuid = ByteArray(16).also(raw::get); assertArrayEquals(guid, actualGuid)
            val encodedName = ByteArray(length - 28).also(raw::get)
            assertEquals("测试手机\u0000", encodedName.toString(Charsets.UTF_16LE)); assertEquals(0x10000, raw.int)
            val ids = mutableListOf<Int>()
            while (raw.hasRemaining()) {
                val from = raw.position(); val n = raw.int
                assertEquals(6, raw.int); assertEquals(1, raw.int); raw.short; ids += raw.int
                raw.position(from + n)
            }
            assertEquals(listOf(0, 1, 2), ids)
            assertArrayEquals(frame(3, le(4).putInt(77).array()), event.sent.toByteArray())
        }
    }

    @Test fun splitTcpReadsAndSeparateEndPacketRemainByteExact() {
        val payload = ByteArray(320_017) { (it * 13).toByte() }
        val stream = response(0) + start(1, payload.size.toLong()) + data(1, payload, false) + data(1, byteArrayOf()) + response(1)
        val fragmented = object : FilterInputStream(ByteArrayInputStream(stream)) {
            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, minOf(len, 7))
        }
        val (p) = connection(fragmented)
        p.use {
            p.command(0x1002, intArrayOf(1)).requireOk()
            val output = ByteArrayOutputStream()
            val reply = p.command(0x1009, intArrayOf(42), payload.size.toLong()) { b, o, n -> output.write(b, o, n) }.requireOk()
            assertArrayEquals(payload, output.toByteArray()); assertEquals(payload.size.toLong(), reply.transferred)
        }
    }

    @Test fun sixGiBDataPhaseAndLargePacketsUseBoundedMemoryAndLongCounters() {
        val size = 6L * 1024 * 1024 * 1024 + 37
        fun zeros(length: Long) = object : InputStream() {
            var remaining = length
            override fun read(): Int = if (remaining-- > 0) 0 else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (remaining <= 0) return -1
                val n = minOf(remaining, len.toLong()).toInt(); remaining -= n
                // Counting sink: generated payload bytes are deliberately not materialized.
                return n
            }
        }
        val chunks = listOf(2L shl 30, 3L shl 30, (1L shl 30) + 37)
        val pieces = mutableListOf<InputStream>(ByteArrayInputStream(response(0) + start(1, size)))
        chunks.forEachIndexed { i, n ->
            pieces += ByteArrayInputStream(frame(if (i == 2) 12 else 10, le(4).putInt(1).array(), n + 12))
            pieces += zeros(n)
        }
        pieces += ByteArrayInputStream(response(1))
        val (p) = connection(SequenceInputStream(Collections.enumeration(pieces)))
        p.use {
            p.command(0x1002, intArrayOf(1)).requireOk()
            var count = 0L
            val result = p.command(0x1009, expected = size) { _, _, n -> count += n }.requireOk()
            assertEquals(size, count); assertEquals(size, result.transferred)
        }
    }

    @Test fun unknownWireLengthStillRequiresEndPacketAndFinalSuccess() {
        val (p) = connection(start(0, -1L) + data(0, byteArrayOf(4, 5, 6)) + response(0))
        p.use { assertArrayEquals(byteArrayOf(4, 5, 6), p.command(0x1001).requireOk().data) }
    }
    @Test fun staleResponseOrDataTransactionClosesBothChannels() {
        assertBroken(response(91)) { it.command(0x1001) }
        assertBroken(start(0, 1) + data(91, byteArrayOf(1))) { it.command(0x1001) }
    }
    @Test fun truncatedDataAndMismatchedLengthNeverSucceed() {
        assertBroken(start(0, 9) + data(0, byteArrayOf(1))) { it.command(0x1009, sink = { _, _, _ -> }) }
        assertBroken(start(0, 1) + data(0, byteArrayOf(1))) { it.command(0x1009, expected = 9, sink = { _, _, _ -> }) }
        assertBroken(start(0, 2) + frame(12, le(4).putInt(0).array(), 14) + byteArrayOf(1)) { it.command(0x1009, sink = { _, _, _ -> }) }
    }
    @Test fun missingEndDataAndOversizedMetadataAreRejected() {
        assertBroken(start(0, 1) + data(0, byteArrayOf(1), false) + response(0)) { it.command(0x1001) }
        assertBroken(start(0, 17L * 1024 * 1024)) { it.command(0x1001) }
        assertBroken(frame(7, byteArrayOf(), 0xffffffffL)) { it.command(0x1001) }
    }
    @Test fun finalCameraFailureIsNotReportedAsCompletedFile() {
        val (p) = connection(start(0, 2) + data(0, byteArrayOf(1, 2)) + response(0, 0x2002))
        p.use {
            val reply = p.command(0x1009, expected = 2, sink = { _, _, _ -> })
            assertEquals(0x2002, reply.code); assertThrows(IOException::class.java) { reply.requireOk() }
        }
    }
    @Test fun unsupportedCommandDoesNotPoisonTheConnection() {
        val (p) = connection(response(0, 0x2005) + response(0))
        p.use { assertEquals(0x2005, p.command(0x9803).code); assertTrue(p.usable); p.command(0x1003).requireOk() }
    }
    @Test fun handshakeRefusalClosesSocketAndReportsConnectionMode() {
        val command = MemorySocket(ByteArrayInputStream(frame(5, le(4).putInt(1).array())))
        val error = assertThrows(IOException::class.java) { PtpIpProtocol.connect(guid, "Android") { command } }
        assertTrue(command.wasClosed); assertTrue(error.message!!.contains("智能设备"))
    }

    @Test fun eventSocketLossInvalidatesBothChannelsAndNotifiesOnlyOnce() {
        val (p, command, event) = connection(byteArrayOf())
        val disconnected = CountDownLatch(1)
        val calls = java.util.concurrent.atomic.AtomicInteger()
        p.onDisconnected = { calls.incrementAndGet(); disconnected.countDown() }
        event.close()
        assertTrue(disconnected.await(3, TimeUnit.SECONDS))
        p.abort(); p.close()
        assertEquals(1, calls.get()); assertTrue(command.wasClosed); assertFalse(p.usable)
    }

    @Test fun realTcpLoopbackExchangesBothChannelsAndAnswersCameraPing() {
        ServerSocket(0, 2, InetAddress.getLoopbackAddress()).use { server ->
            val pool = Executors.newSingleThreadExecutor()
            val failure = AtomicReference<Throwable?>()
            val finished = CountDownLatch(1)
            pool.execute {
                try {
                    server.accept().use { command ->
                        command.soTimeout = 5000
                        fun readPacket(socket: Socket): Pair<Int, ByteArray> {
                            val input = DataInputStream(socket.getInputStream())
                            val h = ByteArray(8).also(input::readFully)
                            val p = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
                            val length = p.int; val type = p.int
                            return type to ByteArray(length - 8).also(input::readFully)
                        }
                        assertEquals(1, readPacket(command).first)
                        // Split handshake header across actual TCP writes.
                        val ack = ack(); command.getOutputStream().write(ack, 0, 3)
                        command.getOutputStream().write(ack, 3, ack.size - 3)
                        server.accept().use { events ->
                            events.soTimeout = 5000
                            assertEquals(3, readPacket(events).first)
                            events.getOutputStream().write(frame(4) + frame(13))
                            assertEquals(14, readPacket(events).first)
                            val request = readPacket(command)
                            assertEquals(6, request.first)
                            command.getOutputStream().write(start(0, 4) + data(0, byteArrayOf(9, 8), false) + data(0, byteArrayOf(7, 6)) + response(0))
                            finished.await(5, TimeUnit.SECONDS)
                        }
                    }
                } catch (e: Throwable) { failure.set(e) }
            }
            try {
                val p = PtpIpProtocol.connect(guid, "Loopback") { Socket(InetAddress.getLoopbackAddress(), server.localPort).apply { soTimeout = 5000 } }
                p.use { assertArrayEquals(byteArrayOf(9, 8, 7, 6), p.command(0x1001).requireOk().data) }
            } finally {
                finished.countDown(); pool.shutdown(); assertTrue(pool.awaitTermination(7, TimeUnit.SECONDS))
            }
            failure.get()?.let { throw AssertionError("mock TCP camera failed", it) }
        }
    }

    @Test fun endpointRejectsInternetDnsInvalidIpAndInvalidPort() {
        listOf("192.168.1.1", "10.0.0.2", "172.16.1.2", "169.254.20.1", "").forEach { WifiEndpoint(it) }
        listOf("8.8.8.8", "127.0.0.1", "example.com", "192.168.1.999", "192.168.1", "192.168.1.-1", "172.15.0.1").forEach {
            assertThrows(IllegalArgumentException::class.java) { WifiEndpoint(it) }
        }
        assertThrows(IllegalArgumentException::class.java) { WifiEndpoint(port = 0) }
        assertThrows(IllegalArgumentException::class.java) { WifiEndpoint(port = 65536) }
    }

    @Test fun subnetMatchingHandlesNonByteAlignedPrefixesAndRejectsDefaultRoutes() {
        fun ip(value: String) = InetAddress.getByName(value).address
        assertTrue(sameIpv4Subnet(ip("192.168.1.20"), ip("192.168.1.1"), 24))
        assertFalse(sameIpv4Subnet(ip("192.168.1.20"), ip("192.168.2.1"), 24))
        assertFalse(sameIpv4Subnet(ip("192.168.1.20"), ip("192.168.1.130"), 25))
        assertTrue(sameIpv4Subnet(ip("192.168.1.129"), ip("192.168.1.130"), 25))
        assertTrue(sameIpv4Subnet(ip("169.254.10.2"), ip("169.254.80.1"), 16))
        assertTrue(sameIpv4Subnet(ip("10.0.0.1"), ip("10.0.0.1"), 32))
        assertFalse(sameIpv4Subnet(ip("10.0.0.1"), ip("10.0.0.2"), 32))
        assertFalse(sameIpv4Subnet(ip("10.0.0.1"), ip("192.168.1.1"), 0))
    }
}
