package com.joetr.mira.media

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.joetr.mira.domain.CaptureQuality
import com.joetr.mira.platform.AndroidContextHolder
import com.joetr.mira.platform.requestAndroidPermissions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume
import kotlin.math.max

private const val PHOTO_REQUEST = 0x4D43
private const val VIDEO_REQUEST = 0x4D56

private var pendingNativeResult: ((CaptureResult?) -> Unit)? = null
private var pendingNativeFile: File? = null
private var pendingNativeQuality: CaptureQuality = CaptureQuality.MEDIUM

public actual fun supportsNativeCapture(kind: CaptureKind): Boolean =
    kind == CaptureKind.PHOTO || kind == CaptureKind.VIDEO

/**
 * Hands capture to whatever camera app the device ships with.
 *
 * The system camera already exposes flash, timer, grid, zoom, and the front/back switch — all of it
 * localised and familiar — so Mira never draws a shutter button of its own on Android. The output
 * still lands in app cache and still comes back as JPEG or mp4, so the rest of the media pipeline
 * cannot tell this apart from an in-app capture.
 */
public actual suspend fun captureWithNativeCamera(
    kind: CaptureKind,
    quality: CaptureQuality,
): CaptureResult? {
    if (pendingNativeResult != null) return null
    val activity = AndroidContextHolder.activity ?: return null
    val context = AndroidContextHolder.application

    val permissions = buildList {
        if (kind == CaptureKind.VIDEO) add(android.Manifest.permission.RECORD_AUDIO)
        add(android.Manifest.permission.CAMERA)
    }
    if (!requestAndroidPermissions(permissions).values.all { it }) return null

    val file = File(
        context.cacheDir,
        "mira-camera-${System.currentTimeMillis()}" +
            if (kind == CaptureKind.VIDEO) ".mp4" else ".jpg",
    )
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    val intent = when (kind) {
        CaptureKind.PHOTO -> Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
        }
        CaptureKind.VIDEO -> Intent(MediaStore.ACTION_VIDEO_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            // Camera apps only distinguish low (0) from high (1); medium is captured high and
            // kept as-is, because Android has no built-in re-encoder to downscale it afterwards.
            putExtra(
                MediaStore.EXTRA_VIDEO_QUALITY,
                if (quality == CaptureQuality.LOW) 0 else 1,
            )
            putExtra(MediaStore.EXTRA_DURATION_LIMIT, (CaptureLimits.MAX_DURATION_MS / 1000).toInt())
        }
        CaptureKind.AUDIO -> return null
    }.apply {
        addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // Some camera apps only receive the grant through the clip data rather than the flags.
        clipData = ClipData.newRawUri("", uri)
    }

    return suspendCancellableCoroutine { continuation ->
        val callback: (CaptureResult?) -> Unit = { continuation.resume(it) }
        pendingNativeResult = callback
        pendingNativeFile = file
        pendingNativeQuality = quality
        continuation.invokeOnCancellation {
            if (pendingNativeResult === callback) {
                pendingNativeResult = null
                pendingNativeFile = null
                file.delete()
            }
        }
        activity.runOnUiThread {
            // Do not ask PackageManager whether a camera exists: from Android 11 on, package
            // visibility can hide it even when the device has a perfectly good camera app. Just
            // launch it and treat ActivityNotFoundException as "no camera".
            val launched = runCatching {
                activity.startActivityForResult(
                    intent,
                    if (kind == CaptureKind.VIDEO) VIDEO_REQUEST else PHOTO_REQUEST,
                )
            }.isSuccess
            if (!launched && pendingNativeResult === callback) {
                pendingNativeResult = null
                pendingNativeFile = null
                file.delete()
                callback(null)
            }
        }
    }
}

/** Routes the camera app's result back to the awaiting [captureWithNativeCamera] call. */
public fun dispatchAndroidNativeCameraResult(requestCode: Int, resultCode: Int, data: Intent?) {
    if (requestCode != PHOTO_REQUEST && requestCode != VIDEO_REQUEST) return
    val callback = pendingNativeResult ?: return
    pendingNativeResult = null
    val file = pendingNativeFile
    pendingNativeFile = null
    val quality = pendingNativeQuality

    if (resultCode != Activity.RESULT_OK || file == null || !file.exists() || file.length() == 0L) {
        file?.delete()
        callback(null)
        return
    }

    val isVideo = requestCode == VIDEO_REQUEST
    if (isVideo) {
        callback(
            CaptureResult(
                kind = CaptureKind.VIDEO,
                path = file.absolutePath,
                bytes = null,
                mime = "video/mp4",
                sizeBytes = file.length(),
                durationMs = probeDurationMs(file),
            ),
        )
        return
    }

    // Camera apps ignore photo-resolution hints, so the chosen quality is applied here, off the
    // main thread: decode, honour EXIF orientation, downscale, and re-encode as JPEG.
    CoroutineScope(Dispatchers.IO).launch {
        val processed = runCatching { processPhoto(file, quality) }.getOrNull() ?: file
        callback(
            CaptureResult(
                kind = CaptureKind.PHOTO,
                path = processed.absolutePath,
                bytes = null,
                mime = "image/jpeg",
                sizeBytes = processed.length(),
                durationMs = 0,
            ),
        )
    }
}

/**
 * Re-encodes a camera JPEG for [quality].
 *
 * HIGH keeps the original file untouched when it is already within the pixel budget, so the best
 * setting does not lose detail to a pointless re-compression. LOW and MEDIUM sample down before
 * decoding, which also keeps peak memory well below a full-sensor bitmap.
 */
private fun processPhoto(file: File, quality: CaptureQuality): File {
    val targetLongEdge = when (quality) {
        CaptureQuality.LOW -> 1080
        CaptureQuality.MEDIUM -> 1920
        CaptureQuality.HIGH -> 4096
    }
    val compression = when (quality) {
        CaptureQuality.LOW -> 60
        CaptureQuality.MEDIUM -> 75
        CaptureQuality.HIGH -> 90
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return file

    val longEdge = max(bounds.outWidth, bounds.outHeight)
    if (quality == CaptureQuality.HIGH && longEdge <= targetLongEdge) return file

    val options = BitmapFactory.Options().apply {
        inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, targetLongEdge)
    }
    val decoded = BitmapFactory.decodeFile(file.absolutePath, options) ?: return file
    val scaled = if (max(decoded.width, decoded.height) <= targetLongEdge) {
        decoded
    } else {
        val scale = targetLongEdge.toFloat() / max(decoded.width, decoded.height)
        val width = (decoded.width * scale).toInt().coerceAtLeast(1)
        val height = (decoded.height * scale).toInt().coerceAtLeast(1)
        Bitmap.createScaledBitmap(decoded, width, height, true).also {
            if (it !== decoded) decoded.recycle()
        }
    }
    val upright = applyExifOrientation(file.readBytes(), scaled)
    if (upright !== scaled) scaled.recycle()

    val output = File(file.parentFile, "mira-camera-${System.currentTimeMillis()}.jpg")
    val written = runCatching {
        FileOutputStream(output).use { stream ->
            upright.compress(Bitmap.CompressFormat.JPEG, compression, stream)
        }
    }.getOrDefault(false)
    upright.recycle()
    if (!written || output.length() == 0L) {
        output.delete()
        return file
    }
    file.delete()
    return output
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
