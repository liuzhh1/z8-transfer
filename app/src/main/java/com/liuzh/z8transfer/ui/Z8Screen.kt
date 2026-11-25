package com.liuzh.z8transfer.ui

import android.graphics.Bitmap
import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.text.selection.SelectionContainer
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.liuzh.z8transfer.AppController
import com.liuzh.z8transfer.AppState
import com.liuzh.z8transfer.core.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.liuzh.z8transfer.storage.connectionDiagnostics
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

private val Paper = Color(0xFFF6F5F1)
private val Ink = Color(0xFF182D27)
private val Green = Color(0xFF176653)
private val Lime = Color(0xFFD9F0BC)
private val Muted = Color(0xFF69726B)
private val Danger = Color(0xFFB52D29)

@Composable
fun Z8Theme(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = lightColorScheme(primary = Green, secondary = Green, background = Paper,
        surface = Paper, onSurface = Ink, onBackground = Ink, surfaceVariant = Color(0xFFE9EBE4),
        primaryContainer = Lime, onPrimaryContainer = Ink, secondaryContainer = Color(0xFFE3E8DB), onSecondaryContainer = Green),
    content = content,
)

@Composable
fun Z8Screen(controller: AppController, onConnect: () -> Unit, onDownload: (Boolean) -> Unit,
    onDelete: (DeletionPlan) -> Unit, onImmersiveChange: (Boolean) -> Unit,
    onWifiSettings: () -> Unit) {
    val state by controller.state.collectAsStateWithLifecycle()
    TransferContent(state, onConnect, controller::disconnect, controller::refresh, controller::toggle,
        controller::selectVisible, controller::clearSelection, onDownload, controller::requestStop,
        controller::dismissError, controller::thumbnail, controller::preview, controller::videoPreview, onDelete, onImmersiveChange,
        controller::connectWifi, onWifiSettings, controller.lastWifiEndpoint(), controller::selectUsbMode, controller::setSelection, controller::focusRegion, controller::openRemote, controller::remoteAction, controller::closeRemote, controller::selectPage,
        onMarkSelected = controller::markSelected, onRawJpegModeChange = controller::setRawJpegMode)
}

