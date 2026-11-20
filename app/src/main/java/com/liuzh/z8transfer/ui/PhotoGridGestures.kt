package com.liuzh.z8transfer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liuzh.z8transfer.core.CameraPhoto
import com.liuzh.z8transfer.core.rangeSelection
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Long press anywhere on a tile, then drag a contiguous range, including across viewport edges. */
@Composable
internal fun Modifier.dragSelect(grid: LazyGridState, files: List<CameraPhoto>, selected: Set<String>,
    enabled: Boolean, onSelection: (Set<String>) -> Unit): Modifier {
    val currentSelection by rememberUpdatedState(selected)
    val change by rememberUpdatedState(onSelection)
    var anchor by remember { mutableIntStateOf(-1) }
    var pointer by remember { mutableStateOf(Offset.Zero) }
    var original by remember { mutableStateOf(emptySet<String>()) }
    val edge = with(LocalDensity.current) { 64.dp.toPx() }
    fun indexAt(point: Offset): Int? {
        val items = grid.layoutInfo.visibleItemsInfo
        return items.firstOrNull { point.x >= it.offset.x && point.x < it.offset.x + it.size.width &&
            point.y >= it.offset.y && point.y < it.offset.y + it.size.height }?.index
            ?: items.minByOrNull {
                val x = (point.x - it.offset.x - it.size.width / 2).toDouble()
                val y = (point.y - it.offset.y - it.size.height / 2).toDouble()
                x * x + y * y
            }?.index
    }
    fun selectAtPointer() { if (anchor >= 0) indexAt(pointer)?.let { change(rangeSelection(files, original, anchor, it)) } }
    LaunchedEffect(anchor, files, enabled) {
        if (anchor < 0 || !enabled) return@LaunchedEffect
        while (anchor >= 0) {
            val bottom = grid.layoutInfo.viewportEndOffset.toFloat() - grid.layoutInfo.afterContentPadding
            val top = grid.layoutInfo.viewportStartOffset.toFloat().coerceAtLeast(0f)
            val speed = when {
                pointer.y < top + edge -> -((top + edge - pointer.y) / edge).coerceIn(0f, 1f) * 24f
                pointer.y > bottom - edge -> ((pointer.y - bottom + edge) / edge).coerceIn(0f, 1f) * 24f
                else -> 0f
            }
            if (speed != 0f) { grid.scrollBy(speed); selectAtPointer() }
            delay(16)
        }
    }
    return pointerInput(files, enabled) {
        if (enabled) detectDragGesturesAfterLongPress(
            onDragStart = { point ->
                pointer = point; original = currentSelection
                anchor = indexAt(point) ?: -1
                selectAtPointer()
            },
            onDragEnd = { anchor = -1 }, onDragCancel = { anchor = -1 },
            onDrag = { event, _ -> event.consume(); pointer = event.position; selectAtPointer() },
        )
    }
}

@Composable
internal fun BoxScope.GridPageHandle(grid: LazyGridState, count: Int, columns: Int) {
    val scope = rememberCoroutineScope()
    val thumbHeight = 64.dp
    val density = LocalDensity.current
    var dragging by remember { mutableStateOf(false) }
    var fraction by remember { mutableFloatStateOf(0f) }
    val currentIndex by remember { derivedStateOf { grid.firstVisibleItemIndex + 1 } }
    val rows = ((count + columns - 1) / columns).coerceAtLeast(1)
    val visibleRows by remember { derivedStateOf {
        grid.layoutInfo.visibleItemsInfo.map { it.row }.filter { it >= 0 }.distinct().size.coerceAtLeast(1)
    } }
    val maxRow = (rows - visibleRows).coerceAtLeast(1)
    val position by remember(count, columns, maxRow) { derivedStateOf {
        if (!grid.canScrollForward && grid.canScrollBackward) 1f else
            (grid.firstVisibleItemIndex / columns.toFloat() / maxRow).coerceIn(0f, 1f)
    } }
    BoxWithConstraints(Modifier.align(Alignment.CenterEnd).width(44.dp).fillMaxHeight().padding(vertical = 8.dp)) {
        val travel = with(density) { (maxHeight - thumbHeight).coerceAtLeast(0.dp).toPx() }
        val livePosition by rememberUpdatedState(position)
        fun seek(value: Float) {
            fraction = value.coerceIn(0f, 1f)
            scope.launch { grid.scrollToItem((maxRow * fraction).roundToInt() * columns) }
        }
        Box(Modifier.align(Alignment.TopEnd).offset { IntOffset(0, ((if (dragging) fraction else position) * travel).roundToInt()) }
            .width(44.dp).height(thumbHeight).testTag("page-handle")
            .semantics {
                contentDescription = "拖动翻页"
                progressBarRangeInfo = ProgressBarRangeInfo(position, 0f..1f)
                setProgress { seek(it); true }
            }
            .pointerInput(travel, count, columns) {
                detectDragGestures(onDragStart = { fraction = livePosition; dragging = true },
                    onDragEnd = { dragging = false }, onDragCancel = { dragging = false },
                    onDrag = { change, delta -> change.consume(); if (travel > 0f) seek(fraction + delta.y / travel) })
            }, contentAlignment = Alignment.CenterEnd) {
            Box(Modifier.width(28.dp).fillMaxHeight().background(Color(0xE6182D27), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                Text("↕", color = Color.White, fontSize = 20.sp)
            }
        }
        if (dragging) Text("$currentIndex / $count", color = Color.White, fontSize = 12.sp,
            modifier = Modifier.align(Alignment.TopEnd).offset { IntOffset(-with(density) { 50.dp.toPx() }.roundToInt(), (fraction * travel).roundToInt()) }
                .background(Color(0xE6182D27), RoundedCornerShape(8.dp)).padding(10.dp))
    }
}
