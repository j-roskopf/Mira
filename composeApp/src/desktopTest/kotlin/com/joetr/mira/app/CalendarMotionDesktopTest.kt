package com.joetr.mira.app

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import io.github.takahirom.roborazzi.captureRoboImage
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.joetr.mira.domain.LocalDateKey
import com.joetr.mira.domain.MonthKey
import com.joetr.mira.feature.calendar.CalendarScreen
import com.joetr.mira.ui.theme.MiraTheme
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class CalendarMotionDesktopTest {
    @Test fun compactEditorSurface() = runDesktopComposeUiTest(width = 390, height = 844) {
        setContent {
            MiraTheme(darkTheme = true, motionEnabled = false) {
                val recorder = androidx.compose.runtime.remember {
                    com.joetr.mira.media.createMediaRecorderController()
                }
                androidx.compose.runtime.DisposableEffect(recorder) {
                    onDispose { recorder.release() }
                }
                com.joetr.mira.navigation.AdaptiveScaffold(
                    widthDp = 390,
                    selected = com.joetr.mira.navigation.TopLevelDestination.Calendar,
                    onNavigate = {},
                    editorSurface = true,
                ) {
                    com.joetr.mira.feature.compose.ComposeEntryScreen(
                        date = LocalDateKey("2026-10-31"), initialBody = "", recorder = recorder,
                        attachments = emptyList(), sourceFor = { null }, onRemoveAttachment = {},
                        onBodyChange = {}, onDateClick = {}, onCaptureFinished = {}, onMediaPicked = {},
                        onSave = {}, onCancel = {},
                    )
                }
            }
        }
        onRoot().captureRoboImage(java.io.File("src/desktopTest/resources/roborazzi/EditorShell_compact_dark.png").absolutePath)
    }

    @Test fun rapidMonthChangesKeepFinalDatesInteractive() = runDesktopComposeUiTest(width = 390, height = 844) {
        val month = mutableStateOf(MonthKey("2026-10"))
        var selected: LocalDateKey? = null
        setContent {
            MiraTheme {
                CalendarScreen(month.value, LocalDateKey("2026-10-31"), emptyMap(), null,
                    { month.value = it }, { selected = it }, {}, {}, {}, showComposeButton = false)
            }
        }
        mainClock.autoAdvance = false
        onNodeWithText("Nov", ignoreCase = true).performClick()
        mainClock.advanceTimeBy(64)
        onNodeWithText("Feb", ignoreCase = true).performClick()
        mainClock.advanceTimeBy(64)
        onNodeWithText("Dec", ignoreCase = true).performClick()
        mainClock.autoAdvance = true
        waitForIdle()
        onNodeWithText("DECEMBER").assertIsDisplayed()
        onNodeWithText("31", useUnmergedTree = true).performClick()
        runOnIdle { assertEquals(LocalDateKey("2026-12-31"), selected) }
    }

    @Test fun reducedMotionReplacesMonthWithoutOutgoingDates() = runDesktopComposeUiTest(width = 390, height = 844) {
        val month = mutableStateOf(MonthKey("2026-01"))
        setContent {
            MiraTheme(motionEnabled = false) {
                CalendarScreen(month.value, LocalDateKey("2026-01-01"), emptyMap(), null,
                    { month.value = it }, {}, {}, {}, {}, showComposeButton = false)
            }
        }
        onNodeWithText("Feb", ignoreCase = true).performClick()
        onNodeWithText("FEBRUARY").assertIsDisplayed()
        onNodeWithText("31", useUnmergedTree = true).assertDoesNotExist()
    }
}
