package com.joetr.mira.data.auth

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.joetr.mira.network.MiraFirebase
import com.joetr.mira.platform.AndroidContextHolder
import com.joetr.mira.platform.MiraConfig
import com.joetr.mira.platform.isDebugBuild

public actual suspend fun googleSignIn(firebase: MiraFirebase): GoogleSignInResult {
    val serverClientId = MiraConfig.GOOGLE_WEB_CLIENT_ID
    require(serverClientId.isNotBlank() && !serverClientId.startsWith("YOUR_")) {
        "Google sign-in is not configured. Set mira.google.webClientId in local.properties " +
            "(Google Cloud OAuth Web client ID), then rebuild."
    }
    val context: Context = AndroidContextHolder.activity
        ?: throw authException("Google sign-in needs an Activity context")

    // Do not set a nonce here — Firebase REST signInWithIdp doesn't participate in our
    // nonce challenge, and a mismatched/hashed nonce has caused silent failures in the field.
    val option = GetSignInWithGoogleOption.Builder(serverClientId).build()
    val request = GetCredentialRequest.Builder()
        .addCredentialOption(option)
        .build()
    val credentialManager = CredentialManager.create(context)
    val response = try {
        credentialManager.getCredential(request = request, context = context)
    } catch (e: NoCredentialException) {
        throw authException(
            "No Google account available. Add a Google account on this device, and make sure " +
                "this app's SHA-1 fingerprint is added in Firebase Project Settings → Your apps.",
        )
    } catch (e: GetCredentialCancellationException) {
        val detail = e.message.orEmpty()
        if (isGoogleSignInCertificateMismatch(detail)) {
            throwGoogleSignInCertificateMismatch(context)
        }
        throw authException("Google sign-in was cancelled")
    } catch (e: GetCredentialException) {
        val detail = e.message.orEmpty()
        if (isGoogleSignInCertificateMismatch(detail)) {
            throwGoogleSignInCertificateMismatch(context)
        }
        throw authException(detail.ifBlank { "Google sign-in failed" })
    }
    val credential = response.credential
    val idToken = if (
        credential is CustomCredential &&
        credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
    ) {
        GoogleIdTokenCredential.createFrom(credential.data).idToken
    } else {
        throw authException("Unexpected credential type returned from Credential Manager")
    }
    return signInToFirebaseWithGoogleIdToken(firebase, idToken)
}

private fun throwGoogleSignInCertificateMismatch(context: Context): Nothing {
    val message = googleSignInCertificateMismatchMessage(
        sha1 = context.signingCertificateSha1(),
        isDebugBuild = isDebugBuild(),
    )
    throw authException("$GOOGLE_ANDROID_SIGN_IN_CERT_MISMATCH:$message")
}
