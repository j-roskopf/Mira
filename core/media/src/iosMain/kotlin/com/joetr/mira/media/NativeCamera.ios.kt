package com.joetr.mira.media

import com.joetr.mira.domain.CaptureQuality
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.AVFoundation.AVAssetExportPreset1280x720
import platform.AVFoundation.AVAssetExportPreset1920x1080
import platform.AVFoundation.AVAssetExportPreset640x480
import platform.AVFoundation.AVAssetExportSession
import platform.AVFoundation.AVAssetExportSessionStatusCompleted
import platform.AVFoundation.AVFileTypeMPEG4
import platform.AVFoundation.AVURLAsset
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.CoreMedia.CMTimeGetSeconds
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIGraphicsBeginImageContextWithOptions
import platform.UIKit.UIGraphicsEndImageContext
import platform.UIKit.UIGraphicsGetImageFromCurrentImageContext
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.UIKit.UIImagePickerController
import platform.UIKit.UIImagePickerControllerDelegateProtocol
import platform.UIKit.UIImagePickerControllerMediaURL
import platform.UIKit.UIImagePickerControllerOriginalImage
import platform.UIKit.UIImagePickerControllerQualityTypeHigh
import platform.UIKit.UIImagePickerControllerQualityTypeLow
import platform.UIKit.UIImagePickerControllerQualityTypeMedium
import platform.UIKit.UIImagePickerControllerSourceType
import platform.UIKit.UINavigationControllerDelegateProtocol
import platform.darwin.NSObject
import kotlin.coroutines.resume
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

public actual fun supportsNativeCapture(kind: CaptureKind): Boolean =
    kind == CaptureKind.PHOTO || kind == CaptureKind.VIDEO

// UIImagePickerController holds its delegate weakly, so the app side must keep it alive until the
// picker finishes.
private var activeNativeCameraDelegate: NSObject? = null

/**
 * Hands capture to the system camera through `UIImagePickerController`.
 *
 * The picker owns the camera UI — shutter, front/back switch, flash, live photo and video controls —
 * so Mira draws none of it. Photos come back as JPEG; videos are exported to mp4/H.264 so they keep
 * matching every other platform's attachments.
 */
@OptIn(ExperimentalForeignApi::class)
public actual suspend fun captureWithNativeCamera(
    kind: CaptureKind,
    quality: CaptureQuality,
): CaptureResult? {
    if (activeNativeCameraDelegate != null) return null
    if (!UIImagePickerController.isSourceTypeAvailable(UIImagePickerControllerSourceType.UIImagePickerControllerSourceTypeCamera)) {
        return null
    }

    return suspendCancellableCoroutine { continuation ->
        val picker = UIImagePickerController().apply {
            sourceType = UIImagePickerControllerSourceType.UIImagePickerControllerSourceTypeCamera
            if (kind == CaptureKind.VIDEO) {
                // The picker expects UTIs here; "public.movie" is the stable movie type.
                mediaTypes = listOf("public.movie")
                videoMaximumDuration = CaptureLimits.MAX_DURATION_MS / 1000.0
                videoQuality = when (quality) {
                    CaptureQuality.LOW -> UIImagePickerControllerQualityTypeLow
                    CaptureQuality.MEDIUM -> UIImagePickerControllerQualityTypeMedium
                    CaptureQuality.HIGH -> UIImagePickerControllerQualityTypeHigh
                }
            }
        }
        val delegate = NativeCameraDelegate(kind, quality, continuation) {
            activeNativeCameraDelegate = null
        }
        activeNativeCameraDelegate = delegate
        picker.delegate = delegate
        continuation.invokeOnCancellation {
            if (activeNativeCameraDelegate === delegate) {
                picker.dismissViewControllerAnimated(true, completion = null)
                activeNativeCameraDelegate = null
            }
        }
        UIApplication.sharedApplication.keyWindow?.rootViewController?.presentViewController(
            picker,
            animated = true,
            completion = null,
        )
    }
}

