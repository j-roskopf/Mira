package com.joetr.mira.media

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit

/**
 * The MJPEG splitter has to keep up with a live 30fps preview.
 *
 * It feeds the stall watchdog, so if splitting is slower than capture the app reports "no video
 * from the camera" on a camera that is working perfectly — the failure looks like a permissions
 * problem and is not one.
 */
class JpegFrameReaderTest {

    private val ffmpeg: String? = FfmpegBinary.path

    @Before
    fun loadSkiko() {
        // Compose loads skiko's native library on first use; a plain JUnit JVM does not, and
        // without it every decode throws NoClassDefFoundError and the test measures nothing.
        runCatching { org.jetbrains.skiko.Library.load() }
    }

    @Test
    fun splitsARealMjpegStreamFastEnoughForLivePreview() = runBlocking {
        assumeTrue("FFmpeg is not available", ffmpeg != null)

        // Two seconds of 640px preview at 30fps: exactly what capture pipes to stdout.
        val mjpeg = generateMjpeg(seconds = 2, fps = 30)
        assertTrue("fixture is too small: ${mjpeg.size}", mjpeg.size > 100_000)

        val frames = mutableListOf<Int>()
        val elapsedMs = kotlin.system.measureTimeMillis {
            readJpegFrames(ByteArrayInputStream(mjpeg)) { frame ->
                frames += frame.size
                true
            }
        }

        assertEquals("expected 60 frames", 60, frames.size)
        frames.forEach { assertTrue("a frame was empty", it > 1_000) }

        // Generous: real time for this stream is 2000ms, and it must run far faster than that
        // to keep up while also decoding. Anything near or above real time starves the preview.
        assertTrue(
            "splitting ${frames.size} frames took ${elapsedMs}ms, slower than the stream itself",
            elapsedMs < 1_000,
        )
    }

    @Test
    fun emitsEachFrameWithIntactJpegMarkers() = runBlocking {
        assumeTrue("FFmpeg is not available", ffmpeg != null)
        val mjpeg = generateMjpeg(seconds = 1, fps = 10)

        readJpegFrames(ByteArrayInputStream(mjpeg)) { frame ->
            assertEquals("missing SOI", 0xFF.toByte(), frame[0])
            assertEquals("missing SOI", 0xD8.toByte(), frame[1])
            assertEquals("missing EOI", 0xFF.toByte(), frame[frame.size - 2])
            assertEquals("missing EOI", 0xD9.toByte(), frame[frame.size - 1])
            true
        }
    }

    /** Returning false stops the reader, which is how cancelling a recording unwinds. */
    @Test
    fun stopsWhenTheConsumerSaysSo() = runBlocking {
        assumeTrue("FFmpeg is not available", ffmpeg != null)
        val mjpeg = generateMjpeg(seconds = 1, fps = 30)

        var seen = 0
        readJpegFrames(ByteArrayInputStream(mjpeg)) {
            seen++
            seen < 3
        }
        assertEquals(3, seen)
    }

    /**
     * Every frame the splitter emits must actually decode.
     *
     * The stall watchdog counts *decoded* frames, so a decode failure is indistinguishable from a
     * camera that sends nothing — and reports the wrong cause to the user.
     */
    @Test
    fun everySplitFrameDecodesToABitmap() = runBlocking {
        assumeTrue("FFmpeg is not available", ffmpeg != null)
        val mjpeg = generateMjpeg(seconds = 1, fps = 10)

        var split = 0
        var decoded = 0
        readJpegFrames(ByteArrayInputStream(mjpeg)) { frame ->
            split++
            if (decodeJpegFrame(frame) != null) decoded++
            true
        }

        assertTrue("no frames were split", split > 5)
        assertEquals("frames were split but not decodable", split, decoded)
    }

    /** The preview branch is encoded as yuvj420p, which is the format that has to decode. */
    @Test
    fun decodesTheExactPreviewFormatCaptureProduces() = runBlocking {
        assumeTrue("FFmpeg is not available", ffmpeg != null)
        val process = ProcessBuilder(
            ffmpeg!!, "-hide_banner", "-loglevel", "error",
            "-f", "lavfi", "-i", "testsrc=size=1280x720:rate=30", "-t", "1",
            "-vf", "scale=$PREVIEW_WIDTH:-2,format=yuvj420p",
            "-f", "mjpeg", "-q:v", "6", "pipe:1",
        ).start()
        val bytes = process.inputStream.readBytes()
        process.waitFor(60, TimeUnit.SECONDS)

        var decoded = 0
        readJpegFrames(ByteArrayInputStream(bytes)) { frame ->
            if (decodeJpegFrame(frame) != null) decoded++
            true
        }
        assertTrue("preview frames did not decode", decoded > 5)
    }

    private fun generateMjpeg(seconds: Int, fps: Int): ByteArray {
        val process = ProcessBuilder(
            ffmpeg!!, "-hide_banner", "-loglevel", "error",
            "-f", "lavfi", "-i", "testsrc=size=640x360:rate=$fps",
            "-t", seconds.toString(),
            "-f", "mjpeg", "-q:v", "6", "pipe:1",
        ).start()
        val bytes = process.inputStream.readBytes()
        process.waitFor(60, TimeUnit.SECONDS)
        return bytes
    }
}
