package com.joetr.mira.media

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import com.joetr.mira.platform.AndroidContextHolder
import com.joetr.mira.platform.requestAndroidPermissions
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/**
 * Copies a captured photo or video into the shared MediaStore collection, so it shows up in the
 * device Gallery as well as in Mira.
 *
 * On API 29+ this uses scoped storage and needs no runtime permission. Older releases still require
 * `WRITE_EXTERNAL_STORAGE`; if the user declines, the journal copy is enough.
 */
public actual suspend fun saveToPhotoRoll(result: CaptureResult) {
    val isVideo = when (result.kind) {
        CaptureKind.PHOTO -> false
        CaptureKind.VIDEO -> true
        CaptureKind.AUDIO -> return
    }
    if (result.path == null && result.bytes == null) return

    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        val granted = requestAndroidPermissions(
            listOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE),
        ).values.all { it }
        if (!granted) return
    }

    val context = AndroidContextHolder.application
    runCatching {
        if (isVideo) {
            insertIntoPhotoRoll(context, isVideo = true) { output ->
                openSource(result)?.use { input -> input.copyTo(output) }
            }
        } else {
            // Gallery viewers may trust the MediaStore orientation column, the JPEG EXIF tag, or the
            // raw pixels — and disagree with each other when those tell different stories. Bake the
            // rotation into the pixels and drop the tag so every consumer sees the same upright image.
            val bytes = uprightJpeg(result) ?: return@runCatching
            insertIntoPhotoRoll(context, isVideo = false) { output -> output.write(bytes) }
        }
    }
}

private fun insertIntoPhotoRoll(
    context: Context,
    isVideo: Boolean,
    write: (java.io.OutputStream) -> Unit,
) {
    val resolver = context.contentResolver
    val collection = if (isVideo) {
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    } else {
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    }
    val displayName = "Mira-${System.currentTimeMillis()}" + if (isVideo) ".mp4" else ".jpg"
    val mime = if (isVideo) "video/mp4" else "image/jpeg"

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val relativePath = (if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES) + "/Mira"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: return
        runCatching {
            resolver.openOutputStream(uri)?.use(write) ?: error("no output stream for $uri")
            val published = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            resolver.update(uri, published, null, null)
        }.onFailure {
            resolver.delete(uri, null, null)
            throw it
        }
    } else {
        @Suppress("DEPRECATION")
        val targetDir = File(
            Environment.getExternalStoragePublicDirectory(
                if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES,
            ),
            "Mira",
        ).apply { mkdirs() }
        val target = File(targetDir, displayName)
        target.outputStream().use(write)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            @Suppress("DEPRECATION")
            put(MediaStore.MediaColumns.DATA, target.absolutePath)
        }
        val uri = resolver.insert(collection, values)
        if (uri == null) target.delete()
    }
}

/**
 * Returns JPEG bytes whose pixels are already upright. The attached file is normally re-encoded
 * upright by the capture pipeline, but at high quality an already-small photo is kept untouched and
 * still carries its EXIF rotation — honour it here so the gallery never has to.
 */
private fun uprightJpeg(result: CaptureResult): ByteArray? {
    val original = result.path?.let { path ->
        File(path).takeIf(File::exists)?.readBytes()
    } ?: result.bytes ?: return null

    val orientation = runCatching {
        ExifInterface(ByteArrayInputStream(original)).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    if (orientation == ExifInterface.ORIENTATION_NORMAL ||
        orientation == ExifInterface.ORIENTATION_UNDEFINED
    ) {
        return original
    }

    val bitmap = BitmapFactory.decodeByteArray(original, 0, original.size) ?: return original
    val upright = applyExifOrientation(original, bitmap)
    val encoded = runCatching {
        ByteArrayOutputStream().use { output ->
            upright.compress(Bitmap.CompressFormat.JPEG, 95, output)
            output.toByteArray()
        }
    }.getOrDefault(ByteArray(0))
    upright.recycle()
    return encoded.takeIf { it.isNotEmpty() } ?: original
}

private fun openSource(result: CaptureResult): InputStream? =
    result.path?.let { path -> File(path).takeIf(File::exists)?.inputStream() }
        ?: result.bytes?.inputStream()
