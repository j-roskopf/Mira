package com.joetr.mira.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.joetr.mira.domain.Attachment
import com.joetr.mira.domain.AttachmentKind
import com.joetr.mira.domain.LocalDateKey
import com.joetr.mira.domain.MonthKey
import com.joetr.mira.feature.calendar.CalendarDayMedia
import com.joetr.mira.feature.calendar.CalendarScreen
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
}
