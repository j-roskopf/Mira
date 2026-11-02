package com.joetr.mira.media

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSItemProvider
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.PhotosUI.PHPickerConfiguration
import platform.PhotosUI.PHPickerFilter
import platform.PhotosUI.PHPickerResult
import platform.PhotosUI.PHPickerViewController
import platform.PhotosUI.PHPickerViewControllerDelegateProtocol
import platform.UIKit.UIApplication
import platform.darwin.NSObject
import kotlin.coroutines.resume

// Held strongly until the picker finishes, mirroring the document-picker delegate in
// ExportFiles.ios.kt — PHPickerViewController only holds its delegate weakly.
private var activePickerDelegate: NSObject? = null

/**
 * `PHPickerViewController` runs out-of-process, so it needs no `NSPhotoLibraryUsageDescription`
 * and no runtime permission prompt at all — the picker, not the app, ever touches the library.
 */
@OptIn(ExperimentalForeignApi::class)
public actual suspend fun pickMedia(allowMultiple: Boolean): List<CaptureResult> {
    val deferred = CompletableDeferred<List<CaptureResult>>()
    val configuration = PHPickerConfiguration().apply {
        filter = PHPickerFilter.anyFilterMatchingSubfilters(
            listOf(PHPickerFilter.imagesFilter(), PHPickerFilter.videosFilter()),
        )
        selectionLimit = if (allowMultiple) 0L else 1L
    }
    val picker = PHPickerViewController(configuration = configuration)
    val delegate = MediaPickerDelegate(deferred) { activePickerDelegate = null }
    activePickerDelegate = delegate
    picker.delegate = delegate
    UIApplication.sharedApplication.keyWindow?.rootViewController?.presentViewController(
        picker,
        animated = true,
        completion = null,
    )
    return deferred.await()
}

@OptIn(ExperimentalForeignApi::class)
private class MediaPickerDelegate(
    private val deferred: CompletableDeferred<List<CaptureResult>>,
    private val onFinished: () -> Unit,
) : NSObject(), PHPickerViewControllerDelegateProtocol {
    override fun picker(picker: PHPickerViewController, didFinishPicking: List<*>) {
        picker.dismissViewControllerAnimated(true, completion = null)
        onFinished()
        val results = didFinishPicking.filterIsInstance<PHPickerResult>()
        if (results.isEmpty()) {
            deferred.complete(emptyList())
            return
        }
        CoroutineScope(Dispatchers.Default).launch {
            val loaded = results.mapIndexed { index, result ->
                async { loadPickerResult(result, index) }
            }.awaitAll()
            deferred.complete(loaded.filterNotNull())
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private suspend fun loadPickerResult(result: PHPickerResult, index: Int): CaptureResult? {
    val provider = result.itemProvider
    val typeIdentifier = provider.registeredTypeIdentifiers.firstOrNull() as? String ?: return null
    val isVideo = typeIdentifier.contains("movie", ignoreCase = true) ||
        typeIdentifier.contains("video", ignoreCase = true)

    // loadFileRepresentation's temp file is deleted the moment the handler returns, so it must
    // be copied out synchronously, inside the handler, before suspending back to the caller.
    val destPath = suspendCancellableCoroutine<String?> { continuation ->
        provider.loadFileRepresentationForTypeIdentifier(typeIdentifier) { url: NSURL?, error: NSError? ->
            val sourcePath = url?.path
            if (error != null || sourcePath == null) {
                continuation.resume(null)
                return@loadFileRepresentationForTypeIdentifier
            }
            val extension = sourcePath.substringAfterLast('.', if (isVideo) "mp4" else "jpg")
            val destPath = NSTemporaryDirectory() + "mira-picked-${nowMillisForPicker()}-$index.$extension"
            val copied = NSFileManager.defaultManager.copyItemAtPath(sourcePath, toPath = destPath, error = null)
            continuation.resume(if (copied) destPath else null)
        }
    } ?: return null

    val attributes = NSFileManager.defaultManager.attributesOfItemAtPath(destPath, null)
    val size = (attributes?.get("NSFileSize") as? Number)?.toLong() ?: 0L
    if (size == 0L) return null

    return CaptureResult(
        kind = if (isVideo) CaptureKind.VIDEO else CaptureKind.PHOTO,
        path = destPath,
        bytes = null,
        mime = if (isVideo) "video/mp4" else "image/jpeg",
        sizeBytes = size,
        durationMs = 0,
    )
}

@OptIn(kotlin.time.ExperimentalTime::class)
private fun nowMillisForPicker(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()
