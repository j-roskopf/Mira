package com.joetr.mira.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.joetr.mira.data.entries.SearchFilters
import com.joetr.mira.domain.Attachment
import com.joetr.mira.domain.AttachmentKind
import com.joetr.mira.domain.AttachmentState
import com.joetr.mira.domain.DayActivity
import com.joetr.mira.domain.Entry
import com.joetr.mira.domain.LocalDateKey
import com.joetr.mira.domain.MonthKey
import com.joetr.mira.domain.SessionState
import com.joetr.mira.domain.SyncState
import com.joetr.mira.domain.SyncStatus
import com.joetr.mira.domain.ThemeMode
import androidx.compose.runtime.remember
import com.joetr.mira.feature.compose.ComposeEntryScreen
import com.joetr.mira.media.createMediaRecorderController
import com.joetr.mira.feature.calendar.CalendarScreen
import com.joetr.mira.feature.calendar.YearScreen
import com.joetr.mira.feature.day.DayScreen
import com.joetr.mira.feature.search.SearchScreen
import com.joetr.mira.feature.settings.SettingsActions
import com.joetr.mira.feature.settings.SettingsScreen
import com.joetr.mira.feature.settings.SettingsUiState
import com.joetr.mira.feature.timeline.TimelineScreen
import com.joetr.mira.ui.layout.MiraContentWidth
import com.joetr.mira.ui.theme.MiraAccent
import com.joetr.mira.ui.theme.MiraTheme
import java.io.File
import io.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test

private val roboDir = File("src/desktopTest/resources/roborazzi")

/**
 * Screenshot coverage for every screen, in both themes.
 *
 * This is how a light-mode token that was never checked, or a type stack that renders differently
 * off Android, gets caught — DESIGN.md says light is derived and must be verified independently,
 * and this is where that verification lives.
 */
class MainScreensDesktopTest {

    @Test fun calendarDark() = capture("Calendar_dark", dark = true) { CalendarSample() }
    @Test fun calendarLight() = capture("Calendar_light", dark = false) { CalendarSample() }

    @Test fun calendarEmptyDark() =
        capture("CalendarEmpty_dark", dark = true) { CalendarSample(empty = true) }

    @Test fun calendarWideDark() =
        capture("CalendarWide_dark", dark = true, width = 1400, height = 950, contentWidth = 1200.dp) {
            CalendarSample()
        }

    @Test fun calendarWideEmptyLight() =
        capture("CalendarWideEmpty_light", dark = false, width = 1400, height = 950, contentWidth = 1200.dp) {
            CalendarSample(empty = true)
        }

    @Test fun calendarCompactDark() =
        capture("CalendarCompact_dark", dark = true, width = 390, height = 780) { CalendarSample() }

    @Test fun yearDark() = capture("Year_dark", dark = true) { YearSample() }
    @Test fun yearLight() = capture("Year_light", dark = false) { YearSample() }

    @Test fun yearWideDark() =
        capture("YearWide_dark", dark = true, width = 1400, height = 950, contentWidth = Dp.Infinity) {
            YearSample()
        }

    @Test fun yearWideLight() =
        capture("YearWide_light", dark = false, width = 1400, height = 950, contentWidth = Dp.Infinity) {
            YearSample()
        }

    @Test fun dayDark() = capture("Day_dark", dark = true) { DaySample() }
    @Test fun dayLight() = capture("Day_light", dark = false) { DaySample() }

    @Test fun dayEmptyDark() = capture("DayEmpty_dark", dark = true) { DaySample(empty = true) }

    @Test fun timelineDark() = capture("Timeline_dark", dark = true) { TimelineSample() }
    @Test fun timelineLight() = capture("Timeline_light", dark = false) { TimelineSample() }

    @Test fun timelineCompactDark() =
        capture("TimelineCompact_dark", dark = true, width = 390, height = 780) { TimelineSample() }

