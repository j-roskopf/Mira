package com.joetr.mira.data.auth

import com.joetr.mira.network.MiraFirebase
import com.joetr.mira.platform.MiraConfig

public actual suspend fun googleSignIn(firebase: MiraFirebase): GoogleSignInResult {
    val clientId = MiraConfig.GOOGLE_IOS_CLIENT_ID
    require(clientId.isNotBlank()) {
        "Google sign-in on iOS requires mira.google.iosClientId in local.properties " +
            "(CLIENT_ID from iosApp/iosApp/GoogleService-Info.plist), or add that plist file."
    }
    val redirectUri = googleIosOAuthRedirectUri(clientId)
    val callbackScheme = googleIosOAuthUrlScheme(clientId)
    val codeVerifier = randomOAuthToken(length = 64)
    val request = buildGoogleAuthorizeRequestWithPkce(
        clientId = clientId,
        redirectUri = redirectUri,
        codeVerifier = codeVerifier,
    )
    val callbackUrl = performOAuthWebAuthenticationSession(
        authUrl = request.url,
        callbackURLScheme = callbackScheme,
    )
    val returnedState = extractCallbackParam(callbackUrl, "state")
    require(returnedState == request.state) { "OAuth state mismatch" }
    val code = extractCallbackParam(callbackUrl, "code")
        ?: throw authException(extractCallbackParam(callbackUrl, "error") ?: "OAuth callback missing code")
    val tokenResponse = exchangeGoogleAuthCodeWithPkce(
        httpClient = firebase.httpClient,
        code = code,
        redirectUri = redirectUri,
        clientId = clientId,
        codeVerifier = codeVerifier,
    )
    return signInToFirebaseWithGoogleIdToken(
        firebase = firebase,
        googleIdToken = tokenResponse.idToken,
        googleAccessToken = tokenResponse.accessToken,
    )
}
