package com.liuzh.z8transfer.core

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PtpProtocolTest {
    private fun frame(type: Int, code: Int, id: Int, body: ByteArray = byteArrayOf(), length: Long = 12L + body.size): ByteArray =
        ByteBuffer.allocate(12 + body.size).order(ByteOrder.LITTLE_ENDIAN).putInt(length.toInt()).putShort(type.toShort()).putShort(code.toShort()).putInt(id).put(body).array()

    private class Packets(val packets: ArrayDeque<ByteArray>) : PtpPipe {
        var closed = false
        val sent = ByteArrayOutputStream()
        private var current: ByteArray? = null
        private var at = 0
        override fun read(buffer: ByteArray): Int {
            if (current == null) { if (packets.isEmpty()) return -1; current = packets.removeFirst(); at = 0 }
            val bytes = current!!
            val n = minOf(buffer.size, bytes.size - at)
            bytes.copyInto(buffer, 0, at, at + n); at += n
            if (at == bytes.size) current = null
            return n
        }
        override fun write(buffer: ByteArray, offset: Int, length: Int): Int { sent.write(buffer, offset, length); return length }
        override fun close() { closed = true }
    }

    @Test fun shortHeaderAndPayloadPacketsRemainByteExact() {
        val payload = ByteArray(300_019) { (it * 19).toByte() }
        val pipe = Packets(ArrayDeque(listOf(frame(2, 0x1009, 1, length = 12L + payload.size), payload, frame(3, 0x2001, 1))))
        val output = ByteArrayOutputStream()
        val result = PtpProtocol(pipe, sessionAlreadyOpen = true).command(0x1009, intArrayOf(77), payload.size.toLong()) { b, o, n -> output.write(b, o, n) }.requireOk()
        assertArrayEquals(payload, output.toByteArray())
        assertEquals(payload.size.toLong(), result.transferred)
        assertEquals(77, ByteBuffer.wrap(pipe.sent.toByteArray()).order(ByteOrder.LITTLE_ENDIAN).getInt(12))
    }

    private inner class HugePipe(private val length: Long, private val zlp: Boolean = false) : PtpPipe {
        var stage = 0
        var remaining = length
        var closed = false
        override fun write(buffer: ByteArray, offset: Int, length: Int): Int = length
        override fun read(buffer: ByteArray): Int = when (stage) {
            0 -> { stage = 1; frame(2, 0x1009, 1, length = 0xffffffffL).copyInto(buffer); 12 }
            1 -> if (remaining > 0) {
                val n = minOf(remaining, buffer.size.toLong()).toInt(); remaining -= n
                if (remaining == 0L && !zlp) stage = 3
                n
            } else { stage = 3; 0 }
            3 -> { stage = 4; frame(3, 0x2001, 1).copyInto(buffer); 12 }
            else -> -1
        }
        override fun close() { closed = true }
    }

    @Test fun knownSixGiBUsesLongCountersWithoutWrapping() {
        val size = 6L * 1024 * 1024 * 1024 + 37
        val pipe = HugePipe(size)
        var count = 0L
        val result = PtpProtocol(pipe, sessionAlreadyOpen = true).command(0x1009, intArrayOf(1), size) { _, _, n -> count += n }.requireOk()
        assertEquals(size, count); assertEquals(size, result.transferred); assertFalse(pipe.closed)
    }
    @Test fun unknownSixGiBEndsAtShortPacketAndReadsFinalResponse() {
        val size = 6L * 1024 * 1024 * 1024 + 37
        var count = 0L
        val result = PtpProtocol(HugePipe(size), sessionAlreadyOpen = true).command(0x1009, intArrayOf(1)) { _, _, n -> count += n }.requireOk()
        assertEquals(size, count); assertEquals(size, result.transferred)
    }
    @Test fun exactPacketMultipleUsesZeroLengthPacketBoundary() {
        val size = 4L * 1024 * 1024 * 1024
        var count = 0L
        PtpProtocol(HugePipe(size, zlp = true), sessionAlreadyOpen = true).command(0x1009, intArrayOf(1)) { _, _, n -> count += n }.requireOk()
        assertEquals(size, count)
    }
    @Test fun compatibilityReadSizePreservesUnknownLengthVideoAndFinalResponse() {
        val size = 4L * 1024 * 1024 * 1024 + 37
        var count = 0L
        val protocol = PtpProtocol(HugePipe(size), bufferSize = 16 * 1024, sessionAlreadyOpen = true)
        val reply = protocol.command(0x1009, intArrayOf(1)) { _, _, n ->
            assertTrue(n <= 16 * 1024); count += n
        }.requireOk()
        assertEquals(size, count); assertEquals(size, reply.transferred); assertTrue(protocol.usable)
    }
    @Test fun finalResponseMustSucceedBeforeFileCanCommit() {
        val pipe = Packets(ArrayDeque(listOf(frame(2, 0x1009, 1, byteArrayOf(1, 2)), frame(3, 0x2002, 1))))
        val reply = PtpProtocol(pipe, sessionAlreadyOpen = true).command(0x1009, sink = { _, _, _ -> })
        assertEquals(0x2002, reply.code)
        try { reply.requireOk(); fail() } catch (_: Exception) { }
    }
    @Test fun staleTransactionAndTruncatedDataInvalidateSession() {
        for (packets in listOf(
            listOf(frame(3, 0x2001, 2)),
            listOf(frame(2, 0x1009, 1, byteArrayOf(1), 100), byteArrayOf()),
        )) {
            val pipe = Packets(ArrayDeque(packets)); val protocol = PtpProtocol(pipe, sessionAlreadyOpen = true)
            try { protocol.command(0x1009, sink = { _, _, _ -> }); fail() } catch (_: Exception) { }
            assertTrue(pipe.closed); assertFalse(protocol.usable)
        }
    }
    @Test fun unsupportedReadOperationDoesNotCorruptSession() {
        val pipe = Packets(ArrayDeque(listOf(frame(3, 0x2005, 1), frame(3, 0x2001, 2))))
        val p = PtpProtocol(pipe, sessionAlreadyOpen = true)
        assertEquals(0x2005, p.command(0x9803).code)
        assertTrue(p.usable)
        p.command(0x1003).requireOk()
    }
    @Test fun containerLengthDisagreementIsRejectedRatherThanTruncatingVideo() {
        val pipe = Packets(ArrayDeque(listOf(frame(2, 0x1009, 1, byteArrayOf(1, 2)))))
        try { PtpProtocol(pipe, sessionAlreadyOpen = true).command(0x1009, expected = 8L) { _, _, _ -> }; fail() } catch (_: Exception) { }
        assertTrue(pipe.closed)
    }
    @Test fun unsignedSizesAndUnicodeNamesAreDecodedWithoutSignedIntLoss() {
        val bytes = ByteBuffer.allocate(4 + 1 + 4).order(ByteOrder.LITTLE_ENDIAN).putInt(-1).put(2).putChar('图').putChar('\u0000').array()
        val data = PtpData(bytes)
        assertEquals(0xffffffffL, data.u32()); assertEquals("图", data.string())
    }
    @Test fun sessionOpeningUsesTransactionZeroAndSubsequentCommandsIncrement() {
        val pipe = Packets(ArrayDeque(listOf(frame(3, 0x2001, 0), frame(3, 0x2001, 1), frame(3, 0x2001, 2), frame(3, 0x2001, 0))))
        val p = PtpProtocol(pipe)
        p.command(0x1002, intArrayOf(1)).requireOk()
        p.command(0x1001).requireOk()
        p.command(0x1003).requireOk()
        p.command(0x1002, intArrayOf(1)).requireOk()
        val raw = ByteBuffer.wrap(pipe.sent.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
        val ids = mutableListOf<Int>()
        while (raw.remaining() > 0) {
            val start = raw.position(); val size = raw.int
            raw.short; raw.short; ids += raw.int; raw.position(start + size)
        }
        assertEquals(listOf(0, 1, 2, 0), ids)
    }
}
