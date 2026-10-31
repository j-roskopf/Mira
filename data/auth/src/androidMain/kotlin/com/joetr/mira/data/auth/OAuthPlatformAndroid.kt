package com.joetr.mira.data.auth

internal actual fun openOAuthUrl(url: String) {
    error("Android uses native Google sign-in")
}

internal actual suspend fun awaitOAuthCallback(): String {
    error("Android uses native Google sign-in")
}
