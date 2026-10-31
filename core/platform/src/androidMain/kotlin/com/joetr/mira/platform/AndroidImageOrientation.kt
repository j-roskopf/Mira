package com.joetr.mira.platform

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

internal fun decodeOrientedBitmap(bytes: ByteArray): Bitmap? {
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
    val rotation = ByteArrayInputStream(bytes).use { ExifInterface(it).rotationDegrees }
    return bitmap.rotate(rotation)
}

public fun rotateJpeg(bytes: ByteArray, rotationDegrees: Int, quality: Int = 90): ByteArray {
    if (rotationDegrees == 0) return bytes
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return bytes
    val rotated = bitmap.rotate(rotationDegrees)
    if (rotated !== bitmap) bitmap.recycle()
    return rotated.toJpeg(quality)
}

private fun Bitmap.rotate(rotationDegrees: Int): Bitmap {
    if (rotationDegrees == 0) return this
    val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
    return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
}

private fun Bitmap.toJpeg(quality: Int): ByteArray {
    val out = ByteArrayOutputStream()
    compress(Bitmap.CompressFormat.JPEG, quality, out)
    recycle()
    return out.toByteArray()
}