@Composable
internal fun TransferContent(
    state: AppState, onConnect: () -> Unit, onDisconnect: () -> Unit, onRefresh: () -> Unit,
    onToggle: (CameraPhoto) -> Unit, onSelectVisible: (List<CameraPhoto>) -> Unit,
    onClearSelection: () -> Unit, onDownload: (Boolean) -> Unit, onStop: () -> Unit,
    onDismissError: () -> Unit, thumbnail: suspend (CameraPhoto) -> Bitmap?,
    preview: suspend (CameraPhoto) -> Bitmap? = thumbnail,
    videoPreview: suspend (CameraPhoto) -> String = { error("当前连接不提供视频预览") },
    onDelete: (DeletionPlan) -> Unit = {}, onImmersiveChange: (Boolean) -> Unit = {},
    onWifiConnect: (WifiEndpoint) -> Unit = {}, onWifiSettings: () -> Unit = {},
    wifiEndpoint: WifiEndpoint = WifiEndpoint(),
    onUsbModeChange: (UsbConnectionMode) -> Unit = {},
    onSelectionChange: ((Set<String>) -> Unit)? = null,
    focusRegion: suspend (CameraPhoto) -> FocusRegion? = { null },
    onOpenRemote: () -> Unit = {}, onRemoteAction: (RemoteAction) -> Unit = {}, onCloseRemote: () -> Unit = {},
    onPageChange: (CameraPage) -> Unit = {},
    onMarkSelected: () -> Unit = {}, onRawJpegModeChange: (RawJpegMode) -> Unit = {},
) {
    var category by rememberSaveable { mutableStateOf("全部") }
    var card by rememberSaveable { mutableStateOf<Int?>(null) }
    var day by rememberSaveable { mutableStateOf("所有日期") }
    var query by rememberSaveable { mutableStateOf("") }
    var collapsed by rememberSaveable { mutableStateOf(false) }
    var columns by rememberSaveable { mutableIntStateOf(2) }
    var sortName by rememberSaveable { mutableStateOf(PhotoSort.NEWEST.name) }
    var showFocus by rememberSaveable { mutableStateOf(false) }
    val sort = PhotoSort.valueOf(sortName)
    var wifi by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf(false) }
    var diagnosticReport by remember { mutableStateOf("") }
    val context = LocalContext.current
    LaunchedEffect(info, state.diagnostics) {
        if (info) diagnosticReport = withContext(Dispatchers.IO) {
            runCatching { connectionDiagnostics(context, state.diagnostics) }.getOrElse {
                "部分环境信息读取失败：${it.message}\n\n${state.diagnostics}"
            }
        }
    }
    var report by remember { mutableStateOf(false) }
    var deleteReport by remember { mutableStateOf(false) }
    var deletionPlan by remember { mutableStateOf<DeletionPlan?>(null) }
    var viewing by remember { mutableStateOf<Pair<List<CameraPhoto>, Int>?>(null) }
    val grid = rememberLazyGridState()
    val selected = remember(state.photos, state.selected) { state.photos.filter { it.key in state.selected } }
    val downloadPlan = remember(selected, state.photos, state.rawJpegMode) { planDownload(selected, state.photos, state.rawJpegMode) }
    val visible = remember(state.photos, state.marked, category, card, day, query, sort) {
        sort.apply(state.photos.filter { p ->
            (when (category) { "照片" -> p.kind.isPhoto; "视频" -> p.kind.isVideo; "N-RAW" -> p.kind == PhotoKind.NEV;
                "RAW" -> p.kind == PhotoKind.RAW; "JPEG" -> p.kind == PhotoKind.JPEG; "已标记" -> p.importKey in state.marked;
                "其他" -> p.kind == PhotoKind.OTHER; else -> true }) &&
                (card == null || p.storageId == card) && (day == "所有日期" || date(p.sortTime, "yyyy-MM-dd") == day) &&
                p.name.contains(query, true)
        })
    }
    LaunchedEffect(category, card, day, query, sortName) { grid.scrollToItem(0) }
    val selectionGesture = Modifier.dragSelect(grid, visible, state.selected, !state.busy) { keys ->
        if (onSelectionChange != null) onSelectionChange(keys) else {
            state.photos.filter { (it.key in keys) != (it.key in state.selected) }.forEach(onToggle)
        }
    }
    val immersive = state.connected && collapsed || viewing != null || state.remote.open
    LaunchedEffect(immersive) { onImmersiveChange(immersive) }
    DisposableEffect(Unit) { onDispose { onImmersiveChange(false) } }
    LaunchedEffect(state.cameraName) {
        card = null; day = "所有日期"
        if (!state.connected) { collapsed = false; viewing = null }
    }
    LaunchedEffect(state.photos, state.connected, state.scanning) {
        val old = viewing ?: return@LaunchedEffect
        if (!state.connected || state.scanning) { viewing = null; return@LaunchedEffect }
        val keys = state.photos.map { it.key }.toSet()
        val remaining = old.first.filter { it.key in keys }
        if (remaining != old.first) {
            val currentKey = old.first.getOrNull(old.second)?.key
            val next = remaining.indexOfFirst { it.key == currentKey }.takeIf { it >= 0 }
                ?: old.second.coerceAtMost((remaining.size - 1).coerceAtLeast(0))
            viewing = remaining.takeIf { it.isNotEmpty() }?.let { it to next }
        }
    }
    LaunchedEffect(state.page) { collapsed = false; viewing = null; deletionPlan = null }
    BackHandler(state.page != CameraPage.HOME && viewing == null && !collapsed && !state.remote.open) { onPageChange(CameraPage.HOME) }
    BackHandler(state.remote.open && !state.switchingPage) { onPageChange(CameraPage.HOME) }
    BackHandler(collapsed && viewing == null) { collapsed = false }
    val threshold = with(LocalDensity.current) { 32.dp.toPx() }
    var upward by remember { mutableFloatStateOf(0f) }
    var downward by remember { mutableFloatStateOf(0f) }
    val scrolling = remember(state.connected, threshold) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (!state.connected || source != NestedScrollSource.UserInput) return Offset.Zero
                if (available.y < 0 && !collapsed) {
                    upward += -available.y; downward = 0f
                    if (upward >= threshold) { collapsed = true; upward = 0f; return Offset(0f, available.y) }
                }
                if (available.y > 0 && collapsed && grid.firstVisibleItemIndex == 0 && grid.firstVisibleItemScrollOffset == 0) {
                    downward += available.y; upward = 0f
                    if (downward >= threshold) { collapsed = false; downward = 0f; return Offset(0f, available.y) }
                }
                return Offset.Zero
            }
        }
    }
    val hasDock = state.page == CameraPage.PHOTOS && (selected.isNotEmpty() || state.progress.running || state.deletion.running)
    Box(Modifier.fillMaxSize().background(Paper).then(if (!collapsed) Modifier.safeDrawingPadding() else Modifier)) {
        if (state.remote.open) RemoteCameraContent(state.remote, state.remoteCapabilities, onRemoteAction,
            onClose = { onPageChange(CameraPage.HOME) }, onBrowsePhotos = { onPageChange(CameraPage.PHOTOS) }, onStop = onCloseRemote)
        else {
            Column(Modifier.fillMaxSize()) {
                if (!collapsed) Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp).testTag("browser-tools")) {
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Z8 Transfer", fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        if (state.connected && state.page == CameraPage.PHOTOS) {
                            TextButton(onClick = { collapsed = true }) { Text("全屏浏览", fontSize = 12.sp) }
                        }
                        if (state.page != CameraPage.HOME) TextButton(onClick = { onPageChange(CameraPage.HOME) },
                            enabled = !state.switchingPage && !state.progress.running && !state.deletion.running) { Text("首页", fontSize = 12.sp) }
                        if (!state.connected) TextButton(onClick = { wifi = true }, enabled = !state.busy) { Text("Wi-Fi 连接", fontSize = 12.sp) }
                        if (!state.connected) TextButton(onClick = { info = true }) { Text("诊断", fontSize = 12.sp) }
                    }
                    Surface(color = Ink, shape = RoundedCornerShape(17.dp)) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(state.cameraName ?: "Nikon Z8", color = Color.White, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(if (state.connected) "● ${state.connectionLabel} 已连接 · 照片与视频" else "USB 或 Wi-Fi · 原文件传输", color = Lime, fontSize = 10.sp)
                            }
                            Button(onClick = if (!state.connected) onConnect else if (state.page == CameraPage.PHOTOS) onRefresh else onDisconnect,
                                enabled = !state.busy,
                                colors = ButtonDefaults.buttonColors(containerColor = Lime, contentColor = Ink), contentPadding = PaddingValues(horizontal = 12.dp)) {
                                Text(if (state.connecting) "连接中…" else if (!state.connected) "连接相机" else if (state.page == CameraPage.PHOTOS) "刷新" else "断开", fontSize = 12.sp)
                            }
                            if (state.connected) {
                                var menu by remember { mutableStateOf(false) }
                                Box {
                                    TextButton(onClick = { menu = true }, modifier = Modifier.width(36.dp), contentPadding = PaddingValues(0.dp)) { Text("⋯", color = Lime, fontSize = 24.sp) }
                                    DropdownMenu(menu, { menu = false }) {
                                        DropdownMenuItem(text = { Text("连接信息") }, onClick = { menu = false; info = true })
                                        DropdownMenuItem(text = { Text("下载结果") }, onClick = { menu = false; report = true })
                                        DropdownMenuItem(text = { Text("删除结果") }, onClick = { menu = false; deleteReport = true })
                                        DropdownMenuItem(text = { Text("Wi-Fi 连接设置") }, enabled = !state.busy,
                                            onClick = { menu = false; wifi = true })
                                        DropdownMenuItem(text = { Text("断开相机") }, enabled = !state.busy, onClick = { menu = false; onDisconnect() })
                                    }
                                }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        listOf(CameraPage.REMOTE, CameraPage.PHOTOS).forEach { page ->
                            val active = state.page == page
                            OutlinedButton(onClick = { onPageChange(page) },
                                enabled = !state.switchingPage && !state.progress.running && !state.deletion.running,
                                colors = ButtonDefaults.outlinedButtonColors(containerColor = if (active) Lime else Color.Transparent),
                                modifier = Modifier.weight(1f).testTag(if (page == CameraPage.REMOTE) "open-remote" else "open-photos")) {
                                Text(page.label)
                            }
                        }
                    }
                    if (!state.connected) {
                        var usbOptions by remember { mutableStateOf(false) }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("有线连接方式", fontSize = 12.sp, color = Muted, modifier = Modifier.weight(1f))
                            Box {
                                TextButton(onClick = { usbOptions = true }, enabled = !state.busy, modifier = Modifier.testTag("usb-mode-selector")) {
                                    Text("${state.usbMode.label} ▾", fontSize = 12.sp)
                                }
                                DropdownMenu(usbOptions, { usbOptions = false }) {
                                    UsbConnectionMode.entries.forEach { mode ->
                                        DropdownMenuItem(text = { Text(mode.label) }, enabled = !state.busy,
                                            onClick = { usbOptions = false; onUsbModeChange(mode) })
                                    }
                                }
                            }
                        }
                        Text(if (state.usbMode == UsbConnectionMode.SYSTEM_MTP) "兼容性优先；大于等于 4 GiB 的文件暂不支持此模式" else
                            "实验连接；若发送失败，请拔线重连并切回系统 MTP", fontSize = 10.sp, color = Muted)
                    }
                    if (state.connected && state.page == CameraPage.PHOTOS) {
                        Row(Modifier.fillMaxWidth().padding(top = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("相机文件", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Text("${visible.size}/${state.photos.size}", color = Muted, fontSize = 11.sp)
                            FilterMenu("${columns}列", (1..6).map { it to "${it}列" }) { columns = it }
                            FilterMenu("排序", PhotoSort.entries.map { it to it.label }) { sortName = it.name }
                            TextButton(onClick = { onSelectVisible(visible) }, enabled = !state.busy && visible.isNotEmpty(), contentPadding = PaddingValues(7.dp)) {
                                Text(if (visible.isNotEmpty() && visible.all { it.key in state.selected }) "取消全选" else "全选结果", fontSize = 11.sp)
                            }
                        }
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            listOf("全部", "已标记", "照片", "RAW", "JPEG", "视频", "N-RAW", "其他").forEach { label ->
                                FilterChip(category == label, { category = label }, label = { Text(label, fontSize = 12.sp) })
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val cards = state.photos.distinctBy { it.storageId }.map { it.storageId to "${it.storageLabel} · ${it.storageId.toUInt().toString(16)}" }
                            FilterMenu(cards.firstOrNull { it.first == card }?.second ?: "所有存储卡", listOf(null to "所有存储卡") + cards) { card = it }
                            val days = listOf("所有日期") + state.photos.map { date(it.sortTime, "yyyy-MM-dd") }.distinct().sortedDescending()
                            FilterMenu(day, days.map { it to it }) { day = it }
                            Spacer(Modifier.weight(1f))
                            if (selected.isNotEmpty()) TextButton(onClick = onClearSelection, enabled = !state.busy) { Text("清空", fontSize = 11.sp) }
                        }
                        OutlinedTextField(query, { query = it }, singleLine = true, placeholder = { Text("搜索文件名", fontSize = 12.sp) },
                            shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().height(52.dp))
                        Text("点按预览 · 长按拖动批选 · 右侧手柄翻页", color = Muted, fontSize = 10.sp, modifier = Modifier.padding(vertical = 8.dp))
                        if (state.scanning) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text("读取列表中 · ${state.examined} 个项目", fontSize = 11.sp, color = Muted)
                        }
                    }
                }
                if (state.error != null && !collapsed) ErrorNotice(state.error, onDismissError)
                if (state.connecting && !state.connected) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 18.dp))
                    Text("正在建立相机连接…", fontSize = 12.sp, color = Muted, modifier = Modifier.padding(18.dp, 8.dp))
                }
                if (!state.connected) {
                    SetupGuide(Modifier.weight(1f))
                } else if (state.page != CameraPage.PHOTOS) {
                    Column(Modifier.weight(1f).fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(if (state.page == CameraPage.REMOTE) "遥控拍摄" else "相机已连接，选择功能", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                        if (state.switchingPage) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text("正在进入${state.page.label}…", color = Muted)
                        } else if (state.page == CameraPage.REMOTE) {
                            Text(if (state.remoteCapabilities.available) "直接开启实时取景和拍照，无需读取存储卡照片。" else state.remoteUnavailableReason,
                                color = Muted, fontSize = 14.sp)
                            Button(onClick = onOpenRemote, enabled = state.remoteCapabilities.available && !state.busy,
                                modifier = Modifier.testTag("start-remote")) { Text("开始遥控拍摄") }
                        } else {
                            Text("遥控拍摄：直接进入取景、对焦和拍照。", color = Muted, fontSize = 15.sp)
                            Text("下载照片：选择此页后才读取相片列表，支持预览、下载和删除。", color = Muted, fontSize = 15.sp)
                        }
                    }
                } else if (visible.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth().nestedScroll(scrolling), contentAlignment = Alignment.Center) {
                        Text(if (state.scanning) "正在读取相机文件…" else "没有符合条件的文件", color = Muted)
                    }
                } else Box(Modifier.weight(1f).fillMaxWidth()) {
                    LazyVerticalGrid(GridCells.Fixed(columns), state = grid,
                        modifier = Modifier.fillMaxSize().nestedScroll(scrolling).then(selectionGesture).testTag("file-grid"),
                        horizontalArrangement = Arrangement.spacedBy(if (columns >= 4) 3.dp else 6.dp),
                        verticalArrangement = Arrangement.spacedBy(if (columns >= 4) 4.dp else 8.dp),
                        contentPadding = PaddingValues(start = if (collapsed) 4.dp else 12.dp, end = 40.dp,
                            top = if (collapsed) 48.dp else 2.dp,
                            bottom = if (hasDock && !state.progress.running && !state.deletion.running) 230.dp else if (hasDock) 144.dp else 16.dp)) {
                        items(visible, key = { it.key }) { file ->
                            FileTile(file, file.key in state.selected, file.key in state.downloaded, state.busy,
                                state.scanning || state.progress.running || state.deletion.running, thumbnail,
                                onSelect = { onToggle(file) }, onPreview = { viewing = visible.toList() to visible.indexOf(file) },
                                compact = columns >= 4, marked = file.importKey in state.marked)
                        }
                    }
                    if (visible.size > columns && (grid.canScrollBackward || grid.canScrollForward)) {
                        GridPageHandle(grid, visible.size, columns)
                    }
                }
            }
            if (collapsed) Surface(color = Ink.copy(alpha = 0.88f), shape = RoundedCornerShape(30.dp),
                modifier = Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(8.dp)) {
                TextButton(onClick = { collapsed = false }) { Text("展开工具栏", color = Color.White, fontSize = 11.sp) }
            }
            if (hasDock) Surface(color = Paper, shadowElevation = 8.dp, shape = RoundedCornerShape(19.dp),
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(10.dp).testTag("selection-dock")) {
                Column(Modifier.padding(horizontal = 13.dp, vertical = 9.dp)) {
                    when {
                        state.deletion.running -> {
                            Text("正在删除相机文件 ${state.deletion.outcomes.size}/${state.deletion.total}", fontSize = 12.sp)
                            Text(state.deletion.currentName, fontSize = 11.sp, color = Muted)
                            TextButton(onClick = onStop) { Text("完成当前项后停止后续删除") }
                        }
                        state.progress.running -> {
                            ProgressPanel(state.progress)
                            TextButton(onClick = onStop, enabled = !state.progress.stopRequested) { Text(if (state.progress.stopRequested) "正在停止后续…" else "完成当前文件后停止") }
                        }
                        else -> {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("已选 ${selected.size} 个文件", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                                Text(selectionSize(downloadPlan.files), fontSize = 11.sp, color = Muted)
                            }
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = onMarkSelected, enabled = !state.busy, modifier = Modifier.testTag("mark-selected")) {
                                    Text(if (selected.all { it.importKey in state.marked }) "取消标记" else "标记选片", fontSize = 12.sp)
                                }
                                FilterMenu(state.rawJpegMode.label, RawJpegMode.entries.map { it to it.label }, onRawJpegModeChange)
                            }
                            if (state.rawJpegMode != RawJpegMode.SELECTED) Text(
                                "按同卡同目录配对 · 下载 ${downloadPlan.files.size} 个文件" +
                                    if (downloadPlan.unmatchedPhotos > 0) " · ${downloadPlan.unmatchedPhotos} 项无对应格式" else "",
                                fontSize = 10.sp, color = Muted)
                            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedButton(onClick = { deletionPlan = DeletionPlan(selected.first().sessionId, selected.toList()) },
                                    enabled = state.connected && !state.busy && state.supportsDeletion,
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Danger), modifier = Modifier.weight(1f)) { Text("删除相机文件", fontSize = 12.sp) }
                                Button(onClick = { onDownload(false) }, enabled = state.connected && !state.busy && state.storageReady && downloadPlan.files.isNotEmpty(),
                                    modifier = Modifier.weight(1.25f)) { Text("下载 ${downloadPlan.files.size} 个原文件", fontSize = 12.sp) }
                            }
                            if (!state.supportsDeletion) Text(state.deletionUnavailableReason, fontSize = 10.sp, color = Muted)
                        }
                    }
                }
            }
        }
    }
    if (wifi) WifiConnectionDialog(!state.busy, wifiEndpoint, onWifiSettings,
        onConnect = { endpoint -> wifi = false; onWifiConnect(endpoint) }, onDismiss = { wifi = false })
    if (info) BasicDialog("连接信息", diagnosticReport.ifBlank { "正在读取本机连接环境…" }) { info = false }
    if (report) ReportDialog(state.progress, { report = false }, if (state.connected && !state.busy && state.progress.failed > 0) {
        { report = false; onDownload(true) }
    } else null)
    if (deleteReport) BasicDialog("相机删除结果", "已删除 ${state.deletion.deleted} · 未成功 ${state.deletion.failed}\n" +
        state.deletion.outcomes.joinToString("\n") { "${it.file.name}：${if (it.deleted) "已删除" else it.error}" }) { deleteReport = false }
    viewing?.let { (files, initial) ->
        key(files.map { it.key }) {
            FullMediaViewer(files, initial, state, thumbnail, preview, videoPreview, onToggle, { viewing = null },
                showFocus, { showFocus = it }, focusRegion,
                onDeleteFile = { file -> deletionPlan = DeletionPlan(file.sessionId, listOf(file)) },
                onCurrentIndex = { index -> viewing?.let { current -> if (current.first == files) viewing = files to index } })
        }
    }
    // Confirmation must be above the viewer dialog, including when deleting its current photo.
    deletionPlan?.let { plan ->
        DeleteConfirmation(plan, state.connected && !state.busy && state.supportsDeletion, { deletionPlan = null },
            onConfirm = { deletionPlan = null; onDelete(plan) },
            companions = companionJpegs(plan.files, state.photos), onConfirmFiles = { files ->
                deletionPlan = null; onDelete(DeletionPlan(plan.sessionId, files))
            })
    }

}

