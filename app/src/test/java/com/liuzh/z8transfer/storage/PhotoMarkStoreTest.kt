package com.liuzh.z8transfer.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.liuzh.z8transfer.core.CameraPhoto
import com.liuzh.z8transfer.core.PhotoKind
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PhotoMarkStoreTest {
    @Test fun savedMarksSurviveNewStoreAndSessionButNotDifferentCardOrReplacement() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val photo = CameraPhoto("session", "camera", 1, "card", "CF", 1,
            "DCIM/100NZ8", "DSC_0001.NEF", 100, 1000, 1000, PhotoKind.RAW)
        PhotoMarkStore(context).write(setOf(photo.importKey))
        val restored = PhotoMarkStore(context).read()
        assertTrue(photo.copy(sessionId = "new-session", handle = 45).importKey in restored)
        assertFalse(photo.copy(storageIdentity = "another-card").importKey in restored)
        assertFalse(photo.copy(size = 101, takenAt = 2000).importKey in restored)
        PhotoMarkStore(context).write(emptySet())
        assertTrue(PhotoMarkStore(context).read().isEmpty())
    }
}
