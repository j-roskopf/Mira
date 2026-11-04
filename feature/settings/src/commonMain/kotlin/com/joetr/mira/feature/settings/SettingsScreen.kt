package com.joetr.mira.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.joetr.mira.domain.CaptureQuality
import com.joetr.mira.domain.SessionState
import com.joetr.mira.domain.SyncState
import com.joetr.mira.domain.SyncStatus
import com.joetr.mira.domain.ThemeMode
import com.joetr.mira.ui.components.Chip
import com.joetr.mira.ui.components.PillButton
import com.joetr.mira.ui.components.SectionLabel
import com.joetr.mira.ui.formatBytes
import com.joetr.mira.ui.theme.MiraAccent
import com.joetr.mira.ui.theme.MiraSpacing
import com.joetr.mira.ui.theme.MiraTheme
import com.joetr.mira.updates.AppUpdateState

public data class SettingsUiState(
    val session: SessionState,
    val syncState: SyncState,
    val themeMode: ThemeMode,
    val accent: MiraAccent,
    val archiveRepo: String?,
    val archiveConnected: Boolean,
    val includeMediaInArchive: Boolean,
    val videoUploadUnmeteredOnly: Boolean,
    val localMediaBytes: Long,
    val mediaCacheCapBytes: Long,
    val orphanCount: Int?,
    val version: String,
    val updateState: AppUpdateState = AppUpdateState.Idle,
    val archivePushing: Boolean = false,
    val archiveError: String? = null,
    val exporting: Boolean = false,
    val importing: Boolean = false,
    val deleting: Boolean = false,
    val dataMessage: String? = null,
    val dataMessageIsError: Boolean = false,
    val videoQuality: CaptureQuality = CaptureQuality.MEDIUM,
    val photoQuality: CaptureQuality = CaptureQuality.MEDIUM,
)

public data class SettingsActions(
    val onBack: () -> Unit,
    val onSignIn: () -> Unit,
    val onSignOut: () -> Unit,
    val onThemeChange: (ThemeMode) -> Unit,
    val onAccentChange: (MiraAccent) -> Unit,
    val onSyncNow: () -> Unit,
    val onConnectGitHub: () -> Unit,
    val onDisconnectGitHub: () -> Unit,
    val onBackUpNow: () -> Unit,
    val onToggleArchiveMedia: (Boolean) -> Unit,
    val onToggleVideoUnmetered: (Boolean) -> Unit,
    val onScanOrphans: () -> Unit,
    val onDeleteOrphans: () -> Unit,
    val onFreeUpSpace: () -> Unit,
    val onCheckForUpdates: () -> Unit,
    val onInstallUpdate: () -> Unit,
    val onExportData: () -> Unit,
    val onImportData: () -> Unit,
    val onDeleteAllData: () -> Unit,
    val onVideoQualityChange: (CaptureQuality) -> Unit = {},
    val onPhotoQualityChange: (CaptureQuality) -> Unit = {},
)

