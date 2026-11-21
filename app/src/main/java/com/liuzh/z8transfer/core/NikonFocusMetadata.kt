package com.liuzh.z8transfer.core

import java.io.File
import java.io.RandomAccessFile

/** Normalized coordinates in the oriented preview image. */
data class FocusRegion(val left: Float, val top: Float, val right: Float, val bottom: Float, val inFocus: Boolean)
data class EmbeddedPreview(val offset: Long, val length: Int)
data class PhotoMetadata(val orientation: Int = 1, val focus: FocusRegion? = null, val preview: EmbeddedPreview? = null)

/** Reads only bounded EXIF/TIFF directories; never searches image pixels for byte signatures.
 * Nikon AFInfo2 0400 layout: https://exiftool.org/TagNames/Nikon.html#AFInfo2V0400
 * Unknown layouts / point masks without explicit coordinates deliberately return no rectangle.
 */
object NikonFocusMetadata {
    fun read(file: File): PhotoMetadata = runCatching {
        RandomAccessFile(file, "r").use { input ->
            var start = 0L
            var end = input.length()
            if (input.length() < 8) return@use PhotoMetadata()
            if (input.readUnsignedShort() == 0xffd8) {
                var found = false
                while (input.filePointer + 4 <= input.length()) {
                    if (input.readUnsignedByte() != 0xff) break
                    var marker = input.readUnsignedByte()
                    while (marker == 0xff) marker = input.readUnsignedByte()
                    if (marker == 0xda || marker == 0xd9) break
                    if (marker == 0x01 || marker in 0xd0..0xd7) continue
                    val length = input.readUnsignedShort()
                    val payload = input.filePointer
                    if (length < 2 || payload + length - 2 > input.length()) break
                    if (marker == 0xe1 && length >= 8) {
                        val signature = ByteArray(6).also(input::readFully)
                        if (signature.contentEquals(byteArrayOf(69, 120, 105, 102, 0, 0))) {
                            start = payload + 6; end = payload + length - 2; found = true; break
                        }
                    }
                    input.seek(payload + length - 2)
                }
                if (!found) return@use PhotoMetadata()
            }
            val tiff = Tiff(input, start, end)
            val root = tiff.directory(tiff.first)
            val orientation = root[0x112]?.let(tiff::shortValue)?.takeIf { it in 1..8 } ?: 1
            var embedded = tiff.preview(root)
            var next = tiff.next(tiff.first)
            val seen = mutableSetOf<Long>()
            while (embedded == null && next != 0L && seen.size < 5 && seen.add(next)) {
                embedded = tiff.preview(tiff.directory(next)); next = tiff.next(next)
            }
            fun metadata() = PhotoMetadata(orientation, preview = embedded)
            val exif = root[0x8769]?.let(tiff::longValue) ?: return@use metadata()
            val maker = tiff.directory(exif)[0x927c] ?: return@use metadata()
            val location = tiff.data(maker, 18) ?: return@use metadata()
            input.seek(location)
            val header = ByteArray(10).also(input::readFully)
            if (!header.copyOfRange(0, 6).contentEquals(byteArrayOf(78, 105, 107, 111, 110, 0)) || header[6].toInt() != 2) {
                return@use metadata()
            }
            val nikon = Tiff(input, location + 10, minOf(end, location + maker.count))
            val notes = nikon.directory(nikon.first)
            if (embedded == null) notes[0x11]?.let(nikon::longValue)?.let { offset ->
                embedded = nikon.preview(nikon.directory(offset), end)
            }
            val af = notes[0xb7] ?: return@use metadata()
            val afStart = nikon.data(af, 75) ?: return@use metadata()
            input.seek(afStart)
            val bytes = ByteArray(75).also(input::readFully)
            PhotoMetadata(orientation, decodeAf(bytes, nikon.little, orientation), embedded)
        }
    }.getOrDefault(PhotoMetadata())

