package com.joetr.mira.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.dataWithContentsOfURL
import platform.Foundation.writeToFile
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UniformTypeIdentifiers.UTTypeZIP
import platform.darwin.NSObject
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume

// The delegate is held weakly by UIDocumentPickerViewController, so a strong reference must live
// somewhere else until the picker finishes — this is that somewhere.
private var activeDocumentPickerDelegate: NSObject? = null

@OptIn(ExperimentalForeignApi::class)
public actual suspend fun exportFile(suggestedName: String, mimeType: String, bytes: ByteArray): Boolean {
    val tempPath = NSTemporaryDirectory() + suggestedName
    val data = bytes.toNSData()
    if (!data.writeToFile(tempPath, true)) return false
    val tempUrl = NSURL.fileURLWithPath(tempPath)

    return suspendCancellableCoroutine { continuation ->
        val picker = UIDocumentPickerViewController(forExportingURLs = listOf(tempUrl), asCopy = true)
        val delegate = ExportPickerDelegate(continuation) {
            activeDocumentPickerDelegate = null
            NSFileManager.defaultManager.removeItemAtPath(tempPath, null)
        }
        activeDocumentPickerDelegate = delegate
        picker.delegate = delegate
        presentPicker(picker)
    }
}

@OptIn(ExperimentalForeignApi::class)
public actual suspend fun importFile(extensions: Set<String>): ByteArray? {
    return suspendCancellableCoroutine { continuation ->
        val picker = UIDocumentPickerViewController(
            forOpeningContentTypes = listOf(UTTypeZIP),
        )
        val delegate = ImportPickerDelegate(continuation) { activeDocumentPickerDelegate = null }
        activeDocumentPickerDelegate = delegate
        picker.delegate = delegate
        presentPicker(picker)
    }
}

private fun presentPicker(picker: UIDocumentPickerViewController) {
    UIApplication.sharedApplication.keyWindow?.rootViewController?.presentViewController(
        picker,
        animated = true,
        completion = null,
    )
}

private class ExportPickerDelegate(
    private val continuation: Continuation<Boolean>,
    private val onFinished: () -> Unit,
) : NSObject(), UIDocumentPickerDelegateProtocol {
    override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
        onFinished()
        continuation.resume(true)
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
        onFinished()
        continuation.resume(false)
    }
}

@OptIn(ExperimentalForeignApi::class)
private class ImportPickerDelegate(
    private val continuation: Continuation<ByteArray?>,
    private val onFinished: () -> Unit,
) : NSObject(), UIDocumentPickerDelegateProtocol {
    override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
        onFinished()
        val url = didPickDocumentsAtURLs.firstOrNull() as? NSURL
        if (url == null) {
            continuation.resume(null)
            return
        }
        val accessed = url.startAccessingSecurityScopedResource()
        val data = NSData.dataWithContentsOfURL(url)
        if (accessed) url.stopAccessingSecurityScopedResource()
        continuation.resume(data?.toByteArray())
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
        onFinished()
        continuation.resume(null)
    }
}
