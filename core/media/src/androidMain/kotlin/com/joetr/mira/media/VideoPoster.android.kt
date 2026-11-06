package com.joetr.mira.media

import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max

public actual suspend fun extractVideoPoster(
    path: String?,
    bytes: ByteArray?,
    mime: String,
): ByteArray? = withContext(Dispatchers.IO) {
    val temp = if (path == null && bytes != null) {
        runCatching {
            File.createTempFile("mira-poster-", ".mp4").apply { writeBytes(bytes) }
        }.getOrNull()
    } else {
        null
    }
    if (path == null && temp == null) return@withContext null

    val retriever = MediaMetadataRetriever()
    try {
        when {
            path != null -> retriever.setDataSource(path)
            temp != null -> retriever.setDataSource(temp.absolutePath)
        }
        val frame = retriever.frameAt(0L) ?: retriever.frameAt(-1L) ?: return@withContext null
        val rotation = retriever
            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            ?.toIntOrNull()
            ?: 0
        val upright = frame.rotated(rotation)
        val fitted = upright.fitLongEdge(VideoPosterMaxLongEdge)
        try {
            ByteArrayOutputStream().use { out ->
                fitted.compress(Bitmap.CompressFormat.JPEG, 85, out)
                out.toByteArray()
            }
        } finally {
            fitted.recycle()
        }
    } catch (_: Exception) {
        null
    } finally {
        runCatching { retriever.release() }
        temp?.delete()
    }
}

private fun MediaMetadataRetriever.frameAt(timeUs: Long): Bitmap? =
    runCatching {
        getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
    }.getOrNull()

private fun Bitmap.rotated(degrees: Int): Bitmap {
    if (degrees == 0) return this
    val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
    val rotated = Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
    if (rotated !== this) recycle()
    return rotated
}

private fun Bitmap.fitLongEdge(maxLongEdge: Int): Bitmap {
    val longEdge = max(width, height)
    if (longEdge <= maxLongEdge) return this
    val scale = maxLongEdge.toFloat() / longEdge
    val targetWidth = (width * scale).toInt().coerceAtLeast(1)
    val targetHeight = (height * scale).toInt().coerceAtLeast(1)
    val scaled = Bitmap.createScaledBitmap(this, targetWidth, targetHeight, true)
    if (scaled !== this) recycle()
    return scaled
}
