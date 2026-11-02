package com.joetr.mira.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import com.joetr.mira.ui.theme.MiraSpacing
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.joetr.mira.data.archive.DeviceFlowResult
import com.joetr.mira.data.archive.GitHubAuthorizationException
import com.joetr.mira.data.archive.GitHubArchive
import com.joetr.mira.data.archive.ImportFailedException
import com.joetr.mira.di.AppGraph
import com.joetr.mira.domain.SessionState
import com.joetr.mira.updates.AppUpdateState
import com.joetr.mira.domain.SyncState
import com.joetr.mira.domain.ThemeMode
import com.joetr.mira.feature.settings.SettingsActions
import com.joetr.mira.feature.settings.SettingsScreen
import com.joetr.mira.feature.settings.SettingsUiState
import com.joetr.mira.platform.MiraBuildInfo
import com.joetr.mira.platform.exportFile
import com.joetr.mira.platform.importFile
import com.joetr.mira.platform.openUrl
import com.joetr.mira.ui.components.MiraConfirmDialog
import com.joetr.mira.ui.theme.MiraAccent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
internal fun SettingsRoute(
    graph: AppGraph,
    ownerId: String,
    onBack: () -> Unit,
    onSignIn: () -> Unit,
    onDataDeleted: () -> Unit,
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
    var connectionDialog by remember { mutableStateOf(false) }
    var connectionMessage by remember { mutableStateOf("") }
    var connectionJob by remember { mutableStateOf<Job?>(null) }
    var accessToken by remember { mutableStateOf<String?>(null) }
    var createRepository by remember { mutableStateOf(true) }
    var repositoryName by remember { mutableStateOf("mira-journal") }
    var existingRepository by remember { mutableStateOf("") }
    var exporting by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var confirmDeleteAll by remember { mutableStateOf(false) }
    var dataMessage by remember { mutableStateOf<String?>(null) }
    var dataMessageIsError by remember { mutableStateOf(false) }

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
            archivePushing = archiveStatus.pushing,
            archiveError = archiveStatus.errorMessage,
            includeMediaInArchive = includeMedia,
            videoUploadUnmeteredOnly = videoUnmetered,
            localMediaBytes = localBytes,
            mediaCacheCapBytes = cacheCap,
            orphanCount = orphanCount,
            version = MiraBuildInfo.versionName,
            updateAvailable = availableVersion,
            exporting = exporting,
            importing = importing,
            deleting = deleting,
            dataMessage = dataMessage,
            dataMessageIsError = dataMessageIsError,
        ),
        actions = SettingsActions(
            onBack = onBack,
            onSignIn = onSignIn,
            onSignOut = { scope.launch { graph.session.signOut() } },
            onThemeChange = { scope.launch { graph.settings.setThemeMode(it) } },
            onAccentChange = { scope.launch { graph.settings.setAccent(it.name) } },
            onSyncNow = { scope.launch { graph.sync.syncNow(forcePull = true) } },
            onConnectGitHub = {
                if (!connectionDialog) {
                    accessToken = null
                    createRepository = true
                    repositoryName = "mira-journal"
                    existingRepository = ""
                    connectionDialog = true
                    connectionMessage = "Requesting a GitHub authorization code…"
                    connectionJob = scope.launch {
                        try {
                            val result = authorizeGitHub(graph) { code, uri ->
                                connectionMessage = "Enter this code at $uri:\n\n$code\n\n" +
                                    "Waiting for you to authorize Mira in your browser."
                            }
                            when (result) {
                                is DeviceFlowResult.Success -> {
                                    accessToken = result.accessToken
                                    connectionMessage = ""
                                }
                                DeviceFlowResult.Expired -> connectionMessage = "The GitHub code expired. Please connect again."
                                DeviceFlowResult.Denied -> connectionMessage = "GitHub authorization was declined. Please connect again."
                                is DeviceFlowResult.Failed -> connectionMessage = result.message
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: GitHubAuthorizationException) {
                            connectionMessage = error.message ?: "GitHub authorization failed."
                        } catch (_: Exception) {
                            connectionMessage = "Could not connect to GitHub. Check your connection and try again."
                        } finally {
                            connectionJob = null
                        }
                    }
                }
            },
            onDisconnectGitHub = { scope.launch { graph.archive.disconnect() } },
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
            onExportData = {
                if (!exporting && !importing) {
                    exporting = true
                    dataMessage = null
                    scope.launch {
                        try {
                            val bytes = graph.exportService.export(ownerId)
                            val saved = exportFile("mira-export.zip", "application/zip", bytes)
                            if (saved) {
                                dataMessageIsError = false
                                dataMessage = "Journal exported."
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            dataMessageIsError = true
                            dataMessage = "Could not export your journal. Check your connection and try again."
                        } finally {
                            exporting = false
                        }
                    }
                }
            },
            onImportData = {
                if (!exporting && !importing) {
                    importing = true
                    dataMessage = null
                    scope.launch {
                        try {
                            val bytes = importFile(setOf("zip"))
                            if (bytes != null) {
                                val result = graph.exportService.import(ownerId, bytes)
                                graph.onImportFinished(ownerId)
                                dataMessageIsError = false
                                dataMessage = buildString {
                                    append("Imported ${result.entriesRestored} ")
                                    append(if (result.entriesRestored == 1) "entry" else "entries")
                                    append(".")
                                    if (result.entriesSkipped > 0) {
                                        append(" Skipped ${result.entriesSkipped} already up to date.")
                                    }
                                }
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: ImportFailedException) {
                            dataMessageIsError = true
                            dataMessage = error.message ?: "Could not import this file."
                        } catch (_: Exception) {
                            dataMessageIsError = true
                            dataMessage = "Could not import this file. Check your connection and try again."
                        } finally {
                            importing = false
                        }
                    }
                }
            },
            onDeleteAllData = {
                if (!deleting && !exporting && !importing) {
                    confirmDeleteAll = true
                }
            },
        ),
    )

    if (confirmDeleteAll) {
        MiraConfirmDialog(
            onDismissRequest = { if (!deleting) confirmDeleteAll = false },
            title = "Delete all data?",
            message = "Every entry and attachment will be permanently deleted from this device " +
                "and the cloud. Your account and preferences stay, and a connected GitHub " +
                "archive keeps its own history. This cannot be undone.",
            confirmText = "Delete everything",
            destructive = true,
            onConfirm = {
                confirmDeleteAll = false
                deleting = true
                dataMessage = null
                scope.launch {
                    try {
                        graph.deleteAllData(ownerId)
                        onDataDeleted()
                        localBytes = graph.media.localBytesUsed()
                        orphanCount = null
                        orphans = emptyList()
                        dataMessageIsError = false
                        dataMessage = "All journal data deleted."
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        dataMessageIsError = true
                        dataMessage = "Could not delete all data. Check your connection and try again."
                    } finally {
                        deleting = false
                    }
                }
            },
        )
    }

    if (connectionDialog) {
        val dismiss = {
            connectionJob?.cancel()
            connectionDialog = false
            accessToken = null
        }
        AlertDialog(
            onDismissRequest = dismiss,
            title = { Text("Connect GitHub") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(MiraSpacing.sm),
                ) {
                    if (accessToken != null) {
                        val busy = connectionJob?.isActive == true
                        Text("Choose where to back up your journal.")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = createRepository, enabled = !busy, onClick = {
                                createRepository = true
                                connectionMessage = ""
                            })
                            Text("Create a new repository")
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = !createRepository, enabled = !busy, onClick = {
                                createRepository = false
                                connectionMessage = ""
                            })
                            Text("Connect an existing repository")
                        }
                        if (createRepository) {
                            OutlinedTextField(
                                value = repositoryName,
                                onValueChange = { repositoryName = it; connectionMessage = "" },
                                label = { Text("Repository name") },
                                singleLine = true,
                                enabled = !busy,
                            )
                            Text("A private repository will be created in your GitHub account.")
                        } else {
                            OutlinedTextField(
                                value = existingRepository,
                                onValueChange = { existingRepository = it; connectionMessage = "" },
                                label = { Text("Repository (owner/name)") },
                                placeholder = { Text("your-account/mira-journal") },
                                singleLine = true,
                                enabled = !busy,
                            )
                            Text("Use a private repository you can write to, with at least one commit. Mira backs up entries here; it does not import them.")
                        }
                    }
                    if (connectionMessage.isNotBlank()) {
                        SelectionContainer { Text(connectionMessage) }
                    }
                }
            },
            confirmButton = {
                if (accessToken != null) {
                    TextButton(
                        enabled = connectionJob?.isActive != true &&
                            (if (createRepository) repositoryName else existingRepository).isNotBlank(),
                        onClick = {
                            val token = accessToken ?: return@TextButton
                            connectionJob = scope.launch {
                                try {
                                    connectionMessage = if (createRepository) "Creating repository…" else "Checking repository…"
                                    val archive = GitHubArchive(graph.httpClientForArchive(), token)
                                    val target = if (createRepository) {
                                        archive.createRepository(repositoryName.trim())
                                            ?: throw IllegalArgumentException("Could not create the repository. Choose a different name or connect an existing repository.")
                                    } else {
                                        archive.existingRepository(existingRepository.trim())
                                    }
                                    graph.archive.connect(token, target)
                                    accessToken = null
                                    connectionDialog = false
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (error: IllegalArgumentException) {
                                    connectionMessage = error.message ?: "Check the repository and try again."
                                } catch (_: Exception) {
                                    connectionMessage = "Could not connect the repository. Check your connection and try again."
                                } finally {
                                    connectionJob = null
                                }
                            }
                        },
                    ) { Text(if (createRepository) "Create and connect" else "Connect repository") }
                }
            },
            dismissButton = {
                TextButton(onClick = dismiss) { Text("Cancel") }
            },
        )
    }
}

/**
 * Runs the GitHub device flow before the user chooses an archive repository.
 *
 * The verification page is opened for convenience, but the code is also surfaced so it can be
 * typed on another device — which is the whole appeal of device flow on a platform where a
 * redirect would be awkward.
 */
private suspend fun authorizeGitHub(graph: AppGraph, onCode: (String, String) -> Unit): DeviceFlowResult {
    val flow = graph.gitHubDeviceFlow()
        ?: return DeviceFlowResult.Failed("GitHub connection is not configured in this build. " +
            "Please install a build with GitHub archive support enabled.")
    val request = flow.requestCode()
        ?: return DeviceFlowResult.Failed("GitHub could not start authorization. Check the OAuth app configuration and enable device flow.")
    onCode(request.userCode, request.verificationUri)
    // The code and URL remain usable even when the platform cannot launch a browser.
    try {
        openUrl(request.verificationUri)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // The user can open the displayed verification URL manually.
    }

    return flow.awaitToken(request)
}
