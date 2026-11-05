package com.joetr.mira.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.joetr.mira.domain.Entry
import com.joetr.mira.domain.LocalDateKey
import com.joetr.mira.domain.MonthKey
import com.joetr.mira.feature.calendar.CalendarScreen
import com.joetr.mira.feature.day.DayEntriesSheet
import com.joetr.mira.navigation.AdaptiveScaffold
import com.joetr.mira.navigation.TopLevelDestination
import com.joetr.mira.ui.layout.LocalBottomBarHeight
import com.joetr.mira.ui.layout.LocalWindowChromeInsets
import com.joetr.mira.ui.theme.MiraAccent
import com.joetr.mira.ui.theme.MiraTheme
import io.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class CalendarDesignDesktopTest {
    @Test fun compactShell() = shell(390, 844, false, MiraAccent.EMBER)
    @Test fun compactDarkShell() = shell(390, 844, true, MiraAccent.IRIS)
    @Test fun desktopShell() = shell(1400, 950, true, MiraAccent.COBALT)
    @Test fun compactBoneShell() = shell(390, 844, false, MiraAccent.BONE)

    private fun shell(width: Int, height: Int, dark: Boolean, accent: MiraAccent) =
        runDesktopComposeUiTest(width = width, height = height) {
            var composed = false
            setContent {
                MiraTheme(darkTheme = dark, accent = accent, motionEnabled = false) {
                    CompositionLocalProvider(LocalWindowChromeInsets provides WindowInsets(top = if (width > 600) 52.dp else 0.dp)) {
                        AdaptiveScaffold(width, TopLevelDestination.Calendar, {},
                            onCompose = { composed = true }, accentCanvas = true) {
                            CalendarScreen(
                                MonthKey("2026-10"), LocalDateKey("2026-10-31"),
                                mapOf(LocalDateKey("2026-10-12") to 1, LocalDateKey("2026-10-20") to 3),
                                null, {}, {}, {}, { composed = true },
                                modifier = Modifier.padding(bottom = LocalBottomBarHeight.current),
                                showComposeButton = width >= 600,
                            )
                        }
                    }
                }
            }
            onRoot().captureRoboImage(File("src/desktopTest/resources/roborazzi/Shell_${width}_${accent.name}.png").absolutePath)
            onNodeWithContentDescription("New entry").performClick()
            runOnIdle { assertTrue(composed) }
        }

    @Test fun selectingDateOpensItsEntries() = runDesktopComposeUiTest(width = 390, height = 844) {
        val selected = mutableStateOf<LocalDateKey?>(null)
        val date = LocalDateKey("2026-10-12")
        val entry = Entry(id = "day-entry", ownerId = "owner", localDate = date,
            createdAt = 1_793_000_000_000, createdTz = "America/Chicago",
            updatedAt = 1_793_000_000_000, body = "A walk by the lake.")
        var opened: Entry? = null
        setContent {
            MiraTheme(darkTheme = false, motionEnabled = false) {
                CalendarScreen(MonthKey("2026-10"), date, mapOf(date to 1), selected.value,
                    {}, { selected.value = it }, {}, {}, showComposeButton = false)
                selected.value?.let { day ->
                    DayEntriesSheet(day, listOf(entry), { selected.value = null },
                        { opened = it }, {})
                }
            }
        }
        // Neighbouring months are composed offscreen for prefetching, so scope the day to the
        // page whose month is on screen.
        onNode(
            hasText("12") and hasAnyAncestor(hasTestTag("calendar-page-2026-10")),
            useUnmergedTree = true,
        ).performClick()
        onNodeWithText("A walk by the lake.").assertIsDisplayed()
        onAllNodes(isRoot()).onLast().captureRoboImage(File("src/desktopTest/resources/roborazzi/DaySheet_compact.png").absolutePath)
        onNodeWithText("A walk by the lake.").performClick()
        runOnIdle { org.junit.Assert.assertEquals(entry, opened) }
    }

    @Test fun daySheetLoadsRetriesAndDismisses() = runDesktopComposeUiTest(width = 900, height = 900) {
        val loading = mutableStateOf(true)
        val error = mutableStateOf<String?>(null)
        val visible = mutableStateOf(true)
        var retried = false
        var composed = false
        setContent {
            MiraTheme(darkTheme = true, motionEnabled = false) {
                Box(Modifier.fillMaxSize()) {
                    if (visible.value) DayEntriesSheet(
                        LocalDateKey("2026-10-31"), emptyList(),
                        onDismiss = { visible.value = false }, onEntryClick = {},
                        onCompose = { composed = true }, loading = loading.value,
                        error = error.value, onRetry = { retried = true },
                    )
                }
            }
        }
        onNodeWithText("Loading entries…").assertIsDisplayed()
        runOnIdle { loading.value = false; error.value = "Could not load entries" }
        onNodeWithText("Try again").performClick()
        runOnIdle { assertTrue(retried); error.value = null }
        onNodeWithText("Nothing yet.").assertIsDisplayed()
        onNodeWithContentDescription("New entry").performClick()
        runOnIdle { assertTrue(composed) }
        onNodeWithContentDescription("Close day").performClick()
        runOnIdle { assertTrue(!visible.value) }
    }
}
