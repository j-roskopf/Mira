package com.joetr.mira.data.auth

import com.joetr.mira.network.MiraFirebase

public actual suspend fun googleSignIn(firebase: MiraFirebase): GoogleSignInResult {
    val redirectUri = webOAuthRedirectUri()
    return googleSignInWithAuthorizationCodeFlow(
        firebase = firebase,
        redirectUri = redirectUri,
        openUrl = ::openOAuthUrl,
        awaitCallback = ::awaitOAuthCallback,
        onAuthorizeRequest = { persistWebOAuthState(it.state) },
    )
}
