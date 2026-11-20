package com.liuzh.z8transfer.core

import java.util.Locale

enum class PhotoSort(val label: String) {
    NEWEST("拍摄时间：从新到旧"), OLDEST("拍摄时间：从旧到新"),
    NAME_ASC("文件名：升序"), NAME_DESC("文件名：降序"),
    SIZE_DESC("文件大小：从大到小"), SIZE_ASC("文件大小：从小到大");

    fun apply(files: List<CameraPhoto>): List<CameraPhoto> {
        val byName = compareBy<CameraPhoto> { it.name.lowercase(Locale.ROOT) }
        val primary = when (this) {
            NEWEST -> compareByDescending<CameraPhoto> { it.sortTime }
            OLDEST -> compareBy { it.sortTime }
            NAME_ASC -> byName
            NAME_DESC -> byName.reversed()
            SIZE_DESC -> compareByDescending { it.size }
            SIZE_ASC -> compareBy { it.size }
        }
        return files.sortedWith(primary.then(byName).thenBy { it.storageId }.thenBy { it.folder }.thenBy { it.handle })
    }
}

/** The range is recalculated from the selection at touch-down, so reversing a drag restores it. */
fun rangeSelection(files: List<CameraPhoto>, original: Set<String>, anchor: Int, end: Int): Set<String> {
    if (anchor !in files.indices || end !in files.indices) return original
    val range = (minOf(anchor, end)..maxOf(anchor, end)).map { files[it].key }.toSet()
    return if (files[anchor].key in original) original - range else original + range
}

/** Match only this session, card (including volume identity), directory and filename stem. */
fun companionJpegs(selected: List<CameraPhoto>, all: List<CameraPhoto>): List<CameraPhoto> {
    fun CameraPhoto.pairKey() = listOf(sessionId, cameraIdentity, storageId.toString(), storageIdentity,
        folder, name.substringBeforeLast('.').lowercase(Locale.ROOT))
    val rawKeys = selected.filter { it.kind == PhotoKind.RAW }.map { it.pairKey() }.toSet()
    val selectedKeys = selected.map { it.key }.toSet()
    return all.filter { it.kind == PhotoKind.JPEG && it.key !in selectedKeys && it.pairKey() in rawKeys }.distinctBy { it.key }
}
