package com.liuzh.z8transfer.wifi

import android.content.Context
import android.net.ConnectivityManager
import androidx.test.core.app.ApplicationProvider
import com.liuzh.z8transfer.core.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import java.io.*
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.concurrent.CountDownLatch

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WifiDeletionTest {
    private fun le(n: Int) = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)
    private fun frame(type: Int, body: ByteArray = byteArrayOf()) = le(body.size + 8).putInt(body.size + 8).putInt(type).put(body).array()
    private fun response(id: Int, code: Int = 0x2001) = frame(7, le(6).putShort(code.toShort()).putInt(id).array())
    private fun data(id: Int, bytes: ByteArray) = frame(9, le(12).putInt(id).putLong(bytes.size.toLong()).array()) +
        frame(12, le(4 + bytes.size).putInt(id).put(bytes).array()) + response(id)
    private fun string(s: String) = byteArrayOf((s.length + 1).toByte()) + "$s\u0000".toByteArray(Charsets.UTF_16LE)
    private class Idle : InputStream() {
        val latch = CountDownLatch(1)
        override fun read(): Int { latch.await(); return -1 }
        override fun close() { latch.countDown() }
    }
    private class FakeSocket(val input: InputStream) : Socket() {
        val output = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = input
        override fun getOutputStream(): OutputStream = output
        override fun setSoTimeout(value: Int) {}
        override fun close() { input.close() }
    }
    private class Fixture(val source: WifiPtpTransport, val protocol: PtpIpProtocol, val socket: FakeSocket) : Closeable {
        override fun close() = protocol.close()
        fun commands(): List<Pair<Int, List<Int>>> {
            val b = ByteBuffer.wrap(socket.output.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
            val handshake = b.int; b.position(handshake)
            return buildList {
                while (b.hasRemaining()) {
                    val end = b.position() + b.int
                    b.int; b.int; val code = b.short.toInt() and 65535; b.int
                    val parameters = mutableListOf<Int>()
                    while (b.position() < end) parameters += b.int
                    if (code != 0x1002) add(code to parameters)
                }
            }
        }
    }
    private fun fixture(protected: Boolean = false, changed: Boolean = false, volume: String = "card", supports: Boolean = true, deleteCode: Int = 0x2001): Fixture {
        val card = le(26).putShort(3).putShort(2).putShort(0).putLong(1000).putLong(500).putInt(50).array() + string("CF") + string(volume)
        val info = le(52).putInt(1).putShort(0x3801).putShort(if (protected) 1 else 0).putInt(if (changed) 900 else 100)
            .putShort(0).putInt(0).putInt(0).putInt(0).putInt(0).putInt(0).putInt(0).putInt(42).putShort(0).putInt(0).putInt(0).array() +
            string("A.JPG") + string("") + string("")
        return wireFixture(data(1, card) + data(2, info) + response(3, deleteCode), if (supports) setOf(0x100b) else emptySet())
    }
    private fun wireFixture(replies: ByteArray, operations: Set<Int>): Fixture {
        val guid = ByteArray(16)
        val ack = frame(2, le(4).putInt(7).array() + guid + "Z8\u0000".toByteArray(Charsets.UTF_16LE) + le(4).putInt(0x10000).array())
        val command = FakeSocket(ByteArrayInputStream(ack + response(0) + replies))
        val event = FakeSocket(SequenceInputStream(Collections.enumeration(listOf(ByteArrayInputStream(frame(4)), Idle()))))
        val sockets = ArrayDeque(listOf(command, event))
        val protocol = PtpIpProtocol.connect(guid, "test") { sockets.removeFirst() }
        protocol.command(0x1002, intArrayOf(1)).requireOk()
        val context: Context = ApplicationProvider.getApplicationContext()
        val routeCtor = WifiRoute::class.java.declaredConstructors.first { it.parameterCount == 3 }.apply { isAccessible = true }
        val route = routeCtor.newInstance(context.getSystemService(ConnectivityManager::class.java), ShadowNetwork.newInstance(108), WifiEndpoint("192.168.1.1")) as WifiRoute
        val ctor = WifiPtpTransport::class.java.declaredConstructors.first { it.parameterCount == 6 }.apply { isAccessible = true }
        val source = ctor.newInstance(route, protocol, "Nikon Z8", "identity", operations, "") as WifiPtpTransport
        return Fixture(source, protocol, command)
    }
    private fun photo(source: WifiPtpTransport) = CameraPhoto(source.sessionId, "identity", 1, "card:1000", "CF", 77,
        "DCIM/100", "A.JPG", 100, 0, 0, PhotoKind.JPEG, parentHandle = 42)

    @Test fun supportedWifiDeleteRevalidatesCardAndObjectThenDeletesExactlyOneHandle() = runTest {
        fixture().use { f ->
            assertTrue(f.source.supportsDeletion)
            f.source.deleteOriginal(photo(f.source))
            assertEquals(listOf(0x1005 to listOf(1), 0x1008 to listOf(77), 0x100b to listOf(77, 0)), f.commands())
        }
    }
    @Test fun unsupportedProtectedChangedCardAndStaleObjectsNeverSendDelete() = runTest {
        for (f in listOf(fixture(supports = false), fixture(protected = true), fixture(changed = true), fixture(volume = "other"))) {
            f.use {
                try { f.source.deleteOriginal(photo(f.source)); fail("Must reject") } catch (_: IllegalStateException) { }
                assertTrue(f.commands().none { it.first == 0x100b })
            }
        }
        fixture().use { f ->
            try { f.source.deleteOriginal(photo(f.source).copy(sessionId = "old")); fail() } catch (_: IllegalStateException) { }
            assertTrue(f.commands().isEmpty())
        }
    }
    @Test fun cameraRejectionIsReportedWithoutRetry() = runTest {
        fixture(deleteCode = 0x200f).use { f ->
            val result = DeletionEngine().run(f.source, DeletionPlan(f.source.sessionId, listOf(photo(f.source))), { false }, {})
            assertEquals(1, result.failed)
            assertEquals(1, f.commands().count { it.first == 0x100b })
        }
    }
    @Test fun wirelessRemoteCommandsUseAdvertisedCapabilitiesAndExactCardCaptureParameters() = runTest {
        val jpeg = byteArrayOf(-1,-40,-1,-32,0,2,-1,-39)
        wireFixture(response(1) + data(2, ByteArray(384) + jpeg) + response(3) + response(4),
            setOf(0x9201,0x9202,0x9203,0x9207)).use { f ->
            val remote = f.source.remoteControl!!
            assertEquals(RemoteCapabilities(true,true,false),remote.capabilities)
            remote.startLiveView(); assertArrayEquals(jpeg,remote.frame())
            remote.capture(false) {}; remote.close()
            assertEquals(listOf(0x9201 to emptyList<Int>(),0x9203 to emptyList(),0x9207 to listOf(-1,0),0x9202 to emptyList()),f.commands())
        }
    }

}
