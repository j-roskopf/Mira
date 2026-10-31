package com.joetr.mira.data.auth

import com.joetr.mira.network.MiraFirebase
import com.joetr.mira.network.FirebaseSession

/**
 * Thin, error-mapped wrappers around [MiraFirebase.auth] used by
 * [DefaultSessionRepository] for the email/password sign-up flow.
 */
internal suspend fun signUpWithEmailPassword(
    firebase: MiraFirebase,
    email: String,
    password: String,
): FirebaseSession = runFirebaseAuthCall {
    firebase.auth.signUpWithEmail(email = email, password = password)
}

internal suspend fun signInWithEmailPassword(
    firebase: MiraFirebase,
    email: String,
    password: String,
): FirebaseSession = runFirebaseAuthCall {
    firebase.auth.signInWithPassword(email = email, password = password)
}

internal suspend fun upgradeAnonymousWithEmailPassword(
    firebase: MiraFirebase,
    idToken: String,
    email: String,
    password: String,
): FirebaseSession = runFirebaseAuthCall {
    firebase.auth.upgradeAnonymousWithEmail(idToken = idToken, email = email, password = password)
}

/**
 * Links email/password to the current anonymous Firebase session, falling back when the
 * guest uid is stale or the email is already registered elsewhere.
 */
internal suspend fun signUpFromAnonymousSession(
    firebase: MiraFirebase,
    email: String,
    password: String,
): FirebaseSession {
    val idToken = firebase.currentSession()?.idToken ?: throw authException("No active session")
    return runCatching {
        upgradeAnonymousWithEmailPassword(firebase, idToken, email, password)
    }.getOrElse { error ->
        when {
            isEmailAlreadyExistsError(error) ->
                signInToExistingEmailAccount(firebase, email, password)
            isUserNotFoundError(error) -> {
                firebase.saveSession(null)
                signUpWithEmailPassword(firebase, email, password)
            }
            else -> throw error
        }
    }
}

private suspend fun signInToExistingEmailAccount(
    firebase: MiraFirebase,
    email: String,
    password: String,
): FirebaseSession = runCatching {
    signInWithEmailPassword(firebase, email, password)
}.getOrElse { signInError ->
    if (isMissingPasswordAccountError(signInError)) {
        throw authException(
            "EMAIL_EXISTS: This email is registered but has no password yet. " +
                "Use Forgot password to set one, then sign in.",
        )
    }
    throw signInError
}

private fun isEmailAlreadyExistsError(error: Throwable): Boolean =
    matchesFirebaseAuthCode(error, "EMAIL_EXISTS") ||
        error.message.orEmpty().contains("already exists", ignoreCase = true)

private fun isUserNotFoundError(error: Throwable): Boolean =
    matchesFirebaseAuthCode(error, "USER_NOT_FOUND")

private fun isMissingPasswordAccountError(error: Throwable): Boolean =
    matchesFirebaseAuthCode(
        error,
        "USER_NOT_FOUND",
        "EMAIL_NOT_FOUND",
        "INVALID_LOGIN_CREDENTIALS",
        "INVALID_PASSWORD",
    ) || error.message.orEmpty().contains("Incorrect email or password", ignoreCase = true)