@Composable
private fun FileTile(file: CameraPhoto, selected: Boolean, downloaded: Boolean, busy: Boolean, paused: Boolean,
    thumbnail: suspend (CameraPhoto) -> Bitmap?, onSelect: () -> Unit, onPreview: () -> Unit, compact: Boolean = false,
    marked: Boolean = false) {
    var bitmap by remember(file.key) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(file.key, paused) { if (!paused && bitmap == null) bitmap = thumbnail(file) }
    Column(Modifier.clip(RoundedCornerShape(13.dp)).background(Color.White)
        .border(if (selected) 2.dp else 1.dp, if (selected) Green else Color(0xFFE3E5DC), RoundedCornerShape(13.dp))
        .clickable(onClick = onPreview)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).background(Color(0xFFE3E7DF))) {
            bitmap?.let { Image(it.asImageBitmap(), file.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                ?: Text(if (file.kind.isVideo) "▷" else "▧", fontSize = if (compact) 22.sp else 46.sp, color = Muted, modifier = Modifier.align(Alignment.Center))
            Surface(color = Ink.copy(alpha = 0.85f), shape = RoundedCornerShape(5.dp), modifier = Modifier.align(Alignment.TopStart).padding(if (compact) 2.dp else 6.dp)) {
                Text(file.kind.label, color = Color.White, fontSize = if (compact) 7.sp else 10.sp, modifier = Modifier.padding(if (compact) 2.dp else 5.dp))
            }
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides if (compact) 0.dp else 48.dp) {
                Checkbox(selected, { onSelect() }, enabled = !busy,
                    modifier = Modifier.align(Alignment.TopEnd).size(if (compact) 24.dp else 48.dp).background(Color.White.copy(alpha = 0.9f), RoundedCornerShape(bottomStart = 12.dp))
                        .semantics { contentDescription = "选择 ${file.name}" })
            }
            if (marked) Text("★", color = Color(0xFFFFD54F), fontSize = if (compact) 14.sp else 23.sp,
                modifier = Modifier.align(Alignment.BottomStart).background(Ink.copy(alpha = .8f)).padding(horizontal = 4.dp)
                    .semantics { contentDescription = "已标记 ${file.name}" })
        }
        Column(Modifier.padding(if (compact) 3.dp else 9.dp)) {
            Text(file.name, fontSize = if (compact) 8.sp else 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!compact) Text("${fileSize(file)} · ${date(file.sortTime, "MM-dd HH:mm")}", fontSize = 10.sp, color = Muted, modifier = Modifier.padding(top = 4.dp))
            if (downloaded) Text("✓ 已下载", color = Green, fontSize = 10.sp)
        }
    }
}

