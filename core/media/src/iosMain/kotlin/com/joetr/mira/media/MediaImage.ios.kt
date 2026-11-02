package com.joetr.mira.media

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.useContents
import org.jetbrains.compose.resources.decodeToImageBitmap
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSData
import platform.Foundation.create
import platform.UIKit.UIGraphicsBeginImageContextWithOptions
import platform.UIKit.UIGraphicsEndImageContext
import platform.UIKit.UIGraphicsGetImageFromCurrentImageContext
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.UIKit.UIImageOrientation

@OptIn(ExperimentalForeignApi::class)
public actual fun decodePhoto(bytes: ByteArray, maxLongEdge: Int): ImageBitmap? {
    if (bytes.isEmpty()) return null
    val image = UIImage(data = bytes.toNSDataForImage()) ?: return null
    var width = 0.0
    var height = 0.0
    image.size.useContents {
        width = this.width
        height = this.height
    }
    if (width <= 0.0 || height <= 0.0) return null

    val longEdge = maxOf(width, height)
    val scale = if (maxLongEdge == Int.MAX_VALUE) 1.0 else minOf(1.0, maxLongEdge / longEdge)
    val upright = image.imageOrientation == UIImageOrientation.UIImageOrientationUp
    val data = if (upright && scale >= 1.0) {
        bytes.toNSDataForImage()
    } else {
        image.redrawnJpeg(width * scale, height * scale) ?: return null
    }
    return runCatching { data.toByteArray().decodeToImageBitmap() }.getOrNull()
}

/**
 * Redraws into [targetWidth]×[targetHeight], baking EXIF orientation into the pixels and optionally
 * downsampling so a thumbnail never keeps the camera-resolution bitmap alive.
 *
 * `drawInRect` is what applies the orientation; re-encoding afterwards produces a plain JPEG that
 * every decoder displays the same way.
 */
@OptIn(ExperimentalForeignApi::class)
private fun UIImage.redrawnJpeg(targetWidth: Double, targetHeight: Double): NSData? {
    if (targetWidth <= 0.0 || targetHeight <= 0.0) return null
    UIGraphicsBeginImageContextWithOptions(CGSizeMake(targetWidth, targetHeight), false, 1.0)
    drawInRect(CGRectMake(0.0, 0.0, targetWidth, targetHeight))
    val rendered = UIGraphicsGetImageFromCurrentImageContext()
    UIGraphicsEndImageContext()
    return rendered?.let { UIImageJPEGRepresentation(it, 0.95) }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun ByteArray.toNSDataForImage(): NSData = memScoped {
    if (isEmpty()) return@memScoped NSData()
    NSData.create(bytes = allocArrayOf(this@toNSDataForImage), length = size.toULong())
}