@OptIn(ExperimentalForeignApi::class, ExperimentalTime::class)
private class NativeCameraDelegate(
    private val kind: CaptureKind,
    private val quality: CaptureQuality,
    private val continuation: kotlinx.coroutines.CancellableContinuation<CaptureResult?>,
    private val onFinished: () -> Unit,
) : NSObject(), UIImagePickerControllerDelegateProtocol, UINavigationControllerDelegateProtocol {

    override fun imagePickerControllerDidCancel(picker: UIImagePickerController) {
        picker.dismissViewControllerAnimated(true, completion = null)
        onFinished()
        continuation.resume(null)
    }

    override fun imagePickerController(
        picker: UIImagePickerController,
        didFinishPickingMediaWithInfo: Map<Any?, *>,
    ) {
        picker.dismissViewControllerAnimated(true, completion = null)
        onFinished()

        when (kind) {
            CaptureKind.PHOTO -> {
                val image = didFinishPickingMediaWithInfo[UIImagePickerControllerOriginalImage] as? UIImage
                continuation.resume(image?.jpegBytes(quality)?.toCaptureResult())
            }
            CaptureKind.VIDEO -> {
                val source = didFinishPickingMediaWithInfo[UIImagePickerControllerMediaURL] as? NSURL
                // The picker's temporary movie is removed as soon as this delegate returns, so
                // move it somewhere stable before the asynchronous export begins.
                val stable = source?.let { copyToStableTemp(it) }
                CoroutineScope(Dispatchers.Default).launch {
                    continuation.resume(exportVideo(stable, quality))
                    stable?.path?.let { NSFileManager.defaultManager.removeItemAtPath(it, null) }
                }
            }
            CaptureKind.AUDIO -> continuation.resume(null)
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun ByteArray.toCaptureResult(): CaptureResult {
    return CaptureResult(
        kind = CaptureKind.PHOTO,
        path = null,
        bytes = this,
        mime = "image/jpeg",
        sizeBytes = size.toLong(),
        durationMs = 0,
    )
}

@OptIn(ExperimentalForeignApi::class, ExperimentalTime::class)
private fun copyToStableTemp(source: NSURL): NSURL? {
    val sourcePath = source.path ?: return null
    val extension = sourcePath.substringAfterLast('.', "mov")
    val destination = NSTemporaryDirectory() + "mira-camera-${nowMillis()}.$extension"
    val copied = NSFileManager.defaultManager.copyItemAtPath(
        sourcePath,
        toPath = destination,
        error = null,
    )
    return if (copied) NSURL.fileURLWithPath(destination) else null
}

/**
 * Encodes the captured image at the chosen quality, downscaling only when the source is larger than
 * the tier allows. Redrawing bakes in the camera's EXIF orientation, so the stored JPEG is upright
 * even for a photo taken sideways.
 */
@OptIn(ExperimentalForeignApi::class)
private fun UIImage.jpegBytes(quality: CaptureQuality): ByteArray? {
    val maxLongEdge = when (quality) {
        CaptureQuality.LOW -> 1080.0
        CaptureQuality.MEDIUM -> 1920.0
        CaptureQuality.HIGH -> 4096.0
    }
    val compression = when (quality) {
        CaptureQuality.LOW -> 0.6
        CaptureQuality.MEDIUM -> 0.75
        CaptureQuality.HIGH -> 0.92
    }
    var width = 0.0
    var height = 0.0
    size.useContents {
        width = this.width
        height = this.height
    }
    if (width <= 0.0 || height <= 0.0) return null

    val longEdge = maxOf(width, height)
    val data = if (longEdge > maxLongEdge) {
        val scale = maxLongEdge / longEdge
        val targetWidth = width * scale
        val targetHeight = height * scale
        UIGraphicsBeginImageContextWithOptions(CGSizeMake(targetWidth, targetHeight), false, 1.0)
        drawInRect(CGRectMake(0.0, 0.0, targetWidth, targetHeight))
        val rendered = UIGraphicsGetImageFromCurrentImageContext()
        UIGraphicsEndImageContext()
        rendered?.let { UIImageJPEGRepresentation(it, compression) }
    } else {
        UIImageJPEGRepresentation(this, compression)
    }
    return data?.toByteArray()
}

/**
 * `UIImagePickerController` records QuickTime, so a native video has to be re-containerised before
 * it can join the archive. `AVAssetExportSession` is part of the OS, not a bundled binary, and its
 * preset is the one place iOS lets us honour the chosen quality tier.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalTime::class)
private suspend fun exportVideo(source: NSURL?, quality: CaptureQuality): CaptureResult? {
    if (source == null) return null
    val asset = AVURLAsset(uRL = source, options = null)
    val outputPath = NSTemporaryDirectory() + "mira-camera-${nowMillis()}.mp4"
    val outputUrl = NSURL.fileURLWithPath(outputPath)
    NSFileManager.defaultManager.removeItemAtPath(outputPath, null)

    val preset = when (quality) {
        CaptureQuality.LOW -> AVAssetExportPreset640x480
        CaptureQuality.MEDIUM -> AVAssetExportPreset1280x720
        CaptureQuality.HIGH -> AVAssetExportPreset1920x1080
    }
    val session = AVAssetExportSession.exportSessionWithAsset(
        asset = asset,
        presetName = preset,
    ) ?: return null
    session.outputURL = outputUrl
    session.outputFileType = AVFileTypeMPEG4

    val completed = CompletableDeferred<Boolean>()
    session.exportAsynchronouslyWithCompletionHandler {
        completed.complete(session.status == AVAssetExportSessionStatusCompleted)
    }
    if (completed.await() != true) return null

    val attributes = NSFileManager.defaultManager.attributesOfItemAtPath(outputPath, null)
    val size = (attributes?.get("NSFileSize") as? Number)?.toLong() ?: 0L
    if (size == 0L) return null

    return CaptureResult(
        kind = CaptureKind.VIDEO,
        path = outputPath,
        bytes = null,
        mime = "video/mp4",
        sizeBytes = size,
        durationMs = (CMTimeGetSeconds(asset.duration) * 1000.0).toLong(),
    )
}

@OptIn(ExperimentalTime::class)
private fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()
