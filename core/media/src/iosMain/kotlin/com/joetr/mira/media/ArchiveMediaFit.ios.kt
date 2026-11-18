package com.joetr.mira.media

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.memScoped
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import platform.AVFoundation.AVAssetExportPreset1280x720
import platform.AVFoundation.AVAssetExportPreset1920x1080
import platform.AVFoundation.AVAssetExportPreset640x480
import platform.AVFoundation.AVAssetExportPresetLowQuality
import platform.AVFoundation.AVAssetExportSession
import platform.AVFoundation.AVAssetExportSessionStatusCompleted
import platform.AVFoundation.AVFileTypeMPEG4
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.fileLengthLimit
import platform.AVFoundation.timeRange
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.CoreMedia.CMTimeRangeMake
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.create
import platform.Foundation.writeToFile

public actual fun archiveFitSupported(mime: String): Boolean = mime.startsWith("video/")

/**
 * Re-encodes with `AVAssetExportSession`, the system encoder iOS already uses for capture.
 *
 * The preset and frame size follow a duration-derived bitrate budget, so a longer recording picks
 * a smaller preset instead of being skipped. `fileLengthLimit` is a backstop; if the export still
 * exceeds the blob ceiling it is split into time ranges and exported as ordered parts.
 */
@OptIn(ExperimentalForeignApi::class)
public actual suspend fun fitArchiveMedia(request: ArchiveFitRequest): ArchiveFitResult =
    withContext(Dispatchers.Default) {
        if (!request.mime.startsWith("video/")) return@withContext ArchiveFitResult.Unsupported
        val sourceSize = when {
            request.path != null -> fileSize(request.path)
            request.bytes != null -> request.bytes.size.toLong()
            else -> null
        } ?: return@withContext ArchiveFitResult.Failed
        if (sourceSize <= request.targetBytes) {
            return@withContext ArchiveFitResult.Whole(
                request.path,
                request.bytes,
                sourceSize,
                transcoded = false,
            )
        }

        val directory = NSTemporaryDirectory() + "mira-archive-${nowMillis()}"
        NSFileManager.defaultManager.createDirectoryAtPath(directory, true, null, null)
        try {
            val inputPath = when {
                request.path != null -> request.path
                request.bytes != null -> {
                    val path = "$directory/input.mp4"
                    val data = request.bytes.toNSDataForArchive()
                    if (!data.writeToFile(path, true)) return@withContext ArchiveFitResult.Failed
                    path
                }
                else -> return@withContext ArchiveFitResult.Failed
            }
            val asset = AVURLAsset(uRL = NSURL.fileURLWithPath(inputPath), options = null)
            val durationSeconds = request.durationMs.takeIf { it > 0 }?.div(1000.0)
                ?: CMTimeGetSeconds(asset.duration)
            if (durationSeconds <= 0) return@withContext ArchiveFitResult.Failed

            val preset = presetFor(durationSeconds, request.targetBytes)
            val outputPath = "$directory/fit.mp4"
            if (exportClip(asset, outputPath, preset, null, request.hardLimitBytes)) {
                val size = fileSize(outputPath)
                if (size in 1..request.hardLimitBytes) {
                    return@withContext ArchiveFitResult.Whole(
                        path = outputPath,
                        bytes = null,
                        sizeBytes = size,
                        transcoded = true,
                        onCleanup = { NSFileManager.defaultManager.removeItemAtPath(directory, null) },
                    )
                }
            }
            // Re-encode could not get under the ceiling: split by time. Each part is a normal mp4.
            val parts = splitByTime(
                asset = asset,
                directory = directory,
                preset = preset,
                durationSeconds = durationSeconds,
                estimatedWholeBytes = fileSize(outputPath).takeIf { it > 0 } ?: sourceSize,
                partLimitBytes = request.partLimitBytes,
            ) ?: return@withContext ArchiveFitResult.Failed
            ArchiveFitResult.Split(
                parts = parts,
                onCleanup = { NSFileManager.defaultManager.removeItemAtPath(directory, null) },
            )
        } catch (error: Exception) {
            NSFileManager.defaultManager.removeItemAtPath(directory, null)
            ArchiveFitResult.Failed
        }
    }

