package com.liuzh.z8transfer.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class UsbSessionInitializerTest {
    private fun u16(n: Int) = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(n.toShort()).array()
    private fun u32(n: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(n).array()
    private fun string(s: String) = byteArrayOf((s.length + 1).toByte()) + (s + '\u0000').toByteArray(Charsets.UTF_16LE)
    private val deviceInfo = u16(100) + u32(0x0a) + u16(100) + string("Nikon") + u16(0) +
        u32(3) + u16(0x1004) + u16(0x1009) + u16(0x100b) + ByteArray(16) +
        string("Nikon") + string("Z 8") + string("3.00") + string("test-serial")
    private fun info() = PtpReply(0x2001, deviceInfo)
    private fun ok() = PtpReply(0x2001)
    private fun stores() = PtpReply(0x2001, u32(1) + u32(0x10001))
    private class Script(vararg replies: PtpReply) {
        val remaining = ArrayDeque(replies.toList())
        val operations = mutableListOf<Int>()
        val arguments = mutableListOf<List<Int>>()
        val waits = mutableListOf<Long>()
        fun execute(code: Int, args: IntArray): PtpReply {
            operations += code; arguments += args.toList()
            return remaining.removeFirst()
        }
        fun initializer() = UsbSessionInitializer(::execute) { waits += it }
    }
    private suspend fun failure(block: suspend () -> Unit): Exception {
        try { block() } catch (e: Exception) { return e }
        throw AssertionError("Expected failure")
    }

    @Test fun readsIdentityBeforeOpeningSessionAndChecksStorageBeforeReportingSuccess() = runTest {
        val script = Script(info(), ok(), stores())
        val result = script.initializer().initialize()
        assertEquals(listOf(0x1001, 0x1002, 0x1004), script.operations)
        assertEquals(listOf(1), script.arguments[1])
        assertEquals("Z 8", result.deviceInfo!!.model)
        assertTrue(0x100b in result.deviceInfo.operations)
        assertArrayEquals(intArrayOf(0x10001), result.storageIds)
    }
    @Test fun camerasRequiringSessionCanReturnSessionNotOpenForFirstIdentityRead() = runTest {
        val script = Script(PtpReply(0x2003), ok(), info(), stores())
        assertEquals("Z 8", script.initializer().initialize().deviceInfo!!.model)
        assertEquals(listOf(0x1001, 0x1002, 0x1001, 0x1004), script.operations)
    }
    @Test fun alreadyOpenSessionIsAdoptedWithoutClosingOrResettingIt() = runTest {
        val script = Script(info(), PtpReply(0x201e), stores())
        val result = script.initializer().initialize()
        assertEquals(listOf(0x1001, 0x1002, 0x1004), script.operations)
        assertFalse(0x1003 in script.operations)
        assertTrue(result.notes.any { it.contains("沿用") })
    }
    @Test fun busyMetadataAndStorageReadAreRetriedWithABoundedPause() = runTest {
        val script = Script(PtpReply(0x2019), PtpReply(0x2019), info(), ok(), PtpReply(0x2019), stores())
        assertEquals("Z 8", script.initializer().initialize().deviceInfo!!.model)
        assertEquals(listOf(250L, 500L, 250L), script.waits)
        assertTrue(script.operations.all { it in setOf(0x1001, 0x1002, 0x1004) })
    }
    @Test fun persistentBusyDoesNotLoopOrPretendToConnect() = runTest {
        val script = Script(info(), PtpReply(0x2019), PtpReply(0x2019), PtpReply(0x2019))
        val error = failure { script.initializer().initialize() }
        assertTrue(error.message!!.contains("0x2019"))
        assertEquals(3, script.operations.count { it == 0x1002 })
        assertEquals(2, script.waits.size)
    }
    @Test fun unreadableIdentityDoesNotBlockReadableStorageAndCannotGrantDeleteCapability() = runTest {
        for (broken in listOf(PtpReply(0x2005), PtpReply(0x2001, byteArrayOf(1, 2)))) {
            val script = Script(broken, ok(), broken, stores())
            val result = script.initializer().initialize()
            assertNull(result.deviceInfo)
            assertEquals(1, result.storageIds.size)
            assertTrue(result.deviceInfo?.operations?.contains(0x100b) != true)
            assertTrue(result.notes.any { it.contains("禁用删除") })
        }
    }
    @Test fun missingIdentityAndUnreadableStorageRemainAHardFailure() = runTest {
        val script = Script(PtpReply(0x2005), ok(), PtpReply(0x2005), PtpReply(0x2003))
        val error = failure { script.initializer().initialize() }
        assertTrue(error.message!!.contains("0x1004")); assertTrue(error.message!!.contains("0x2003"))
    }
    @Test fun malformedStorageArrayIsNotTreatedAsAnEmptyCard() = runTest {
        val script = Script(info(), ok(), PtpReply(0x2001, u32(12)))
        assertTrue(failure { script.initializer().initialize() }.message!!.contains("存储卡列表数据不完整"))
    }
    @Test fun transportFailureIsNotRetriedOnTheBrokenChannel() = runTest {
        var calls = 0; var waits = 0
        val initializer = UsbSessionInitializer({ _, _ -> calls++; throw IOException("bulk read failed") }) { waits++ }
        val error = failure { initializer.initialize() }
        assertTrue(error.message!!.contains("0x1001")); assertTrue(error.message!!.contains("bulk read failed"))
        assertEquals(1, calls); assertEquals(0, waits)
    }
    @Test fun nullNativeMtpIdentityCanUseVerifiedStorageRatherThanFailingAtDeviceInfo() = runTest {
        var reads = 0
        val info = probeMtpConnection({ reads++; null as String? }, { intArrayOf(1) }, {})
        assertNull(info); assertEquals(2, reads)
    }
    @Test fun nativeMtpIdentityCanBecomeAvailableAfterCameraWakeup() = runTest {
        var reads = 0
        val info = probeMtpConnection({ if (++reads == 2) "Nikon Z8" else null }, { intArrayOf(1) }, {})
        assertEquals("Nikon Z8", info); assertEquals(2, reads)
    }
    @Test fun nativeMtpDoesNotConnectIfBothMetadataAndStorageAreUnreadable() = runTest {
        val error = failure { probeMtpConnection({ null as String? }, { null }, {}) }
        assertTrue(error.message!!.contains("设备信息和存储卡"))
    }
    @Test fun emptyStorageListIsAValidNoCardState() = runTest {
        val script = Script(info(), ok(), PtpReply(0x2001, u32(0)))
        assertTrue(script.initializer().initialize().storageIds.isEmpty())
        assertNull(probeMtpConnection({ null as String? }, { intArrayOf() }, {}))
    }
    @Test fun defaultConnectionUsesNativeMtpWithoutAnyDirectPtpProbe() = runTest {
        val opened = openSelectedUsb(system = { "native" }, standard = { fail("raw PTP must not run before native MTP"); "ptp" })
        assertEquals("native", opened)
    }
    @Test fun directPtpIsOnlyUsedWhenExplicitlySelected() = runTest {
        val opened = openSelectedUsb(UsbConnectionMode.DIRECT_PTP,
            system = { fail("unexpected native MTP"); "native" }, standard = { "ptp" })
        assertEquals("ptp", opened)
    }
    @Test fun nativeFailureDoesNotAutomaticallyRunRawCommands() = runTest {
        var rawAttempted = false
        val original = IOException("native MTP info unavailable")
        val error = failure { openSelectedUsb<String>(system = { throw original }, standard = { rawAttempted = true; "ptp" }) }
        assertSame(original, error); assertFalse(rawAttempted)
    }
    @Test fun failedRawWriteDoesNotImmediatelyOpenNativeMtpOnTheSameUsbLink() = runTest {
        var nativeAttempted = false
        val original = IOException("USB 命令发送失败")
        val error = failure { openSelectedUsb<String>(UsbConnectionMode.DIRECT_PTP,
            system = { nativeAttempted = true; "native" }, standard = { throw original }) }
        assertSame(original, error); assertFalse(nativeAttempted)
    }
    @Test fun cancellationDoesNotSwitchDriverOrTurnIntoMetadataFailure() = runTest {
        var rawAttempted = false
        val cancelled = failure { openSelectedUsb<String>(system = { throw CancellationException("cancelled") },
            standard = { rawAttempted = true; "ptp" }) }
        assertTrue(cancelled is CancellationException); assertFalse(rawAttempted)
        val initializer = UsbSessionInitializer({ _, _ -> throw CancellationException("cancelled") }, {})
        assertTrue(failure { initializer.initialize() } is CancellationException)
    }

    @Test fun actualUsbFramesUseZeroBeforeSessionAndAdvanceAfterAdoptingExistingSession() = runTest {
        fun frame(type: Int, code: Int, id: Int, bytes: ByteArray = byteArrayOf()) =
            ByteBuffer.allocate(12 + bytes.size).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(12 + bytes.size).putShort(type.toShort()).putShort(code.toShort()).putInt(id).put(bytes).array()
        val packets = ArrayDeque(listOf(frame(2, 0x1001, 0, deviceInfo), frame(3, 0x2001, 0),
            frame(3, 0x201e, 0), frame(2, 0x1004, 1, stores().data), frame(3, 0x2001, 1)))
        val output = ByteArrayOutputStream()
        val pipe = object : PtpPipe {
            override fun read(buffer: ByteArray): Int {
                val bytes = packets.removeFirst(); bytes.copyInto(buffer); return bytes.size
            }
            override fun write(buffer: ByteArray, offset: Int, length: Int): Int { output.write(buffer, offset, length); return length }
            override fun close() {}
        }
        PtpProtocol(pipe, bufferSize = 16 * 1024).use { protocol ->
            val initialized = UsbSessionInitializer({ code, args -> protocol.command(code, args) }, {}).initialize()
            assertEquals("Z 8", initialized.deviceInfo!!.model); assertTrue(protocol.hasSession)
        }
        val sent = ByteBuffer.wrap(output.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
        val transactions = mutableListOf<Int>()
        while (sent.hasRemaining()) {
            val position = sent.position(); val size = sent.int; sent.short; sent.short
            transactions += sent.int; sent.position(position + size)
        }
        assertEquals(listOf(0, 0, 1), transactions)
    }
}
