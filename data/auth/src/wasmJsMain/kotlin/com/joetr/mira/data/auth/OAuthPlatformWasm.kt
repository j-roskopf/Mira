package com.joetr.mira.data.auth

import kotlinx.browser.window
import kotlinx.coroutines.awaitCancellation

internal actual fun openOAuthUrl(url: String) {
    window.location.href = url
}

internal actual suspend fun awaitOAuthCallback(): String {
    // Same-window redirect navigates away before this returns; completion happens on
    // the next app load via [completePendingWebOAuth].
    awaitCancellation()
}
