package com.joetr.mira.media

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

class VideoPosterTest {

    @Test
    fun posterIsExtractedFromALocalVideo() {
        val fixture = videoFixture(File.createTempFile("mira-poster-fixture-", ".mp4").apply { delete() })
        try {
            val poster = runBlocking { extractVideoPoster(fixture.absolutePath, null, "video/mp4") }
            assertJpegAndSmall(poster)
        } finally {
            fixture.delete()
        }
    }

    @Test
    fun posterIsExtractedFromBytesWhenThereIsNoPath() {
        val fixture = videoFixture(File.createTempFile("mira-poster-bytes-", ".mp4").apply { delete() })
        try {
            val poster = runBlocking { extractVideoPoster(null, fixture.readBytes(), "video/mp4") }
            assertJpegAndSmall(poster)
        } finally {
            fixture.delete()
        }
    }

    private fun assertJpegAndSmall(bytes: ByteArray?) {
        assertTrue("poster was not produced", bytes != null && bytes.isNotEmpty())
        assertTrue(
            "poster is not a JPEG",
            bytes!![0] == 0xFF.toByte() &&
                bytes[1] == 0xD8.toByte() &&
                bytes[2] == 0xFF.toByte(),
        )
        val image = ImageIO.read(bytes.inputStream())
        assertTrue("poster did not decode", image != null)
        assertTrue(
            "poster is larger than the tile budget",
            maxOf(image!!.width, image.height) <= VideoPosterMaxLongEdge,
        )
    }

    private fun videoFixture(file: File): File {
        assumeTrue("bundled FFmpeg is not available", FfmpegBinary.isAvailable)
        val binary = systemFfmpeg()
        assumeTrue("system ffmpeg is not available", binary != null)
        val result = ProcessBuilder(
            binary, "-hide_banner", "-loglevel", "error", "-y",
            "-f", "lavfi", "-i", "testsrc=size=320x240:rate=10",
            "-t", "1",
            "-c:v", "libx264", "-pix_fmt", "yuv420p",
            file.absolutePath,
        ).redirectErrorStream(true).start()
        val log = result.inputStream.bufferedReader().readText()
        check(result.waitFor(120, TimeUnit.SECONDS) && file.length() > 0) {
            "could not create video fixture: $log"
        }
        return file
    }

    private fun systemFfmpeg(): String? {
        val path = System.getenv("PATH") ?: return null
        return path.split(File.pathSeparator)
            .map { File(it, "ffmpeg") }
            .firstOrNull { it.canExecute() }
            ?.absolutePath
    }
}
