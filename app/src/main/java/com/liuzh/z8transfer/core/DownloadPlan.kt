package com.liuzh.z8transfer.core

import java.util.Locale

enum class RawJpegMode(val label: String) {
    SELECTED("仅所选文件"), PAIR("RAW＋JPEG 成对"), RAW_ONLY("只下载 RAW"), JPEG_ONLY("只下载 JPEG")
}

data class DownloadPlan(val files: List<CameraPhoto>, val unmatchedPhotos: Int = 0)

private fun CameraPhoto.captureKey() = listOf(sessionId, cameraIdentity, storageId.toString(),
    storageIdentity, folder, name.substringBeforeLast('.').lowercase(Locale.ROOT))

/** Resolve companions only inside the current session, camera, card and original directory. */
fun planDownload(selected: List<CameraPhoto>, all: List<CameraPhoto>, mode: RawJpegMode): DownloadPlan {
    if (selected.isEmpty()) return DownloadPlan(emptyList())
    val current = all.associateBy { it.key }
    val valid = selected.mapNotNull { current[it.key] }.distinctBy { it.key }
    if (mode == RawJpegMode.SELECTED) return DownloadPlan(valid)
    val pairs = all.filter { it.kind == PhotoKind.RAW || it.kind == PhotoKind.JPEG }.groupBy { it.captureKey() }
    var unmatched = 0
    val files = valid.flatMap { photo ->
        if (photo.kind != PhotoKind.RAW && photo.kind != PhotoKind.JPEG) listOf(photo) else {
            val candidates = pairs[photo.captureKey()].orEmpty()
            val matches = when (mode) {
                RawJpegMode.PAIR -> candidates
                RawJpegMode.RAW_ONLY -> candidates.filter { it.kind == PhotoKind.RAW }
                RawJpegMode.JPEG_ONLY -> candidates.filter { it.kind == PhotoKind.JPEG }
                RawJpegMode.SELECTED -> listOf(photo)
            }
            if (matches.isEmpty()) unmatched++
            matches
        }
    }.distinctBy { it.key }
    return DownloadPlan(files, unmatched)
}
