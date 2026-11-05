package com.joetr.mira.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.joetr.mira.domain.Attachment
import com.joetr.mira.domain.AttachmentKind
import com.joetr.mira.domain.Entry
import com.joetr.mira.domain.LocalDateKey
import com.joetr.mira.domain.MonthKey
import com.joetr.mira.feature.calendar.CalendarDayMedia
import com.joetr.mira.feature.calendar.CalendarScreen
import com.joetr.mira.feature.day.DayEntriesSheet
import com.joetr.mira.media.MediaSource
import com.joetr.mira.ui.theme.MiraTheme
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class CalendarMediaDesktopTest {
    @Test fun photoOnADayRendersAsItsTile() = runDesktopComposeUiTest(width = 390, height = 844) {
        val date = LocalDateKey("2026-10-12")
        val image = Attachment(
            id = "calendar-photo",
            kind = AttachmentKind.IMAGE,
            storagePath = "users/owner/media/calendar-photo.png",
            mime = "image/png",
        )
        val png = BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB).also { bitmap ->
            bitmap.createGraphics().apply {
                color = java.awt.Color(0x33, 0x66, 0x99)
                fillRect(0, 0, bitmap.width, bitmap.height)
                dispose()
            }
        }.let { bitmap ->
            ByteArrayOutputStream().also { ImageIO.write(bitmap, "png", it) }.toByteArray()
        }

        setContent {
            MiraTheme(darkTheme = false, motionEnabled = false) {
                CalendarScreen(
                    month = MonthKey("2026-10"),
                    today = LocalDateKey("2026-10-31"),
                    activity = mapOf(date to 1),
                    selected = null,
                    onMonthChange = {},
                    onDayClick = {},
                    onYearClick = {},
                    onComposeClick = {},
                    showComposeButton = false,
                    dayMedia = mapOf(
                        date to CalendarDayMedia(image = image, hasVideo = true, hasAudio = true),
                    ),
                    thumbnailFor = { MediaSource.Bytes(png, "image/png") },
                )
            }
        }

        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithContentDescription("Photo on day 12").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithContentDescription("Photo on day 12").assertIsDisplayed()
    }

    @Test fun daySheetPreviewsAnEntrysMedia() = runDesktopComposeUiTest(width = 390, height = 844) {
        val date = LocalDateKey("2026-10-12")
        val png = BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB).also { bitmap ->
            bitmap.createGraphics().apply {
                color = java.awt.Color(0x33, 0x66, 0x99)
                fillRect(0, 0, bitmap.width, bitmap.height)
                dispose()
            }
        }.let { bitmap ->
            ByteArrayOutputStream().also { ImageIO.write(bitmap, "png", it) }.toByteArray()
        }
        val attachments = (1..3).map { index ->
            Attachment(
                id = "day-photo-$index",
                kind = AttachmentKind.IMAGE,
                storagePath = "users/owner/media/day-photo-$index.png",
                mime = "image/png",
            )
        }
        val entry = Entry(
            id = "day-media-entry",
            ownerId = "owner",
            localDate = date,
            createdAt = 1_793_000_000_000,
            createdTz = "America/Chicago",
            updatedAt = 1_793_000_000_000,
            body = "Three frames from the walk.",
            attachments = attachments,
        )
        var openedForMedia: Entry? = null
        var openedEntry: Entry? = null

        setContent {
            MiraTheme(darkTheme = false, motionEnabled = false) {
                DayEntriesSheet(
                    date = date,
                    entries = listOf(entry),
                    onDismiss = {},
                    onEntryClick = { openedEntry = it },
                    onMediaClick = { openedForMedia = it },
                    onCompose = {},
                    thumbnailFor = { MediaSource.Bytes(png, "image/png") },
                )
            }
        }

        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithText("+1").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText("+1").assertIsDisplayed()
        onAllNodesWithContentDescription("Open photo").onFirst().performClick()
        runOnIdle {
            org.junit.Assert.assertEquals(entry, openedForMedia)
            org.junit.Assert.assertNull(openedEntry)
        }
    }
}
