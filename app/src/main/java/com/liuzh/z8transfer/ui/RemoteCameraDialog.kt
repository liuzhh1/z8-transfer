package com.liuzh.z8transfer.ui

import android.os.SystemClock
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.liuzh.z8transfer.RemoteUiState
import com.liuzh.z8transfer.core.RemoteAction
import com.liuzh.z8transfer.core.RemoteCapabilities
import com.liuzh.z8transfer.core.ShootingSetting
import com.liuzh.z8transfer.core.ShootingGroup
import com.liuzh.z8transfer.core.ShootingParameter
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
    var histogram by rememberSaveable { mutableStateOf(false) }
    var zebra by rememberSaveable { mutableStateOf(false) }
    var peaking by rememberSaveable { mutableStateOf(false) }
    var grid by rememberSaveable { mutableStateOf(false) }
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = fullscreen) { fullscreen = false }
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
    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF111A17)).safeDrawingPadding()) {
        val compact = maxHeight < 500.dp
        val parameterHeight = (maxHeight * (if (compact) .3f else .4f)).coerceAtMost(350.dp)
        val controlsHeight = if (compact) 120.dp else 300.dp
        Column(Modifier.fillMaxSize().background(Color(0xFF111A17)).testTag("remote-camera")) {
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
                LiveViewMonitor(state.frame, histogram, zebra, peaking, grid, Modifier.fillMaxSize())
                if (state.frame == null) Text(if (capabilities.liveView) "等待相机实时画面…" else "当前连接不提供实时取景", color = Color.LightGray)
                TextButton(onClick = { fullscreen = !fullscreen; showParameters = false },
                    modifier = Modifier.align(Alignment.TopEnd).background(Color.Black.copy(alpha = .65f)).testTag("monitor-fullscreen")) {
                    Text(if (fullscreen) "恢复控件" else "全屏监看", color = Color.White, fontSize = 11.sp)
                }
                if (state.frame != null && (!state.status.live || now - state.receivedAt >= 2500)) Text(
                    if (state.status.live) "画面暂未更新" else "取景已暂停 · 保留最后一帧",
                    color = Color.White, fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.TopCenter).background(Color.Black.copy(alpha = .7f)).padding(10.dp))
                if (state.closing) Text("正在结束相机控制…", color = Color.White,
                    modifier = Modifier.background(Color.Black.copy(alpha = .8f)).padding(18.dp))
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MonitorChip("直方图", histogram, "monitor-toggle-histogram") { histogram = !histogram }
                MonitorChip("斑马纹", zebra, "monitor-toggle-zebra") { zebra = !zebra }
                MonitorChip("峰值对焦", peaking, "monitor-toggle-peaking") { peaking = !peaking }
                MonitorChip("构图网格", grid, "monitor-toggle-grid") { grid = !grid }
            }
            if (showParameters) ShootingParametersPanel(state, onAction,
                Modifier.heightIn(max = parameterHeight)) { showParameters = false }
            Column(Modifier.fillMaxWidth().heightIn(max = controlsHeight).padding(14.dp)) {
                if (!showParameters && !fullscreen) Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
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
                    enabled = capabilities.capture && !state.busy && !state.status.captureBlocked &&
                        !(showParameters && state.status.parameterGroup == ShootingGroup.VIDEO),
                    modifier = Modifier.fillMaxWidth().testTag("remote-shutter")) {
                    Text(if (state.busy) "相机处理中…" else "拍照", fontSize = 17.sp)
                }
                if (!fullscreen && !compact) Text("监看辅助分析预览画面，不代表 RAW 曝光。请将相机切到照片模式。原片保存到相机存储卡，进入「下载照片」页后再读取列表，可选择下载。画面是实时取景，未录制到手机。",
                    color = Color.LightGray, fontSize = 10.sp, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

@Composable
private fun MonitorChip(label: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label, fontSize = 11.sp) },
        colors = FilterChipDefaults.filterChipColors(containerColor = Color(0xFF1B2924), labelColor = Color.White,
            selectedContainerColor = Color(0xFF166554), selectedLabelColor = Color.White), modifier = Modifier.testTag(tag))
}