    @Test fun timelineCompactLight() =
        capture("TimelineCompact_light", dark = false, width = 390, height = 780) { TimelineSample() }

    @Test fun searchDark() = capture("Search_dark", dark = true) { SearchSample() }
    @Test fun searchLight() = capture("Search_light", dark = false) { SearchSample() }

    @Test fun composeEntryDark() = capture("ComposeEntry_dark", dark = true) { ComposeSample() }
    @Test fun composeEntryLight() = capture("ComposeEntry_light", dark = false) { ComposeSample() }

    @Test fun settingsDark() = capture("Settings_dark", dark = true) { SettingsSample() }
    @Test fun settingsLight() = capture("Settings_light", dark = false) { SettingsSample() }

    /** A near-neutral accent is where "accent vs. inkMuted" can collapse, so it gets its own shot. */
    @Test fun calendarBoneAccent() =
        capture("Calendar_bone", dark = true, accent = MiraAccent.BONE) { CalendarSample() }

    @OptIn(ExperimentalTestApi::class)
    private fun capture(
        name: String,
        dark: Boolean,
        accent: MiraAccent = MiraAccent.Default,
        width: Int = 900,
        height: Int = 1100,
        contentWidth: Dp = 720.dp,
        content: @Composable () -> Unit,
    ) = runDesktopComposeUiTest(width = width, height = height) {
        setContent {
            // Motion off: a staggered reveal mid-animation makes screenshots flaky.
            MiraTheme(darkTheme = dark, accent = accent, motionEnabled = false) {
                // Mirrors App.kt: without the cap, screenshots show a measure the app never
                // actually renders.
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MiraTheme.colors.canvas),
                ) {
                    MiraContentWidth(Modifier.fillMaxSize(), maxWidth = contentWidth) { content() }
                }
            }
        }
        onRoot().captureRoboImage(roboDir.resolve("$name.png").absolutePath)
    }
}

private val sampleDate = LocalDateKey("2026-10-30")
private val sampleMonth = MonthKey("2026-10")

private val sampleEntries = listOf(
    Entry(
        id = "entry-1",
        ownerId = "owner",
        localDate = sampleDate,
        createdAt = 1_793_000_000_000,
        createdTz = "America/Chicago",
        updatedAt = 1_793_000_000_000,
        body = "Walked the long way home because the light was doing that thing it does in late " +
            "October, where everything looks like it is remembering itself.",
        tags = listOf("walk"),
    ),
    Entry(
        id = "entry-2",
        ownerId = "owner",
        localDate = sampleDate,
        createdAt = 1_793_010_000_000,
        createdTz = "America/Chicago",
        updatedAt = 1_793_010_000_000,
        body = "Voice note from the car.",
        attachments = listOf(
            Attachment(
                id = "a1",
                kind = AttachmentKind.AUDIO,
                storagePath = "users/owner/media/entry-2/a1.m4a",
                mime = "audio/mp4",
                durationMs = 94_000,
                state = AttachmentState.UPLOADED,
            ),
            Attachment(
                id = "a2",
                kind = AttachmentKind.VIDEO,
                storagePath = "users/owner/media/entry-2/a2.mp4",
                mime = "video/mp4",
                durationMs = 12_000,
                state = AttachmentState.UPLOADING,
            ),
        ),
    ),
    Entry(
        id = "entry-3",
        ownerId = "owner",
        localDate = LocalDateKey("2026-09-14"),
        createdAt = 1_789_000_000_000,
        createdTz = "America/Chicago",
        updatedAt = 1_789_000_000_000,
        body = "First cold morning. Put the heavy blanket back on the bed.",
    ),
)

private val sampleActivity = mapOf(
    LocalDateKey("2026-10-01") to 1,
    LocalDateKey("2026-10-02") to 3,
    LocalDateKey("2026-10-05") to 2,
    LocalDateKey("2026-10-09") to 5,
    LocalDateKey("2026-10-12") to 1,
    LocalDateKey("2026-10-13") to 4,
    LocalDateKey("2026-10-19") to 2,
    LocalDateKey("2026-10-23") to 7,
    LocalDateKey("2026-10-26") to 1,
    LocalDateKey("2026-10-30") to 2,
)

