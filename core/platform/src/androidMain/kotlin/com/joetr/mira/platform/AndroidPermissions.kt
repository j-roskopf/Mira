package com.joetr.mira.platform

import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

private const val CAPTURE_PERMISSION_REQUEST = 0x4D49

private var pendingCaptureResult: ((Map<String, Boolean>) -> Unit)? = null

/**
 * Requests a set of dangerous permissions, showing the system dialog when needed.
 *
 * Returns which of [permissions] are granted after the prompt resolves. If the activity is not
 * available, or a permission was permanently denied, the corresponding value is false.
 */
public suspend fun requestAndroidPermissions(permissions: List<String>): Map<String, Boolean> {
    val activity = AndroidContextHolder.activity
        ?: return permissions.associateWith { false }
    val context = AndroidContextHolder.application
    val alreadyGranted = permissions.filter {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }.toSet()
    val missing = permissions.filterNot { it in alreadyGranted }
    if (missing.isEmpty()) return permissions.associateWith { true }

    return suspendCancellableCoroutine { continuation ->
        val callback: (Map<String, Boolean>) -> Unit = { results ->
            continuation.resume(
                permissions.associateWith { it in alreadyGranted || results[it] == true },
            )
        }
        pendingCaptureResult = callback
        continuation.invokeOnCancellation {
            if (pendingCaptureResult === callback) pendingCaptureResult = null
        }
        activity.runOnUiThread {
            ActivityCompat.requestPermissions(
                activity,
                missing.toTypedArray(),
                CAPTURE_PERMISSION_REQUEST,
            )
        }
    }
}

/** Routes the activity's permission result back to the awaiting [requestAndroidPermissions]. */
public fun dispatchAndroidPermissionResult(
    requestCode: Int,
    permissions: Array<out String>,
    grantResults: IntArray,
) {
    if (requestCode != CAPTURE_PERMISSION_REQUEST) return
    val callback = pendingCaptureResult ?: return
    pendingCaptureResult = null
    val results = permissions.mapIndexed { index, permission ->
        permission to (grantResults.getOrNull(index) == PackageManager.PERMISSION_GRANTED)
    }.toMap()
    callback(results)
}
