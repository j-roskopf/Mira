package com.joetr.mira.data.auth

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.AuthenticationServices.ASWebAuthenticationPresentationContextProvidingProtocol
import platform.AuthenticationServices.ASWebAuthenticationSession
import platform.Foundation.NSError
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIWindow
import platform.darwin.NSObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@OptIn(ExperimentalForeignApi::class)
private class OAuthPresentationContextProvider : NSObject(), ASWebAuthenticationPresentationContextProvidingProtocol {
    override fun presentationAnchorForWebAuthenticationSession(session: ASWebAuthenticationSession): UIWindow {
        val application = UIApplication.sharedApplication
        return application.keyWindow as? UIWindow
            ?: error("No UIWindow available for Google sign-in")
    }
}

@OptIn(ExperimentalForeignApi::class)
internal suspend fun performOAuthWebAuthenticationSession(
    authUrl: String,
    callbackURLScheme: String,
): String = suspendCancellableCoroutine { continuation ->
    val url = NSURL.URLWithString(authUrl)
    if (url == null) {
        continuation.resumeWithException(IllegalArgumentException("Invalid Google sign-in URL"))
        return@suspendCancellableCoroutine
    }
    val presentationContextProvider = OAuthPresentationContextProvider()
    val session = ASWebAuthenticationSession(
        uRL = url,
        callbackURLScheme = callbackURLScheme,
        completionHandler = { callbackUrl: NSURL?, error: NSError? ->
            when {
                error != null -> continuation.resumeWithException(
                    authException(error.localizedDescription ?: "Google sign-in failed"),
                )
                callbackUrl != null -> continuation.resume(callbackUrl.absoluteString ?: "")
                else -> continuation.resumeWithException(authException("Google sign-in was cancelled"))
            }
        },
    )
    session.presentationContextProvider = presentationContextProvider
    session.prefersEphemeralWebBrowserSession = false
    if (!session.start()) {
        continuation.resumeWithException(authException("Failed to open Google sign-in"))
        return@suspendCancellableCoroutine
    }
    continuation.invokeOnCancellation { session.cancel() }
}
