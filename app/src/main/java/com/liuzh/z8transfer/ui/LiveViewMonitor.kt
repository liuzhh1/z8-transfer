package com.liuzh.z8transfer.ui

import android.graphics.Bitmap
import androidx.core.graphics.scale
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.liuzh.z8transfer.core.analyzeMonitor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.math.roundToInt

private data class PreviewAnalysis(val width: Int, val height: Int, val histogram: IntArray,
    val overlay: Bitmap?, val brightFraction: Float)

@Composable
internal fun LiveViewMonitor(frame: Bitmap?, histogram: Boolean, zebra: Boolean, peaking: Boolean,
    grid: Boolean, modifier: Modifier = Modifier) {
    val latest by rememberUpdatedState(frame)
    var result by remember { mutableStateOf<PreviewAnalysis?>(null) }
    var unavailable by remember { mutableStateOf(false) }
    // Analyze the latest available preview at most five times per second. Camera acquisition never waits for this job.
    LaunchedEffect(histogram, zebra, peaking) {
        result = null
        unavailable = false
        if (!histogram && !zebra && !peaking) return@LaunchedEffect
        var previous: Bitmap? = null
        while (isActive) {
            val source = latest
            if (source != null && source !== previous) {
                try {
                    result = withContext(Dispatchers.Default) {
                        val scale = min(1f, 256f / maxOf(source.width, source.height))
                        val width = (source.width * scale).roundToInt().coerceAtLeast(1)
                        val height = (source.height * scale).roundToInt().coerceAtLeast(1)
                        val sample = source.scale(width, height, true)
                        val pixels = IntArray(width * height)
                        try { sample.getPixels(pixels, 0, width, 0, 0, width, height) }
                        finally { if (sample !== source) sample.recycle() }
                        val analysis = analyzeMonitor(pixels, width, height, zebra, peaking)
                        PreviewAnalysis(source.width, source.height, analysis.histogram,
                            if (zebra || peaking) Bitmap.createBitmap(analysis.overlay, width, height, Bitmap.Config.ARGB_8888) else null,
                            analysis.brightFraction)
                    }
                    unavailable = false
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { result = null; unavailable = true }
                previous = source
            }
            delay(200)
        }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        if (frame != null) {
            Image(frame.asImageBitmap(), "相机实时取景", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            val analysis = result?.takeIf { it.width == frame.width && it.height == frame.height }
            analysis?.overlay?.let {
                Image(it.asImageBitmap(), "监看辅助叠层", Modifier.fillMaxSize().testTag("monitor-overlay"), contentScale = ContentScale.Fit)
            }
            if (grid) Canvas(Modifier.fillMaxSize().testTag("monitor-grid")) {
                val scale = min(size.width / frame.width, size.height / frame.height)
                val width = frame.width * scale
                val height = frame.height * scale
                val left = (size.width - width) / 2
                val top = (size.height - height) / 2
                for (part in 1..2) {
                    drawLine(Color.White.copy(alpha = .65f), Offset(left + width * part / 3, top),
                        Offset(left + width * part / 3, top + height), strokeWidth = 1.dp.toPx())
                    drawLine(Color.White.copy(alpha = .65f), Offset(left, top + height * part / 3),
                        Offset(left + width, top + height * part / 3), strokeWidth = 1.dp.toPx())
                }
            }
            if (histogram && analysis != null) Canvas(Modifier.align(Alignment.BottomStart).padding(10.dp)
                .size(126.dp, 58.dp).background(Color.Black.copy(alpha = .7f)).testTag("monitor-histogram")) {
                val peak = (analysis.histogram.maxOrNull() ?: 1).coerceAtLeast(1)
                val barWidth = size.width / analysis.histogram.size
                analysis.histogram.forEachIndexed { index, count ->
                    val height = count.toFloat() / peak * (size.height - 4.dp.toPx())
                    drawRect(Color(0xFFD9F0BC), Offset(index * barWidth, size.height - height), Size(barWidth.coerceAtLeast(1f), height))
                }
            }
            if (zebra && analysis != null) Text("亮部 ${(analysis.brightFraction * 100).roundToInt()}% · 预览 RGB ≥235",
                color = Color.White, fontSize = 10.sp, modifier = Modifier.align(Alignment.BottomEnd)
                    .background(Color.Black.copy(alpha = .7f)).padding(6.dp))
            if (unavailable) Text("监看辅助暂不可用", color = Color.LightGray, fontSize = 11.sp,
                modifier = Modifier.align(Alignment.BottomStart).background(Color.Black.copy(alpha = .7f)).padding(8.dp))
        }
    }
}