@Composable
private fun FullMediaViewer(files: List<CameraPhoto>, initial: Int, state: AppState,
    thumbnail: suspend (CameraPhoto) -> Bitmap?, preview: suspend (CameraPhoto) -> Bitmap?,
    videoPreview: suspend (CameraPhoto) -> String, onToggle: (CameraPhoto) -> Unit, onClose: () -> Unit,
    showFocus: Boolean, onShowFocus: (Boolean) -> Unit, focusRegion: suspend (CameraPhoto) -> FocusRegion?,
    onDeleteFile: (CameraPhoto) -> Unit, onCurrentIndex: (Int) -> Unit) {
    val pager = rememberPagerState(initialPage = initial, pageCount = { files.size })
    val scope = rememberCoroutineScope()
    LaunchedEffect(pager.currentPage) { onCurrentIndex(pager.currentPage) }
    var currentScale by remember { mutableFloatStateOf(1f) }
    var showInfo by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        DisposableEffect(view) {
            (view.parent as? DialogWindowProvider)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).apply {
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    hide(WindowInsetsCompat.Type.systemBars())
                }
            }
            onDispose { }
        }
        Box(Modifier.fillMaxSize().background(Color.Black).testTag("full-media-viewer")) {
            HorizontalPager(pager, userScrollEnabled = currentScale <= 1.01f, modifier = Modifier.fillMaxSize()) { index ->
                val file = files[index]
                var bitmap by remember(file.key) { mutableStateOf<Bitmap?>(null) }
                var loading by remember(file.key) { mutableStateOf(false) }
                var videoPath by remember(file.key) { mutableStateOf<String?>(null) }
                var error by remember(file.key) { mutableStateOf<String?>(null) }
                var focus by remember(file.key) { mutableStateOf<FocusRegion?>(null) }
                var focusStatus by remember(file.key) { mutableStateOf<String?>(null) }
                LaunchedEffect(file.key, showFocus, index == pager.currentPage) {
                    focus = null; focusStatus = null
                    if (showFocus && file.kind.isPhoto && index == pager.currentPage) {
                        focusStatus = "正在读取对焦信息…"
                        try {
                            focus = focusRegion(file)
                            focusStatus = if (focus == null) "此照片无可用对焦坐标，或预览格式暂不支持" else null
                        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                        catch (e: Exception) { focusStatus = e.message ?: "对焦信息读取失败" }
                    }
                }
                var scale by remember(file.key) { mutableFloatStateOf(1f) }
                var offset by remember(file.key) { mutableStateOf(Offset.Zero) }
                LaunchedEffect(pager.currentPage) { if (index == pager.currentPage) currentScale = scale }
                LaunchedEffect(file.key, index == pager.currentPage) {
                    if (index == pager.currentPage) {
                        bitmap = thumbnail(file)
                        if (file.kind.isPhoto) { loading = true; bitmap = preview(file) ?: bitmap; loading = false }
                    }
                }
                val transform = rememberTransformableState { zoom, pan, _ ->
                    scale = (scale * zoom).coerceIn(1f, 8f)
                    offset = if (scale <= 1.01f) Offset.Zero else Offset((offset.x + pan.x).coerceIn(-2500f, 2500f), (offset.y + pan.y).coerceIn(-2500f, 2500f))
                    if (index == pager.currentPage) currentScale = scale
                }
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val path = videoPath
                    if (path != null) {
                        var player by remember { mutableStateOf<VideoView?>(null) }
                        AndroidView(factory = { context -> VideoView(context).also { video ->
                            player = video
                            video.setMediaController(MediaController(context).also { it.setAnchorView(video) })
                            video.setOnErrorListener { _, _, _ -> error = "手机无法解码此视频，原文件仍可下载"; videoPath = null; true }
                            video.setVideoPath(path); video.start()
                        } }, modifier = Modifier.fillMaxSize())
                        DisposableEffect(path, index == pager.currentPage) { onDispose { player?.stopPlayback() } }
                    } else {
                        bitmap?.let { image ->
                            Box(Modifier.fillMaxSize().transformable(transform, canPan = { scale > 1.01f })
                                .pointerInput(file.key) { detectTapGestures(onDoubleTap = {
                                    scale = if (scale > 1f) 1f else 3f; offset = Offset.Zero; currentScale = scale
                                }) }.graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y)) {
                                Image(image.asImageBitmap(), file.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                                if (showFocus && focus != null && !loading) Canvas(Modifier.fillMaxSize().testTag("focus-overlay")) {
                                    val fit = minOf(size.width / image.width, size.height / image.height)
                                    val w = image.width * fit; val h = image.height * fit
                                    val f = focus!!
                                    drawRect(if (f.inFocus) Color.Green else Color.Red,
                                        topLeft = Offset((size.width - w) / 2 + f.left * w, (size.height - h) / 2 + f.top * h),
                                        size = Size((f.right - f.left) * w, (f.bottom - f.top) * h), style = Stroke(2.dp.toPx()))
                                }
                            }
                        } ?: Text(if (file.kind.isVideo) "${file.kind.label} 视频" else "暂无可用预览", color = Color.White, fontSize = 24.sp)
                        if (showFocus && file.kind.isPhoto && focusStatus != null) Text(focusStatus!!, color = Color.LightGray,
                            fontSize = 11.sp, modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 24.dp, vertical = 100.dp))
                        if (loading) CircularProgressIndicator(Modifier.size(36.dp), color = Lime)
                        if (file.kind.isVideo) Column(Modifier.align(Alignment.BottomCenter).padding(horizontal = 24.dp, vertical = 130.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            if (file.kind == PhotoKind.NEV) Text("N-RAW 原文件可下载；播放使用同目录、同名 MP4 代理", color = Color.White, fontSize = 12.sp)
                            Button(enabled = !loading && !state.busy, onClick = {
                                scope.launch {
                                    loading = true; error = null
                                    runCatching { videoPreview(file) }.onSuccess { videoPath = it }.onFailure { error = it.message }
                                    loading = false
                                }
                            }) { Text(if (file.proxyFrom(state.photos) != null) "播放 MP4 代理" else "尝试播放") }
                            error?.let { Text(it, color = Color(0xFFFFB8A8), fontSize = 12.sp) }
                        }
                    }
                }
            }
            val file = files[pager.currentPage]
            val deleteFailure = state.deletion.outcomes.lastOrNull { it.file.key == file.key && !it.deleted }
            if (!state.deletion.running && deleteFailure != null) Text("删除未成功：${deleteFailure.error}",
                color = Color(0xFFFFB8A8), fontSize = 12.sp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(start = 18.dp, end = 18.dp, bottom = 86.dp))
            Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().safeDrawingPadding().background(Color.Black.copy(alpha = 0.55f)).padding(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onClose) { Text("返回", color = Color.White) }
                Column(Modifier.weight(1f)) {
                    Text(file.name, color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${pager.currentPage + 1}/${files.size} · ${file.kind.label} · ${fileSize(file)}", color = Color.LightGray, fontSize = 11.sp)
                }
                if (file.kind.isPhoto) TextButton(onClick = { onShowFocus(!showFocus) }, modifier = Modifier.testTag("focus-toggle")) {
                    Text(if (showFocus) "隐藏对焦框" else "显示对焦框", color = if (showFocus) Lime else Color.White, fontSize = 12.sp)
                }
                TextButton(onClick = { showInfo = !showInfo }) { Text("信息", color = Color.White) }
            }
            if (showInfo) Surface(color = Color(0xDD182D27), modifier = Modifier.align(Alignment.Center).padding(20.dp), shape = RoundedCornerShape(16.dp)) {
                Text("${file.name}\n${file.kind.label} · ${fileSize(file)}\n${file.storageLabel}/${file.folder}\n${date(file.sortTime, "yyyy-MM-dd HH:mm:ss")}\n照片支持双指缩放、双击放大；左右滑动切换文件。",
                    color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(20.dp))
            }
            Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().background(Color.Black.copy(alpha = 0.55f)).padding(10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Checkbox(file.key in state.selected, { onToggle(file) }, enabled = !state.busy,
                    colors = CheckboxDefaults.colors(uncheckedColor = Color.White), modifier = Modifier.semantics { contentDescription = "选择预览文件 ${file.name}" })
                Text("选择此文件", color = Color.White, fontSize = 13.sp)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { onDeleteFile(file) }, enabled = state.connected && !state.busy && state.supportsDeletion,
                    modifier = Modifier.testTag("delete-preview-file")) { Text("删除此文件", color = if (state.supportsDeletion) Color(0xFFFFB8A8) else Muted, fontSize = 12.sp) }
                TextButton(onClick = { scope.launch { currentScale = 1f; pager.animateScrollToPage((pager.currentPage + 1).coerceAtMost(files.lastIndex)) } },
                    enabled = pager.currentPage < files.lastIndex) { Text("下一个", color = Color.White) }
            }
        }
    }
}

