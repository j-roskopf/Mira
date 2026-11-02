package com.joetr.mira.media

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Image
import org.jetbrains.skia.SamplingMode
import kotlin.math.max

public actual fun decodePhoto(bytes: ByteArray, maxLongEdge: Int): ImageBitmap? {
    if (bytes.isEmpty()) return null
    val image = runCatching { Image.makeFromEncoded(bytes) }.getOrNull() ?: return null
    val longEdge = max(image.width, image.height)
    if (maxLongEdge == Int.MAX_VALUE || longEdge <= maxLongEdge) {
        return image.toComposeImageBitmap()
    }

    val scale = maxLongEdge.toFloat() / longEdge
    val width = (image.width * scale).toInt().coerceAtLeast(1)
    val height = (image.height * scale).toInt().coerceAtLeast(1)
    val scaled = Bitmap().also { it.allocN32Pixels(width, height) }
    val pixels = scaled.peekPixels() ?: return image.toComposeImageBitmap()
    if (!image.scalePixels(pixels, SamplingMode.LINEAR, true)) {
        return image.toComposeImageBitmap()
    }
    return Image.makeFromBitmap(scaled).toComposeImageBitmap()
}