@Composable
private fun CalendarSample(empty: Boolean = false) {
    CalendarScreen(
        month = sampleMonth,
        today = sampleDate,
        activity = if (empty) emptyMap() else sampleActivity,
        selected = if (empty) null else LocalDateKey("2026-10-23"),
        onMonthChange = {},
        onDayClick = {},
        onYearClick = {},
        onComposeClick = {},
    )
}

@Composable
private fun YearSample() {
    YearScreen(
        year = 2026,
        activity = sampleActivity.map { DayActivity(it.key, it.value) } +
            listOf(
                DayActivity(LocalDateKey("2026-03-04"), 2),
                DayActivity(LocalDateKey("2026-03-05"), 6),
                DayActivity(LocalDateKey("2026-07-21"), 3),
            ),
        onMonthClick = {},
        onBack = {},
        onYearChange = {},
    )
}

@Composable
private fun DaySample(empty: Boolean = false) {
    DayScreen(
        date = sampleDate,
        entries = if (empty) emptyList() else sampleEntries.take(2),
        onBack = {},
        onEntryClick = {},
        onCompose = {},
        onPreviousDay = {},
        onNextDay = {},
    )
}

@Composable
private fun TimelineSample() {
    TimelineScreen(entries = sampleEntries, onEntryClick = {}, today = sampleDate)
}

@Composable
private fun SearchSample() {
    SearchScreen(
        query = "morning",
        filters = SearchFilters(),
        results = sampleEntries.takeLast(1),
        availableTags = listOf("walk", "work", "travel"),
        onQueryChange = {},
        onFiltersChange = {},
        onEntryClick = {},
    )
}

@Composable
private fun ComposeSample() {
    val recorder = remember { createMediaRecorderController() }
    ComposeEntryScreen(
        date = sampleDate,
        initialBody = sampleEntries[1].body,
        recorder = recorder,
        attachments = sampleEntries[1].attachments,
        tags = listOf("voice", "commute"),
        recentTags = listOf("walk", "work", "travel"),
        sourceFor = { null },
        onRemoveAttachment = {},
        onBodyChange = {},
        onDateClick = {},
        onCaptureFinished = {}, onMediaPicked = {},
        onSave = {},
        onCancel = {},
        onDelete = {},
    )
}

@Composable
private fun SettingsSample() {
    SettingsScreen(
        state = SettingsUiState(
            session = SessionState.Authenticated("owner", "joe@example.com"),
            syncState = SyncState(status = SyncStatus.PENDING, pendingCount = 2),
            themeMode = ThemeMode.SYSTEM,
            accent = MiraAccent.EMBER,
            archiveRepo = "j-roskopf/mira-journal",
            archiveConnected = true,
            includeMediaInArchive = false,
            videoUploadUnmeteredOnly = true,
            localMediaBytes = 2_400_000_000,
            mediaCacheCapBytes = 5L * 1024 * 1024 * 1024,
            orphanCount = null,
            version = "2026.1030.2200",
        ),
        actions = SettingsActions(
            onBack = {},
            onSignIn = {},
            onSignOut = {},
            onThemeChange = {},
            onAccentChange = {},
            onSyncNow = {},
            onConnectGitHub = {},
            onDisconnectGitHub = {},
            onBackUpNow = {},
            onToggleArchiveMedia = {},
            onToggleVideoUnmetered = {},
            onScanOrphans = {},
            onDeleteOrphans = {},
            onFreeUpSpace = {},
            onCheckForUpdates = {},
            onInstallUpdate = {},
            onExportData = {},
            onImportData = {},
            onDeleteAllData = {},
        ),
    )
}