private fun presetFor(durationSeconds: Double, targetBytes: Long): String {
    val budgetKbps = (targetBytes.toDouble() * 8 / 1000 / durationSeconds).toInt()
    val videoKbps = budgetKbps - AUDIO_KBPS
    return when {
        videoKbps >= 1500 -> AVAssetExportPreset1920x1080
        videoKbps >= 600 -> AVAssetExportPreset1280x720
        videoKbps >= 250 -> AVAssetExportPreset640x480
        else -> AVAssetExportPresetLowQuality
    }
}

@OptIn(ExperimentalForeignApi::class)
private suspend fun splitByTime(
    asset: AVURLAsset,
    directory: String,
    preset: String,
    durationSeconds: Double,
    estimatedWholeBytes: Long,
    partLimitBytes: Long,
): List<ArchiveFitPart>? {
    // 20% headroom: variable bitrate means equal time slices are not equal size slices.
    val count = ((estimatedWholeBytes * 1.2 / partLimitBytes).toInt() + 1).coerceAtLeast(2)
    val sliceSeconds = durationSeconds / count
    val padding = count.toString().length.coerceAtLeast(3)
    val parts = mutableListOf<ArchiveFitPart>()
    for (index in 0 until count) {
        val start = index * sliceSeconds
        val length = minOf(sliceSeconds, durationSeconds - start)
        if (length <= 0.05) break
        val path = "$directory/part${(index + 1).toString().padStart(padding, '0')}.mp4"
        val range = CMTimeRangeMake(
            CMTimeMakeWithSeconds(start, PREFERRED_TIMESCALE),
            CMTimeMakeWithSeconds(length, PREFERRED_TIMESCALE),
        )
        if (!exportClip(asset, path, preset, range, partLimitBytes)) return null
        val size = fileSize(path)
        if (size == 0L || size > partLimitBytes) return null
        parts += ArchiveFitPart(path, null, size)
    }
    return parts.takeIf { it.isNotEmpty() }
}

@OptIn(ExperimentalForeignApi::class)
private suspend fun exportClip(
    asset: AVURLAsset,
    outputPath: String,
    preset: String,
    timeRange: kotlinx.cinterop.CValue<platform.CoreMedia.CMTimeRange>?,
    fileLengthLimit: Long,
): Boolean {
    NSFileManager.defaultManager.removeItemAtPath(outputPath, null)
    val session = AVAssetExportSession.exportSessionWithAsset(asset, preset) ?: return false
    session.outputURL = NSURL.fileURLWithPath(outputPath)
    session.outputFileType = AVFileTypeMPEG4
    session.fileLengthLimit = fileLengthLimit
    timeRange?.let { session.timeRange = it }
    val completed = CompletableDeferred<Boolean>()
    session.exportAsynchronouslyWithCompletionHandler {
        completed.complete(session.status == AVAssetExportSessionStatusCompleted)
    }
    val finished = withTimeoutOrNull(EXPORT_TIMEOUT_MS) { completed.await() }
    if (finished == null) {
        session.cancelExport()
        return false
    }
    return finished && fileSize(outputPath) > 0
}

@OptIn(ExperimentalForeignApi::class)
private fun fileSize(path: String): Long {
    val attributes = NSFileManager.defaultManager.attributesOfItemAtPath(path, null)
    return (attributes?.get("NSFileSize") as? Number)?.toLong() ?: 0L
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun ByteArray.toNSDataForArchive(): NSData = memScoped {
    NSData.create(bytes = allocArrayOf(this@toNSDataForArchive), length = size.toULong())
}

private const val AUDIO_KBPS: Int = 64
private const val PREFERRED_TIMESCALE: Int = 600
private const val EXPORT_TIMEOUT_MS: Long = 20 * 60 * 1000
private const val ARCHIVE_TEMP_PREFIX: String = "mira-archive-"

private fun nowMillis(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()