    internal fun decodeAf(bytes: ByteArray, little: Boolean, orientation: Int): FocusRegion? {
        if (bytes.size < 75 || bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) != "0400" || bytes[7].toInt() != 1) return null
        fun u16(at: Int): Int {
            val a = bytes[at].toInt() and 255; val b = bytes[at + 1].toInt() and 255
            return if (little) a or (b shl 8) else (a shl 8) or b
        }
        val width = u16(62); val height = u16(64)
        val x = u16(66); val y = u16(68); val w = u16(70); val h = u16(72)
        if (width == 0 || height == 0 || x !in 0..width || y !in 0..height || w !in 1..width || h !in 1..height) return null
        val left = ((x - w / 2f) / width).coerceIn(0f, 1f)
        val right = ((x + w / 2f) / width).coerceIn(0f, 1f)
        val top = ((y - h / 2f) / height).coerceIn(0f, 1f)
        val bottom = ((y + h / 2f) / height).coerceIn(0f, 1f)
        fun rotate(x: Float, y: Float): Pair<Float, Float> = when (orientation) {
            2 -> 1f - x to y; 3 -> 1f - x to 1f - y; 4 -> x to 1f - y
            5 -> y to x; 6 -> 1f - y to x; 7 -> 1f - y to 1f - x; 8 -> y to 1f - x
            else -> x to y
        }
        val corners = listOf(rotate(left, top), rotate(right, top), rotate(left, bottom), rotate(right, bottom))
        return FocusRegion(corners.minOf { it.first }, corners.minOf { it.second }, corners.maxOf { it.first },
            corners.maxOf { it.second }, bytes[74].toInt() == 1)
    }

    private data class Entry(val type: Int, val count: Long, val valueAt: Long)
    private class Tiff(val input: RandomAccessFile, val base: Long, val end: Long) {
        val little: Boolean
        val first: Long
        init {
            require(base >= 0 && base + 8 <= end && end <= input.length())
            input.seek(base)
            little = when (input.readUnsignedShort()) { 0x4949 -> true; 0x4d4d -> false; else -> error("Not TIFF") }
            require(short() == 42)
            first = long()
        }
        fun short(): Int { val a = input.readUnsignedByte(); val b = input.readUnsignedByte(); return if (little) a or (b shl 8) else (a shl 8) or b }
        fun long(): Long { val a = short().toLong(); val b = short().toLong(); return if (little) a or (b shl 16) else (a shl 16) or b }
        fun directory(offset: Long): Map<Int, Entry> {
            require(offset >= 8 && base + offset + 2 <= end)
            input.seek(base + offset)
            val count = short()
            require(count <= 1024 && base + offset + 2 + count * 12L <= end)
            return buildMap {
                repeat(count) {
                    val tag = short(); val type = short(); val n = long(); val at = input.filePointer
                    put(tag, Entry(type, n, at)); input.seek(at + 4)
                }
            }
        }
        fun next(offset: Long): Long {
            input.seek(base + offset)
            val n = short()
            val at = base + offset + 2 + n * 12L
            if (n > 1024 || at + 4 > end) return 0
            input.seek(at); return long()
        }
        fun preview(entries: Map<Int, Entry>, imageEnd: Long = end): EmbeddedPreview? {
            val offset = entries[0x201]?.let(::longValue) ?: return null
            val length = entries[0x202]?.let(::longValue) ?: return null
            val start = base + offset
            if (length !in 1..32L * 1024 * 1024 || start < base || start + length > imageEnd) return null
            return EmbeddedPreview(start, length.toInt())
        }
        fun shortValue(e: Entry): Int? = if (e.type == 3 && e.count == 1L) { input.seek(e.valueAt); short() } else null
        fun longValue(e: Entry): Long? = if (e.type == 4 && e.count == 1L) { input.seek(e.valueAt); long() } else null
        fun data(e: Entry, minimum: Int): Long? {
            if (e.type != 7 || e.count < minimum || e.count > 262144) return null
            input.seek(e.valueAt)
            val offset = base + long()
            return offset.takeIf { it >= base && it + e.count <= end }
        }
    }
}
