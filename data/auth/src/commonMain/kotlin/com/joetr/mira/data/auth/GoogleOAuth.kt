package com.joetr.mira.data.auth

import com.joetr.mira.network.MiraFirebase
import com.joetr.mira.network.FirebaseSession
import com.joetr.mira.network.SignInWithIdpResult
import com.joetr.mira.platform.MiraConfig
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.forms.submitForm
import io.ktor.http.Parameters
import io.ktor.http.decodeURLQueryComponent
import io.ktor.http.formUrlEncode
import io.ktor.http.parametersOf
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.yield
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.random.Random

private const val GOOGLE_AUTHORIZE_URL = "https://accounts.google.com/o/oauth2/v2/auth"
private const val GOOGLE_TOKEN_URL = "https://oauth2.googleapis.com/token"

internal data class GoogleAuthorizeRequest(val url: String, val state: String, val nonce: String)

/**
 * Builds the Google OAuth2 "authorization code" consent URL used by the desktop, iOS,
 * and wasm sign-in flows (Android instead uses Credential Manager natively, see
 * `GoogleSignInAndroid.kt`).
 */
internal fun buildGoogleAuthorizeRequest(redirectUri: String): GoogleAuthorizeRequest {
    val clientId = MiraConfig.GOOGLE_WEB_CLIENT_ID
    require(clientId.isNotBlank()) {
        "Google sign-in is not configured. Set mira.google.webClientId in local.properties " +
            "(Google Cloud OAuth Web client ID)."
    }
    val state = randomOAuthToken()
    val nonce = randomOAuthToken()
    val query = Parameters.build {
        append("client_id", clientId)
        append("redirect_uri", redirectUri)
        append("response_type", "code")
        append("scope", "openid email profile")
        append("nonce", nonce)
        append("state", state)
        append("access_type", "offline")
        append("prompt", "select_account")
    }.formUrlEncode()
    return GoogleAuthorizeRequest(url = "$GOOGLE_AUTHORIZE_URL?$query", state = state, nonce = nonce)
}

internal fun buildGoogleAuthorizeRequestWithPkce(
    clientId: String,
    redirectUri: String,
    codeVerifier: String,
): GoogleAuthorizeRequest {
    require(clientId.isNotBlank()) {
        "Google sign-in is not configured. Set the platform Google OAuth client ID."
    }
    val state = randomOAuthToken()
    val nonce = randomOAuthToken()
    val query = Parameters.build {
        append("client_id", clientId)
        append("redirect_uri", redirectUri)
        append("response_type", "code")
        append("scope", "openid email profile")
        append("nonce", nonce)
        append("state", state)
        append("access_type", "offline")
        append("prompt", "select_account")
        append("code_challenge", codeVerifier)
        append("code_challenge_method", "plain")
    }.formUrlEncode()
    return GoogleAuthorizeRequest(url = "$GOOGLE_AUTHORIZE_URL?$query", state = state, nonce = nonce)
}

internal fun googleIosOAuthRedirectUri(clientId: String): String {
    require(clientId.endsWith(".apps.googleusercontent.com")) {
        "Invalid Google iOS client ID: $clientId"
    }
    val prefix = clientId.removeSuffix(".apps.googleusercontent.com")
    return "com.googleusercontent.apps.$prefix:/oauth2redirect"
}

internal fun googleIosOAuthUrlScheme(clientId: String): String {
    require(clientId.endsWith(".apps.googleusercontent.com")) {
        "Invalid Google iOS client ID: $clientId"
    }
    val prefix = clientId.removeSuffix(".apps.googleusercontent.com")
    return "com.googleusercontent.apps.$prefix"
}

internal fun randomOAuthToken(length: Int = 32): String {
    val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    return buildString {
        repeat(length) { append(chars[Random.nextInt(chars.length)]) }
    }
}

internal fun extractCallbackParam(callbackUrl: String, name: String): String? {
    val query = callbackUrl.substringBefore('#').substringAfter('?', "")
    val marker = "$name="
    if (!query.contains(marker)) return null
    val raw = query.substringAfter(marker).substringBefore('&').ifBlank { null } ?: return null
    // Google percent-encodes codes (e.g. "/" → "%2F"). Exchange must use the decoded value.
    return raw.decodeURLQueryComponent()
}

@Serializable
internal data class GoogleTokenResponse(
    @SerialName("id_token") val idToken: String,
    @SerialName("access_token") val accessToken: String? = null,
)

internal suspend fun exchangeGoogleAuthCode(
    httpClient: HttpClient,
    code: String,
    redirectUri: String,
): GoogleTokenResponse = runFirebaseAuthCall {
    requireGoogleWebClientSecret()
    httpClient.submitForm(
        url = GOOGLE_TOKEN_URL,
        formParameters = parametersOf(
            "code" to listOf(code),
            "client_id" to listOf(MiraConfig.GOOGLE_WEB_CLIENT_ID),
            "client_secret" to listOf(MiraConfig.GOOGLE_WEB_CLIENT_SECRET),
            "redirect_uri" to listOf(redirectUri),
            "grant_type" to listOf("authorization_code"),
        ),
    ).body()
}

internal suspend fun exchangeGoogleAuthCodeWithPkce(
    httpClient: HttpClient,
    code: String,
    redirectUri: String,
    clientId: String,
    codeVerifier: String,
): GoogleTokenResponse = runFirebaseAuthCall {
    httpClient.submitForm(
        url = GOOGLE_TOKEN_URL,
        formParameters = parametersOf(
            "code" to listOf(code),
            "client_id" to listOf(clientId),
            "redirect_uri" to listOf(redirectUri),
            "grant_type" to listOf("authorization_code"),
            "code_verifier" to listOf(codeVerifier),
        ),
    ).body()
}

