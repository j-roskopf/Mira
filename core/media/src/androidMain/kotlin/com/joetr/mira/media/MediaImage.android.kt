package com.joetr.mira.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import kotlin.math.max

public actual fun decodePhoto(bytes: ByteArray, maxLongEdge: Int): ImageBitmap? {
    if (bytes.isEmpty()) return null

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    val options = BitmapFactory.Options().apply {
        inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxLongEdge)
    }
    val decoded = runCatching {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }.getOrNull() ?: return null

    val fitted = fitLongEdge(decoded, maxLongEdge)
    return applyExifOrientation(bytes, fitted).asImageBitmap()
}

/** Largest power-of-two sample that still keeps the long edge at least [maxLongEdge]. */
internal fun sampleSizeFor(width: Int, height: Int, maxLongEdge: Int): Int {
    if (maxLongEdge == Int.MAX_VALUE) return 1
    val longEdge = max(width, height)
    var sample = 1
    while (longEdge / (sample * 2) >= maxLongEdge) {
        sample *= 2
    }
    return sample
}

private fun fitLongEdge(bitmap: Bitmap, maxLongEdge: Int): Bitmap {
    if (maxLongEdge == Int.MAX_VALUE) return bitmap
    val longEdge = max(bitmap.width, bitmap.height)
    if (longEdge <= maxLongEdge) return bitmap
    val scale = maxLongEdge.toFloat() / longEdge
    val width = (bitmap.width * scale).toInt().coerceAtLeast(1)
    val height = (bitmap.height * scale).toInt().coerceAtLeast(1)
    val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
    if (scaled !== bitmap) bitmap.recycle()
    return scaled
}

internal fun applyExifOrientation(bytes: ByteArray, bitmap: Bitmap): Bitmap {
    val orientation = runCatching {
        ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> {
            matrix.postRotate(90f)
            matrix.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_TRANSVERSE -> {
            matrix.postRotate(270f)
            matrix.postScale(-1f, 1f)
        }
        else -> return bitmap
    }

    val upright = runCatching {
        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }.getOrNull() ?: return bitmap
    if (upright !== bitmap) bitmap.recycle()
    return upright
}
