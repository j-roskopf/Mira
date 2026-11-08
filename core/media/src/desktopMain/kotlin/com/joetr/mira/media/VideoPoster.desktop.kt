package com.joetr.mira.media

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * Pulls one frame out with the FFmpeg the app already bundles for capture, playback and
 * transcoding. Nothing extra is shipped for posters.
 */
public actual suspend fun extractVideoPoster(
    path: String?,
    bytes: ByteArray?,
    mime: String,
): ByteArray? = withContext(Dispatchers.IO) {
    val binary = FfmpegBinary.path ?: return@withContext null
    if (path == null && bytes == null) return@withContext null

    val directory = runCatching { Files.createTempDirectory("mira-poster").toFile() }.getOrNull()
        ?: return@withContext null
    val output = File(directory, "poster.jpg")
    try {
        val input = if (path != null) {
            File(path)
        } else {
            File(directory, "input.mp4").apply { writeBytes(bytes!!) }
        }
        if (!input.isFile) return@withContext null

        val command = listOf(
            binary,
            "-hide_banner",
            "-loglevel", "error",
            "-y",
            "-i", input.absolutePath,
            "-frames:v", "1",
            "-vf", "scale=min($VideoPosterMaxLongEdge\\,iw):-2",
            "-q:v", "3",
            output.absolutePath,
        )
        val process = runCatching { ProcessBuilder(command).start() }.getOrNull()
            ?: return@withContext null
        process.errorStream.use { it.copyTo(java.io.OutputStream.nullOutputStream()) }
        if (!process.waitFor(15, TimeUnit.SECONDS)) {
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

public actual val needsVideoPosterRotationRepair: Boolean = false
