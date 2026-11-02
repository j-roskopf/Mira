package com.joetr.mira.media

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.joetr.mira.platform.AndroidContextHolder
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

private const val PICK_MEDIA_REQUEST_CODE = 0x4D52
private const val MAX_PICK_ITEMS = 50

private var pendingPickResult: ((List<CaptureResult>) -> Unit)? = null

/**
 * Android's system Photo Picker (API 33+) needs no runtime permission at all — that is its whole
 * point over `READ_MEDIA_IMAGES`/`READ_MEDIA_VIDEO`. Older OS versions fall back to the generic
 * document picker filtered to images and videos, which is also permission-free.
 */
public actual suspend fun pickMedia(allowMultiple: Boolean): List<CaptureResult> {
    val activity = AndroidContextHolder.activity ?: return emptyList()
    return suspendCancellableCoroutine { continuation ->
        val callback: (List<CaptureResult>) -> Unit = { continuation.resume(it) }
        pendingPickResult = callback
        continuation.invokeOnCancellation {
            if (pendingPickResult === callback) pendingPickResult = null
        }
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Intent(MediaStore.ACTION_PICK_IMAGES).apply {
                if (allowMultiple) putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, MAX_PICK_ITEMS)
            }
        } else {
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
                if (allowMultiple) putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
        }
        activity.runOnUiThread {
            @Suppress("DEPRECATION")
            activity.startActivityForResult(intent, PICK_MEDIA_REQUEST_CODE)
        }
    }
}

/** Routes the activity's result back to the awaiting [pickMedia] call. */
public fun dispatchAndroidMediaPickerResult(requestCode: Int, resultCode: Int, data: Intent?) {
    if (requestCode != PICK_MEDIA_REQUEST_CODE) return
    val callback = pendingPickResult ?: return
    pendingPickResult = null
    if (resultCode != Activity.RESULT_OK || data == null) {
        callback(emptyList())
        return
    }
    val uris = buildList {
        data.data?.let { add(it) }
        data.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) add(clip.getItemAt(i).uri)
        }
    }
    val context = AndroidContextHolder.application
    callback(uris.mapIndexedNotNull { index, uri -> uriToCaptureResult(context, uri, index) })
}

/** Copies a picked item into the app's cache, since the content:// URI itself may outlive the grant. */
private fun uriToCaptureResult(context: Context, uri: Uri, index: Int): CaptureResult? {
    val resolver = context.contentResolver
    val mime = resolver.getType(uri) ?: return null
    val isVideo = mime.startsWith("video/")
    if (!isVideo && !mime.startsWith("image/")) return null
    val extension = if (isVideo) "mp4" else mime.substringAfter('/').ifBlank { "jpg" }
    val file = File(context.cacheDir, "mira-picked-${System.currentTimeMillis()}-$index.$extension")
    val copied = runCatching {
        resolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        } != null
    }.getOrDefault(false)
    if (!copied || !file.exists() || file.length() == 0L) return null

    val durationMs = if (isVideo) probeDurationMs(file) else 0L
    return CaptureResult(
        kind = if (isVideo) CaptureKind.VIDEO else CaptureKind.PHOTO,
        path = file.absolutePath,
        bytes = null,
        mime = mime,
        sizeBytes = file.length(),
        durationMs = durationMs,
    )
}

private fun probeDurationMs(file: File): Long {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(file.absolutePath)
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
    } catch (_: Exception) {
        0L
    } finally {
        retriever.release()
    }
}
