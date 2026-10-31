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
import com.joetr.mira.domain.SessionState
import com.joetr.mira.domain.SyncState
import com.joetr.mira.domain.SyncStatus
import com.joetr.mira.domain.ThemeMode
import com.joetr.mira.ui.components.Chip
import com.joetr.mira.ui.components.CircleIconButton
import com.joetr.mira.ui.components.PillButton
import com.joetr.mira.ui.components.SectionLabel
import com.joetr.mira.ui.formatBytes
import com.joetr.mira.ui.icons.MiraIcons
import com.joetr.mira.ui.theme.MiraAccent
import com.joetr.mira.ui.theme.MiraSpacing
import com.joetr.mira.ui.theme.MiraTheme

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
    val updateAvailable: String?,
)

public data class SettingsActions(
    val onBack: () -> Unit,
    val onSignIn: () -> Unit,
    val onSignOut: () -> Unit,
    val onThemeChange: (ThemeMode) -> Unit,
    val onAccentChange: (MiraAccent) -> Unit,
    val onSyncNow: () -> Unit,
    val onConnectGitHub: () -> Unit,
    val onBackUpNow: () -> Unit,
    val onToggleArchiveMedia: (Boolean) -> Unit,
    val onToggleVideoUnmetered: (Boolean) -> Unit,
    val onScanOrphans: () -> Unit,
    val onDeleteOrphans: () -> Unit,
    val onFreeUpSpace: () -> Unit,
    val onCheckForUpdates: () -> Unit,
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
            .verticalScroll(rememberScrollState())
            .padding(horizontal = MiraSpacing.gutter),
    ) {
        Spacer(Modifier.height(MiraSpacing.lg))
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircleIconButton(MiraIcons.Back, "Back", onClick = actions.onBack)
            Spacer(Modifier.weight(1f))
        }

        Spacer(Modifier.height(MiraSpacing.md))
        Text("SETTINGS", style = MiraTheme.type.monthTitle, color = colors.ink)
        Spacer(Modifier.height(MiraSpacing.sectionGap))

        Section("Account") {
            when (val session = state.session) {
                is SessionState.Authenticated -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = session.email ?: "Signed in",
                            style = MiraTheme.type.entryBody,
                            color = colors.ink,
                            modifier = Modifier.weight(1f),
                        )
                        PillButton("Sign out", onClick = actions.onSignOut, filled = false)
                    }
                }
                else -> {
                    Text(
                        text = "Writing locally. Sign in to sync across your devices — " +
                            "everything written so far comes with you.",
                        style = MiraTheme.type.entryBody,
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

        Section("Sync") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = when (state.syncState.status) {
                        SyncStatus.SYNCED -> "Everything is synced"
                        SyncStatus.SYNCING -> "Syncing…"
                        SyncStatus.PENDING -> "${state.syncState.pendingCount} waiting to sync"
                        SyncStatus.ERROR -> state.syncState.errorMessage ?: "Sync failed"
                    },
                    style = MiraTheme.type.entryBody,
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
                style = MiraTheme.type.entryBody,
                color = colors.inkMuted,
            )
            Spacer(Modifier.height(MiraSpacing.md))
            Row(horizontalArrangement = Arrangement.spacedBy(MiraSpacing.sm)) {
                if (state.archiveConnected) {
                    PillButton("Back up now", onClick = actions.onBackUpNow, filled = false)
                } else {
                    PillButton("Connect GitHub", onClick = actions.onConnectGitHub)
                }
            }
            if (state.archiveConnected) {
                Spacer(Modifier.height(MiraSpacing.md))
                ToggleRow(
                    label = "Include media in backup",
                    detail = "Commits audio and video via Git LFS, not just links.",
                    checked = state.includeMediaInArchive,
                    onCheckedChange = actions.onToggleArchiveMedia,
                )
            }
        }

        Section("Storage") {
            Text(
                text = "${formatBytes(state.localMediaBytes)} of media on this device",
                style = MiraTheme.type.entryBody,
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

        Section("About") {
            Text("Mira ${state.version}", style = MiraTheme.type.entryBody, color = colors.ink)
            Spacer(Modifier.height(MiraSpacing.md))
            state.updateAvailable?.let { version ->
                Text(
                    text = "Version $version is available.",
                    style = MiraTheme.type.meta,
                    color = colors.accent,
                )
                Spacer(Modifier.height(MiraSpacing.sm))
            }
            PillButton("Check for updates", onClick = actions.onCheckForUpdates, filled = false)
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
            Text(label, style = MiraTheme.type.entryBody, color = colors.ink)
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
