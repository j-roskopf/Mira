package com.joetr.mira.platform

import android.app.Activity
import android.content.Intent
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

private var pendingExportBytes: ByteArray? = null
private var pendingExportResult: ((Boolean) -> Unit)? = null
private var pendingImportResult: ((ByteArray?) -> Unit)? = null

/** Android has no byte-array save dialog — `ACTION_CREATE_DOCUMENT` is the Storage Access Framework. */
public actual suspend fun exportFile(suggestedName: String, mimeType: String, bytes: ByteArray): Boolean {
    val activity = AndroidContextHolder.activity ?: return false
    return suspendCancellableCoroutine { continuation ->
        pendingExportBytes = bytes
        val callback: (Boolean) -> Unit = { continuation.resume(it) }
        pendingExportResult = callback
        continuation.invokeOnCancellation {
            if (pendingExportResult === callback) {
                pendingExportResult = null
                pendingExportBytes = null
            }
        }
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mimeType
            putExtra(Intent.EXTRA_TITLE, suggestedName)
        }
        activity.runOnUiThread {
            if (!launchAndroidActivityForResult(intent, AndroidRequestCodes.EXPORT_FILE) &&
                pendingExportResult === callback
            ) {
                pendingExportResult = null
                pendingExportBytes = null
                callback(false)
            }
        }
    }
}

public actual suspend fun importFile(extensions: Set<String>): ByteArray? {
    val activity = AndroidContextHolder.activity ?: return null
    return suspendCancellableCoroutine { continuation ->
        val callback: (ByteArray?) -> Unit = { continuation.resume(it) }
        pendingImportResult = callback
        continuation.invokeOnCancellation {
            if (pendingImportResult === callback) pendingImportResult = null
        }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        activity.runOnUiThread {
            if (!launchAndroidActivityForResult(intent, AndroidRequestCodes.IMPORT_FILE) &&
                pendingImportResult === callback
            ) {
                pendingImportResult = null
                callback(null)
            }
        }
    }
}

/** Routes the activity's result back to the awaiting [exportFile] or [importFile] call. */
public fun dispatchAndroidActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    when (requestCode) {
        AndroidRequestCodes.EXPORT_FILE -> {
            val callback = pendingExportResult ?: return
            pendingExportResult = null
            val bytes = pendingExportBytes
            pendingExportBytes = null
            val uri = data?.data
            if (resultCode != Activity.RESULT_OK || uri == null || bytes == null) {
                callback(false)
                return
            }
            val success = runCatching {
                AndroidContextHolder.application.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                true
            }.getOrDefault(false)
            callback(success)
        }
        AndroidRequestCodes.IMPORT_FILE -> {
            val callback = pendingImportResult ?: return
            pendingImportResult = null
            val uri = data?.data
            if (resultCode != Activity.RESULT_OK || uri == null) {
                callback(null)
                return
            }
            val bytes = runCatching {
                AndroidContextHolder.application.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }.getOrNull()
            callback(bytes)
        }
    }
}
