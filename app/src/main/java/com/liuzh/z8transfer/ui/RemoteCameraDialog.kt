package com.liuzh.z8transfer.ui

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.liuzh.z8transfer.core.ShootingSetting
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
internal fun RemoteCameraDialog(state: RemoteUiState, capabilities: RemoteCapabilities,
    onAction: (RemoteAction) -> Unit, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        RemoteCameraContent(state, capabilities, onAction, onClose)
    }
}

@Composable
internal fun RemoteCameraContent(state: RemoteUiState, capabilities: RemoteCapabilities,
    onAction: (RemoteAction) -> Unit, onClose: () -> Unit,
    onBrowsePhotos: (() -> Unit)? = null, onStop: () -> Unit = onClose) {
    var autofocus by rememberSaveable { mutableStateOf(capabilities.autofocus) }
    var showParameters by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = showParameters) { showParameters = false }
    val owner = LocalLifecycleOwner.current
    val close by rememberUpdatedState(onStop)
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) close() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); close() }
    }
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
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val parameterHeight = (maxHeight * .4f).coerceAtMost(350.dp)
        Column(Modifier.fillMaxSize().background(Color(0xFF111A17)).safeDrawingPadding().testTag("remote-camera")) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("相机遥控", color = Color.White, fontSize = 19.sp, modifier = Modifier.weight(1f))
                Text(String.format(Locale.CHINA, "%.1f fps · %d 帧", if (state.status.live && now - state.receivedAt < 2500) state.fps else 0.0,
                    state.frameNumber), color = Color.LightGray, fontSize = 11.sp)
                TextButton(onClick = onClose, enabled = !state.closing) { Text(if (onBrowsePhotos == null) "返回照片" else "返回首页", color = Color.White) }
                if (onBrowsePhotos != null) TextButton(onClick = onBrowsePhotos, enabled = !state.closing) {
                    Text("下载照片", color = Color.White)
                }
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
            if (showParameters) ShootingParametersPanel(state, onAction,
                Modifier.heightIn(max = parameterHeight)) { showParameters = false }
            Column(Modifier.fillMaxWidth().heightIn(max = 300.dp).padding(14.dp)) {
                if (!showParameters) Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    Text(state.status.message.ifBlank { "正在建立遥控…" }, color = Color.White, fontSize = 12.sp)
                    state.status.error?.let { Text(it, color = Color(0xFFFFB8A8), fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)) }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (state.status.parametersLoaded) "已读取：" + state.status.parameters.joinToString(" · ") { it.currentLabel }
                            else "按需读取 · 快门 / 光圈 / ISO / EV / 白平衡", color = Color.LightGray, fontSize = 10.sp,
                            modifier = Modifier.weight(1f).padding(end = 8.dp))
                        OutlinedButton(onClick = { showParameters = true; onAction(RemoteAction.ReadParameters) },
                            enabled = capabilities.parameters && !state.busy, modifier = Modifier.testTag("remote-parameters")) {
                            Text("拍摄参数", color = Color.White, fontSize = 12.sp)
                        }
                    }
                    if (!capabilities.parameters) Text("当前连接未开放拍摄参数调节", color = Color.LightGray, fontSize = 10.sp)
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
                }
                Button(onClick = { onAction(RemoteAction.Capture(autofocus && capabilities.autofocus)) },
                    enabled = capabilities.capture && !state.busy && !state.status.captureBlocked,
                    modifier = Modifier.fillMaxWidth().testTag("remote-shutter")) {
                    Text(if (state.busy) "相机处理中…" else "拍照", fontSize = 17.sp)
                }
                Text("请将相机切到照片模式。原片保存到相机存储卡，进入「下载照片」页后再读取列表，可选择下载。画面是实时取景，未录制到手机。",
                    color = Color.LightGray, fontSize = 10.sp, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

@Composable
private fun ShootingParametersPanel(state: RemoteUiState, onAction: (RemoteAction) -> Unit,
    modifier: Modifier = Modifier, onClose: () -> Unit) {
    var selected by remember { mutableStateOf<ShootingSetting?>(null) }
    val item = state.status.parameters.firstOrNull { it.setting == selected }
    Surface(modifier.fillMaxWidth().testTag("shooting-parameters-panel"), color = Color(0xFF1B2924), contentColor = Color.White) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(selected?.title ?: "拍摄参数", fontSize = 19.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { selected = null; onAction(RemoteAction.ReadParameters) }, enabled = !state.busy) { Text("刷新", color = Color.White) }
                TextButton(onClick = { if (selected != null) selected = null else onClose() }) {
                    Text(if (selected != null) "返回参数" else "返回取景", color = Color.White)
                }
            }
            Text("${state.status.message}。选项来自相机；机身调节后点刷新同步。", fontSize = 11.sp, color = Color.LightGray)
            state.status.error?.let { Text(it, color = Color(0xFFFFB8A8), fontSize = 12.sp) }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 10.dp))
            if (!state.status.parametersLoaded && !state.busy) Text("读取未完成，请点刷新重试", modifier = Modifier.padding(12.dp))
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).testTag("parameter-options")) {
                if (selected == null) items(state.status.parameters, key = { it.setting }) { parameter ->
                    Column(Modifier.fillMaxWidth().clickable(enabled = parameter.editable && !state.busy) {
                        selected = parameter.setting
                    }.testTag("parameter-${parameter.setting.name}").padding(vertical = 10.dp)) {
                        Row(Modifier.fillMaxWidth()) {
                            Text(parameter.setting.title, modifier = Modifier.weight(1f))
                            Text(parameter.currentLabel + if (parameter.editable) "  ›" else "", color = Color.White)
                        }
                        parameter.explanation?.let { Text(it, fontSize = 11.sp, color = Color.LightGray) }
                    }
                    HorizontalDivider(color = Color.White.copy(alpha = .15f))
                } else if (item != null) items(item.options, key = { it }) { value ->
                    Row(Modifier.fillMaxWidth().clickable(enabled = !state.busy) {
                        selected = null
                        onAction(RemoteAction.SetParameter(item.setting, value))
                    }.testTag("parameter-value-$value").padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = item.property?.current == value, onClick = null,
                            colors = RadioButtonDefaults.colors(unselectedColor = Color.LightGray))
                        Text(item.label(value), modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
            Text("受 P/A/S/M 模式、镜头和参数锁定限制。B 门 / Time 请使用机身；修改结果以相机回读为准。",
                fontSize = 10.sp, color = Color.LightGray, modifier = Modifier.padding(top = 8.dp))
        }
    }
}
