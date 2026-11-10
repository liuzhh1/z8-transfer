package com.liuzh.z8transfer.storage

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.provider.MediaStore
import androidx.core.net.toUri
import com.liuzh.z8transfer.core.CameraPhoto
import com.liuzh.z8transfer.core.ImportStore
import com.liuzh.z8transfer.core.OriginalDestination
import com.liuzh.z8transfer.core.PhotoKind
import com.liuzh.z8transfer.core.relativeDirectory
import com.liuzh.z8transfer.core.safeSegment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileDescriptor
import java.io.IOException

class MediaImportStore(context: Context) : ImportStore {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val ledger = ImportLedger(appContext)
    private val collections: List<Uri> = listOf(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY))
    private fun collection(photo: CameraPhoto): Uri = when {
        photo.kind.isPhoto -> collections[0]
        photo.kind == PhotoKind.NEV || photo.kind == PhotoKind.OTHER -> collections[2]
        else -> collections[1]
    }

    /** Only our own incomplete imports are removed after a process death. */
    suspend fun cleanupIncomplete() = withContext(Dispatchers.IO) {
        val selection = "${MediaStore.MediaColumns.IS_PENDING} = 1 AND " +
            "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ? AND " +
            "(${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? OR ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? OR ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?)"
        val stale = mutableListOf<Uri>()
        for (collection in collections) resolver.query(collection, arrayOf(MediaStore.MediaColumns._ID), selection,
            arrayOf(appContext.packageName, "Pictures/Z8Transfer/%", "Movies/Z8Transfer/%", "Download/Z8Transfer/%"), null)?.use { cursor ->
            while (cursor.moveToNext()) stale += ContentUris.withAppendedId(collection, cursor.getLong(0))
        }
        for (uri in stale) resolver.delete(uri, null, null)
    }

    override suspend fun existing(photo: CameraPhoto): String? = withContext(Dispatchers.IO) {
        // Missing dates are insufficient evidence for automatic cross-session deduplication.
        if (photo.size <= 0 || (photo.takenAt <= 0 && photo.modifiedAt <= 0)) return@withContext null
        val uriString = ledger.find(photo.importKey) ?: return@withContext null
        val uri = uriString.toUri()
        val actual = runCatching {
            resolver.openFileDescriptor(uri, "r")?.use { it.statSize }
        }.getOrNull()
        if (actual == photo.size) uriString else {
            ledger.remove(photo.importKey)
            null
        }
    }

    override suspend fun begin(photo: CameraPhoto): OriginalDestination = withContext(Dispatchers.IO) {
        val available = StatFs(appContext.getExternalFilesDir(null)?.absolutePath ?: appContext.filesDir.absolutePath).availableBytes
        check(photo.size < Long.MAX_VALUE - 32L * 1024 * 1024 && available > photo.size.coerceAtLeast(0) + 32L * 1024 * 1024) { "手机存储空间不足，请至少额外预留 32 MiB" }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, safeSegment(photo.name))
            put(MediaStore.MediaColumns.MIME_TYPE, if (photo.kind == PhotoKind.HEIF &&
                photo.name.endsWith(".heic", true)) "image/heic" else photo.kind.mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, photo.relativeDirectory())
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            if (photo.sortTime > 0 && photo.kind.isPhoto) put(MediaStore.Images.ImageColumns.DATE_TAKEN, photo.sortTime)
        }
        val uri = resolver.insert(collection(photo), values) ?: throw IOException("无法在手机存储创建文件")
        try {
            val descriptor = resolver.openFileDescriptor(uri, "rw") ?: throw IOException("无法打开手机存储文件")
            MediaDestination(uri, descriptor, photo)
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    private inner class MediaDestination(
        private val uri: Uri,
        private val descriptor: ParcelFileDescriptor,
        private val photo: CameraPhoto,
    ) : OriginalDestination {
        private var descriptorClosed = false
        override val fileDescriptor: FileDescriptor get() = descriptor.fileDescriptor
        override fun size(): Long = descriptor.statSize
        override suspend fun commit(): String {
            descriptor.fileDescriptor.sync()
            val count = resolver.update(uri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null)
            check(count == 1) { "无法完成相册文件发布" }
            ledger.put(photo.importKey, uri.toString())
            return uri.toString()
        }
        override suspend fun rollback() {
            close()
            resolver.delete(uri, null, null)
        }
        override fun close() {
            if (!descriptorClosed) { descriptorClosed = true; descriptor.close() }
        }
    }

    private class ImportLedger(context: Context) : SQLiteOpenHelper(context, "imports.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE imports (source_key TEXT PRIMARY KEY, uri TEXT NOT NULL, saved_at INTEGER NOT NULL)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        fun find(key: String): String? = readableDatabase.query("imports", arrayOf("uri"),
            "source_key = ?", arrayOf(key), null, null, null).use { if (it.moveToFirst()) it.getString(0) else null }
        fun remove(key: String) { writableDatabase.delete("imports", "source_key = ?", arrayOf(key)) }
        fun put(key: String, uri: String) {
            writableDatabase.insertWithOnConflict("imports", null, ContentValues().apply {
                put("source_key", key); put("uri", uri); put("saved_at", System.currentTimeMillis())
            }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) { "无法保存下载记录" } }
        }
    }
}
