package com.joetr.mira.media

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit

/**
 * Remote attachments are the normal case on the second device: the bytes are in Storage and the
 * local blob was never there. Desktop must materialise them, because the trimmed FFmpeg build has
 * no network protocol to open a URL itself.
 */
class RemotePlaybackTest {

    @Test
    fun remoteMediaIsMaterialisedIntoALocalFile() {
        val payload = ByteArray(64 * 1024) { (it % 251).toByte() }
        withServer(payload) { url ->
            val destination = File.createTempFile("mira-remote-", ".mp4").apply { delete() }
            try {
                assertTrue(downloadRemoteMedia(url, destination))
                assertArrayEquals(payload, destination.readBytes())
            } finally {
                destination.delete()
            }
        }
    }

    @Test
    fun aMissingRemoteObjectDoesNotLeaveAnEmptyFile() {
        val destination = File.createTempFile("mira-remote-", ".mp4").apply { delete() }
        try {
            assertFalse(downloadRemoteMedia("http://127.0.0.1:1/nothing-here.mp4", destination))
            assertFalse("failed download left bytes behind", destination.isFile && destination.length() > 0)
        } finally {
            destination.delete()
        }
    }

    /** The real end-to-end path: download a recording, then decode it to preview frames. */
    @Test
    fun downloadedRecordingPlays() {
        val ffmpeg = FfmpegBinary.path
        assumeTrue("FFmpeg is not available", ffmpeg != null)
        val recorded = captureFixture()
        try {
            val payload = recorded.readBytes()
            withServer(payload) { url ->
                val downloaded = File.createTempFile("mira-remote-playback-", ".mp4").apply { delete() }
                try {
                    assertTrue(downloadRemoteMedia(url, downloaded))
                    val process = ProcessBuilder(
                        buildVideoPlaybackCommand(ffmpeg!!, downloaded.absolutePath, 0, fps = 5),
                    ).redirectErrorStream(false).start()
                    val stdout = process.inputStream.readBytes()
                    process.errorStream.use { it.copyTo(java.io.OutputStream.nullOutputStream()) }
                    assertTrue(process.waitFor(60, TimeUnit.SECONDS))
                    assertEquals(0, process.exitValue())
                    var frames = 0
                    for (index in 0 until stdout.size - 2) {
                        if (stdout[index] == 0xFF.toByte() &&
                            stdout[index + 1] == 0xD8.toByte() &&
                            stdout[index + 2] == 0xFF.toByte()
                        ) {
                            frames++
                        }
                    }
                    assertTrue("downloaded recording did not decode", frames > 3)
                } finally {
                    downloaded.delete()
                }
            }
        } finally {
            recorded.delete()
        }
    }

    private fun withServer(payload: ByteArray, block: (String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/media.mp4") { exchange ->
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}/media.mp4")
        } finally {
            server.stop(0)
        }
    }

    private fun captureFixture(): File {
        val file = File.createTempFile("mira-remote-fixture-", ".mp4").apply { delete() }
        val result = ProcessBuilder(
            buildCaptureCommand(
                binary = FfmpegBinary.path!!,
                kind = CaptureKind.VIDEO,
                outputPath = file.absolutePath,
                inputArgs = listOf(
                    "-f", "lavfi", "-i", "testsrc=size=320x240:rate=30",
                    "-f", "lavfi", "-i", "sine=frequency=440",
                ),
                audioInputIndex = 1,
                durationSeconds = 2,
            ),
        ).redirectErrorStream(true).start()
        val log = result.inputStream.bufferedReader().readText()
        check(result.waitFor(120, TimeUnit.SECONDS) && file.length() > 0) {
            "could not create fixture: $log"
        }
        return file
    }
}
