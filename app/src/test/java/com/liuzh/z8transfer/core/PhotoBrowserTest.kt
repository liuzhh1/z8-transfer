package com.liuzh.z8transfer.core

import org.junit.Assert.*
import org.junit.Test

class PhotoBrowserTest {
    private fun file(i: Int, name: String = "DSC_$i.NEF") = CameraPhoto("s", "cam", 1, "volume", "card", i,
        "DCIM/100", name, i * 100L, i * 1000L, 0, PhotoKind.fromName(name)!!)

    @Test fun allSortOrdersUseExpectedDirectionAndStableTies() {
        val a = file(1, "b.NEF"); val b = file(2, "A.JPG"); val c = file(3, "c.NEF")
        val files = listOf(c, a, b)
        assertEquals(listOf(c,b,a), PhotoSort.NEWEST.apply(files))
        assertEquals(listOf(a,b,c), PhotoSort.OLDEST.apply(files))
        assertEquals(listOf(b,a,c), PhotoSort.NAME_ASC.apply(files))
        assertEquals(listOf(c,a,b), PhotoSort.NAME_DESC.apply(files))
        assertEquals(listOf(c,b,a), PhotoSort.SIZE_DESC.apply(files))
        assertEquals(listOf(a,b,c), PhotoSort.SIZE_ASC.apply(files))
        assertEquals(PhotoSort.NEWEST.apply(files), PhotoSort.NEWEST.apply(files.reversed()))
    }
    @Test fun selectingDraggingBackAndDeselectingPreserveUnrelatedSelection() {
        val files = (1..10).map { file(it) }
        val original = setOf(files[9].key)
        assertEquals((1..4).map { files[it].key }.toSet() + original, rangeSelection(files, original, 1, 4))
        assertEquals(setOf(files[1].key, files[2].key) + original, rangeSelection(files, original, 1, 2))
        val all = files.map { it.key }.toSet()
        assertEquals(all - setOf(files[1].key, files[2].key, files[3].key), rangeSelection(files, all, 3, 1))
        assertEquals(original, rangeSelection(files, original, -1, 3))
    }
    @Test fun jpegCompanionsNeverCrossCardDirectorySessionOrVolumeAndDeduplicate() {
        val raw = file(1, "DSC_0001.NEF"); val jpeg = file(2, "dsc_0001.JpEg")
        val others = listOf(jpeg.copy(storageId = 2), jpeg.copy(folder = "DCIM/101"),
            jpeg.copy(sessionId = "old"), jpeg.copy(storageIdentity = "replacement"),
            jpeg.copy(cameraIdentity = "other"), file(3, "DSC_0002.JPG"), file(4, "DSC_0001.MP4"))
        assertEquals(listOf(jpeg), companionJpegs(listOf(raw), listOf(jpeg, jpeg) + others))
        assertTrue(companionJpegs(listOf(raw, jpeg), listOf(jpeg)).isEmpty())
        assertTrue(companionJpegs(listOf(jpeg), listOf(raw, jpeg)).isEmpty())
    }
}
