package com.liuzh.z8transfer.core

import java.security.MessageDigest
import java.util.Locale

const val UNKNOWN_FILE_SIZE = -1L

enum class PhotoKind(val label: String, val mime: String, val isVideo: Boolean = false) {
    JPEG("JPEG", "image/jpeg"), RAW("RAW", "image/x-nikon-nef"), HEIF("HEIF", "image/heif"),
    NEV("N-RAW", "application/octet-stream", true), MOV("MOV", "video/quicktime", true),
    MP4("MP4", "video/mp4", true), VIDEO("视频", "video/mp2t", true), OTHER("其他", "application/octet-stream");

    val isPhoto: Boolean get() = this == JPEG || this == RAW || this == HEIF
    val canPlayOnAndroid: Boolean get() = this == MP4 || this == MOV

    companion object {
        fun fromName(name: String): PhotoKind? = when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "jpg", "jpeg" -> JPEG
            "nef" -> RAW
            "heif", "hif", "heic" -> HEIF
            "nev" -> NEV
            "mov" -> MOV
            "mp4", "m4v" -> MP4
            "mts", "m2ts", "avi", "mxf", "r3d" -> VIDEO
            else -> OTHER
        }
    }
}

data class CameraPhoto(
    val sessionId: String,
    val cameraIdentity: String,
    val storageId: Int,
    val storageIdentity: String,
    val storageLabel: String,
    val handle: Int,
    val folder: String,
    val name: String,
    val size: Long,
    val takenAt: Long,
    val modifiedAt: Long,
    val kind: PhotoKind,
    val thumbnailBytes: Long = 0,
    val reportedSize: Long = size,
    val parentHandle: Int = -1,
) {
    // PTP handles are session-local and must never be used as persistent identity.
    val key: String = "$sessionId:$storageId:$handle:" + stableHash(
        listOf(folder, name, size, takenAt, modifiedAt).joinToString("\u0000")
    ).take(12)
    val importKey: String = stableHash(listOf(
        cameraIdentity, storageIdentity, storageId.toString(), folder, name,
        size.toString(), takenAt.toString(), modifiedAt.toString(),
    ).joinToString("\u0000"))
    val sortTime: Long get() = takenAt.takeIf { it > 0 } ?: modifiedAt
}

fun stableHash(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

fun safeSegment(input: String, fallback: String = "unnamed"): String {
    val cleaned = input.replace(Regex("[\\\\/\\p{Cntrl}:*?\"<>|]"), "_")
        .trim().trim('.').take(100)
    return cleaned.ifBlank { fallback }
}

fun CameraPhoto.relativeDirectory(): String {
    val card = "${safeSegment(storageLabel, "card")}-${storageId.toUInt().toString(16)}"
    val folders = folder.split('/').filter { it.isNotBlank() && it != "." && it != ".." }
        .map { safeSegment(it) }.take(12)
    val base = when { kind.isPhoto -> "Pictures"; kind == PhotoKind.NEV || kind == PhotoKind.OTHER -> "Download"; else -> "Movies" }
    return (listOf(base, "Z8Transfer", cameraIdentity.take(12), card) + folders)
        .joinToString("/", postfix = "/")
}

enum class OutcomeStatus { SAVED, SKIPPED, FAILED }

data class TransferOutcome(
    val photo: CameraPhoto,
    val status: OutcomeStatus,
    val uri: String? = null,
    val error: String? = null,
    val sizeVerified: Boolean = true,
    val actualBytes: Long = 0,
)

data class TransferProgress(
    val running: Boolean = false,
    val totalFiles: Int = 0,
    val finishedFiles: Int = 0,
    val currentName: String = "",
    val currentBytes: Long = 0,
    val currentSize: Long = 0,
    val bytesPerSecond: Double = 0.0,
    val outcomes: List<TransferOutcome> = emptyList(),
    val stopRequested: Boolean = false,
    val stopped: Boolean = false,
    val copiedBytes: Long = 0,
    val elapsedMillis: Long = 0,
) {
    val saved: Int get() = outcomes.count { it.status == OutcomeStatus.SAVED }
    val skipped: Int get() = outcomes.count { it.status == OutcomeStatus.SKIPPED }
    val failed: Int get() = outcomes.count { it.status == OutcomeStatus.FAILED }
}