@Composable
internal fun DeleteConfirmation(plan: DeletionPlan, enabled: Boolean, onClose: () -> Unit, onConfirm: () -> Unit,
    companions: List<CameraPhoto> = emptyList(), onConfirmFiles: ((List<CameraPhoto>) -> Unit)? = null) {
    var includeJpeg by remember(plan) { mutableStateOf(false) }
    val files = remember(plan, companions, includeJpeg) { (plan.files + if (includeJpeg) companions else emptyList()).distinctBy { it.key } }
    var acknowledged by remember(plan, includeJpeg) { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onClose, title = { Text("删除相机存储卡中的文件", color = Danger) }, text = {
        Column {
            if (companions.isNotEmpty()) Row(Modifier.fillMaxWidth().clickable { includeJpeg = !includeJpeg }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(includeJpeg, { includeJpeg = it }, modifier = Modifier.semantics { contentDescription = "同时删除同名 JPEG" })
                Text("同时删除同卡同目录的同名 JPEG（${companions.size} 个）", fontSize = 12.sp)
            }
            Text("将永久删除以下 ${files.size} 个相机原文件。此操作不可撤销；手机上已有的下载副本会保留。", fontSize = 13.sp)
            LazyColumn(Modifier.heightIn(max = 230.dp).padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(files, key = { it.key }) { file ->
                    Column {
                        Text(file.name, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text("${file.storageLabel}/${file.folder} · ${fileSize(file)}", fontSize = 10.sp, color = Muted)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().clickable { acknowledged = !acknowledged }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(acknowledged, { acknowledged = it }, modifier = Modifier.semantics { contentDescription = "确认永久删除相机原文件" })
                Text("我确认这些相机原文件可以永久删除", fontSize = 12.sp)
            }
            if (!enabled) Text("连接或任务状态已变化，请重新选择文件", color = Danger, fontSize = 11.sp)
        }
    }, confirmButton = { TextButton(onClick = { onConfirmFiles?.invoke(files) ?: onConfirm() }, enabled = enabled && acknowledged) { Text("永久删除 ${files.size} 个文件", color = if (enabled && acknowledged) Danger else Muted) } },
        dismissButton = { TextButton(onClick = onClose) { Text("取消") } })
}

@Composable private fun ErrorNotice(message: String, dismiss: () -> Unit) {
    var details by remember(message) { mutableStateOf(false) }
    Surface(color = Color(0xFFFFEDE5), shape = RoundedCornerShape(12.dp), modifier = Modifier.padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp)) {
            Text(message.lineSequence().first(), fontSize = 11.sp, color = Danger, maxLines = 2,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            TextButton(onClick = { details = true }) { Text("查看详情", fontSize = 11.sp) }
            TextButton(onClick = dismiss) { Text("关闭") }
        }
    }
    if (details) BasicDialog("错误详情", message) { details = false }
}

@Composable private fun BasicDialog(title: String, text: String, close: () -> Unit) {
    val clipboard = LocalContext.current.getSystemService(ClipboardManager::class.java)
    var copied by remember(text) { mutableStateOf(false) }
    AlertDialog(onDismissRequest = close, title = { Text(title) },
        text = { SelectionContainer { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) { Text(text, fontSize = 13.sp) } } },
        confirmButton = { TextButton(onClick = close) { Text("关闭") } },
        dismissButton = { TextButton(onClick = { clipboard.setPrimaryClip(ClipData.newPlainText("Z8 Transfer 信息", text)); copied = true }) {
            Text(if (copied) "已复制" else "复制信息")
        } })
}

@Composable private fun <T> FilterMenu(label: String, values: List<Pair<T, String>>, choose: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("$label ▾", maxLines = 1, fontSize = 11.sp) }
        DropdownMenu(open, { open = false }) { values.forEach { (key, text) -> DropdownMenuItem(text = { Text(text, fontSize = 12.sp) }, onClick = { open = false; choose(key) }) } }
    }
}

