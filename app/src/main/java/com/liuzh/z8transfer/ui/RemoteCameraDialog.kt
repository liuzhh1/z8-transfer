package com.liuzh.z8transfer.ui

import android.os.SystemClock
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.liuzh.z8transfer.RemoteUiState
import com.liuzh.z8transfer.core.RemoteAction
import com.liuzh.z8transfer.core.RemoteCapabilities
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
internal fun RemoteCameraDialog(state: RemoteUiState, capabilities: RemoteCapabilities,
    onAction: (RemoteAction) -> Unit, onClose: () -> Unit) {
    var autofocus by rememberSaveable { mutableStateOf(capabilities.autofocus) }
    val owner = LocalLifecycleOwner.current
    val close by rememberUpdatedState(onClose)
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) close() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); close() }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val view = LocalView.current
        DisposableEffect(view) {
            val previous = view.keepScreenOn
            view.keepScreenOn = true
            onDispose { view.keepScreenOn = previous }
        }
        var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
        LaunchedEffect(state.status.live, state.receivedAt) {
            while (state.status.live) { now = SystemClock.elapsedRealtime(); delay(500) }
        }
        Column(Modifier.fillMaxSize().background(Color(0xFF111A17)).safeDrawingPadding().testTag("remote-camera")) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("相机遥控", color = Color.White, fontSize = 19.sp, modifier = Modifier.weight(1f))
                Text(String.format(Locale.CHINA, "%.1f fps · %d 帧", if (state.status.live && now - state.receivedAt < 2500) state.fps else 0.0,
                    state.frameNumber), color = Color.LightGray, fontSize = 11.sp)
                TextButton(onClick = onClose, enabled = !state.closing) { Text("返回照片", color = Color.White) }
            }
            Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black).testTag("live-view-frame"), contentAlignment = Alignment.Center) {
                state.frame?.let { Image(it.asImageBitmap(), "相机实时取景", Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                    ?: Text(if (capabilities.liveView) "等待相机实时画面…" else "当前连接不提供实时取景", color = Color.LightGray)
                if (state.frame != null && (!state.status.live || now - state.receivedAt >= 2500)) Text(
                    if (state.status.live) "画面暂未更新" else "取景已暂停 · 保留最后一帧",
                    color = Color.White, fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.TopCenter).background(Color.Black.copy(alpha = .7f)).padding(10.dp))
                if (state.closing) Text("正在结束相机控制…", color = Color.White,
                    modifier = Modifier.background(Color.Black.copy(alpha = .8f)).padding(18.dp))
            }
            Column(Modifier.fillMaxWidth().heightIn(max = 280.dp).verticalScroll(rememberScrollState()).padding(14.dp)) {
                Text(state.status.message.ifBlank { "正在建立遥控…" }, color = Color.White, fontSize = 12.sp)
                state.status.error?.let { Text(it, color = Color(0xFFFFB8A8), fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)) }
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onAction(if (state.status.live) RemoteAction.Pause else RemoteAction.Start) },
                        enabled = capabilities.liveView && !state.busy, modifier = Modifier.weight(1f)) {
                        Text(if (state.status.live) "暂停取景" else "开启取景", color = Color.White, fontSize = 12.sp)
                    }
                    OutlinedButton(onClick = { onAction(RemoteAction.Focus) }, enabled = capabilities.autofocus && !state.busy,
                        modifier = Modifier.weight(1f)) { Text("自动对焦", color = Color.White, fontSize = 12.sp) }
                }
                if (capabilities.autofocus) Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(autofocus, { autofocus = it }, enabled = !state.busy,
                        colors = CheckboxDefaults.colors(uncheckedColor = Color.White),
                        modifier = Modifier.semantics { contentDescription = "拍照前自动对焦" })
                    Text("拍照前自动对焦", color = Color.White, fontSize = 12.sp)
                }
                Button(onClick = { onAction(RemoteAction.Capture(autofocus && capabilities.autofocus)) },
                    enabled = capabilities.capture && !state.busy && !state.status.captureBlocked,
                    modifier = Modifier.fillMaxWidth().testTag("remote-shutter")) {
                    Text(if (state.busy) "相机处理中…" else "拍照", fontSize = 17.sp)
                }
                Text("请将相机切到照片模式。原片保存到相机存储卡，退出遥控后更新列表，可选择下载。画面是实时取景，未录制到手机。",
                    color = Color.LightGray, fontSize = 10.sp, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}
