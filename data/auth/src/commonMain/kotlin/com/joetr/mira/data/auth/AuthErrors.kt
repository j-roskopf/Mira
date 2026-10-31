package com.joetr.mira.data.auth

import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

private val errorJson = Json { ignoreUnknownKeys = true }

internal class FirebaseAuthException(
    val code: String,
    userMessage: String,
) : IllegalStateException(userMessage)

internal fun authException(raw: String): FirebaseAuthException =
    FirebaseAuthException(raw.trim(), authErrorMessage(raw))

internal fun Throwable.firebaseAuthCode(): String? = (this as? FirebaseAuthException)?.code

internal fun matchesFirebaseAuthCode(throwable: Throwable, vararg codes: String): Boolean {
    val code = throwable.firebaseAuthCode().orEmpty()
    return codes.any { code.contains(it, ignoreCase = true) }
}

/** Refresh token is no longer valid; persisted credentials should be cleared. */
internal fun isUnrecoverableSessionError(throwable: Throwable): Boolean =
    matchesFirebaseAuthCode(
        throwable,
        "TOKEN_EXPIRED",
        "INVALID_REFRESH_TOKEN",
        "USER_NOT_FOUND",
        "USER_DISABLED",
    )

/**
 * Runs a Firebase Identity Toolkit / Cloud Functions call, translating a 4xx
 * [ClientRequestException] into a friendly [IllegalStateException] using the
 * Identity Toolkit `error.message` code (e.g. `EMAIL_EXISTS`).
 */
internal suspend fun <T> runFirebaseAuthCall(block: suspend () -> T): T {
    try {
        return block()
    } catch (e: ClientRequestException) {
        throw authException(extractFirebaseErrorCode(e) ?: e.message.orEmpty())
    } catch (e: IllegalStateException) {
        val message = e.message.orEmpty()
        if (message.startsWith("Firebase Auth error:")) {
            throw authException(message.removePrefix("Firebase Auth error:").trim())
        }
        throw e
    }
}

private suspend fun extractFirebaseErrorCode(e: ClientRequestException): String? {
    val body = runCatching { e.response.bodyAsText() }.getOrNull() ?: return null
    return parseAuthErrorBody(body)
}

/** Firebase Identity Toolkit and Google OAuth use different `error` JSON shapes. */
internal fun parseAuthErrorBody(body: String): String? {
    val element = runCatching { errorJson.parseToJsonElement(body) }.getOrNull()
        ?: return body.trim().takeIf { it.isNotBlank() }
    val obj = element as? JsonObject ?: return body.trim().takeIf { it.isNotBlank() }

    when (val error = obj["error"]) {
        is JsonObject -> error["message"]?.jsonPrimitive?.contentOrNull?.let { return it }
        is JsonPrimitive -> {
            val code = error.contentOrNull ?: return body.trim().takeIf { it.isNotBlank() }
            val description = obj["error_description"]?.jsonPrimitive?.contentOrNull
            return if (description != null) "$code: $description" else code
        }
        else -> Unit
    }

    return body.trim().takeIf { it.isNotBlank() }
}

/**
 * Maps Firebase Identity Toolkit error codes/messages to user-friendly copy.
 */
internal fun authErrorMessage(raw: String): String {
    val message = raw.trim()
    return when {
        message.contains("EMAIL_EXISTS", ignoreCase = true) ->
            "An account with this email already exists. Try signing in instead."
        message.contains("USER_NOT_FOUND", ignoreCase = true) ->
            "No account found for this email. Use Create account instead."
        message.contains("EMAIL_NOT_FOUND", ignoreCase = true) ->
            "No account found for this email. Use Create account instead."
        message.contains("INCOMPLETE_SESSION", ignoreCase = true) ->
            "Sign-in succeeded but the session was incomplete. Please try again."
        message.contains("INVALID_LOGIN_CREDENTIALS", ignoreCase = true) ||
            message.contains("INVALID_PASSWORD", ignoreCase = true) ||
            message.contains("INVALID_CREDENTIAL", ignoreCase = true) ->
            "Incorrect email or password. If you started sign-up before, use Forgot password to set one."
        message.contains("OPERATION_NOT_ALLOWED", ignoreCase = true) &&
            message.contains("verify the new email", ignoreCase = true) ->
            "This email is already on your account. Use Forgot password to set a password, then sign in."
        message.contains("USER_DISABLED", ignoreCase = true) ->
            "This account has been disabled."
        message.contains("TOO_MANY_ATTEMPTS_TRY_LATER", ignoreCase = true) ->
            "Too many attempts. Please wait a few minutes and try again."
        message.contains("WEAK_PASSWORD", ignoreCase = true) ->
            "Password is too weak. Use at least 6 characters."
        message.contains("INVALID_EMAIL", ignoreCase = true) ->
            "That doesn't look like a valid email address."
        message.contains("CREDENTIAL_TOO_OLD_LOGIN_AGAIN", ignoreCase = true) ||
            message.contains("TOKEN_EXPIRED", ignoreCase = true) ||
            message.contains("INVALID_REFRESH_TOKEN", ignoreCase = true) ->
            "Please sign in again to continue."
        message.contains("FEDERATED_USER_ID_ALREADY_LINKED", ignoreCase = true) ||
            message.contains("CREDENTIAL_ALREADY_IN_USE", ignoreCase = true) ->
            "That Google account is already linked to another user."
        message.startsWith("GOOGLE_ANDROID_SIGN_IN_CERT_MISMATCH:") ->
            message.removePrefix("GOOGLE_ANDROID_SIGN_IN_CERT_MISMATCH:").trim()
        message.contains("reauth", ignoreCase = true) || message.contains("[16]") ->
            "Google sign-in failed (certificate mismatch). Add the app's SHA-1 fingerprint " +
                "(debug and release) in Firebase Project Settings → Your apps → com.joetr.mira, " +
                "re-download google-services.json, and rebuild."
        message.contains("invalid_client", ignoreCase = true) ||
            message.contains("client_secret is missing", ignoreCase = true) ->
            "Google sign-in requires mira.google.webClientSecret in local.properties " +
                "(uncommented, same Web OAuth client as webClientId). Rebuild: ./gradlew :composeApp:run"
        message.contains("redirect_uri_mismatch", ignoreCase = true) ->
            "Google redirect URI mismatch. In Google Cloud Console → Credentials → your Web OAuth client, " +
                "add this exact Authorized redirect URI: ${webOAuthRedirectUriForErrorMessage()}"
        message.contains("invalid_grant", ignoreCase = true) ->
            when {
                message.contains("redirect", ignoreCase = true) ->
                    "Google redirect URI mismatch. Add ${webOAuthRedirectUriForErrorMessage()} to your " +
                        "Web OAuth client's authorized redirect URIs."
                message.contains("code", ignoreCase = true) ->
                    "Google sign-in timed out or was already used. Close the browser tab and try again."
                else ->
                    "That verification code is invalid or has expired."
            }
        message.isNotBlank() -> message
        else -> "Authentication failed"
    }
}

/** Web builds include the current origin; other platforms show common dev URIs. */
internal expect fun webOAuthRedirectUriForErrorMessage(): String
