package com.joetr.mira.media

import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.joetr.mira.platform.AndroidContextHolder
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

public actual fun archiveFitSupported(mime: String): Boolean = mime.startsWith("video/")

/**
 * Re-encodes with Media3's Transformer, the only video re-encoder available on Android without
 * bundling FFmpeg.
 *
 * The export target follows the same duration-derived budget as desktop: a longer recording gets a
 * lower bitrate and resolution so it fits the blob ceiling. If the encoder cannot get under the
 * limit, the result is split losslessly rather than skipped.
 */
@UnstableApi
public actual suspend fun fitArchiveMedia(request: ArchiveFitRequest): ArchiveFitResult {
    if (!request.mime.startsWith("video/")) return ArchiveFitResult.Unsupported
    val context = AndroidContextHolder.application
    val sourceSize = when {
        request.path != null -> File(request.path).takeIf { it.isFile }?.length()
        request.bytes != null -> request.bytes.size.toLong()
        else -> null
    } ?: return ArchiveFitResult.Failed
    if (sourceSize <= request.targetBytes) {
        return ArchiveFitResult.Whole(request.path, request.bytes, sourceSize, transcoded = false)
    }

    val directory = withContext(Dispatchers.IO) {
        runCatching { File(context.cacheDir, "mira-archive").apply { mkdirs() } }.getOrNull()
    } ?: return ArchiveFitResult.Failed
    val work = runCatching {
        java.nio.file.Files.createTempDirectory(directory.toPath(), "fit").toFile()
    }.getOrNull() ?: return ArchiveFitResult.Failed

    val input = when {
        request.path != null -> File(request.path).takeIf { it.isFile }
        request.bytes != null -> File(work, "input.mp4").apply { writeBytes(request.bytes) }
        else -> null
    } ?: run {
        work.deleteRecursively()
        return ArchiveFitResult.Failed
    }

    val durationMs = request.durationMs.takeIf { it > 0 } ?: probeDurationMs(input)
    val seconds = durationMs / 1000.0
    val output = File(work, "fit.mp4")
    val transcoded = if (seconds > 0) {
        // Media3 cannot always observe a stuck encoder; bound it so the push fails over to the
        // lossless split instead of leaving the archive spinning indefinitely.
        withTimeoutOrNull(TRANSCODE_TIMEOUT_MS) {
            transcode(input, output, seconds, request.targetBytes)
        }
    } else {
        null
    }

    if (transcoded != null && transcoded.length() in 1..request.hardLimitBytes) {
        return ArchiveFitResult.Whole(
            path = transcoded.absolutePath,
            bytes = null,
            sizeBytes = transcoded.length(),
            transcoded = true,
            onCleanup = { work.deleteRecursively() },
        )
    }
    val splitSource = listOfNotNull(transcoded, input)
        .filter { it.isFile && it.length() > 0 }
        .minByOrNull { it.length() }
        ?: input
    return splitFile(splitSource, work, request.partLimitBytes)
}

@UnstableApi
private suspend fun transcode(
    input: File,
    output: File,
    durationSeconds: Double,
    targetBytes: Long,
): File? = withContext(Dispatchers.IO) {
    val budgetKbps = (targetBytes.toDouble() * 8 / 1000 / durationSeconds).toInt()
    val videoKbps = (budgetKbps - AUDIO_KBPS).coerceAtLeast(MIN_VIDEO_KBPS)
    val edge = when {
        videoKbps >= 1000 -> 720
        videoKbps >= 400 -> 480
        else -> 360
    }
    val context = AndroidContextHolder.application
    val encoderFactory = DefaultEncoderFactory.Builder(context)
        .setRequestedVideoEncoderSettings(
            VideoEncoderSettings.Builder().setBitrate(videoKbps * 1000).build(),
        )
        .build()
    val item = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(input)))
        .setFrameRate(FRAME_RATE)
        .setEffects(Effects(emptyList(), listOf(Presentation.createForHeight(edge))))
        .build()

    suspendCancellableCoroutine { continuation ->
        val handler = Handler(Looper.getMainLooper())
        handler.post {
            val transformer = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setEncoderFactory(encoderFactory)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        if (continuation.isActive) {
                            continuation.resume(output.takeIf { it.isFile && it.length() > 0 })
                        }
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException,
                    ) {
                        if (continuation.isActive) continuation.resume(null)
                    }
                })
                .build()
            continuation.invokeOnCancellation {
                handler.post { transformer.cancel() }
            }
            runCatching { transformer.start(item, output.absolutePath) }
                .onFailure { if (continuation.isActive) continuation.resume(null) }
        }
    }
}

private fun probeDurationMs(input: File): Long {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(input.absolutePath)
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
    } catch (_: Exception) {
        0L
    } finally {
        retriever.release()
    }
}

private fun splitFile(source: File, directory: File, partLimitBytes: Long): ArchiveFitResult {
    val total = source.length()
    if (total <= 0) {
        directory.deleteRecursively()
        return ArchiveFitResult.Failed
    }
    val count = ((total + partLimitBytes - 1) / partLimitBytes).toInt().coerceAtLeast(1)
    val padding = count.toString().length.coerceAtLeast(3)
    val parts = mutableListOf<ArchiveFitPart>()
    val buffer = ByteArray(1 shl 20)
    source.inputStream().use { input ->
        var remaining = total
        var index = 1
        while (remaining > 0) {
            val want = minOf(partLimitBytes, remaining)
            val part = File(directory, "part${index.toString().padStart(padding, '0')}.mp4")
            var written = 0L
            part.outputStream().use { output ->
                while (written < want) {
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), want - written).toInt())
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    written += read
                }
            }
            if (written == 0L) {
                directory.deleteRecursively()
                return ArchiveFitResult.Failed
            }
            parts += ArchiveFitPart(part.absolutePath, null, written)
            remaining -= written
            index++
        }
    }
    return ArchiveFitResult.Split(parts, onCleanup = { directory.deleteRecursively() })
}

private const val AUDIO_KBPS: Int = 128
private const val MIN_VIDEO_KBPS: Int = 80
private const val FRAME_RATE: Int = 24
private const val TRANSCODE_TIMEOUT_MS: Long = 20 * 60 * 1000
