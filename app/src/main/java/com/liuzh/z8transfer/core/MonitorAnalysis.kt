package com.liuzh.z8transfer.core

import kotlin.math.abs

data class MonitorAnalysis(val histogram: IntArray, val overlay: IntArray, val brightFraction: Float)

/** Preview RGB analysis, independent of camera properties and the sensor's RAW exposure. */
fun analyzeMonitor(pixels: IntArray, width: Int, height: Int, zebra: Boolean, peaking: Boolean,
    zebraThreshold: Int = 235, edgeThreshold: Int = 38): MonitorAnalysis {
    require(width in 1..640 && height in 1..640 && pixels.size == width * height)
    require(zebraThreshold in 0..255 && edgeThreshold in 1..255)
    val histogram = IntArray(64)
    val luminance = IntArray(pixels.size)
    val overlay = IntArray(pixels.size)
    var bright = 0
    pixels.forEachIndexed { index, pixel ->
        val r = pixel ushr 16 and 255
        val g = pixel ushr 8 and 255
        val b = pixel and 255
        val value = (77 * r + 150 * g + 29 * b) ushr 8
        luminance[index] = value
        histogram[value ushr 2]++
        if (value >= zebraThreshold) {
            bright++
            if (zebra && (index % width + index / width) % 10 < 5) overlay[index] = 0xB3FFF176.toInt()
        }
    }
    if (peaking && width > 2 && height > 2) {
        for (y in 1 until height - 1) for (x in 1 until width - 1) {
            val index = y * width + x
            val edge = maxOf(abs(luminance[index + 1] - luminance[index - 1]),
                abs(luminance[index + width] - luminance[index - width]))
            if (edge >= edgeThreshold) overlay[index] = 0xE6FF5252.toInt()
        }
    }
    return MonitorAnalysis(histogram, overlay, bright.toFloat() / pixels.size)
}
