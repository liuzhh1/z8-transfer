package com.liuzh.z8transfer.core

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class NikonFocusMetadataTest {
    private fun af(little: Boolean = true): ByteArray = ByteArray(75).apply {
        "0400".toByteArray().copyInto(this); this[7] = 1; this[74] = 1
        val b = ByteBuffer.wrap(this).order(if (little) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
        b.putShort(62, 8000.toShort()); b.putShort(64, 6000.toShort())
        b.putShort(66, 2000.toShort()); b.putShort(68, 3000.toShort())
        b.putShort(70, 800.toShort()); b.putShort(72, 600.toShort())
    }
    private fun tiff(little: Boolean = true, orientation: Int = 1): ByteArray {
        val b = ByteBuffer.allocate(220).order(if (little) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
        b.putShort(if (little) 0x4949.toShort() else 0x4d4d.toShort()).putShort(42).putInt(8)
        b.putShort(2)
        b.putShort(0x112).putShort(3).putInt(1).putShort(orientation.toShort()).putShort(0)
        b.putShort(0x8769.toShort()).putShort(4).putInt(1).putInt(40)
        b.position(40); b.putShort(1).putShort(0x927c.toShort()).putShort(7).putInt(150).putInt(60)
        b.position(60); b.put(byteArrayOf(78,105,107,111,110,0,2,0,0,0))
        b.putShort(if (little) 0x4949.toShort() else 0x4d4d.toShort()).putShort(42).putInt(8)
        b.putShort(1).putShort(0xb7).putShort(7).putInt(75).putInt(26)
        b.position(96); b.put(af(little))
        return b.array()
    }
    private fun read(bytes: ByteArray): PhotoMetadata {
        val f = File.createTempFile("nikon-focus", ".dat")
        return try { f.writeBytes(bytes); NikonFocusMetadata.read(f) } finally { f.delete() }
    }
    @Test fun readsNikonDirectoriesInBothByteOrdersAndJpegExif() {
        for (little in listOf(true, false)) {
            val tiff = tiff(little)
            val focus = read(tiff).focus!!
            assertEquals(.2f, focus.left, .0001f); assertEquals(.3f, focus.right, .0001f)
            assertEquals(.45f, focus.top, .0001f); assertTrue(focus.inFocus)
            val length = tiff.size + 8
            val jpeg = byteArrayOf(-1,-40,-1,-31,(length shr 8).toByte(),length.toByte(),69,120,105,102,0,0) + tiff + byteArrayOf(-1,-39)
            assertEquals(focus, read(jpeg).focus)
        }
    }
    @Test fun embeddedJpegIsResolvedFromTiffPreviewDirectory() {
        val bytes = tiff().copyOf(224)
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(34, 180); b.position(180); b.putShort(2)
        b.putShort(0x201).putShort(4).putInt(1).putInt(220)
        b.putShort(0x202).putShort(4).putInt(1).putInt(4)
        b.position(220); b.put(byteArrayOf(-1,-40,-1,-39))
        val metadata = read(bytes)
        assertEquals(EmbeddedPreview(220, 4), metadata.preview)
        assertNotNull(metadata.focus)
    }

    @Test fun portraitAndMirroredOrientationsTransformActualCoordinates() {
        val portrait = read(tiff(orientation = 6))
        assertEquals(6, portrait.orientation)
        assertEquals(.45f, portrait.focus!!.left, .0001f)
        assertEquals(.2f, portrait.focus.top, .0001f)
        val mirror = read(tiff(orientation = 2)).focus!!
        assertEquals(.7f, mirror.left, .0001f)
    }
    @Test fun missingCoordinatesUnknownVersionsTruncatedAndMaliciousInputsReturnNoBox() {
        assertNull(NikonFocusMetadata.decodeAf(af().apply { this[7] = 0 }, true, 1))
        assertNull(NikonFocusMetadata.decodeAf(af().apply { this[0] = 49 }, true, 1))
        assertNull(NikonFocusMetadata.decodeAf(af().copyOf(30), true, 1))
        assertNull(read(byteArrayOf(1,2,3)).focus)
        assertNull(read(tiff().apply { ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).putInt(50, Int.MAX_VALUE) }).focus)
        assertNull(read(byteArrayOf(-1,-40,-1,-31,127,-1,69,120,105,102,0,0)).focus)
    }
}
