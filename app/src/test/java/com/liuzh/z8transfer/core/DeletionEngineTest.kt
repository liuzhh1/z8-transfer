package com.liuzh.z8transfer.core

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class DeletionEngineTest {
    private fun file(handle: Int = 1) = CameraPhoto("session", "camera", 1, "card", "CFexpress", handle,
        "DCIM/100NZ_8", "DSC_$handle.NEF", 1234, 1000, 2000, PhotoKind.RAW, parentHandle = 42)
    private class Source : CameraTransport {
        override val sessionId = "session"
        override val displayName = "Fake"
        override val diagnostics = ""
        override var attached = true
        override val supportsDeletion = true
        val calls = mutableListOf<Int>()
        var reject: Int? = null
        var changed = false
        var directory = false
        var afterDelete: () -> Unit = {}
        override suspend fun deleteOriginal(photo: CameraPhoto) {
            check(photo.sameObject(photo.name, photo.storageId, if (changed) 8 else photo.size,
                photo.takenAt, photo.modifiedAt, photo.parentHandle, directory))
            if (photo.handle == reject) error("文件受保护")
            calls += photo.handle
            afterDelete()
        }
        override suspend fun scan(onBatch: (List<CameraPhoto>) -> Unit, onExamined: (Int) -> Unit) = Unit
        override suspend fun thumbnail(photo: CameraPhoto): ByteArray? = null
        override suspend fun copyOriginal(photo: CameraPhoto, destination: OriginalDestination, progress: (Long) -> Unit) = Unit
        override fun markDetached() { attached = false }
        override suspend fun close() = Unit
    }

    @Test fun onlyTheConfirmedSnapshotIsDeleted() = runTest {
        val selection = mutableListOf(file())
        val plan = DeletionPlan("session", selection)
        selection += file(2)
        val source = Source()
        val result = DeletionEngine().run(source, plan, { false }, {})
        assertEquals(listOf(1), source.calls)
        assertEquals(1, result.deleted)
    }
    @Test fun oldSessionIsRejectedBeforeAnyDelete() = runTest {
        val source = Source()
        try { DeletionEngine().run(source, DeletionPlan("old", listOf(file().copy(sessionId = "old"))), { false }, {}); fail() }
        catch (_: IllegalStateException) { assertTrue(source.calls.isEmpty()) }
    }
    @Test fun wildcardHandlesCanNeverEnterADeletionPlan() {
        for (handle in listOf(0, -1)) try { DeletionPlan("session", listOf(file(handle))); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun protectedFileFailureDoesNotRepeatAndDoesNotExpandSelection() = runTest {
        val source = Source().apply { reject = 1 }
        val result = DeletionEngine().run(source, DeletionPlan("session", listOf(file(), file(2))), { false }, {})
        assertEquals(listOf(2), source.calls)
        assertEquals(1, result.failed)
        assertEquals(1, result.deleted)
    }
    @Test fun changedObjectAndDirectoriesAreNeverDeleted() = runTest {
        for (source in listOf(Source().apply { changed = true }, Source().apply { directory = true })) {
            val result = DeletionEngine().run(source, DeletionPlan("session", listOf(file())), { false }, {})
            assertEquals(1, result.failed); assertTrue(source.calls.isEmpty())
        }
    }
    @Test fun stopAndDisconnectPreventFurtherCommands() = runTest {
        var stop = false
        val source = Source().apply { afterDelete = { stop = true } }
        val result = DeletionEngine().run(source, DeletionPlan("session", listOf(file(), file(2))), { stop }, {})
        assertEquals(listOf(1), source.calls); assertTrue(result.stopped)
        val detached = Source().apply { attached = false }
        assertTrue(DeletionEngine().run(detached, DeletionPlan("session", listOf(file())), { false }, {}).stopped)
        assertTrue(detached.calls.isEmpty())
    }
    @Test fun parentNameAndStorageAreRevalidated() {
        val p = file()
        assertTrue(p.sameObject(p.name, 1, 1234, 1000, 2000, 42, false))
        assertFalse(p.sameObject(p.name, 2, 1234, 1000, 2000, 42, false))
        assertFalse(p.sameObject(p.name, 1, 1234, 1000, 2000, 43, false))
        assertFalse(p.sameObject("OTHER.NEF", 1, 1234, 1000, 2000, 42, false))
    }
}
