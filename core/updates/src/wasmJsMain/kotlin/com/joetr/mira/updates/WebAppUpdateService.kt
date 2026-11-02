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
    ): UpdateInstallResult {
        onProgress("Refreshing Mira ${update.versionName}...", 1f)
        val confirmed = requestInstallConfirmation(update)
        if (!confirmed) {
            return UpdateInstallResult.RequiresUserAction(
                "Refresh when you're ready to load Mira ${update.versionName}.",
            )
        }
        // Cache-bust the URL so a stale service worker/CDN cannot serve the old build again.
        val path = window.location.pathname.ifBlank { "/" }
        window.location.assign("$path?miraVersion=${update.versionName}")
        return UpdateInstallResult.Started("Mira is reloading the latest web build.")
    }
}

public actual fun currentUpdatePlatform(): UpdatePlatform = UpdatePlatform.Web

public actual fun createAppUpdateService(
    scope: CoroutineScope,
    httpClient: HttpClient,
): AppUpdateService = WebAppUpdateService(scope, httpClient)
