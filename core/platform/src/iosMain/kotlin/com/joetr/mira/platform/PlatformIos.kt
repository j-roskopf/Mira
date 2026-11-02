package com.joetr.mira.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.create
import platform.Foundation.timeIntervalSince1970
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.memScoped
import platform.UIKit.UIGraphicsBeginImageContextWithOptions
import platform.UIKit.UIGraphicsEndImageContext
import platform.UIKit.UIGraphicsGetImageFromCurrentImageContext
import platform.UIKit.UIApplication
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.UIKit.UIImpactFeedbackGenerator
import platform.UIKit.UIImpactFeedbackStyle
import platform.UIKit.UIPasteboard
import platform.posix.memcpy

public actual fun currentTimeMillis(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()

public actual fun isNetworkAvailable(): Boolean = true

public actual fun openUrl(url: String) {
    val nsUrl = platform.Foundation.NSURL.URLWithString(url) ?: return
    UIApplication.sharedApplication.openURL(
        nsUrl,
        options = emptyMap<Any?, Any?>(),
        completionHandler = null,
    )
}

public actual fun copyText(text: String) {
    UIPasteboard.generalPasteboard.string = text
}

public actual fun shareText(text: String) {
    val controller = UIActivityViewController(activityItems = listOf(text), applicationActivities = null)
    UIApplication.sharedApplication.keyWindow?.rootViewController?.presentViewController(
        controller,
        animated = true,
        completion = null,
    )
}

public actual fun keepScreenOn(enabled: Boolean) {
    UIApplication.sharedApplication.idleTimerDisabled = enabled
}

public actual fun hapticSuccess() {
    UIImpactFeedbackGenerator(UIImpactFeedbackStyle.UIImpactFeedbackStyleMedium).impactOccurred()
}

public actual fun playTimerCompleteSound() = Unit

public actual class ImageCapture {
    public actual companion object {
        public actual val isAvailable: Boolean = true
    }
}

@OptIn(ExperimentalForeignApi::class)
public actual suspend fun resizeImage(bytes: ByteArray, maxLongEdge: Int, quality: Int): ByteArray {
    if (bytes.isEmpty()) return bytes
    val data = bytes.toNSData()
    val image = UIImage(data = data) ?: return bytes

    val width: Double
    val height: Double
    image.size.useContents {
        width = this.width
        height = this.height
    }
    val longEdge = maxOf(width, height).coerceAtLeast(1.0)
    val scale = minOf(1.0, maxLongEdge / longEdge)
    val targetWidth = (width * scale).toInt().coerceAtLeast(1)
    val targetHeight = (height * scale).toInt().coerceAtLeast(1)

    val outputImage = if (scale < 1.0) {
        UIGraphicsBeginImageContextWithOptions(
            CGSizeMake(targetWidth.toDouble(), targetHeight.toDouble()),
            false,
            1.0,
        )
        image.drawInRect(CGRectMake(0.0, 0.0, targetWidth.toDouble(), targetHeight.toDouble()))
        val resized = UIGraphicsGetImageFromCurrentImageContext()
        UIGraphicsEndImageContext()
        resized ?: image
    } else {
        image
    }

    val jpegQuality = quality.coerceIn(0, 100) / 100.0
    val jpeg = UIImageJPEGRepresentation(outputImage, jpegQuality) ?: return bytes
    return jpeg.toByteArray()
}

@OptIn(ExperimentalForeignApi::class)
internal fun ByteArray.toNSData(): NSData = memScoped {
    if (isEmpty()) return@memScoped NSData()
    NSData.create(bytes = allocArrayOf(this@toNSData), length = size.toULong())
}

@OptIn(ExperimentalForeignApi::class)
internal fun NSData.toByteArray(): ByteArray {
    val length = this.length.toInt()
    if (length == 0) return ByteArray(0)
    val out = ByteArray(length)
    out.usePinned { pinned ->
        memcpy(pinned.addressOf(0), this.bytes, this.length)
    }
    return out
}

public actual suspend fun readClipboardText(): String? {
    val pasteboard = platform.UIKit.UIPasteboard.generalPasteboard
    return pasteboard.string
}

public actual fun isDebugBuild(): Boolean = false

public actual fun platformName(): String = "ios"

/**
 * This platform cannot report metering, so uploads are not held back.
 *
 * Desktop and web are effectively always on a connection the user chose; on iOS, reading the
 * cellular state needs a reachability dependency Mira does not otherwise carry.
 */
public actual fun isUnmeteredNetwork(): Boolean = true
