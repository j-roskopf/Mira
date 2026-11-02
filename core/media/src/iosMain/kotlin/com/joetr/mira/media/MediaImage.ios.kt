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
public actual fun decodePhoto(bytes: ByteArray): ImageBitmap? {
    if (bytes.isEmpty()) return null
    val image = UIImage(data = bytes.toNSDataForImage()) ?: return null
    val data = if (image.imageOrientation == UIImageOrientation.UIImageOrientationUp) {
        bytes.toNSDataForImage()
    } else {
        image.uprightJpeg() ?: return null
    }
    return runCatching { data.toByteArray().decodeToImageBitmap() }.getOrNull()
}

/**
 * Redraws the image into an upright context, baking its EXIF orientation into the pixels.
 *
 * `drawInRect` is what applies the orientation; re-encoding afterwards produces a plain JPEG that
 * every decoder displays the same way.
 */
@OptIn(ExperimentalForeignApi::class)
private fun UIImage.uprightJpeg(): NSData? {
    var width = 0.0
    var height = 0.0
    size.useContents {
        width = this.width
        height = this.height
    }
    if (width <= 0.0 || height <= 0.0) return null

    UIGraphicsBeginImageContextWithOptions(CGSizeMake(width, height), false, 1.0)
    drawInRect(CGRectMake(0.0, 0.0, width, height))
    val rendered = UIGraphicsGetImageFromCurrentImageContext()
    UIGraphicsEndImageContext()
    return rendered?.let { UIImageJPEGRepresentation(it, 0.95) }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun ByteArray.toNSDataForImage(): NSData = memScoped {
    if (isEmpty()) return@memScoped NSData()
    NSData.create(bytes = allocArrayOf(this@toNSDataForImage), length = size.toULong())
}
