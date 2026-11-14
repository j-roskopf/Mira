package com.joetr.mira.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.joetr.mira.domain.LocalDateKey
import com.joetr.mira.domain.SessionState
import com.joetr.mira.domain.SyncState
import com.joetr.mira.domain.ThemeMode
import com.joetr.mira.feature.settings.SettingsActions
import com.joetr.mira.feature.settings.SettingsScreen
import com.joetr.mira.feature.settings.SettingsUiState
import com.joetr.mira.ui.theme.MiraAccent
import com.joetr.mira.ui.theme.MiraTheme
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class ArchiveSettingsDesktopTest {
    @Test
    fun mediaBackupExplainsTheGitHubFileLimit() = runDesktopComposeUiTest(width = 1000, height = 1100) {
        setContent {
            MiraTheme(darkTheme = false, motionEnabled = false) {
                SettingsScreen(
                    state = SettingsUiState(
                        session = SessionState.Authenticated("owner", "joe@example.com"),
                        syncState = SyncState(),
                        themeMode = ThemeMode.SYSTEM,
                        accent = MiraAccent.EMBER,
                        archiveRepo = "me/journal",
                        archiveConnected = true,
                        includeMediaInArchive = true,
                        videoUploadUnmeteredOnly = true,
                        localMediaBytes = 0,
                        mediaCacheCapBytes = 5L * 1024 * 1024 * 1024,
                        orphanCount = null,
                        version = "test",
                        today = LocalDateKey("2026-10-31"),
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
        }
        onNodeWithText("Larger files stay in cloud storage", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }
}
