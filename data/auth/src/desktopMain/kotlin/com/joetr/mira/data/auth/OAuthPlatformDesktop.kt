package com.joetr.mira.data.auth

internal actual fun openOAuthUrl(url: String) {
    error("Desktop OAuth uses loopback flow in GoogleSignInDesktop")
}

internal actual suspend fun awaitOAuthCallback(): String {
    error("Desktop OAuth uses loopback flow in GoogleSignInDesktop")
}
