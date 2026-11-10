package com.liuzh.z8transfer.core

import kotlinx.coroutines.CancellationException

class DeletionPlan(val sessionId: String, files: List<CameraPhoto>) {
    val files: List<CameraPhoto> = java.util.Collections.unmodifiableList(files.toList())
    init {
        require(files.isNotEmpty()) { "未选择文件" }
        require(files.all { it.sessionId == sessionId && it.handle != 0 && it.handle != -1 }) { "删除请求包含旧连接或无效句柄" }
        require(files.map { it.key }.distinct().size == files.size) { "删除请求包含重复文件" }
    }
}

data class DeletionOutcome(val file: CameraPhoto, val deleted: Boolean, val error: String? = null)
data class DeletionProgress(
    val running: Boolean = false, val total: Int = 0, val currentName: String = "",
    val outcomes: List<DeletionOutcome> = emptyList(), val stopped: Boolean = false,
) {
    val deleted: Int get() = outcomes.count { it.deleted }
    val failed: Int get() = outcomes.count { !it.deleted }
}

class DeletionEngine {
    suspend fun run(source: CameraTransport, plan: DeletionPlan, shouldStop: () -> Boolean,
        update: (DeletionProgress) -> Unit): DeletionProgress {
        check(source.supportsDeletion) { "相机未声明支持 USB 删除，请在相机上删除" }
        check(source.sessionId == plan.sessionId) { "相机已重新连接，请重新选择文件并确认删除" }
        val results = mutableListOf<DeletionOutcome>()
        for (file in plan.files) {
            if (shouldStop() || !source.attached) break
            update(DeletionProgress(true, plan.files.size, file.name, results.toList()))
            try {
                // Transport must revalidate metadata and protection immediately before delete.
                source.deleteOriginal(file)
                results += DeletionOutcome(file, true)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { results += DeletionOutcome(file, false, error.message ?: "删除结果未确认，请刷新核实") }
        }
        return DeletionProgress(false, plan.files.size, outcomes = results.toList(), stopped = results.size < plan.files.size).also(update)
    }
}

fun CameraPhoto.sameObject(name: String, storageId: Int, reportedSize: Long, createdAt: Long, modifiedAt: Long,
    parent: Int, isDirectory: Boolean): Boolean = !isDirectory && handle != 0 && handle != -1 &&
    this.name == name && this.storageId == storageId && this.reportedSize == reportedSize &&
    takenAt == createdAt && this.modifiedAt == modifiedAt && (parentHandle == -1 || parentHandle == parent)

/** Proxy pairing never crosses cards or directories. MOV is a container, not a RAW codec claim. */
fun CameraPhoto.proxyFrom(files: List<CameraPhoto>): CameraPhoto? =
    if (kind != PhotoKind.NEV && kind != PhotoKind.MOV) null else files.firstOrNull {
        it.sessionId == sessionId && it.storageId == storageId && it.folder == folder && it.kind == PhotoKind.MP4 &&
        it.name.substringBeforeLast('.').equals(name.substringBeforeLast('.'), ignoreCase = true)
    }
