package com.joetr.mira.media

import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

public actual fun archiveFitSupported(mime: String): Boolean = mime.startsWith("video/")

/**
 * Re-encodes a recording to fit the archive, then splits it losslessly if even that is too large.
 *
 * The encode budget is derived from the duration rather than a fixed preset: `bitrate = bytes × 8
 * ÷ seconds`, minus the audio track. Longer recordings therefore trade resolution and bitrate for
 * fitting inside one blob. Splitting is a pure byte copy, so `cat part* > video.mp4` always restores
 * the original exactly.
 */
public actual suspend fun fitArchiveMedia(request: ArchiveFitRequest): ArchiveFitResult {
    if (!request.mime.startsWith("video/")) return ArchiveFitResult.Unsupported
    val sourceSize = sourceSizeOf(request) ?: return ArchiveFitResult.Failed
    if (sourceSize <= request.targetBytes) {
        return ArchiveFitResult.Whole(request.path, request.bytes, sourceSize, transcoded = false)
    }
    val binary = FfmpegBinary.path

    return withContext(Dispatchers.IO) {
        val directory = runCatching { java.nio.file.Files.createTempDirectory("mira-archive").toFile() }
            .getOrNull() ?: return@withContext ArchiveFitResult.Failed
        try {
            val input = materialize(request, directory) ?: return@withContext ArchiveFitResult.Failed
            val durationSeconds = when {
                request.durationMs > 0 -> request.durationMs / 1000.0
                binary != null -> probeDurationSeconds(binary, input)
                else -> null
            }
            val transcoded = if (binary != null && durationSeconds != null) {
                transcode(binary, input, File(directory, "fit.mp4"), durationSeconds, request.targetBytes)
            } else {
                null
            }
            if (transcoded != null && transcoded.length() in 1..request.hardLimitBytes) {
                return@withContext ArchiveFitResult.Whole(
                    path = transcoded.absolutePath,
                    bytes = null,
                    sizeBytes = transcoded.length(),
                    transcoded = true,
                    onCleanup = { directory.deleteRecursively() },
                )
            }
            // Prefer the smaller of the re-encode and the source; splitting preserves whatever
            // bytes it is handed, so a partially successful encode still helps.
            val splitSource = listOfNotNull(transcoded, input)
                .filter { it.isFile && it.length() > 0 }
                .minByOrNull { it.length() }
                ?: input
            split(splitSource, directory, request.partLimitBytes)
        } catch (error: Exception) {
            directory.deleteRecursively()
            ArchiveFitResult.Failed
        }
    }
}

private fun sourceSizeOf(request: ArchiveFitRequest): Long? = when {
    request.path != null -> File(request.path).takeIf { it.isFile }?.length()
    request.bytes != null -> request.bytes.size.toLong()
    else -> null
}

private fun materialize(request: ArchiveFitRequest, directory: File): File? = when {
    request.path != null -> File(request.path).takeIf { it.isFile }
    request.bytes != null -> File(directory, "input.mp4").apply { writeBytes(request.bytes) }
    else -> null
}

private fun probeDurationSeconds(binary: String, input: File): Double? = runCatching {
    val process = ProcessBuilder(binary, "-hide_banner", "-i", input.absolutePath)
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    if (!process.waitFor(30, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        return@runCatching null
    }
    val match = DURATION.find(output) ?: return@runCatching null
    val hours = match.groupValues[1].toInt()
    val minutes = match.groupValues[2].toInt()
    val seconds = match.groupValues[3].toDouble()
    hours * 3600 + minutes * 60 + seconds
}.getOrNull()?.takeIf { it > 0 }

private fun transcode(
    binary: String,
    input: File,
    output: File,
    durationSeconds: Double,
    targetBytes: Long,
): File? = runCatching {
    val budgetKbps = (targetBytes.toDouble() * 8 / 1000 / durationSeconds).toInt()
    val videoKbps = (budgetKbps - AUDIO_KBPS).coerceAtLeast(MIN_VIDEO_KBPS)
    val edge = when {
        videoKbps >= 1000 -> 720
        videoKbps >= 400 -> 480
        else -> 360
    }
    val command = buildList {
        add(binary)
        add("-hide_banner")
        add("-loglevel")
        add("error")
        add("-y")
        add("-i")
        add(input.absolutePath)
        add("-vf")
        add("scale=$edge:$edge:force_original_aspect_ratio=decrease:force_divisible_by=2,setsar=1")
        add("-r")
        add("24")
        add("-pix_fmt")
        add("yuv420p")
        addAll(archiveVideoEncoderArgs(videoKbps))
        add("-c:a")
        add("aac")
        add("-b:a")
        add("${AUDIO_KBPS}k")
        add("-ac")
        add("1")
        add("-ar")
        add("44100")
        add("-map_metadata")
        add("-1")
        add("-movflags")
        add("+faststart")
        add(output.absolutePath)
    }
    val process = ProcessBuilder(command).start()
    process.errorStream.use { it.copyTo(java.io.OutputStream.nullOutputStream()) }
    if (!process.waitFor(TRANSCODE_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
        process.destroyForcibly()
        return@runCatching null
    }
    if (process.exitValue() != 0 || !output.isFile || output.length() == 0L) return@runCatching null
    output
}.getOrNull()

/**
 * Archive encodes run once, offline, so they may use a slower preset than live capture.
 *
 * VideoToolbox has no `-preset`; it still accepts a `-maxrate` cap, so both encoder paths aim for
 * the same budget. Oversized output falls through to splitting.
 */
private fun archiveVideoEncoderArgs(kbps: Int): List<String> = when (desktopOs) {
    DesktopOs.MAC -> listOf(
        "-c:v", "h264_videotoolbox",
        "-b:v", "${kbps}k",
        "-maxrate", "${kbps}k",
        "-bufsize", "${kbps * 2}k",
    )
    DesktopOs.WINDOWS, DesktopOs.LINUX -> listOf(
        "-c:v", "libx264",
        "-preset", "veryfast",
        "-b:v", "${kbps}k",
        "-maxrate", "${kbps}k",
        "-bufsize", "${kbps * 2}k",
    )
}

private fun split(source: File, directory: File, partLimitBytes: Long): ArchiveFitResult {
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

private const val AUDIO_KBPS: Int = 64
private const val MIN_VIDEO_KBPS: Int = 80
private const val TRANSCODE_TIMEOUT_MINUTES: Long = 30
private val DURATION = Regex("""Duration:\s*(\d+):(\d+):(\d+(?:\.\d+)?)""")
