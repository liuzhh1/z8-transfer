package com.liuzh.z8transfer.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.io.RandomAccessFile

class TransferEngineTest {
    @get:Rule val temporary = TemporaryFolder()
    private val payload = ByteArray(512 * 1024 + 17) { (it * 31).toByte() }
    private fun photo(handle: Int = 1, name: String = "DSC_0001.NEF") = CameraPhoto(
        "session-a", "camera-a", 1, "volume-a", "CFexpress", handle,
        "DCIM/100NZ_8", name, payload.size.toLong(), 1000, 2000, PhotoKind.RAW,
    )

    private interface WritableTarget : OriginalDestination { fun write(bytes: ByteArray, count: Int) }

    private inner class Store : ImportStore {
        val destinations = mutableListOf<Target>()
        val records = mutableMapOf<String, File>()
        var commitFails = false
        override suspend fun existing(photo: CameraPhoto): String? = records[photo.importKey]
            ?.takeIf { it.exists() && it.length() == photo.size }?.toURI()?.toString()
        override suspend fun begin(photo: CameraPhoto): OriginalDestination = Target(photo).also(destinations::add)
        inner class Target(private val photo: CameraPhoto) : WritableTarget {
            val file = temporary.newFile()
            private val writer = RandomAccessFile(file, "rw")
            var committed = false
            var rolledBack = false
            override val fileDescriptor: FileDescriptor get() = writer.fd
            override fun size(): Long = file.length()
            override fun write(bytes: ByteArray, count: Int) { writer.write(bytes, 0, count) }
            override suspend fun commit(): String {
                if (commitFails) throw IOException("相册发布失败")
                committed = true
                records[photo.importKey] = file
                return file.toURI().toString()
            }
            override suspend fun rollback() { rolledBack = true; writer.close(); file.delete() }
            override fun close() { writer.close() }
        }
    }

    private class Source(private val bytes: ByteArray) : CameraTransport {
        override val sessionId = "session-a"
        override val displayName = "Test camera"
        override val diagnostics = ""
        override var attached = true
        var copies = 0
        var truncate = false
        var failHandle: Int? = null
        var cancel = false
        var afterCopy: () -> Unit = {}
        override suspend fun scan(onBatch: (List<CameraPhoto>) -> Unit, onExamined: (Int) -> Unit) = Unit
        override suspend fun thumbnail(photo: CameraPhoto): ByteArray? = null
        override suspend fun copyOriginal(photo: CameraPhoto, destination: OriginalDestination, progress: (Long) -> Unit) {
            copies++
            (destination as WritableTarget).write(bytes, if (truncate) bytes.size / 2 else bytes.size)
            progress(destination.size())
            if (cancel) throw CancellationException("cancelled")
            if (photo.handle == failHandle) throw IOException("USB disconnected")
            afterCopy()
        }
        override fun markDetached() { attached = false }
        override suspend fun close() = Unit
    }

    @Test fun originalsRemainByteIdenticalAndPublishOnlyOnSuccess() = runTest {
        val store = Store()
        val source = Source(payload)
        val result = TransferEngine(store).run(source, listOf(photo()), { false }, {})
        assertEquals(1, result.saved)
        assertEquals(0, result.failed)
        assertArrayEquals(payload, store.destinations.single().file.readBytes())
        assertTrue(store.destinations.single().committed)
    }

    @Test fun truncatedDataIsRolledBackEvenWhenTransportReportsSuccess() = runTest {
        val store = Store()
        val result = TransferEngine(store).run(Source(payload).apply { truncate = true }, listOf(photo()), { false }, {})
        assertEquals(1, result.failed)
        assertFalse(store.destinations.single().committed)
        assertTrue(store.destinations.single().rolledBack)
        assertFalse(store.destinations.single().file.exists())
    }

    @Test fun failedTransferCleansPartialFileAndContinuesToNextSelection() = runTest {
        val store = Store()
        val source = Source(payload).apply { failHandle = 1 }
        val result = TransferEngine(store).run(source, listOf(photo(), photo(2, "DSC_0002.NEF")), { false }, {})
        assertEquals(1, result.saved)
        assertEquals(1, result.failed)
        assertTrue(store.destinations.first().rolledBack)
        assertArrayEquals(payload, store.destinations.last().file.readBytes())
    }

    @Test fun stopFinishesCurrentPhotoAndLeavesLaterPhotosUntouched() = runTest {
        val store = Store()
        var stop = false
        val source = Source(payload).apply { afterCopy = { stop = true } }
        val result = TransferEngine(store).run(source, listOf(photo(), photo(2)), { stop }, {})
        assertEquals(1, source.copies)
        assertEquals(1, result.saved)
        assertTrue(result.stopped)
        assertEquals(2, result.totalFiles)
    }

    @Test fun oldConnectionHandlesNeverTriggerDownloads() = runTest {
        val source = Source(payload)
        val result = TransferEngine(Store()).run(source, listOf(photo().copy(sessionId = "old-session")), { false }, {})
        assertEquals(0, source.copies)
        assertEquals(1, result.failed)
    }

    @Test fun existingOriginalIsSkippedButDeletedLocalCopyIsDownloadedAgain() = runTest {
        val store = Store()
        val engine = TransferEngine(store)
        val source = Source(payload)
        engine.run(source, listOf(photo()), { false }, {})
        val skipped = engine.run(source, listOf(photo()), { false }, {})
        assertEquals(1, source.copies)
        assertEquals(1, skipped.skipped)
        store.destinations.single().file.delete()
        val redownload = engine.run(source, listOf(photo()), { false }, {})
        assertEquals(2, source.copies)
        assertEquals(1, redownload.saved)
    }

