package com.joetr.mira.media

import com.joetr.mira.platform.desktopFileDialogOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.io.File

private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "heic", "webp", "gif", "bmp")
private val VIDEO_EXTENSIONS = setOf("mp4", "mov", "m4v", "avi", "webm", "mkv")

/** `java.awt.FileDialog` is the native open dialog on every desktop OS (NSOpenPanel on macOS). */
public actual suspend fun pickMedia(allowMultiple: Boolean): List<CaptureResult> = withContext(Dispatchers.IO) {
    val dialog = FileDialog(desktopFileDialogOwner, "Choose photos or videos", FileDialog.LOAD)
    dialog.isMultipleMode = allowMultiple
    val allowed = IMAGE_EXTENSIONS + VIDEO_EXTENSIONS
    dialog.setFilenameFilter { _, name -> name.substringAfterLast('.', "").lowercase() in allowed }
    dialog.isVisible = true

    val files = if (allowMultiple) {
        dialog.files?.toList().orEmpty()
    } else {
        val selected = dialog.file
        val directory = dialog.directory
        if (selected != null && directory != null) listOf(File(directory, selected)) else emptyList()
    }
    files.mapNotNull { it.toCaptureResult() }
}

private fun File.toCaptureResult(): CaptureResult? {
    if (!exists() || length() == 0L) return null
    val ext = extension.lowercase()
    val isVideo = ext in VIDEO_EXTENSIONS
    if (!isVideo && ext !in IMAGE_EXTENSIONS) return null
    return CaptureResult(
        kind = if (isVideo) CaptureKind.VIDEO else CaptureKind.PHOTO,
        path = absolutePath,
        bytes = null,
        mime = mimeFor(ext, isVideo),
        sizeBytes = length(),
        durationMs = 0,
    )
}

private fun mimeFor(extension: String, isVideo: Boolean): String = when {
    isVideo && extension == "mov" -> "video/quicktime"
    isVideo -> "video/$extension"
    extension == "jpg" -> "image/jpeg"
    else -> "image/$extension"
}
