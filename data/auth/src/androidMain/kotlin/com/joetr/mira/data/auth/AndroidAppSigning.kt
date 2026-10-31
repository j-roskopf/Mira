package com.joetr.mira.data.auth

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

internal fun Context.signingCertificateSha1(): String? {
    val packageInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
        }
    val signatures =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            packageInfo.signatures
        }
    val signature = signatures?.firstOrNull() ?: return null
    val digest = MessageDigest.getInstance("SHA-1").digest(signature.toByteArray())
    return digest.joinToString(":") { byte -> "%02X".format(byte) }
}

internal const val GOOGLE_ANDROID_SIGN_IN_CERT_MISMATCH = "GOOGLE_ANDROID_SIGN_IN_CERT_MISMATCH"

internal fun googleSignInCertificateMismatchMessage(
    sha1: String?,
    isDebugBuild: Boolean,
): String {
    val buildType = if (isDebugBuild) "debug" else "release"
    val fingerprint = sha1 ?: "(run ./gradlew :androidApp:signingReport to list fingerprints)"
    return "Google sign-in failed (certificate mismatch). Add this $buildType SHA-1 fingerprint " +
        "in Firebase Project Settings → Your apps → com.joetr.mira, then re-download " +
        "google-services.json and rebuild:\n$fingerprint"
}

internal fun isGoogleSignInCertificateMismatch(detail: String): Boolean =
    detail.contains("reauth", ignoreCase = true) || detail.contains("[16]")