    @Test fun publicationFailureDoesNotLeaveCompletedOrPartialFile() = runTest {
        val store = Store().apply { commitFails = true }
        val result = TransferEngine(store).run(Source(payload), listOf(photo()), { false }, {})
        assertEquals(1, result.failed)
        assertFalse(store.destinations.single().file.exists())
    }

    @Test fun cancellationRollsBackBeforePropagating() = runTest {
        val store = Store()
        try {
            TransferEngine(store).run(Source(payload).apply { cancel = true }, listOf(photo()), { false }, {})
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            assertTrue(store.destinations.single().rolledBack)
            assertFalse(store.destinations.single().file.exists())
        }
    }

    @Test fun duplicateSelectionCopiesOnlyOnce() = runTest {
        val source = Source(payload)
        val result = TransferEngine(Store()).run(source, listOf(photo(), photo()), { false }, {})
        assertEquals(1, source.copies)
        assertEquals(1, result.totalFiles)
        assertFalse(result.stopped)
    }

    @Test fun corruptLocalCopyIsNotSilentlySkipped() = runTest {
        val store = Store()
        val source = Source(payload)
        val engine = TransferEngine(store)
        engine.run(source, listOf(photo()), { false }, {})
        store.destinations.single().file.writeBytes(byteArrayOf(1))
        assertEquals(1, engine.run(source, listOf(photo()), { false }, {}).saved)
        assertEquals(2, source.copies)
    }

    @Test fun cardFolderAndSourceMetadataArePartOfPersistentIdentity() {
        val p = photo()
        assertEquals(p.importKey, p.copy(handle = 99, sessionId = "new-session").importKey)
        assertNotEquals(p.importKey, p.copy(storageIdentity = "another-card").importKey)
        assertNotEquals(p.importKey, p.copy(folder = "DCIM/101NZ_8").importKey)
        assertNotEquals(p.importKey, p.copy(takenAt = 2001).importKey)
        assertNotEquals(p.key, p.copy(size = 30).key)
    }

    @Test fun sameNameOnTwoCardsGetsDistinctDestinationDirectories() {
        assertNotEquals(photo().relativeDirectory(), photo().copy(storageId = 2).relativeDirectory())
    }

    @Test fun unsafeNamesCannotEscapeOutputDirectory() {
        val path = photo(name = "../outside.NEF").copy(folder = "../../DCIM/../100:BAD").relativeDirectory()
        assertFalse(path.contains(".."))
        assertTrue(path.startsWith("Pictures/Z8Transfer/"))
        assertFalse(safeSegment("../outside.NEF").contains('/'))
        assertFalse(safeSegment("a\\b\u0000.NEF").contains('\\'))
    }

    @Test fun unsupportedAndZeroSizeObjectsAreNotDownloaded() = runTest {
        assertEquals(PhotoKind.JPEG, PhotoKind.fromName("DSC_0001.JPG"))
        assertEquals(PhotoKind.RAW, PhotoKind.fromName("DSC_0001.NEF"))
        assertEquals(PhotoKind.HEIF, PhotoKind.fromName("DSC_0001.HIF"))
        assertEquals(PhotoKind.MOV, PhotoKind.fromName("clip.MOV"))
        assertEquals(PhotoKind.NEV, PhotoKind.fromName("clip.NEV"))
        assertEquals(PhotoKind.OTHER, PhotoKind.fromName("SIDE.WAV"))
        val source = Source(payload)
        val result = TransferEngine(Store()).run(source, listOf(photo().copy(size = 0)), { false }, {})
        assertEquals(0, source.copies)
        assertEquals(1, result.failed)
    }

    @Test fun unknownSizeFileStreamsWithoutFalseSizeVerification() = runTest {
        val store = Store()
        val result = TransferEngine(store).run(Source(payload), listOf(photo().copy(size = UNKNOWN_FILE_SIZE, kind = PhotoKind.NEV)), { false }, {})
        assertEquals(1, result.saved)
        assertFalse(result.outcomes.single().sizeVerified)
        assertEquals(payload.size.toLong(), result.copiedBytes)
        assertArrayEquals(payload, store.destinations.single().file.readBytes())
    }

    @Test fun videoAndRawVideoUseAppropriateStorageRoots() {
        assertTrue(photo().copy(kind = PhotoKind.MOV).relativeDirectory().startsWith("Movies/"))
        assertTrue(photo().copy(kind = PhotoKind.NEV).relativeDirectory().startsWith("Download/"))
        assertTrue(photo().copy(kind = PhotoKind.OTHER).relativeDirectory().startsWith("Download/"))
    }

    @Test fun proxyPairingIsExplicitAndDoesNotCrossCardsOrDirectories() {
        val raw = photo(name = "CLIP.NEV").copy(kind = PhotoKind.NEV)
        val proxy = photo(2, "CLIP.MP4").copy(kind = PhotoKind.MP4)
        assertEquals(proxy, raw.proxyFrom(listOf(proxy)))
        assertNull(raw.proxyFrom(listOf(proxy.copy(storageId = 2))))
        assertNull(raw.proxyFrom(listOf(proxy.copy(folder = "DCIM/OTHER"))))
        assertNull(raw.proxyFrom(listOf(proxy.copy(name = "OTHER.MP4"))))
    }
}
