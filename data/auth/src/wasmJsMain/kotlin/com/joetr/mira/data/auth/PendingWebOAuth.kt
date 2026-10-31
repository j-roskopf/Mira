package com.joetr.mira.data.auth

import com.joetr.mira.network.MiraFirebase
import kotlinx.browser.window

internal const val OAUTH_STATE_KEY = "mira_oauth_state"

internal fun webOAuthRedirectUri(): String = "${window.location.origin}/auth-callback"

internal fun isWebOAuthCallbackPath(): Boolean =
    window.location.pathname.removeSuffix("/").endsWith("/auth-callback")

internal fun persistWebOAuthState(state: String) {
    window.sessionStorage.setItem(OAUTH_STATE_KEY, state)
}

internal suspend fun resumeWebOAuthIfNeeded(firebase: MiraFirebase): GoogleSignInResult? {
    if (!isWebOAuthCallbackPath()) return null
    val callbackUrl = window.location.href
    if (!callbackUrl.contains("code=") && !callbackUrl.contains("error=")) return null

    val redirectUri = webOAuthRedirectUri()
    val expectedState = window.sessionStorage.getItem(OAUTH_STATE_KEY)
    window.sessionStorage.removeItem(OAUTH_STATE_KEY)

    val returnedState = extractCallbackParam(callbackUrl, "state")
    if (expectedState != null && returnedState != expectedState) {
        throw authException("OAuth state mismatch")
    }
    val code = extractCallbackParam(callbackUrl, "code")
        ?: throw authException(extractCallbackParam(callbackUrl, "error") ?: "OAuth callback missing code")
    val tokenResponse = exchangeGoogleAuthCode(firebase.httpClient, code, redirectUri)
    val result = signInToFirebaseWithGoogleIdToken(firebase, tokenResponse.idToken, tokenResponse.accessToken)
    window.history.replaceState(null, "", "/")
    return result
}

public actual suspend fun resumeWebOAuthSignIn(firebase: MiraFirebase): GoogleSignInResult? =
    resumeWebOAuthIfNeeded(firebase)
