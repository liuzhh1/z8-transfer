package com.liuzh.z8transfer.core

import org.junit.Assert.*
import org.junit.Test

class MonitorAnalysisTest {
    @Test fun blackAndWhitePopulateExtremeHistogramBins() {
        val analysis = analyzeMonitor(intArrayOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), 2, 1, true, true)
        assertEquals(1, analysis.histogram.first())
        assertEquals(1, analysis.histogram.last())
        assertEquals(2, analysis.histogram.sum())
        assertEquals(.5f, analysis.brightFraction, .0001f)
    }
    @Test fun disabledOverlaysStayTransparentEvenWithClippedPixels() {
        val analysis = analyzeMonitor(IntArray(25) { 0xFFFFFFFF.toInt() }, 5, 5, false, false)
        assertTrue(analysis.overlay.all { it == 0 })
    }
    @Test fun zebraStripesOnlyHighlightBrightRegions() {
        val pixels = IntArray(100) { if (it % 10 < 5) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        val overlay = analyzeMonitor(pixels, 10, 10, true, false).overlay
        assertTrue(overlay.any { it != 0 })
        pixels.forEachIndexed { index, pixel -> if (pixel == 0xFF000000.toInt()) assertEquals(0, overlay[index]) }
    }
    @Test fun peakingFindsAnEdgeButNotAUniformSurface() {
        val flat = IntArray(25) { 0xFF808080.toInt() }
        assertTrue(analyzeMonitor(flat, 5, 5, false, true).overlay.all { it == 0 })
        val edge = IntArray(25) { if (it % 5 < 2) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        val overlay = analyzeMonitor(edge, 5, 5, false, true).overlay
        assertTrue(overlay[2 * 5 + 2] != 0)
        assertEquals(0, overlay[0])
    }
    @Test(expected = IllegalArgumentException::class)
    fun rejectMismatchedDimensions() { analyzeMonitor(IntArray(4), 3, 3, false, false) }
}
