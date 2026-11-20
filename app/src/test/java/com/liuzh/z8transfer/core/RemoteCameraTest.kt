package com.liuzh.z8transfer.core

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class RemoteCameraTest {
    private val full = setOf(0x90c1, 0x90c2, 0x90c8, 0x9201, 0x9202, 0x9203, 0x9207)
    private val jpeg = byteArrayOf(-1,-40,-1,-32,0,2,-1,-39)
    private class Rig(ops: Set<Int>) {
        val calls = mutableListOf<Pair<Int,List<Int>>>()
        var response: (Int, IntArray) -> PtpReply = { _, _ -> PtpReply(0x2001) }
        val camera = NikonRemoteCamera(ops) { op, args -> calls += op to args.toList(); response(op,args) }
    }
    @Test fun capabilitiesRequireAllLiveViewOperationsAndExcludeSdramOnlyCapture() {
        assertEquals(RemoteCapabilities(), Rig(emptySet()).camera.capabilities)
        assertFalse(Rig(setOf(0x9201,0x9203)).camera.capabilities.liveView)
        assertFalse(Rig(setOf(0x90c0,0x90cb)).camera.capabilities.capture)
        assertTrue(Rig(setOf(0x100e)).camera.capabilities.capture)
    }
    @Test fun startsFocusesCapturesExactlyOnceToCardAndReleasesControl() = runTest {
        val rig = Rig(full)
        var accepted = 0
        rig.camera.startLiveView()
        val receipt = rig.camera.capture(true) { accepted++ }
        rig.camera.close()
        assertTrue(receipt.ready); assertEquals(1, accepted)
        assertEquals(listOf(-1,0), rig.calls.single { it.first == 0x9207 }.second)
        assertTrue(rig.calls.indexOfFirst { it.first == 0x90c1 } < rig.calls.indexOfFirst { it.first == 0x9207 })
        assertTrue(rig.calls.indexOfFirst { it.first == 0x9202 } < rig.calls.indexOfLast { it == 0x90c2 to listOf(0) })
        val count = rig.calls.size
        rig.camera.close(); assertEquals(count, rig.calls.size)
    }
    @Test fun standardCaptureUsesNoVendorOrSdramCommands() = runTest {
        val rig = Rig(setOf(0x100e))
        rig.camera.capture(false) {}
        rig.camera.close()
        assertEquals(listOf(0x100e to listOf(0,0)), rig.calls)
    }
    @Test fun shutterRejectionAndIoAmbiguityNeverRetryOrFallBack() = runTest {
        val rig = Rig(full + 0x100e)
        rig.response = { op, _ -> PtpReply(if (op == 0x9207) 0x2019 else 0x2001) }
        try { rig.camera.capture(false) { fail("Not accepted") }; fail() } catch (_: IOException) { }
        assertEquals(1, rig.calls.count { it.first == 0x9207 }); assertTrue(rig.calls.none { it.first == 0x100e })
        rig.calls.clear()
        rig.response = { op,_ -> if (op == 0x9207) throw IOException("lost response") else PtpReply(0x2001) }
        try { rig.camera.capture(false) {}; fail() } catch (_: CaptureUncertainException) { }
        assertEquals(1, rig.calls.count { it.first == 0x9207 }); assertTrue(rig.calls.none { it.first == 0x100e })
    }
    @Test fun busyBeforeShutterAndFocusFailureDoNotIssueCapture() = runTest {
        val rig = Rig(full)
        rig.response = { op,_ -> PtpReply(if (op == 0x90c8) 0x2019 else 0x2001) }
        try { rig.camera.capture(false) {}; fail() } catch (_: IOException) { }
        assertTrue(rig.calls.none { it.first == 0x9207 })
        rig.calls.clear()
        rig.response = { op,_ -> PtpReply(if (op == 0x90c1) 0xa002 else 0x2001) }
        try { rig.camera.capture(true) {}; fail() } catch (_: IOException) { }
        assertTrue(rig.calls.none { it.first == 0x9207 })
    }
    @Test fun acceptedCaptureReportsUnknownReadinessInsteadOfReissuingShutter() = runTest {
        val rig = Rig(full)
        var accepted = false
        rig.response = { op,_ -> PtpReply(if (op == 0x90c8 && accepted) 0x2019 else 0x2001) }
        val receipt = rig.camera.capture(false) { accepted = true }
        assertTrue(accepted); assertFalse(receipt.ready)
        assertEquals(1, rig.calls.count { it.first == 0x9207 })
    }
    @Test fun framesHandleHeaderBusyAndOneLiveViewRestartWithoutTakingPhotos() = runTest {
        val rig = Rig(full)
        var response = PtpReply(0x2019)
        rig.response = { op,_ -> if (op == 0x9203) response else PtpReply(0x2001) }
        rig.camera.startLiveView(); assertNull(rig.camera.frame())
        response = PtpReply(0x2001, ByteArray(384) + jpeg + byteArrayOf(0,0))
        assertArrayEquals(jpeg, rig.camera.frame())
        response = PtpReply(0xa00b); assertNull(rig.camera.frame())
        try { rig.camera.frame(); fail() } catch (_: IllegalStateException) { }
        assertEquals(2,rig.calls.count { it.first == 0x9201 })
        assertTrue(rig.calls.none { it.first == 0x9207 })
        rig.camera.close()
    }
    @Test fun failedLiveViewStartStillReleasesOwnedControl() = runTest {
        val rig = Rig(full)
        rig.response = { op,_ -> PtpReply(if (op == 0x9201) 0xa004 else 0x2001) }
        try { rig.camera.startLiveView(); fail() } catch (_: IOException) { }
        rig.camera.close()
        assertTrue(rig.calls.contains(0x90c2 to listOf(0)))
    }
    @Test fun frameParserRejectsMissingTruncatedOrOversizeData() {
        assertArrayEquals(jpeg, liveViewJpeg(ByteArray(1024) + jpeg + ByteArray(8)))
        for (bad in listOf(ByteArray(5), jpeg.copyOf(6), ByteArray(8 * 1024 * 1024 + 1))) {
            try { liveViewJpeg(bad); fail() } catch (_: Exception) { }
        }
    }
    @Test fun usbProtocolCarriesLiveViewAndSingleCardShutterWithTransactionIds() = runTest {
        fun packet(type: Int, op: Int, id: Int, body: ByteArray = byteArrayOf()): ByteArray =
            java.nio.ByteBuffer.allocate(12+body.size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .putInt(12+body.size).putShort(type.toShort()).putShort(op.toShort()).putInt(id).put(body).array()
        val received = packet(3,0x2001,1) + packet(2,0x9203,2,ByteArray(128)+jpeg) + packet(3,0x2001,2) +
            packet(3,0x2001,3) + packet(3,0x2001,4)
        val input = java.io.ByteArrayInputStream(received)
        val sent = java.io.ByteArrayOutputStream()
        val pipe = object : PtpPipe {
            override fun read(buffer: ByteArray): Int = input.read(buffer)
            override fun write(buffer: ByteArray, offset: Int, length: Int): Int { sent.write(buffer,offset,length);return length }
            override fun close() = Unit
        }
        val protocol = PtpProtocol(pipe,sessionAlreadyOpen = true)
        val remote = NikonRemoteCamera(setOf(0x9201,0x9202,0x9203,0x9207)) { op,args -> protocol.command(op,args) }
        remote.startLiveView(); assertArrayEquals(jpeg,remote.frame()); remote.capture(false) {}; remote.close()
        val b = java.nio.ByteBuffer.wrap(sent.toByteArray()).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val ops = mutableListOf<Int>(); val ids = mutableListOf<Int>()
        while (b.hasRemaining()) {
            val end = b.position()+b.int; assertEquals(1,b.short.toInt()); ops += b.short.toInt() and 65535; ids += b.int
            if (ops.last()==0x9207) { assertEquals(-1,b.int); assertEquals(0,b.int) }
            b.position(end)
        }
        assertEquals(listOf(0x9201,0x9203,0x9207,0x9202),ops); assertEquals(listOf(1,2,3,4),ids)
    }

}
