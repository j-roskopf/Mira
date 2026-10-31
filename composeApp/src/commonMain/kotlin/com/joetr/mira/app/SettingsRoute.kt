package com.joetr.mira.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.joetr.mira.data.archive.ArchiveTarget
import com.joetr.mira.data.archive.DeviceFlowResult
import com.joetr.mira.data.archive.GitHubArchive
import com.joetr.mira.di.AppGraph
import com.joetr.mira.domain.SessionState
import com.joetr.mira.updates.AppUpdateState
import com.joetr.mira.domain.SyncState
import com.joetr.mira.domain.ThemeMode
import com.joetr.mira.feature.settings.SettingsActions
import com.joetr.mira.feature.settings.SettingsScreen
import com.joetr.mira.feature.settings.SettingsUiState
import com.joetr.mira.platform.MiraBuildInfo
import com.joetr.mira.platform.MiraConfig
import com.joetr.mira.platform.openUrl
import com.joetr.mira.ui.theme.MiraAccent
import kotlinx.coroutines.launch

@Composable
internal fun SettingsRoute(
    graph: AppGraph,
    ownerId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()

    val session by graph.session.observeSession()
        .collectAsState(SessionState.LocalPending(ownerId))
    val syncState by graph.sync.state.collectAsState()
    val themeMode by graph.settings.observeThemeMode().collectAsState(ThemeMode.SYSTEM)
    val accentName by graph.settings.observeAccent().collectAsState("EMBER")
    val archiveStatus by graph.archive.status.collectAsState()
    val includeMedia by graph.settings.observeIncludeMediaInArchive().collectAsState(false)
    val videoUnmetered by graph.settings.observeVideoUploadOnUnmeteredOnly().collectAsState(true)
    val cacheCap by graph.settings.observeMediaCacheCapBytes().collectAsState(0L)

    var localBytes by remember { mutableStateOf(0L) }
    var orphanCount by remember { mutableStateOf<Int?>(null) }
    var orphans by remember { mutableStateOf<List<String>>(emptyList()) }
    var deviceCode by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { localBytes = graph.media.localBytesUsed() }

    val updateState by graph.updates.state.collectAsState()
    val availableVersion =
        (updateState as? AppUpdateState.Available)?.update?.versionName

    SettingsScreen(
        modifier = modifier,
        state = SettingsUiState(
            session = session,
            syncState = syncState,
            themeMode = themeMode,
            accent = MiraAccent.fromName(accentName),
            archiveRepo = archiveStatus.repoSlug,
            archiveConnected = archiveStatus.connected,
            includeMediaInArchive = includeMedia,
            videoUploadUnmeteredOnly = videoUnmetered,
            localMediaBytes = localBytes,
            mediaCacheCapBytes = cacheCap,
            orphanCount = orphanCount,
            version = MiraBuildInfo.versionName,
            updateAvailable = availableVersion,
        ),
        actions = SettingsActions(
            onBack = onBack,
            onSignIn = onBack,
            onSignOut = { scope.launch { graph.session.signOut() } },
            onThemeChange = { scope.launch { graph.settings.setThemeMode(it) } },
            onAccentChange = { scope.launch { graph.settings.setAccent(it.name) } },
            onSyncNow = { scope.launch { graph.sync.syncNow(forcePull = true) } },
            onConnectGitHub = {
                scope.launch { connectGitHub(graph) { code -> deviceCode = code } }
            },
            onBackUpNow = {
                scope.launch { graph.archive.pushNow(ownerId, includeMedia) }
            },
            onToggleArchiveMedia = {
                scope.launch { graph.settings.setIncludeMediaInArchive(it) }
            },
            onToggleVideoUnmetered = {
                scope.launch { graph.settings.setVideoUploadOnUnmeteredOnly(it) }
            },
            onScanOrphans = {
                scope.launch {
                    orphans = graph.media.findOrphans(ownerId)
                    orphanCount = orphans.size
                }
            },
            onDeleteOrphans = {
                scope.launch {
                    graph.media.deleteOrphans(orphans)
                    orphans = emptyList()
                    orphanCount = 0
                    localBytes = graph.media.localBytesUsed()
                }
            },
            onFreeUpSpace = {
                scope.launch {
                    graph.media.enforceRetention()
                    localBytes = graph.media.localBytesUsed()
                }
            },
            onCheckForUpdates = { scope.launch { graph.updates.checkForUpdates() } },
        ),
    )
}

/**
 * Runs the GitHub device flow and connects the archive repository.
 *
 * The verification page is opened for convenience, but the code is also surfaced so it can be
 * typed on another device — which is the whole appeal of device flow on a platform where a
 * redirect would be awkward.
 */
private suspend fun connectGitHub(graph: AppGraph, onCode: (String) -> Unit) {
    val flow = graph.gitHubDeviceFlow() ?: return
    val request = flow.requestCode() ?: return
    onCode(request.userCode)
    openUrl(request.verificationUri)

    when (val result = flow.awaitToken(request)) {
        is DeviceFlowResult.Success -> {
            val archive = GitHubArchive(graph.httpClientForArchive(), result.accessToken)
            val login = archive.currentLogin() ?: return
            val target = archive.createRepository("mira-journal")
                ?: ArchiveTarget(login, "mira-journal")
            graph.archive.connect(result.accessToken, target)
        }
        else -> Unit
    }
}
