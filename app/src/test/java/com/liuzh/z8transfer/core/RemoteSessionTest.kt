package com.liuzh.z8transfer.core

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteSessionTest {
    private class Camera(override val capabilities: RemoteCapabilities = RemoteCapabilities(true,true,true)) : RemoteCamera {
        val calls = mutableListOf<String>()
        var bytes: ByteArray? = byteArrayOf(1)
        var captureError: Exception? = null
        var parameterError: Exception? = null
        override suspend fun readParameters(): List<ShootingParameter> {
            calls += "read"; parameterError?.let { throw it }
            return listOf(ShootingParameter(ShootingSetting.ISO,DeviceProperty(0x500f,4,true,100,listOf(100,200))))
        }
        override suspend fun setParameter(setting: ShootingSetting, value: Long): List<ShootingParameter> {
            calls += "set"; delay(300); parameterError?.let { throw it }
            return listOf(ShootingParameter(setting,DeviceProperty(0x500f,4,true,value,listOf(100,200))))
        }
        override suspend fun startLiveView() { calls += "start" }
        override suspend fun stopLiveView() { calls += "stop" }
        override suspend fun frame(): ByteArray? { calls += "frame"; return bytes }
        override suspend fun focus() { calls += "focus" }
        override suspend fun capture(autofocus: Boolean, onAccepted: () -> Unit): CaptureReceipt {
            calls += "capture"; captureError?.let { throw it }; onAccepted(); return CaptureReceipt(true,"accepted")
        }
        override suspend fun close() { calls += "close" }
    }
    @Test fun pollingPauseCaptureResumeAndCancellationAreSerialized() = runTest {
        val c = Camera(); val session = RemoteSession(c)
        var status = RemoteStatus(); var frames = 0; var accepted = 0
        val job = launch { session.run({status=it},{frames++},{accepted++},{throw it}) }
        runCurrent(); advanceTimeBy(400); runCurrent(); assertTrue(frames >= 3)
        assertTrue(session.submit(RemoteAction.Pause)); advanceTimeBy(130); runCurrent()
        assertFalse(status.live)
        val before = frames; advanceTimeBy(1000); assertEquals(before,frames)
        assertTrue(session.submit(RemoteAction.Capture(false)))
        assertFalse(session.submit(RemoteAction.Capture(false)))
        runCurrent(); assertEquals(1,accepted); assertEquals(1,c.calls.count { it=="capture" })
        assertTrue(session.submit(RemoteAction.Start)); runCurrent(); assertTrue(status.live)
        job.cancelAndJoin(); assertEquals("close",c.calls.last())
        assertFalse(session.submit(RemoteAction.Start))
    }
    @Test fun unknownShutterResultBlocksFurtherShotsButStillCleansUp() = runTest {
        val c = Camera(RemoteCapabilities(false,true,false)).apply { captureError = CaptureUncertainException("unknown",IOException()) }
        val session = RemoteSession(c); var status = RemoteStatus()
        val job = launch { session.run({status=it},{},{},{throw it}) }
        runCurrent(); session.submit(RemoteAction.Capture(false)); runCurrent()
        assertTrue(status.captureBlocked)
        session.submit(RemoteAction.Capture(false)); runCurrent()
        assertEquals(1,c.calls.count { it=="capture" })
        job.cancelAndJoin(); assertEquals("close",c.calls.last())
    }
    @Test fun repeatedFrameFailuresPauseStreamAndAllowExplicitRestart() = runTest {
        val c = Camera(); val session = RemoteSession(c); var status = RemoteStatus()
        val job = launch { session.run({status=it},{throw IOException("bad jpeg")},{},{throw it}) }
        runCurrent(); advanceTimeBy(400); runCurrent()
        assertFalse(status.live); assertEquals("bad jpeg",status.error)
        assertTrue(c.calls.contains("stop"))
        session.submit(RemoteAction.Start); runCurrent(); assertTrue(status.live)
        job.cancelAndJoin(); assertEquals("close",c.calls.last())
    }
    @Test fun parametersLoadLazilyAndWritesSerializeWithFramePollingAndShutter() = runTest {
        val c = Camera(); val session = RemoteSession(c); var status = RemoteStatus()
        val job = launch { session.run({status=it},{},{},{throw it}) }
        runCurrent(); advanceTimeBy(250); runCurrent()
        assertFalse(c.calls.contains("read")); assertFalse(c.calls.contains("set"))
        session.submit(RemoteAction.ReadParameters); advanceTimeBy(130); runCurrent()
        assertTrue(status.parametersLoaded); assertEquals(100L,status.parameters.single().property!!.current)
        session.submit(RemoteAction.SetParameter(ShootingSetting.ISO,200)); advanceTimeBy(130); runCurrent()
        assertTrue(status.busy); assertTrue(status.parameters.isEmpty())
        val frames = c.calls.count { it == "frame" }
        assertFalse(session.submit(RemoteAction.Capture(false)))
        advanceTimeBy(100); runCurrent(); assertEquals(frames,c.calls.count { it == "frame" })
        advanceTimeBy(400); runCurrent()
        assertEquals(200L,status.parameters.single().property!!.current); assertFalse(status.busy); assertTrue(status.live)
        session.submit(RemoteAction.Capture(false)); advanceTimeBy(130); runCurrent()
        assertEquals(1,c.calls.count { it == "capture" })
        job.cancelAndJoin()
    }
    @Test fun parameterFailureClearsUnverifiedValuesAndKeepsShutterAvailable() = runTest {
        val c = Camera(); val session = RemoteSession(c); var status = RemoteStatus()
        val job = launch { session.run({status=it},{},{},{throw it}) }
        runCurrent(); session.submit(RemoteAction.ReadParameters); advanceTimeBy(130); runCurrent()
        c.parameterError = IOException("read failed")
        session.submit(RemoteAction.ReadParameters); advanceTimeBy(130); runCurrent()
        assertEquals("read failed",status.error); assertFalse(status.parametersLoaded); assertTrue(status.parameters.isEmpty())
        assertFalse(status.captureBlocked); assertTrue(status.live)
        session.submit(RemoteAction.Capture(false)); advanceTimeBy(130); runCurrent()
        assertEquals(1,c.calls.count { it == "capture" }); job.cancelAndJoin()
    }
}
