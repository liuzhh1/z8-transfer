package com.liuzh.z8transfer.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.FileDescriptor

/** USB and Wi-Fi implementations share this transport contract. */
interface CameraTransport {
    val sessionId: String
    val displayName: String
    val diagnostics: String
    val attached: Boolean
    val usbDeviceId: Int? get() = null
    val supportsDeletion: Boolean get() = false
    val connectionLabel: String get() = "USB"
    val deletionUnavailableReason: String get() = "当前相机连接未声明支持删除"
    suspend fun scan(onBatch: (List<CameraPhoto>) -> Unit, onExamined: (Int) -> Unit)
    suspend fun thumbnail(photo: CameraPhoto): ByteArray?
    suspend fun copyOriginal(photo: CameraPhoto, destination: OriginalDestination, progress: (Long) -> Unit)
    suspend fun deleteOriginal(photo: CameraPhoto) { error("当前连接不支持删除相机文件") }
    fun markDetached()
    suspend fun close()
}

interface OriginalDestination : Closeable {
    val fileDescriptor: FileDescriptor
    fun size(): Long
    /** Publish a complete file. A subsequent failure must still allow rollback. */
    suspend fun commit(): String
    suspend fun rollback()
}

interface ImportStore {
    /** Only return an existing URI after checking the local bytes still exist. */
    suspend fun existing(photo: CameraPhoto): String?
    suspend fun begin(photo: CameraPhoto): OriginalDestination
}

class TransferEngine(private val store: ImportStore) {
    suspend fun run(
        source: CameraTransport,
        photos: List<CameraPhoto>,
        shouldStop: () -> Boolean,
        onProgress: (TransferProgress) -> Unit,
    ): TransferProgress {
        val unique = photos.distinctBy { it.key }
        val batchStarted = System.nanoTime()
        val outcomes = mutableListOf<TransferOutcome>()
        var state = TransferProgress(running = true, totalFiles = unique.size)
        fun emit() = onProgress(state.copy(outcomes = outcomes.toList(), stopRequested = shouldStop()))
        emit()
        for (photo in unique) {
            if (shouldStop() || !source.attached) break
            state = state.copy(
                finishedFiles = outcomes.size, currentName = photo.name,
                currentBytes = 0, currentSize = photo.size, bytesPerSecond = 0.0,
            )
            emit()
            var destination: OriginalDestination? = null
            try {
                check(photo.sessionId == source.sessionId) { "相机已重新连接，请刷新照片列表后重试" }
                check(photo.size > 0 || photo.size == UNKNOWN_FILE_SIZE) { "相机提供的文件大小无效" }
                val existing = store.existing(photo)
                if (existing != null) {
                    outcomes += TransferOutcome(photo, OutcomeStatus.SKIPPED, existing)
                } else {
                    destination = store.begin(photo)
                    val started = System.nanoTime()
                    var previousBytes = 0L
                    source.copyOriginal(photo, destination) { bytes ->
                        // Some providers preallocate their file. The progress is indicative;
                        // completion is exclusively determined by the transport result + size.
                        val count = if (photo.size > 0) bytes.coerceIn(previousBytes, photo.size) else bytes.coerceAtLeast(previousBytes)
                        previousBytes = count
                        val elapsed = ((System.nanoTime() - started) / 1e9).coerceAtLeast(0.001)
                        state = state.copy(currentBytes = count, bytesPerSecond = count / elapsed)
                        emit()
                    }
                    val actual = destination.size()
                    check(if (photo.size > 0) actual == photo.size else actual > 0) { "文件大小校验失败：预期 ${photo.size} 字节，实际 $actual 字节" }
                    val uri = destination.commit()
                    destination.close()
                    destination = null
                    outcomes += TransferOutcome(photo, OutcomeStatus.SAVED, uri, sizeVerified = photo.size > 0, actualBytes = actual)
                    state = state.copy(copiedBytes = state.copiedBytes + actual)
                }
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) {
                    try { destination?.rollback() } finally { destination?.close() }
                }
                throw cancelled
            } catch (error: Exception) {
                val cleanupError = runCatching { destination?.rollback() }.exceptionOrNull()
                runCatching { destination?.close() }
                val message = error.message ?: error.javaClass.simpleName
                outcomes += TransferOutcome(photo, OutcomeStatus.FAILED,
                    error = message + if (cleanupError != null) "；未完成副本将在下次启动时清理" else "")
            }
            state = state.copy(finishedFiles = outcomes.size)
            emit()
        }
        state = state.copy(
            running = false, finishedFiles = outcomes.size, outcomes = outcomes.toList(),
            stopped = outcomes.size < unique.size,
            stopRequested = shouldStop(),
            elapsedMillis = (System.nanoTime() - batchStarted) / 1_000_000,
        )
        onProgress(state)
        return state
    }
}