@Composable
private fun ShootingParametersPanel(state: RemoteUiState, onAction: (RemoteAction) -> Unit,
    modifier: Modifier = Modifier, onClose: () -> Unit) {
    var selected by remember { mutableStateOf<ShootingSetting?>(null) }
    var coverage by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val context = LocalContext.current
    val group = state.status.parameterGroup
    LaunchedEffect(group) { selected = null }
    val item = state.status.parameters.firstOrNull { it.setting == selected }
    Surface(modifier.fillMaxWidth().testTag("shooting-parameters-panel"), color = Color(0xFF1B2924), contentColor = Color.White) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(selected?.title ?: "拍摄参数", fontSize = 17.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { coverage = true },enabled = state.status.parameterCoverage != null) { Text("能力",color = Color.White) }
                TextButton(onClick = {
                    selected = null
                    keyboard?.hide()
                    onAction(if (group == ShootingGroup.COMMON) RemoteAction.ReadParameters else RemoteAction.ReadParameterGroup(group))
                }, enabled = !state.busy) { Text("刷新", color = Color.White) }
                TextButton(onClick = { keyboard?.hide(); if (selected != null) selected = null else onClose() }) {
                    Text(if (selected != null) "返回参数" else "返回取景", color = Color.White)
                }
            }
            if (selected == null) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ShootingGroup.entries.forEach { next ->
                    FilterChip(selected = next == group, enabled = !state.busy,
                        onClick = {
                            selected = null
                            keyboard?.hide()
                            onAction(RemoteAction.ReadParameterGroup(next))
                        },
                        label = { Text(next.title) },
                        colors = FilterChipDefaults.filterChipColors(containerColor = Color(0xFF1B2924),labelColor = Color.White,
                            selectedContainerColor = Color(0xFF166554),selectedLabelColor = Color.White,disabledLabelColor = Color.LightGray),
                        modifier = Modifier.testTag("parameter-group-${next.name}"))
                }
            }
            Text(state.status.message.ifBlank { "选项来自相机，机身调整后点刷新同步" }, fontSize = 11.sp, color = Color.LightGray)
            state.status.error?.let { Text(it, color = Color(0xFFFFB8A8), fontSize = 12.sp) }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 10.dp))
            if (!state.status.parametersLoaded && !state.busy) Text("读取未完成，请点刷新重试", modifier = Modifier.padding(12.dp))
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).testTag("parameter-options")) {
                if (selected == null && group == ShootingGroup.COMMON) items(state.status.parameters.chunked(2),key = { it.first().setting }) { pair ->
                    Row(Modifier.fillMaxWidth(),horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        pair.forEach { parameter -> ParameterCell(parameter,state.busy,Modifier.weight(1f)) { selected = parameter.setting } }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                } else if (selected == null) items(state.status.parameters, key = { it.setting }) { parameter ->
                    ParameterCell(parameter,state.busy,Modifier.fillMaxWidth()) { selected = parameter.setting }
                    HorizontalDivider(color = Color.White.copy(alpha = .15f))
                } else if (item?.manualRange != null) item("range-editor") {
                    val range = checkNotNull(item.manualRange)
                    var number by rememberSaveable(item.setting,item.property?.current) { mutableStateOf(item.property?.current?.toString().orEmpty()) }
                    val value = number.toLongOrNull()
                    Text("当前：${item.currentLabel}",modifier = Modifier.padding(vertical = 8.dp))
                    OutlinedTextField(number,{ number = it },enabled = !state.busy,singleLine = true,
                        label = { Text("目标数值") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White,unfocusedTextColor = Color.White,
                            focusedBorderColor = Color.White,unfocusedBorderColor = Color.LightGray,cursorColor = Color.White,
                            focusedLabelColor = Color.White,unfocusedLabelColor = Color.LightGray,
                            focusedSupportingTextColor = Color.LightGray,unfocusedSupportingTextColor = Color.LightGray,
                            errorTextColor = Color(0xFFFFB8A8)),
                        supportingText = { Text("范围 ${range.min}–${range.max}，步长 ${range.step}") },
                        isError = value == null || !item.accepts(value),modifier = Modifier.fillMaxWidth().testTag("parameter-range-input"))
                    Button(onClick = { keyboard?.hide(); selected = null; onAction(RemoteAction.SetParameter(item.setting,checkNotNull(value))) },
                        enabled = !state.busy && value != null && item.accepts(value),modifier = Modifier.testTag("parameter-range-apply")) { Text("应用") }
                } else if (item?.property?.type == 0xffff) items(item.property.textChoices,key = { it }) { text ->
                    Row(Modifier.fillMaxWidth().clickable(enabled = !state.busy) {
                        selected = null; onAction(RemoteAction.SetTextParameter(item.setting,text))
                    }.testTag("parameter-text-$text").padding(vertical = 8.dp),verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = item.property.currentText == text,onClick = null,
                            colors = RadioButtonDefaults.colors(unselectedColor = Color.LightGray))
                        Text(text,modifier = Modifier.padding(start = 12.dp))
                    }
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
            Text(when (group) {
                ShootingGroup.EXPOSURE -> "M 模式固定 ISO 时，曝光补偿主要改变曝光指示；使用自动 ISO 时可影响实际曝光。"
                ShootingGroup.RELEASE -> "连拍/自拍改变快门行为；一次遥控指令不保证只产生一张照片。"
                ShootingGroup.COLOR -> "色温需与机身白平衡模式配合；更改数值不会自动切换白平衡模式。"
                ShootingGroup.VIDEO -> "视频设置需先在机身切到视频模式；本版不提供录像开始/停止。请切回照片模式后拍照。"
                else -> "只读/未开放项显示原因。B 门 / Time 使用机身；修改以回读确认，机身调整后点刷新。"
            },
                fontSize = 10.sp, color = Color.LightGray, modifier = Modifier.padding(top = 8.dp))
        }
    }
    if (coverage) AlertDialog(onDismissRequest = { coverage = false },title = { Text("相机控制能力") },
        text = { Text(state.status.parameterCoverage.orEmpty().substringBefore("属性编号："),modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = {
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(
                ClipData.newPlainText("Z8 参数能力",state.status.parameterCoverage.orEmpty()))
        }) { Text("复制完整报告") } }, dismissButton = { TextButton(onClick = { coverage = false }) { Text("返回") } })
}

@Composable
private fun ParameterCell(parameter: ShootingParameter,busy: Boolean,modifier: Modifier,onClick: () -> Unit) {
    Column(modifier.clickable(enabled = parameter.editable && !busy,onClick = onClick)
        .testTag("parameter-${parameter.setting.name}").padding(vertical = 6.dp)) {
        Text(parameter.setting.title,fontSize = 12.sp,lineHeight = 15.sp,color = Color.LightGray)
        Text(parameter.currentLabel + if (parameter.editable) "  ›" else "",fontSize = 14.sp,lineHeight = 18.sp,color = Color.White)
        parameter.explanation?.let { Text(it,fontSize = 10.sp,lineHeight = 13.sp,color = Color.LightGray) }
    }
}
