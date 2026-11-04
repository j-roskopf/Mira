package com.joetr.mira.app

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import com.joetr.mira.domain.LocalDateKey
import com.joetr.mira.feature.compose.ComposeEntryScreen
import com.joetr.mira.media.CaptureKind
import com.joetr.mira.media.MediaRecorderController
import com.joetr.mira.media.RecordingState
import com.joetr.mira.media.VideoAspectRatio
import com.joetr.mira.media.VideoCaptureSettings
import com.joetr.mira.ui.theme.MiraTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class CapturePreviewLayoutDesktopTest {

    private class FakeRecorder(
        private val fillsViewport: Boolean,
        private val adaptsToOrientation: Boolean,
        private val aspectRatios: List<VideoAspectRatio> = emptyList(),
    ) : MediaRecorderController {
        override val state = MutableStateFlow<RecordingState>(RecordingState.Idle)
        override val supportsVideoSettings: Boolean = true
        override val supportedAspectRatios: List<VideoAspectRatio> = aspectRatios
        override val previewFillsViewport: Boolean = fillsViewport
        override val adaptsControlsToOrientation: Boolean = adaptsToOrientation

        override fun configureVideo(settings: VideoCaptureSettings) {}
        override suspend fun start(kind: CaptureKind) {}
        override suspend fun stop() {}
        override fun cancel() {}
        override fun release() {}
    }

    private fun ComposeUiTest.openCapture(recorder: MediaRecorderController) {
        setContent {
            MiraTheme(darkTheme = true, motionEnabled = false) {
                ComposeEntryScreen(
                    date = LocalDateKey("2026-11-02"),
                    initialBody = "",
                    recorder = recorder,
                    attachments = emptyList(),
                    sourceFor = { null },
                    onRemoveAttachment = {},
                    onBodyChange = {},
                    onDateClick = {},
                    onCaptureFinished = {},
                    onMediaPicked = {},
                    onSave = {},
                    onCancel = {},
                    initialCapture = CaptureKind.VIDEO,
                )
            }
        }
    }

    /** Turning the phone sideways must leave room for the camera, not only the control dock. */
    @Test
    fun landscapeKeepsThePreviewVisible() = runDesktopComposeUiTest(width = 900, height = 400) {
        openCapture(FakeRecorder(fillsViewport = true, adaptsToOrientation = true))
        val bounds = onNodeWithTag("capture-preview").getUnclippedBoundsInRoot()
        assertTrue("preview collapsed to ${bounds.width}", bounds.width.value > 200f)
        assertTrue("preview has no height", bounds.height.value > 0f)
    }

    /** Mobile landscape keeps its controls on the right, with the shutter centred beside them. */
    @Test
    fun landscapeControlsClingToTheRightWithACentredShutter() = runDesktopComposeUiTest(width = 900, height = 400) {
        openCapture(
            FakeRecorder(
                fillsViewport = true,
                adaptsToOrientation = true,
                aspectRatios = VideoAspectRatio.entries,
            ),
        )
        val settings = onNodeWithTag("capture-settings", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val shutter = onNodeWithTag("capture-shutter", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("settings are not on the right edge: ${settings.right}", settings.right.value > 860f)
        assertTrue("shutter is not left of the settings", shutter.right.value <= settings.left.value)
        val settingsMiddle = (settings.top.value + settings.bottom.value) / 2f
        val shutterMiddle = (shutter.top.value + shutter.bottom.value) / 2f
        assertEquals("shutter is not centred beside the settings", settingsMiddle, shutterMiddle, 2f)
    }

    /** The desktop viewfinder must match the selected output shape. */
    @Test
    fun squareSelectionMakesTheViewfinderSquare() = runDesktopComposeUiTest(width = 1000, height = 800) {
        openCapture(
            FakeRecorder(
                fillsViewport = false,
                adaptsToOrientation = false,
                aspectRatios = VideoAspectRatio.entries,
            ),
        )
        onNodeWithContentDescription("Shape: Square 1:1").performClick()
        val bounds = onNodeWithTag("capture-preview").getUnclippedBoundsInRoot()
        assertEquals("square viewfinder was ${bounds.width} x ${bounds.height}", bounds.width.value, bounds.height.value, 1f)
        val centre = (bounds.left.value + bounds.right.value) / 2f
        assertEquals("preview is not horizontally centred", 500f, centre, 2f)
    }
}
