package com.joetr.mira.updates

import com.joetr.mira.platform.openUrl
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope

internal class IosAppUpdateService(
    scope: CoroutineScope,
    httpClient: HttpClient,
) : BaseAppUpdateService(scope, httpClient, UpdatePlatform.Ios) {

    override suspend fun performInstall(
        update: AvailableUpdate,
        onProgress: (message: String, progress: Float?) -> Unit,
    ): UpdateInstallResult {
        onProgress("Opening Mira ${update.versionName} download page...", 1f)
        openUrl(update.releasePageUrl)
        return UpdateInstallResult.OpenedReleasePage(
            "Mira opened the latest release page. iOS needs a supported distribution channel " +
                "to install updates.",
        )
    }
}

public actual fun currentUpdatePlatform(): UpdatePlatform = UpdatePlatform.Ios

public actual fun createAppUpdateService(
    scope: CoroutineScope,
    httpClient: HttpClient,
): AppUpdateService = IosAppUpdateService(scope, httpClient)
