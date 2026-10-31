package com.joetr.mira.data.auth

import com.joetr.mira.platform.openUrl

internal actual fun openOAuthUrl(url: String) {
    openUrl(url)
}

internal actual suspend fun awaitOAuthCallback(): String = OAuthCallbackGate.awaitCallback()
