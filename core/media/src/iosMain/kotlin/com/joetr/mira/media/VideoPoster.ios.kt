package com.joetr.mira.media

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.memScoped
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.AVFoundation.AVAssetImageGenerator
import platform.AVFoundation.AVURLAsset
import platform.CoreGraphics.CGSizeMake
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.create
import platform.Foundation.writeToFile
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalForeignApi::class)
public actual suspend fun extractVideoPoster(
    path: String?,
    bytes: ByteArray?,
    mime: String,
): ByteArray? = withContext(Dispatchers.Default) {
    val tempPath = if (path == null && bytes != null) {
        NSTemporaryDirectory() + "mira-poster-${nowMillisForPoster()}.mp4"
    } else {
        null
    }
    try {
        if (tempPath != null) {
            val data = bytes?.toPosterNSData() ?: return@withContext null
            if (!data.writeToFile(tempPath, true)) return@withContext null
        }
        val sourcePath = path ?: tempPath ?: return@withContext null
        val asset = AVURLAsset(uRL = NSURL.fileURLWithPath(sourcePath), options = null)
        val generator = AVAssetImageGenerator(asset = asset).apply {
            appliesPreferredTrackTransform = true
            maximumSize = CGSizeMake(
                VideoPosterMaxLongEdge.toDouble(),
                VideoPosterMaxLongEdge.toDouble(),
            )
        }
        val frame = generator.copyCGImageAtTime(
            CMTimeMakeWithSeconds(0.1, 600),
            actualTime = null,
            error = null,
        ) ?: generator.copyCGImageAtTime(
            CMTimeMakeWithSeconds(0.0, 600),
            actualTime = null,
            error = null,
        ) ?: return@withContext null
        val image = UIImage.imageWithCGImage(frame)
        UIImageJPEGRepresentation(image, 0.85)?.toByteArray()
    } catch (_: Exception) {
        null
    } finally {
        if (tempPath != null) NSFileManager.defaultManager.removeItemAtPath(tempPath, null)
    }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun ByteArray.toPosterNSData(): NSData = memScoped {
    if (isEmpty()) return@memScoped NSData()
    NSData.create(bytes = allocArrayOf(this@toPosterNSData), length = size.toULong())
}

@OptIn(ExperimentalTime::class)
private fun nowMillisForPoster(): Long = Clock.System.now().toEpochMilliseconds()

public actual val needsVideoPosterRotationRepair: Boolean = false
