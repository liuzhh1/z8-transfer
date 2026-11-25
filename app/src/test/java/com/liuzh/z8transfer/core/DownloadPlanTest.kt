package com.liuzh.z8transfer.core

import org.junit.Assert.*
import org.junit.Test

class DownloadPlanTest {
    private fun photo(handle: Int, name: String) = CameraPhoto("session", "z8", 1, "card-a", "CF", handle,
        "DCIM/100NZ8", name, 100, 1000, 1000, PhotoKind.fromName(name)!!)
    private val raw = photo(1, "DSC_0001.NEF")
    private val jpeg = photo(2, "DSC_0001.JPG")

    @Test fun pairFromEitherSideAndDeduplicateSelections() {
        val files = listOf(raw, jpeg)
        assertEquals(files, planDownload(listOf(raw), files, RawJpegMode.PAIR).files)
        assertEquals(files, planDownload(listOf(jpeg), files, RawJpegMode.PAIR).files)
        assertEquals(files, planDownload(files + raw, files, RawJpegMode.PAIR).files)
    }

    @Test fun neverPairAcrossCameraCardDirectoryOrSession() {
        val unrelated = listOf(jpeg.copy(storageId = 2), jpeg.copy(storageIdentity = "another-card"),
            jpeg.copy(cameraIdentity = "another-z8"), jpeg.copy(folder = "DCIM/101NZ8"),
            jpeg.copy(sessionId = "stale-session"), jpeg.copy(name = "DSC_0002.JPG"))
        assertEquals(listOf(raw), planDownload(listOf(raw), listOf(raw) + unrelated, RawJpegMode.PAIR).files)
        assertEquals(1, planDownload(listOf(raw), listOf(raw) + unrelated, RawJpegMode.JPEG_ONLY).unmatchedPhotos)
    }

    @Test fun filtersOnlyRawJpegAndLeavesVideosAndHeifExplicitlySelected() {
        val video = photo(3, "CLIP.MP4")
        val heif = photo(4, "DSC_0002.HIF")
        val files = listOf(raw, jpeg, video, heif)
        assertEquals(listOf(jpeg, video, heif), planDownload(listOf(raw, video, heif), files, RawJpegMode.JPEG_ONLY).files)
        assertEquals(listOf(raw), planDownload(listOf(jpeg), files, RawJpegMode.RAW_ONLY).files)
    }

    @Test fun missingCompanionIsReportedAndNotReplacedWithWrongFormat() {
        val result = planDownload(listOf(raw), listOf(raw), RawJpegMode.JPEG_ONLY)
        assertTrue(result.files.isEmpty())
        assertEquals(1, result.unmatchedPhotos)
    }

    @Test fun selectedModeIsDefaultAndStaleSelectionsCannotDownload() {
        assertEquals(listOf(raw), planDownload(listOf(raw), listOf(raw, jpeg), RawJpegMode.SELECTED).files)
        assertTrue(planDownload(listOf(raw.copy(sessionId = "old")), listOf(raw), RawJpegMode.PAIR).files.isEmpty())
    }

    @Test fun filenameCaseDoesNotPreventPairing() {
        val lower = jpeg.copy(name = "dsc_0001.jpeg")
        assertEquals(listOf(raw, lower), planDownload(listOf(raw), listOf(raw, lower), RawJpegMode.PAIR).files)
    }
}
