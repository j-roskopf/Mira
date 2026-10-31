package com.joetr.mira.updates

public expect fun currentUpdatePlatform(): UpdatePlatform

public expect fun createAppUpdateService(
    scope: kotlinx.coroutines.CoroutineScope,
    httpClient: io.ktor.client.HttpClient,
): AppUpdateService
