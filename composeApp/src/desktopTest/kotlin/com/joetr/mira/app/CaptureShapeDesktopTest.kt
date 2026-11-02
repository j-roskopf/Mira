package com.joetr.mira.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
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
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class CaptureShapeDesktopTest {

    private class FakeRecorder : MediaRecorderController {
        override val state = MutableStateFlow<RecordingState>(RecordingState.Idle)
        override val supportsVideoSettings: Boolean = true
        val configured = mutableListOf<VideoCaptureSettings>()

        override fun configureVideo(settings: VideoCaptureSettings) {
            configured += settings
        }

        override suspend fun start(kind: CaptureKind) {}
        override suspend fun stop() {}
        override fun cancel() {}
        override fun release() {}
    }

    @Test
    fun choosingSquareAppliesTheShapeToTheRecorder() = runDesktopComposeUiTest {
        val recorder = FakeRecorder()
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
                    initialCapture = CaptureKind.PHOTO,
                )
            }
        }
        onNodeWithContentDescription("Shape: Square 1:1").performClick()
        runOnIdle {
            assertEquals(VideoAspectRatio.SQUARE, recorder.configured.last().aspectRatio)
        }
    }
}
