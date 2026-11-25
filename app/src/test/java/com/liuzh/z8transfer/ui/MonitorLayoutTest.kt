package com.liuzh.z8transfer.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.liuzh.z8transfer.RemoteUiState
import com.liuzh.z8transfer.core.RemoteCapabilities
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w852dp-h393dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MonitorLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun landscapeKeepsImageAndShutterVisibleWhenOpeningParameters() {
        val bitmap = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888)
        compose.setContent { Z8Theme { RemoteCameraContent(RemoteUiState(open = true, frame = bitmap),
            RemoteCapabilities(true, true, true, true), {}, {}) } }
        compose.onNodeWithContentDescription("相机实时取景").assertIsDisplayed()
        compose.onNodeWithTag("remote-shutter").assertIsDisplayed()
        assertTrue(compose.onNodeWithTag("live-view-frame").fetchSemanticsNode().boundsInRoot.height > 80)
        compose.onNodeWithTag("remote-parameters").performScrollTo().performClick()
        compose.onNodeWithTag("shooting-parameters-panel").assertIsDisplayed()
        compose.onNodeWithContentDescription("相机实时取景").assertIsDisplayed()
        compose.onNodeWithTag("remote-shutter").assertIsDisplayed()
        assertTrue(compose.onNodeWithTag("live-view-frame").fetchSemanticsNode().boundsInRoot.height > 40)
    }
}
