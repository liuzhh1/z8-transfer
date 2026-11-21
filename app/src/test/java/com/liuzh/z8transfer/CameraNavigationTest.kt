package com.liuzh.z8transfer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.liuzh.z8transfer.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/** Exercise the real controller's connection/navigation path with a camera whose scan can remain unfinished. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CameraNavigationTest {
    private lateinit var controller: AppController
    private lateinit var source: Source
    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context: Context = ApplicationProvider.getApplicationContext()
        controller = AppController(context)
        source = Source()
    }
    @After fun cleanup() = runBlocking {
        if (::controller.isInitialized) controller.scope.coroutineContext[Job]?.cancelAndJoin()
        Dispatchers.resetMain()
    }
    private fun connect() {
        val open: suspend () -> CameraTransport = { source }
        controller.javaClass.declaredMethods.single { it.name == "connectCamera" }.apply { isAccessible = true }
            .invoke(controller,open)
    }
    private suspend fun await(predicate: (AppState) -> Boolean): AppState = withTimeout(5000) { controller.state.first(predicate) }
    private class Source : CameraTransport {
        override val sessionId = "navigation-session"
        override val displayName = "Nikon Z8"
        override val diagnostics = "test camera"
        override var attached = true
        private val wire = Mutex()
        val scans = AtomicInteger(); val cancelledScans = AtomicInteger(); val starts = AtomicInteger(); val captures = AtomicInteger()
        var slowScan = false
        var failScan = false
        @Volatile var scanning = false
        var files = listOf(photo(1))
        val parameterReads = java.util.concurrent.atomic.AtomicInteger()
        val parameterWrites = java.util.concurrent.atomic.AtomicInteger()
        override val remoteControl = object : RemoteCamera {
            override val capabilities = RemoteCapabilities(true,true,true,true)
            override suspend fun readParameters(): List<ShootingParameter> {
                parameterReads.incrementAndGet()
                return listOf(ShootingParameter(ShootingSetting.ISO,DeviceProperty(0x500f,4,true,100,listOf(100,200))))
            }
            override suspend fun setParameter(setting: ShootingSetting, value: Long): List<ShootingParameter> {
                parameterWrites.incrementAndGet()
                return listOf(ShootingParameter(setting,DeviceProperty(0x500f,4,true,value,listOf(100,200))))
            }
            override suspend fun startLiveView() = wire.withLock { check(!scanning); starts.incrementAndGet(); Unit }
            override suspend fun stopLiveView() = Unit
            override suspend fun frame(): ByteArray? = null
            override suspend fun focus() = Unit
            override suspend fun capture(autofocus: Boolean, onAccepted: () -> Unit): CaptureReceipt {
                check(!scanning); captures.incrementAndGet(); files = files + photo(2); onAccepted()
                return CaptureReceipt(true,"saved")
            }
            override suspend fun close() = Unit
        }
        override suspend fun scan(onBatch: (List<CameraPhoto>) -> Unit, onExamined: (Int) -> Unit) = wire.withLock {
            scans.incrementAndGet(); scanning = true
            try {
                if (failScan) error("card scan unavailable")
                if (slowScan) awaitCancellation()
                onBatch(files); onExamined(files.size)
            } finally {
                scanning = false
                if (slowScan) {
                    cancelledScans.incrementAndGet()
                    // A late callback from a cancelled scan must not leak into the remote page.
                    onBatch(listOf(photo(99))); onExamined(999)
                }
            }
        }
        override suspend fun thumbnail(photo: CameraPhoto): ByteArray? = null
        override suspend fun copyOriginal(photo: CameraPhoto, destination: OriginalDestination, progress: (Long) -> Unit) = Unit
        override fun markDetached() { attached = false }
        override suspend fun close() = Unit
        companion object {
            fun photo(i: Int) = CameraPhoto("navigation-session","camera",1,"card","CF",i,"DCIM/100",
                "DSC_$i.JPG",100,1000,1000,PhotoKind.JPEG)
        }
    }
    @Test fun connectionDoesNotLoadAnyPhotoUntilDownloadsIsSelected() = runBlocking {
        connect(); await { it.connected }
        assertEquals(CameraPage.HOME,controller.state.value.page)
        assertEquals(0,source.scans.get()); assertFalse(controller.state.value.photosCurrent)
        controller.selectPage(CameraPage.PHOTOS)
        await { it.photosCurrent }
        assertEquals(1,source.scans.get()); assertEquals(1,controller.state.value.photos.size)
    }
    @Test fun remoteStartsAndShootsWithNoPhotoEnumeration() = runBlocking {
        connect(); await { it.connected }; controller.openRemote()
        await { it.remote.status.live && !it.remote.busy }
        assertEquals(0,source.scans.get())
        controller.remoteAction(RemoteAction.Capture(false))
        await { source.captures.get() == 1 && !it.remote.busy }
        controller.closeRemote(); await { !it.remote.open }
        assertEquals(0,source.scans.get())
    }
    @Test fun selectingRemoteBeforeConnectingStartsItAfterHandshakeWithoutScanning() = runBlocking {
        controller.selectPage(CameraPage.REMOTE); connect()
        await { it.remote.status.live }
        assertEquals(1,source.starts.get()); assertEquals(0,source.scans.get())
    }
    @Test fun parameterControlsStayInsideRemoteSessionWithoutPhotoEnumeration() = runBlocking {
        connect(); await { it.connected }; controller.openRemote(); await { it.remote.status.live && !it.remote.busy }
        assertEquals(0,source.parameterReads.get())
        controller.remoteAction(RemoteAction.ReadParameters); await { it.remote.status.parametersLoaded && !it.remote.busy }
        assertEquals(1,source.parameterReads.get()); assertEquals(0,source.scans.get())
        controller.remoteAction(RemoteAction.SetParameter(ShootingSetting.ISO,200))
        await { it.remote.status.parameters.firstOrNull()?.property?.current == 200L && !it.remote.busy }
        controller.remoteAction(RemoteAction.Capture(false)); await { source.captures.get() == 1 && !it.remote.busy }
        controller.closeRemote(); await { !it.remote.open }
        assertEquals(1,source.parameterWrites.get()); assertEquals(0,source.scans.get())
    }
    @Test fun selectingDownloadsBeforeConnectingIsAnExplicitScanRequest() = runBlocking {
        controller.selectPage(CameraPage.PHOTOS); connect()
        await { it.photosCurrent }
        assertEquals(1,source.scans.get()); assertEquals(0,source.starts.get())
    }
    @Test fun unfinishedScanIsCancelledBeforeRemoteAndLateCallbacksAreIgnored() = runBlocking {
        source.slowScan = true
        connect(); await { it.connected }; controller.selectPage(CameraPage.PHOTOS)
        await { it.scanning && source.scans.get() == 1 }
        controller.selectPage(CameraPage.REMOTE)
        await { it.remote.status.live }
        assertFalse(source.scanning); assertFalse(controller.state.value.scanning)
        assertEquals(1,source.cancelledScans.get()); assertEquals(1,source.scans.get())
        assertTrue(controller.state.value.photos.isEmpty()); assertNotEquals(999,controller.state.value.examined)
    }
    @Test fun leavingRemoteAfterCaptureDefersRefreshUntilDownloadsAndRetainsSelection() = runBlocking {
        connect(); await { it.connected }; controller.selectPage(CameraPage.PHOTOS); await { it.photosCurrent }
        val old = controller.state.value.photos.single(); controller.toggle(old)
        controller.openRemote(); await { it.remote.status.live && !it.remote.busy }
        controller.remoteAction(RemoteAction.Capture(false)); await { source.captures.get() == 1 && !it.remote.busy }
        controller.selectPage(CameraPage.HOME); await { !it.remote.open && !it.switchingPage }
        assertEquals(1,source.scans.get()); assertFalse(controller.state.value.photosCurrent)
        controller.selectPage(CameraPage.PHOTOS); await { it.photosCurrent }
        assertEquals(2,source.scans.get()); assertEquals(2,controller.state.value.photos.size)
        assertEquals(setOf(old.key),controller.state.value.selected)
    }
    @Test fun reenteringDownloadsRefreshesOnceButRepeatedTapOnCurrentPageDoesNot() = runBlocking {
        connect(); await { it.connected }; controller.selectPage(CameraPage.PHOTOS); await { it.photosCurrent }
        controller.selectPage(CameraPage.PHOTOS)
        assertEquals(1,source.scans.get())
        controller.selectPage(CameraPage.HOME); await { !it.switchingPage }
        assertEquals(1,source.scans.get())
        controller.selectPage(CameraPage.PHOTOS); await { it.photosCurrent }
        assertEquals(2,source.scans.get())
        controller.refresh(); await { it.photosCurrent }
        assertEquals(3,source.scans.get())
    }
    @Test fun albumReadFailureDoesNotBlockRemoteShootingPage() = runBlocking {
        source.failScan = true
        connect(); await { it.connected }; controller.selectPage(CameraPage.PHOTOS)
        await { !it.scanning && it.error?.contains("card scan unavailable") == true }
        controller.openRemote(); await { it.remote.status.live }
        assertEquals(1,source.scans.get()); assertEquals(1,source.starts.get())
    }
    @Test fun connectionCompletingInBackgroundDoesNotStartRemoteUntilExplicitResume() = runBlocking {
        controller.setForeground(false)
        controller.selectPage(CameraPage.REMOTE); connect(); await { it.connected }
        assertEquals(0,source.starts.get()); assertFalse(controller.state.value.remote.open)
        controller.setForeground(true)
        assertEquals(0,source.starts.get())
        controller.openRemote(); await { it.remote.status.live }
        assertEquals(1,source.starts.get()); assertEquals(0,source.scans.get())
    }

}
