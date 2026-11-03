package com.joetr.mira.media

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

public actual val supportsVideoNormalization: Boolean
    get() = FfmpegBinary.isAvailable

/**
 * Re-encodes HEVC to H.264 with the same bundled FFmpeg used for capture and playback.
 *
 * A file already in H.264 is returned byte-for-byte so a normal upload never pays for a needless
 * transcode. The container stays mp4 and the audio is re-encoded to AAC, matching the rest of the
 * archive.
 */
public actual suspend fun normalizeVideo(bytes: ByteArray, mime: String): ByteArray? {
    if (!isHevcVideo(bytes)) return bytes
    val binary = FfmpegBinary.path ?: return null
    return withContext(Dispatchers.IO) {
        val directory = runCatching { java.nio.file.Files.createTempDirectory("mira-transcode").toFile() }
            .getOrNull() ?: return@withContext null
        try {
            val input = File(directory, "input.mp4").apply { writeBytes(bytes) }
            val output = File(directory, "output.mp4")
            val command = buildList {
                add(binary)
                add("-hide_banner")
                add("-loglevel")
                add("error")
                add("-y")
                add("-i")
                add(input.absolutePath)
                addAll(videoEncoderArgs())
                add("-tag:v:0")
                add("avc1")
                add("-c:a")
                add("aac")
                add("-b:a")
                add(CaptureLimits.AUDIO_BITRATE.toString())
                add("-ac")
                add(CaptureLimits.AUDIO_CHANNELS.toString())
                add("-ar")
                add(CaptureLimits.AUDIO_SAMPLE_RATE.toString())
                add("-movflags")
                add("+faststart")
                add(output.absolutePath)
            }
            val process = runCatching { ProcessBuilder(command).start() }.getOrNull()
                ?: return@withContext null
            process.errorStream.use { it.copyTo(java.io.OutputStream.nullOutputStream()) }
            if (!process.waitFor(TRANSCODE_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                process.destroyForcibly()
                return@withContext null
            }
            if (process.exitValue() != 0 || !output.isFile || output.length() == 0L) {
                return@withContext null
            }
            output.readBytes()
        } finally {
            directory.deleteRecursively()
        }
    }
}

/** Ten minutes of footage on hardware encoders finishes in well under this. */
private const val TRANSCODE_TIMEOUT_MINUTES: Long = 10
