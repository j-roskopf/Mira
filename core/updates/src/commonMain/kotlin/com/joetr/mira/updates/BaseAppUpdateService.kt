package com.joetr.mira.updates

import io.ktor.client.HttpClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal abstract class BaseAppUpdateService(
    protected val scope: CoroutineScope,
    protected val httpClient: HttpClient,
    protected val platform: UpdatePlatform,
) : AppUpdateService {
    protected val mutableState = MutableStateFlow<AppUpdateState>(AppUpdateState.Idle)
    override val state: StateFlow<AppUpdateState> = mutableState.asStateFlow()

    private val mutablePendingInstallConfirmation = MutableStateFlow<AvailableUpdate?>(null)
    override val pendingInstallConfirmation: StateFlow<AvailableUpdate?> =
        mutablePendingInstallConfirmation.asStateFlow()

    private var pendingInstallConfirmationResponse: CompletableDeferred<Boolean>? = null

    override suspend fun checkForUpdates(onFailure: (Throwable) -> Unit) {
        if (mutableState.value is AppUpdateState.Installing) return
        mutableState.value = AppUpdateState.Checking
        runCatching {
            GitHubReleaseChecker.checkForUpdate(httpClient, platform)
        }.onSuccess { update ->
            mutableState.value = if (update == null) {
                AppUpdateState.Current
            } else {
                AppUpdateState.Available(update)
            }
            if (update == null) {
                scope.launch {
                    delay(3000)
                    if (mutableState.value is AppUpdateState.Current) {
                        mutableState.value = AppUpdateState.Idle
                    }
                }
            }
        }.onFailure { error ->
            onFailure(error)
            mutableState.value = AppUpdateState.Failed(error.message ?: "Couldn't check for updates.")
        }
    }

    override suspend fun installAvailableUpdate(onMessage: (String) -> Unit) {
        val update = when (val current = mutableState.value) {
            is AppUpdateState.Available -> current.update
            is AppUpdateState.Failed -> current.lastKnownUpdate
            is AppUpdateState.Installing -> return
            else -> null
        } ?: return

        val initialMessage = "Downloading Mira ${update.versionName}..."
        mutableState.value = AppUpdateState.Installing(update, initialMessage)
        onMessage(initialMessage)

        scope.launch {
            runCatching {
                performInstall(update) { message, progress ->
                    mutableState.value = AppUpdateState.Installing(
                        update = update,
                        message = message,
                        progress = progress,
                    )
                }
            }.onFailure { error ->
                val message = error.message ?: "Couldn't install the update."
                onMessage(message)
                mutableState.value = AppUpdateState.Failed(message, update)
            }
        }
    }

    override fun respondToInstallConfirmation(install: Boolean) {
        pendingInstallConfirmationResponse?.complete(install)
    }

    protected suspend fun requestInstallConfirmation(update: AvailableUpdate): Boolean {
        pendingInstallConfirmationResponse?.complete(false)
        val response = CompletableDeferred<Boolean>()
        pendingInstallConfirmationResponse = response
        mutablePendingInstallConfirmation.value = update
        return try {
            response.await()
        } finally {
            if (pendingInstallConfirmationResponse === response) {
                pendingInstallConfirmationResponse = null
                mutablePendingInstallConfirmation.value = null
            }
        }
    }

    protected abstract suspend fun performInstall(
        update: AvailableUpdate,
        onProgress: (message: String, progress: Float?) -> Unit,
    )
}
