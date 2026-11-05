package com.joetr.mira.app

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.joetr.mira.domain.Attachment
import com.joetr.mira.domain.AttachmentKind
import com.joetr.mira.domain.LocalDateKey
import com.joetr.mira.feature.compose.ComposeEntryScreen
import com.joetr.mira.media.CaptureKind
import com.joetr.mira.media.createMediaRecorderController
import com.joetr.mira.media.supportsCapture
import com.joetr.mira.ui.theme.MiraTheme
import io.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class AttachmentLayoutDesktopTest {
    @Test fun mixedAttachmentsWide() = verifyAttachments(1000, 800, true)
    @Test fun mixedAttachmentsCompact() = verifyAttachments(390, 780, false)

    @Test fun sheetOpensOnArrivalFromMedia() = runDesktopComposeUiTest(width = 1000, height = 800) {
        val attachments = listOf(
            Attachment("photo", AttachmentKind.IMAGE, "", mime = "image/jpeg"),
            Attachment("audio", AttachmentKind.AUDIO, "", mime = "audio/mp4", durationMs = 4000),
        )
        setContent {
            MiraTheme(darkTheme = false, motionEnabled = false) {
                val recorder = remember { createMediaRecorderController() }
                ComposeEntryScreen(
                    date = LocalDateKey("2026-10-31"),
                    initialBody = "Reached from the media archive.",
                    recorder = recorder,
                    attachments = attachments,
                    sourceFor = { null },
                    onRemoveAttachment = {},
                    onBodyChange = {}, onDateClick = {}, onCaptureFinished = {}, onMediaPicked = {},
                    onSave = {}, onCancel = {},
                    initialAttachmentsOpen = true,
                )
            }
        }
        onNodeWithText("ATTACHMENTS · 2").assertIsDisplayed()
    }

    private fun verifyAttachments(width: Int, height: Int, dark: Boolean) =
        runDesktopComposeUiTest(width = width, height = height) {
            val attachments = mutableStateOf(listOf(
                Attachment("video", AttachmentKind.VIDEO, "", mime = "video/mp4", durationMs = 6000),
                Attachment("audio", AttachmentKind.AUDIO, "", mime = "audio/mp4", durationMs = 4000),
                Attachment("last", AttachmentKind.AUDIO, "", mime = "audio/mp4", durationMs = 9000),
            ))
            setContent {
                MiraTheme(darkTheme = dark, motionEnabled = false) {
                    val recorder = remember { createMediaRecorderController() }
                    ComposeEntryScreen(
                        date = LocalDateKey("2026-10-31"),
                        initialBody = "A note with three recordings.",
                        recorder = recorder,
                        attachments = attachments.value,
                        sourceFor = { null },
                        onRemoveAttachment = { removed ->
                            attachments.value = attachments.value.filterNot { it.id == removed.id }
                        },
                        onBodyChange = {}, onDateClick = {}, onCaptureFinished = {}, onMediaPicked = {},
                        onSave = {}, onCancel = {},
                    )
                }
            }
            // Capture controls only exist when FFmpeg is bundled; CI runs without it.
            val captureSupported = supportsCapture(CaptureKind.AUDIO)
            onNodeWithContentDescription("View attachments").assertIsDisplayed()
            onNodeWithContentDescription("Save entry").assertIsDisplayed()
            if (captureSupported) onNodeWithContentDescription("Record audio").assertIsDisplayed()

            onNodeWithContentDescription("View attachments").performClick()
            onNodeWithText("ATTACHMENTS · 3").assertIsDisplayed()
            onNodeWithText("1 video · 2 voice notes").assertIsDisplayed()
            onNodeWithText("Video 1").assertIsDisplayed()
            onAllNodes(isRoot()).onLast().captureRoboImage("src/desktopTest/resources/roborazzi/Attachments_${width}.png")

            onNodeWithContentDescription("Remove Voice note 3").performScrollTo().assertIsDisplayed().performClick()
            runOnIdle { assertEquals(listOf("video", "audio"), attachments.value.map { it.id }) }
            onNodeWithContentDescription("Remove Voice note 2").performScrollTo().assertIsDisplayed()
        }
}
