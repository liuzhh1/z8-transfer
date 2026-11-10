package com.liuzh.z8transfer.transfer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.liuzh.z8transfer.MainActivity
import com.liuzh.z8transfer.R
import com.liuzh.z8transfer.Z8Application
import com.liuzh.z8transfer.core.TransferProgress
import com.liuzh.z8transfer.AppState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class TransferService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var deleting = false
    private var activeStartId = 0
    private val controller get() = (application as Z8Application).controller
    private val notifications get() = getSystemService(NotificationManager::class.java)

    override fun onCreate() {
        super.onCreate()
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "原片导入", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        activeStartId = startId
        if (intent?.action == ACTION_STOP) {
            controller.requestStop()
            if (!controller.state.value.progress.running && !controller.state.value.deletion.running) stopSelfResult(activeStartId)
            return START_NOT_STICKY
        }
        deleting = intent?.action == ACTION_DELETE
        startForeground(ID, notification(controller.state.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Z8Transfer:Import").apply {
                acquire(60 * 60 * 1000L)
            }
        }
        if (deleting) controller.startQueuedDeletion() else controller.startQueuedTransfer()
        if (observer == null) observer = scope.launch {
            var lastUpdate = 0L
            var lastName = ""
            controller.state.collect { state ->
                val running = state.progress.running || state.deletion.running
                val name = if (deleting) state.deletion.currentName else state.progress.currentName
                val now = SystemClock.elapsedRealtime()
                if (!running || name != lastName || now - lastUpdate >= 1000) {
                    notifications.notify(ID, notification(state))
                    lastName = name; lastUpdate = now
                }
                if (!running) {
                    stopForeground(STOP_FOREGROUND_DETACH)
                    stopSelfResult(activeStartId)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun notification(state: AppState): Notification {
        val p = state.progress
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_transfer)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(p.running || state.deletion.running)
        if (deleting) {
            val d = state.deletion
            builder.setContentTitle(if (d.running) "正在删除相机文件" else "相机删除结果")
                .setContentText(if (d.running) "${d.outcomes.size}/${d.total} · ${d.currentName}" else "已删除 ${d.deleted} · 未成功 ${d.failed}")
            if (d.running) builder.addAction(0, "停止后续", PendingIntent.getService(this, 1,
                Intent(this, TransferService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE))
            else builder.setAutoCancel(true)
            return builder.build()
        }
        if (p.running) {
            val stop = PendingIntent.getService(this, 1, Intent(this, TransferService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
            builder.setContentTitle(if (p.stopRequested) "完成当前照片后停止" else "正在导入原片")
                .setContentText("${p.finishedFiles}/${p.totalFiles} · ${p.currentName}")
                .setProgress(p.totalFiles.coerceAtLeast(1), p.finishedFiles, false)
            if (!p.stopRequested) builder.addAction(0, "停止后续", stop)
        } else {
            builder.setContentTitle(if (p.stopped) "导入已停止" else "导入完成")
                .setContentText("成功 ${p.saved} · 跳过 ${p.skipped} · 失败 ${p.failed}")
                .setAutoCancel(true)
        }
        return builder.build()
    }

    override fun onDestroy() {
        if (controller.state.value.progress.running || controller.state.value.deletion.running) controller.requestStop()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "com.liuzh.z8transfer.STOP_QUEUE"
        const val ACTION_DELETE = "com.liuzh.z8transfer.DELETE_CONFIRMED"
        private const val CHANNEL = "z8_imports"
        private const val ID = 8
    }
}