@Composable private fun SetupGuide(modifier: Modifier = Modifier) {
    LazyColumn(modifier.fillMaxWidth().padding(horizontal = 22.dp), contentPadding = PaddingValues(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item { Text("连接 Z8，浏览照片与视频", fontSize = 22.sp, fontWeight = FontWeight.SemiBold) }
        items(listOf("USB：用数据线连接 Z8 的 USB 数据接口，在相机选择 MTP/PTP，点击「连接相机」并允许访问。", "Wi-Fi：点右上角「Wi-Fi 连接」，按引导在手机设置中连接 Z8 热点，再回到 App 建立连接。", "点按文件全屏预览，勾选后下载原文件。USB 和 Wi-Fi 删除均按相机开放能力提供。", "上滑收起工具栏，向下拉或点击「展开工具栏」恢复。支持 NEF、JPEG、HEIF、NEV、MOV、MP4 及其他相机文件。")) {
            Text(it, fontSize = 14.sp, lineHeight = 23.sp, color = Muted)
        }
        item { Text("照片保存至 Pictures/Z8Transfer；普通视频至 Movies/Z8Transfer；NEV 与其他文件至 Download/Z8Transfer。视频传输大小受当前连接方式限制，手机播放优先使用 MP4 代理。", fontSize = 12.sp, color = Muted) }
    }
}

@Composable private fun ProgressPanel(p: TransferProgress) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("导入 ${(p.finishedFiles + 1).coerceAtMost(p.totalFiles)}/${p.totalFiles}", fontSize = 12.sp)
        Text("${bytes(p.bytesPerSecond.toLong())}/s", fontSize = 11.sp, color = Green)
    }
    if (p.currentSize > 0) LinearProgressIndicator(progress = { (p.currentBytes.toDouble() / p.currentSize).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 7.dp))
    else LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 7.dp))
    Text("${p.currentName} · ${bytes(p.currentBytes)} / ${if (p.currentSize > 0) bytes(p.currentSize) else "相机未提供精确大小"}",
        fontSize = 10.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable private fun ReportDialog(p: TransferProgress, close: () -> Unit, retry: (() -> Unit)?) {
    AlertDialog(onDismissRequest = close, title = { Text("下载结果") }, text = {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Text("成功 ${p.saved} · 已存在 ${p.skipped} · 失败 ${p.failed}", fontSize = 13.sp) }
            if (p.elapsedMillis > 0) item { Text("成功导入 ${bytes(p.copiedBytes)} · ${String.format(Locale.CHINA, "%.1f", p.elapsedMillis / 1000.0)} 秒\n平均 ${bytes((p.copiedBytes * 1000.0 / p.elapsedMillis).toLong())}/s", fontSize = 12.sp) }
            items(p.outcomes) { result ->
                Text(result.photo.name + "\n" + when (result.status) {
                    OutcomeStatus.SAVED -> if (result.sizeVerified) "已保存 · 大小校验通过" else "协议传输完成 · 相机未提供精确大小，未独立核对长度"
                    OutcomeStatus.SKIPPED -> "本地文件已存在"
                    OutcomeStatus.FAILED -> result.error ?: "下载失败"
                }, fontSize = 11.sp, color = if (result.status == OutcomeStatus.FAILED) Danger else Muted)
            }
        }
    }, confirmButton = { TextButton(onClick = close) { Text("关闭") } }, dismissButton = { if (retry != null) TextButton(onClick = retry) { Text("重试失败项") } })
}

private val dateFormatters = ConcurrentHashMap<String, DateTimeFormatter>()
private fun date(millis: Long, pattern: String): String = if (millis > 0) dateFormatters.getOrPut(pattern) {
    DateTimeFormatter.ofPattern(pattern, Locale.CHINA).withZone(ZoneId.systemDefault())
}.format(Instant.ofEpochMilli(millis)) else "时间未知"
private fun fileSize(file: CameraPhoto): String = if (file.size > 0) bytes(file.size) else "大小未知/≥4GiB"
private fun selectionSize(files: List<CameraPhoto>): String = bytes(files.filter { it.size > 0 }.sumOf { it.size }) +
    if (files.any { it.size <= 0 }) " + 未知大小文件" else ""
internal fun bytes(value: Long): String = when {
    value >= 1024L * 1024 * 1024 -> String.format(Locale.CHINA, "%.2f GiB", value / (1024.0 * 1024 * 1024))
    value >= 1024L * 1024 -> String.format(Locale.CHINA, "%.1f MiB", value / (1024.0 * 1024))
    value >= 1024 -> String.format(Locale.CHINA, "%.0f KiB", value / 1024.0)
    else -> "$value B"
}
