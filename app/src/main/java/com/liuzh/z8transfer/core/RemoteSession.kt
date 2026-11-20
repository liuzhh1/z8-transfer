package com.liuzh.z8transfer.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

sealed interface RemoteAction {
    data object Start : RemoteAction
    data object Pause : RemoteAction
    data object Focus : RemoteAction
    data class Capture(val autofocus: Boolean) : RemoteAction
}
data class RemoteStatus(val busy: Boolean = false, val live: Boolean = false,
    val message: String = "", val error: String? = null, val captureBlocked: Boolean = false)
class CaptureUncertainException(message: String, cause: Throwable) : IOException(message, cause)

/** One foreground session serializes frame polling, focus and shutter. No shutter is replayed. */
class RemoteSession(private val camera: RemoteCamera) {
    private val actions = Channel<RemoteAction>(1)
    private val pending = AtomicBoolean(false)
    fun submit(action: RemoteAction): Boolean {
        if (!pending.compareAndSet(false, true)) return false
        if (actions.trySend(action).isSuccess) return true
        pending.set(false); return false
    }

    suspend fun run(onState: (RemoteStatus) -> Unit, onFrame: suspend (ByteArray) -> Unit,
        onAccepted: () -> Unit, onCleanupFailure: (Exception) -> Unit) {
        var status = RemoteStatus()
        fun publish(value: RemoteStatus) { status = value; onState(value) }
        suspend fun execute(action: RemoteAction) {
            if (action is RemoteAction.Capture && status.captureBlocked) { pending.set(false); return }
            publish(status.copy(busy = true, error = null, message = when (action) {
                RemoteAction.Start -> "正在开启实时取景…"
                RemoteAction.Pause -> "正在暂停取景…"
                RemoteAction.Focus -> "正在自动对焦…"
                is RemoteAction.Capture -> "正在发送拍照指令…"
            }))
            try {
                when (action) {
                    RemoteAction.Start -> { camera.startLiveView(); publish(status.copy(live = true, message = "实时画面传输中")) }
                    RemoteAction.Pause -> { camera.stopLiveView(); publish(status.copy(live = false, message = "实时取景已暂停")) }
                    RemoteAction.Focus -> { camera.focus(); publish(status.copy(message = "对焦指令已完成")) }
                    is RemoteAction.Capture -> {
                        val receipt = camera.capture(action.autofocus) {
                            onAccepted()
                            publish(status.copy(message = "拍照指令已接受，等待相机处理…"))
                        }
                        publish(status.copy(message = receipt.message, captureBlocked = !receipt.ready))
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                publish(status.copy(message = if (error is CaptureUncertainException) "拍照结果未确认" else "操作未完成", error = error.message ?: "相机遥控失败",
                    captureBlocked = status.captureBlocked || error is CaptureUncertainException))
            } finally { pending.set(false); publish(status.copy(busy = false)) }
        }
        try {
            if (camera.capabilities.liveView) execute(RemoteAction.Start)
            else publish(status.copy(message = "当前连接支持遥控拍照，未开放实时取景"))
            var failures = 0
            var emptyFrames = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                val action = if (status.live) actions.tryReceive().getOrNull() else actions.receive()
                if (action != null) { execute(action); failures = 0; emptyFrames = 0 }
                if (status.live) {
                    val before = System.nanoTime()
                    try {
                        val bytes = camera.frame()
                        if (bytes != null) { onFrame(bytes); failures = 0; emptyFrames = 0 }
                        else if (++emptyFrames > 120) throw IOException("相机长时间未提供画面，请重新开启取景")
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) {
                        if (++failures >= 3) {
                            runCatching { camera.stopLiveView() }.onFailure { if (it is CancellationException) throw it }
                            publish(status.copy(live = false, error = error.message ?: "实时画面传输中断"))
                        }
                    }
                    delay((125L - (System.nanoTime() - before) / 1_000_000).coerceAtLeast(20))
                }
            }
        } finally {
            actions.close()
            withContext(NonCancellable) {
                try { camera.close() } catch (error: Exception) { onCleanupFailure(error) }
            }
        }
    }
}