@Composable
public fun SettingsScreen(
    state: SettingsUiState,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
) {
    val colors = MiraTheme.colors

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.canvas)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = MiraSpacing.gutter),
    ) {
        Spacer(Modifier.height(MiraSpacing.lg))
        Text("SETTINGS", style = MiraTheme.type.monthTitle, color = colors.ink)
        Spacer(Modifier.height(MiraSpacing.sectionGap))

        Section("Account") {
            when (val session = state.session) {
                is SessionState.Authenticated -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = session.email ?: "Signed in",
                            style = MiraTheme.type.uiBody,
                            color = colors.ink,
                            modifier = Modifier.weight(1f),
                        )
                        PillButton("Sign out", onClick = actions.onSignOut, filled = false)
                    }
                }
                is SessionState.Anonymous -> {
                    Text(
                        text = "Signed in anonymously",
                        style = MiraTheme.type.uiBody,
                        color = colors.ink,
                    )
                    Spacer(Modifier.height(MiraSpacing.md))
                    Text(
                        text = "Your entries already sync to the cloud, but this account has no " +
                            "email or password — it only exists on this device. Sign in to reach " +
                            "the same journal from another device, or to make sure you can get back " +
                            "in if you lose this one.",
                        style = MiraTheme.type.uiBody,
                        color = colors.inkMuted,
                    )
                    Spacer(Modifier.height(MiraSpacing.md))
                    PillButton("Sign in", onClick = actions.onSignIn)
                }
                is SessionState.LocalPending -> {
                    Text(
                        text = "Writing locally. Sign in to sync across your devices — " +
                            "everything written so far comes with you.",
                        style = MiraTheme.type.uiBody,
                        color = colors.inkMuted,
                    )
                    Spacer(Modifier.height(MiraSpacing.md))
                    PillButton("Sign in", onClick = actions.onSignIn)
                }
            }
        }

        Section("Appearance") {
            Row(horizontalArrangement = Arrangement.spacedBy(MiraSpacing.sm)) {
                ThemeMode.entries.forEach { mode ->
                    Chip(
                        text = mode.name.lowercase().replaceFirstChar { it.uppercase() },
                        selected = state.themeMode == mode,
                        onClick = { actions.onThemeChange(mode) },
                    )
                }
            }
            Spacer(Modifier.height(MiraSpacing.xl))
            SectionLabel("Accent")
            Spacer(Modifier.height(MiraSpacing.md))
            MiraAccent.entries.chunked(5).forEach { row ->
                Row(
                    modifier = Modifier.padding(bottom = MiraSpacing.md),
                    horizontalArrangement = Arrangement.spacedBy(MiraSpacing.md),
                ) {
                    row.forEach { accent ->
                        val isSelected = accent == state.accent
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .background(accent.color, CircleShape)
                                .then(
                                    if (isSelected) {
                                        Modifier.border(2.dp, colors.ink, CircleShape)
                                    } else {
                                        Modifier
                                    },
                                )
                                .clickable { actions.onAccentChange(accent) },
                        )
                    }
                }
            }
        }

        Section("Capture") {
            Text(
                text = "The default quality for new photos and videos. Native camera apps use " +
                    "these as a guide, so the exact size can vary by device.",
                style = MiraTheme.type.meta,
                color = colors.inkMuted,
            )
            Spacer(Modifier.height(MiraSpacing.lg))
            SectionLabel("Video")
            Spacer(Modifier.height(MiraSpacing.md))
            Row(horizontalArrangement = Arrangement.spacedBy(MiraSpacing.sm)) {
                CaptureQuality.entries.forEach { quality ->
                    Chip(
                        text = quality.label,
                        selected = state.videoQuality == quality,
                        onClick = { actions.onVideoQualityChange(quality) },
                    )
                }
            }
            Spacer(Modifier.height(MiraSpacing.xl))
            SectionLabel("Photo")
            Spacer(Modifier.height(MiraSpacing.md))
            Row(horizontalArrangement = Arrangement.spacedBy(MiraSpacing.sm)) {
                CaptureQuality.entries.forEach { quality ->
                    Chip(
                        text = quality.label,
                        selected = state.photoQuality == quality,
                        onClick = { actions.onPhotoQualityChange(quality) },
                    )
                }
            }
        }

        Section("Sync") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = when (state.syncState.status) {
                        SyncStatus.SYNCED -> "Everything is synced"
                        SyncStatus.SYNCING -> "Syncing…"
                        SyncStatus.PENDING -> "${state.syncState.pendingCount} waiting to sync"
                        SyncStatus.ERROR -> state.syncState.errorMessage ?: "Sync failed"
                    },
                    style = MiraTheme.type.uiBody,
                    color = if (state.syncState.status == SyncStatus.ERROR) {
                        colors.error
                    } else {
                        colors.inkMuted
                    },
                    modifier = Modifier.weight(1f),
                )
                PillButton("Sync now", onClick = actions.onSyncNow, filled = false)
            }

            Spacer(Modifier.height(MiraSpacing.xl))
            SectionLabel("GitHub archive")
            Spacer(Modifier.height(MiraSpacing.sm))
            Text(
                text = if (state.archiveConnected) {
                    "Backing up to ${state.archiveRepo}. Every edit is a commit, so each entry " +
                        "file carries its own history."
                } else {
                    "Keep a plain-text copy of your journal in a private repository you own."
                },
                style = MiraTheme.type.uiBody,
                color = colors.inkMuted,
            )
            Spacer(Modifier.height(MiraSpacing.md))
            Column(verticalArrangement = Arrangement.spacedBy(MiraSpacing.sm)) {
                if (state.archiveConnected) {
                    PillButton(if (state.archivePushing) "Backing up…" else "Back up now", onClick = {
                        if (!state.archivePushing) actions.onBackUpNow()
                    }, filled = false)
                    state.archiveError?.let { Text(it, color = colors.error, style = MiraTheme.type.uiBody) }
                    PillButton("Disconnect GitHub", onClick = actions.onDisconnectGitHub, filled = false)
                } else {
                    PillButton("Connect GitHub", onClick = actions.onConnectGitHub)
                }
            }
            if (state.archiveConnected) {
                Spacer(Modifier.height(MiraSpacing.md))
                ToggleRow(
                    label = "Include media in backup",
                    detail = "Backs up audio and video files as well as entry text.",
                    checked = state.includeMediaInArchive,
                    onCheckedChange = actions.onToggleArchiveMedia,
                )
            }
        }

        Section("Storage") {
            Text(
                text = "${formatBytes(state.localMediaBytes)} of media on this device",
                style = MiraTheme.type.uiBody,
                color = colors.ink,
            )
            Spacer(Modifier.height(MiraSpacing.xs))
            Text(
                text = "Cache limit ${formatBytes(state.mediaCacheCapBytes)}. Anything evicted " +
                    "streams again on demand — your originals stay in the cloud.",
                style = MiraTheme.type.meta,
                color = colors.inkMuted,
            )
            Spacer(Modifier.height(MiraSpacing.md))
            PillButton("Free up space", onClick = actions.onFreeUpSpace, filled = false)

            Spacer(Modifier.height(MiraSpacing.xl))
            ToggleRow(
                label = "Upload video on Wi-Fi only",
                detail = "A minute of 1080p is 60–100 MB.",
                checked = state.videoUploadUnmeteredOnly,
                onCheckedChange = actions.onToggleVideoUnmetered,
            )

            Spacer(Modifier.height(MiraSpacing.xl))
            SectionLabel("Orphaned media")
            Spacer(Modifier.height(MiraSpacing.sm))
            Text(
                text = "If an entry was deleted while you were offline, its audio or video can " +
                    "be left behind in storage. This finds and removes those.",
                style = MiraTheme.type.meta,
                color = colors.inkMuted,
            )
            Spacer(Modifier.height(MiraSpacing.md))
            Row(horizontalArrangement = Arrangement.spacedBy(MiraSpacing.sm)) {
                PillButton("Scan", onClick = actions.onScanOrphans, filled = false)
                state.orphanCount?.let { count ->
                    if (count > 0) {
                        PillButton("Delete $count", onClick = actions.onDeleteOrphans)
                    }
                }
            }
            state.orphanCount?.takeIf { it == 0 }?.let {
                Spacer(Modifier.height(MiraSpacing.sm))
                Text("Nothing orphaned.", style = MiraTheme.type.meta, color = colors.inkMuted)
            }
        }

        Section("Data") {
            Text(
                text = "Export your whole journal — every entry and attachment — as a single " +
                    "file you keep yourself. Importing restores a file like that into this " +
                    "account, without overwriting anything newer already here.",
                style = MiraTheme.type.uiBody,
                color = colors.inkMuted,
            )
            Spacer(Modifier.height(MiraSpacing.md))
            Row(horizontalArrangement = Arrangement.spacedBy(MiraSpacing.sm)) {
                PillButton(
                    text = if (state.exporting) "Exporting…" else "Export data",
                    onClick = actions.onExportData,
                    filled = false,
                    enabled = !state.exporting && !state.importing,
                )
                PillButton(
                    text = if (state.importing) "Importing…" else "Import data",
                    onClick = actions.onImportData,
                    filled = false,
                    enabled = !state.exporting && !state.importing,
                )
            }
            state.dataMessage?.let {
                Spacer(Modifier.height(MiraSpacing.sm))
                Text(
                    text = it,
                    style = MiraTheme.type.meta,
                    color = if (state.dataMessageIsError) colors.error else colors.inkMuted,
                )
            }

            Spacer(Modifier.height(MiraSpacing.xl))
            SectionLabel("Delete all data")
            Spacer(Modifier.height(MiraSpacing.sm))
            Text(
                text = "Permanently deletes every entry and attachment from this device and the " +
                    "cloud. Your account and preferences stay, and a connected GitHub archive " +
                    "keeps its own history. This cannot be undone.",
                style = MiraTheme.type.meta,
                color = colors.inkMuted,
            )
            Spacer(Modifier.height(MiraSpacing.md))
            PillButton(
                text = if (state.deleting) "Deleting…" else "Delete all data",
                onClick = actions.onDeleteAllData,
                filled = false,
                enabled = !state.deleting && !state.exporting && !state.importing,
            )
        }

        Section("About") {
            Text("Mira ${state.version}", style = MiraTheme.type.uiBody, color = colors.ink)
            val updateState = state.updateState
            val status = when (updateState) {
                AppUpdateState.Idle -> null
                AppUpdateState.Checking -> "Checking GitHub releases…"
                AppUpdateState.Current -> "Mira is up to date."
                is AppUpdateState.Available -> "Version ${updateState.update.versionName} is available."
                is AppUpdateState.Installing -> updateState.message
                is AppUpdateState.Failed -> updateState.message
            }
            if (status != null) {
                Spacer(Modifier.height(MiraSpacing.md))
                Text(
                    text = status,
                    style = MiraTheme.type.meta,
                    color = when (updateState) {
                        is AppUpdateState.Failed -> colors.error
                        is AppUpdateState.Available -> colors.accent
                        else -> colors.inkMuted
                    },
                )
                val progress = (updateState as? AppUpdateState.Installing)?.progress
                if (progress != null) {
                    Spacer(Modifier.height(MiraSpacing.sm))
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                        color = colors.accent,
                        trackColor = colors.surfaceRaised,
                    )
                }
            }
            Spacer(Modifier.height(MiraSpacing.md))
            val canInstall = updateState is AppUpdateState.Available ||
                (updateState as? AppUpdateState.Failed)?.lastKnownUpdate != null
            val label = when {
                updateState is AppUpdateState.Checking -> "Checking…"
                updateState is AppUpdateState.Installing -> "Updating…"
                updateState is AppUpdateState.Available ->
                    "Update to ${updateState.update.versionName}"
                canInstall -> "Retry update"
                else -> "Check for updates"
            }
            PillButton(
                text = label,
                onClick = if (canInstall) actions.onInstallUpdate else actions.onCheckForUpdates,
                filled = canInstall,
                enabled = updateState !is AppUpdateState.Checking &&
                    updateState !is AppUpdateState.Installing,
            )
        }

        Spacer(Modifier.height(MiraSpacing.giant))
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        SectionLabel(title)
        Spacer(Modifier.height(MiraSpacing.md))
        content()
        Spacer(Modifier.height(MiraSpacing.sectionGap))
    }
}

@Composable
private fun ToggleRow(
    label: String,
    detail: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val colors = MiraTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MiraTheme.type.uiBody, color = colors.ink)
            Text(detail, style = MiraTheme.type.meta, color = colors.inkMuted)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.onAccent,
                checkedTrackColor = colors.accent,
                uncheckedThumbColor = colors.inkMuted,
                uncheckedTrackColor = colors.surfaceRaised,
            ),
        )
    }
}
