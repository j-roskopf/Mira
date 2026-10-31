package com.joetr.mira.updates

import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.browser.window

internal class WebAppUpdateService(
    scope: CoroutineScope,
    httpClient: HttpClient,
) : BaseAppUpdateService(scope, httpClient, UpdatePlatform.Web) {

    override suspend fun performInstall(
        update: AvailableUpdate,
        onProgress: (message: String, progress: Float?) -> Unit,
    ) {
        onProgress("Refreshing Mira ${update.versionName}...", 1f)
        val confirmed = requestInstallConfirmation(update)
        if (!confirmed) {
            mutableState.value = AppUpdateState.Available(update)
            return
        }
        window.location.reload()
    }
}

public actual fun currentUpdatePlatform(): UpdatePlatform = UpdatePlatform.Web

public actual fun createAppUpdateService(
    scope: CoroutineScope,
    httpClient: HttpClient,
): AppUpdateService = WebAppUpdateService(scope, httpClient)