/**
 * Runs the full "authorization code" Google OAuth flow: opens the consent screen,
 * awaits the redirect callback, exchanges the code for tokens at Google, then signs
 * into Firebase with the resulting Google id token (see [signInToFirebaseWithGoogleIdToken]).
 */
internal fun requireGoogleWebClientSecret() {
    require(MiraConfig.GOOGLE_WEB_CLIENT_SECRET.isNotBlank()) {
        "Google sign-in requires mira.google.webClientSecret in local.properties " +
            "(or MIRA_GOOGLE_WEB_CLIENT_SECRET). Rebuild after setting it: ./gradlew :composeApp:run"
    }
}

internal suspend fun googleSignInWithAuthorizationCodeFlow(
    firebase: MiraFirebase,
    redirectUri: String,
    openUrl: (String) -> Unit,
    awaitCallback: suspend () -> String,
    onAuthorizeRequest: (GoogleAuthorizeRequest) -> Unit = {},
): GoogleSignInResult {
    requireGoogleWebClientSecret()
    val request = buildGoogleAuthorizeRequest(redirectUri)
    onAuthorizeRequest(request)
    // Start the callback waiter before opening the browser so a fast Google redirect
    // (already-signed-in account) cannot hit the loopback port before it is listening.
    val callbackUrl = coroutineScope {
        val callbackDeferred = async { awaitCallback() }
        yield()
        openUrl(request.url)
        callbackDeferred.await()
    }
    val returnedState = extractCallbackParam(callbackUrl, "state")
    require(returnedState == request.state) { "OAuth state mismatch" }
    val code = extractCallbackParam(callbackUrl, "code")
        ?: throw authException(extractCallbackParam(callbackUrl, "error") ?: "OAuth callback missing code")
    val tokenResponse = exchangeGoogleAuthCode(firebase.httpClient, code, redirectUri)
    return signInToFirebaseWithGoogleIdToken(firebase, tokenResponse.idToken, tokenResponse.accessToken)
}

/**
 * Signs into Firebase with a Google ID token.
 *
 * Uses a direct `signInWithIdp` (no anonymous-link attempt). Linking first is attractive
 * for uid continuity, but when the Google identity already exists — the common returning-
 * user case — Firebase consumes the one-time Credential Manager token on the failed link
 * and returns `needConfirmation`, which is easy to get stuck on. Direct sign-in works for
 * both new and existing Google accounts; [DefaultSessionRepository] offers a merge prompt
 * when the uid changes and local entries exist.
 */
internal suspend fun signInToFirebaseWithGoogleIdToken(
    firebase: MiraFirebase,
    googleIdToken: String,
    googleAccessToken: String? = null,
): GoogleSignInResult {
    val session: FirebaseSession = runFirebaseAuthCall {
        resolveGoogleSignIn(
            firebase = firebase,
            idToken = googleIdToken,
            accessToken = googleAccessToken,
        )
    }
    firebase.saveSession(session)
    persistFirebaseSessionToPlatform(session)
    return GoogleSignInResult(session.localId, session.email)
}

/**
 * Completes Google sign-in without linking. Prefers [pendingToken] when present (safe to
 * replay after a failed anonymous link); otherwise uses the Google id/access tokens.
 */
private suspend fun resolveGoogleSignIn(
    firebase: MiraFirebase,
    idToken: String?,
    accessToken: String?,
    pendingToken: String? = null,
    confirmation: SignInWithIdpResult.NeedsConfirmation? = null,
): FirebaseSession {
    // Prefer pendingToken (Firebase's replayable credential), then oauth/id token.
    val attempts = buildList {
        if (!pendingToken.isNullOrBlank()) {
            add(Triple(pendingToken, null as String?, null as String?))
        }
        if (!idToken.isNullOrBlank() || !accessToken.isNullOrBlank()) {
            add(Triple(null, idToken, accessToken))
        }
    }

    var lastConfirmation = confirmation
    val triedPending = mutableSetOf<String>()
    for ((pending, googleId, googleAccess) in attempts) {
        if (!pending.isNullOrBlank()) triedPending += pending
        when (
            val result = firebase.auth.signInWithGoogleIdToken(
                idToken = googleId,
                accessToken = googleAccess,
                pendingToken = pending,
                existingIdToken = null,
                returnIdpCredential = true,
            )
        ) {
            is SignInWithIdpResult.Success -> return result.session
            is SignInWithIdpResult.NeedsConfirmation -> {
                lastConfirmation = result
                val nextPending = result.pendingToken
                if (!nextPending.isNullOrBlank() && triedPending.add(nextPending)) {
                    when (
                        val retry = firebase.auth.signInWithGoogleIdToken(
                            pendingToken = nextPending,
                            existingIdToken = null,
                            returnIdpCredential = true,
                        )
                    ) {
                        is SignInWithIdpResult.Success -> return retry.session
                        is SignInWithIdpResult.NeedsConfirmation -> lastConfirmation = retry
                    }
                }
            }
        }
    }

    val email = lastConfirmation?.email
    val providers = lastConfirmation?.verifiedProviders.orEmpty()
    if (providers.any { it.equals("password", ignoreCase = true) }) {
        error(
            "An account already exists for" +
                (email?.let { " $it" } ?: " this email") +
                " with email/password. Sign in with email first, then link Google.",
        )
    }
    error(
        "Google sign-in needs another step" +
            (email?.let { " for $it" } ?: "") +
            ". Sign out, then try Continue with Google again.",
    )
}
