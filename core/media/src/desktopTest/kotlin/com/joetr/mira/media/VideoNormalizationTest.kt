package com.joetr.mira.media

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

class VideoNormalizationTest {

    @Test
    fun h264SampleEntryIsNotMistakenForHevc() {
        val bytes = "....avc1....ftypisom".encodeToByteArray()
        assertFalse(isHevcVideo(bytes))
    }

    @Test
    fun hevcSampleEntryIsDetected() {
        assertTrue(isHevcVideo("moov....hvc1....".encodeToByteArray()))
        assertTrue(isHevcVideo("moov....hev1....".encodeToByteArray()))
        assertFalse(isHevcVideo(ByteArray(0)))
    }

    @Test
    fun alreadyPortableVideoIsReturnedUntouched() = runBlocking {
        val bytes = "....avc1....".encodeToByteArray()
        assertArrayEquals(bytes, normalizeVideo(bytes, "video/mp4"))
    }

    @Test
    fun hevcIsReencodedToH264ThatDesktopCanDecode() {
        val source = systemFfmpeg()
        assumeTrue("system ffmpeg with libx265 is not available", source != null && hasLibx265(source))
        val bundled = FfmpegBinary.path
        assumeTrue("FFmpeg is not available", bundled != null)

        val fixture = hevcFixture(source!!)
        try {
            val hevc = fixture.readBytes()
            assertTrue("fixture was not HEVC", isHevcVideo(hevc))

            val normalized = runBlocking { normalizeVideo(hevc, "video/mp4") }
            assertTrue("transcode produced nothing", normalized != null && normalized.isNotEmpty())
            assertFalse("transcode kept HEVC", isHevcVideo(normalized!!))

            val output = File.createTempFile("mira-normalized-", ".mp4").apply { delete() }
            output.writeBytes(normalized)
            try {
                val result = ProcessBuilder(
                    buildVideoPlaybackCommand(bundled!!, output.absolutePath, 0, fps = 5),
                ).redirectErrorStream(false).start()
                val stdout = result.inputStream.readBytes()
                result.errorStream.use { it.copyTo(java.io.OutputStream.nullOutputStream()) }
                assertTrue(result.waitFor(60, TimeUnit.SECONDS))
                assertTrue("normalised video did not decode", countJpegFrames(stdout) > 3)
            } finally {
                output.delete()
            }
        } finally {
            fixture.delete()
        }
    }

    private fun systemFfmpeg(): String? {
        val path = System.getenv("PATH") ?: return null
        return path.split(File.pathSeparator)
            .map { File(it, "ffmpeg") }
            .firstOrNull { it.canExecute() }
            ?.absolutePath
    }

    private fun hasLibx265(binary: String): Boolean = runCatching {
        val process = ProcessBuilder(binary, "-hide_banner", "-encoders").redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().readText()
        process.waitFor(30, TimeUnit.SECONDS)
        text.contains("libx265")
    }.getOrDefault(false)

    private fun hevcFixture(binary: String): File {
        val file = File.createTempFile("mira-hevc-fixture-", ".mp4").apply { delete() }
        val result = ProcessBuilder(
            binary, "-hide_banner", "-loglevel", "error", "-y",
            "-f", "lavfi", "-i", "testsrc=size=320x240:rate=30",
            "-f", "lavfi", "-i", "sine=frequency=440",
            "-t", "1",
            "-c:v", "libx265", "-tag:v", "hvc1",
            "-c:a", "aac",
            file.absolutePath,
        ).redirectErrorStream(true).start()
        val log = result.inputStream.bufferedReader().readText()
        check(result.waitFor(120, TimeUnit.SECONDS) && file.length() > 0) {
            "could not create HEVC fixture: $log"
        }
        return file
    }

    private fun countJpegFrames(bytes: ByteArray): Int {
        var count = 0
        for (index in 0 until bytes.size - 2) {
            if (bytes[index] == 0xFF.toByte() &&
                bytes[index + 1] == 0xD8.toByte() &&
                bytes[index + 2] == 0xFF.toByte()
            ) {
                count++
            }
        }
        return count
    }
}
