package com.joetr.mira.media

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.memScoped
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSData
import platform.Foundation.NSURL
import platform.Foundation.create
import platform.Photos.PHAccessLevelAddOnly
import platform.Photos.PHAssetChangeRequest
import platform.Photos.PHAssetCreationRequest
import platform.Photos.PHAssetResourceCreationOptions
import platform.Photos.PHAssetResourceTypePhoto
import platform.Photos.PHAuthorizationStatusAuthorized
import platform.Photos.PHAuthorizationStatusLimited
import platform.Photos.PHPhotoLibrary
import kotlin.coroutines.resume

/**
 * Saves a captured photo or video into the user's Photos library.
 *
 * Uses add-only access: iOS shows a lightweight "allow Mira to add photos" prompt backed by
 * `NSPhotoLibraryAddUsageDescription`, and Mira never gains permission to read the library.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
public actual suspend fun saveToPhotoRoll(result: CaptureResult) {
    when (result.kind) {
        CaptureKind.AUDIO -> return
        CaptureKind.PHOTO, CaptureKind.VIDEO -> Unit
    }
    if (result.path == null && result.bytes == null) return
    if (!isAddOnlyAuthorized()) return
    publishToLibrary(result)
}

@OptIn(ExperimentalForeignApi::class)
private suspend fun isAddOnlyAuthorized(): Boolean = suspendCancellableCoroutine { continuation ->
    PHPhotoLibrary.requestAuthorizationForAccessLevel(PHAccessLevelAddOnly) { status ->
        continuation.resume(
            status == PHAuthorizationStatusAuthorized || status == PHAuthorizationStatusLimited,
        )
    }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun publishToLibrary(result: CaptureResult) {
    PHPhotoLibrary.sharedPhotoLibrary().performChanges(
        changeBlock = {
            val path = result.path
            when {
                result.kind == CaptureKind.VIDEO && path != null ->
                    PHAssetChangeRequest.creationRequestForAssetFromVideoAtFileURL(
                        NSURL.fileURLWithPath(path),
                    )
                path != null ->
                    PHAssetChangeRequest.creationRequestForAssetFromImageAtFileURL(
                        NSURL.fileURLWithPath(path),
                    )
                result.bytes != null -> {
                    val request = PHAssetCreationRequest.creationRequestForAsset()
                    request.addResourceWithType(
                        type = PHAssetResourceTypePhoto,
                        data = result.bytes.toNSData(),
                        options = PHAssetResourceCreationOptions(),
                    )
                }
            }
        },
        completionHandler = null,
    )
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun ByteArray.toNSData(): NSData = memScoped {
    if (isEmpty()) return@memScoped NSData()
    NSData.create(bytes = allocArrayOf(this@toNSData), length = size.toULong())
}
