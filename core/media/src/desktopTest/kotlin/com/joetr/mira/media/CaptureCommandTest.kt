package com.joetr.mira.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Runs the real capture command against synthetic `lavfi` inputs.
 *
 * No camera or microphone, so this runs anywhere — which matters, because the pipeline was broken
 * in a way no amount of reading the command would reveal: `tee` shares one encoded stream across
 * its slaves, so routing H.264 to the file and MJPEG to the preview from a single encoder fails at
 * runtime with "mjpeg muxer supports only codec mjpeg for type video".
 */
class CaptureCommandTest {

    private val ffmpeg: String? = FfmpegBinary.path

    /** Synthetic camera and microphone: video on input 0, audio on input 1. */
    private val lavfiInputs = listOf(
        "-f", "lavfi", "-i", "testsrc=size=1280x720:rate=30",
        "-f", "lavfi", "-i", "sine=frequency=440",
    )

    @Test
    fun videoCaptureWritesPlayableMp4AndPreviewFrames() {
        assumeTrue("FFmpeg is not available", ffmpeg != null)
        val output = File.createTempFile("mira-capture-test-", ".mp4").apply { delete() }
        try {
            val command = buildCaptureCommand(
                binary = ffmpeg!!,
                kind = CaptureKind.VIDEO,
                outputPath = output.absolutePath,
                inputArgs = lavfiInputs,
                audioInputIndex = 1,
                durationSeconds = 2,
            )
            val result = run(command)

            assertEquals("ffmpeg failed: ${result.stderr}", 0, result.exitCode)
            assertTrue("no mp4 written: ${result.stderr}", output.length() > 0)

            // The preview arrives on stdout as concatenated JPEGs; each starts with FFD8FF.
            val frames = countJpegFrames(result.stdout)
            assertTrue("expected preview frames, got $frames", frames > 10)

            val probe = probe(output)
            println("video capture probe:\n$probe")
            val expectedCodec = if (desktopOs == DesktopOs.MAC) "hevc" else "h264"
            assertTrue("expected $expectedCodec in the mp4, got: $probe", probe.contains(expectedCodec))
            assertTrue("expected AAC in the mp4, got: $probe", probe.contains("aac"))
            // The recording branch upscales to the configured width before encoding.
            assertTrue("expected yuv420p, got: $probe", probe.contains("yuv420p"))
            assertTrue(
                "expected ${CaptureLimits.VIDEO_WIDTH} wide, got: $probe",
                probe.contains("width=${CaptureLimits.VIDEO_WIDTH}"),
            )
        } finally {
            output.delete()
        }
    }

    @Test
    fun microsecondCameraRateDoesNotFloodEncodersWithDuplicateFrames() {
        assumeTrue("FFmpeg is not available", ffmpeg != null)
        val output = File.createTempFile("mira-rate-test-", ".mp4").apply { delete() }
        try {
            // Like the observed AVFoundation feed: declared 1M fps, actual timestamps ~30 fps.
            val command = buildCaptureCommand(
                binary = ffmpeg!!,
                kind = CaptureKind.VIDEO,
                outputPath = output.absolutePath,
                inputArgs = listOf(
                    "-itsscale", "33333.333333", "-f", "lavfi", "-i", "testsrc=size=64x64:rate=1000000",
                    "-f", "lavfi", "-i", "sine=frequency=440",
                ),
                audioInputIndex = 1,
                durationSeconds = 2,
            )
            val result = run(command)
            assertEquals(result.stderr, 0, result.exitCode)
            val frames = countJpegFrames(result.stdout)
            assertTrue("Expected ~60 preview frames, got $frames", frames in 50..70)
        } finally {
            output.delete()
        }
    }

    @Test
    fun audioCaptureWritesMonoAacAtTheConfiguredRate() {
        assumeTrue("FFmpeg is not available", ffmpeg != null)
        val output = File.createTempFile("mira-capture-test-", ".m4a").apply { delete() }
        try {
            val command = buildCaptureCommand(
                binary = ffmpeg!!,
                kind = CaptureKind.AUDIO,
                outputPath = output.absolutePath,
                inputArgs = listOf("-f", "lavfi", "-i", "sine=frequency=440"),
                audioInputIndex = 0,
                durationSeconds = 2,
            )
            val result = run(command)

            assertEquals("ffmpeg failed: ${result.stderr}", 0, result.exitCode)
            assertTrue("no m4a written: ${result.stderr}", output.length() > 0)

            val probe = probe(output)
            println("audio capture probe:\n$probe")
            assertTrue("expected AAC, got: $probe", probe.contains("aac"))
            assertTrue("expected mono, got: $probe", probe.contains("channels=1"))
            assertTrue(
                "expected ${CaptureLimits.AUDIO_SAMPLE_RATE} Hz, got: $probe",
                probe.contains("sample_rate=${CaptureLimits.AUDIO_SAMPLE_RATE}"),
            )
        } finally {
            output.delete()
        }
    }

    @Test
    fun framingPreviewStreamsMjpegWithoutAudioOrMuxer() {
        val command = buildPreviewCommand(
            binary = "ffmpeg",
            inputArgs = listOf("-f", "lavfi", "-i", "testsrc=size=1280x720:rate=30"),
        )
        assertEquals("mjpeg", command[command.lastIndex - 1])
        assertEquals("pipe:1", command.last())
        assertTrue(command.none { it == "-c:a" || it == "tee" || it == "-map" })
    }

    /** The framing filter must already be shaped, so the preview shows the final framing. */
    @Test
    fun framingPreviewMatchesTheSelectedShape() {
        val landscape = buildPreviewCommand(
            "ffmpeg", emptyList(), VideoCaptureSettings(VideoAspectRatio.LANDSCAPE),
        )
        val portrait = buildPreviewCommand(
            "ffmpeg", emptyList(), VideoCaptureSettings(VideoAspectRatio.PORTRAIT),
        )
        val landscapeFilter = landscape[landscape.indexOf("-vf") + 1]
        val portraitFilter = portrait[portrait.indexOf("-vf") + 1]
        assertTrue("landscape was $landscapeFilter", landscapeFilter.contains("scale=1920:1080"))
        assertTrue("portrait was $portraitFilter", portraitFilter.contains("scale=1080:1920"))
    }

    @Test
    fun framingPreviewProducesFrames() {
        assumeTrue("FFmpeg is not available", ffmpeg != null)
        val command = buildPreviewCommand(
            binary = ffmpeg!!,
            inputArgs = listOf("-f", "lavfi", "-i", "testsrc=size=1280x720:rate=30"),
        )
        val process = ProcessBuilder(command).start()
        val frames = process.inputStream.readNBytes(256 * 1024)
        process.destroyForcibly()
        assertTrue("expected preview frames", countJpegFrames(frames) > 0)
    }

    /** The escape matters: unescaped, tee reads the comma as the start of the next option. */
    @Test
    fun teeSelectCommaIsEscaped() {
        val command = buildCaptureCommand(
            binary = "ffmpeg",
            kind = CaptureKind.VIDEO,
            outputPath = "/tmp/out.mp4",
            inputArgs = lavfiInputs,
            audioInputIndex = 1,
        )
        val slaves = command.last()
        assertTrue("tee slaves were $slaves", slaves.contains("""[select=0\,2:f=mp4]"""))
        assertTrue("tee slaves were $slaves", slaves.contains("[select=1:f=mjpeg]pipe:1"))
    }

    /** VideoToolbox rejects libx264's -preset outright, so the two must never be mixed. */
    @Test
    fun videoEncoderMatchesThePlatform() {
        val command = buildCaptureCommand(
            binary = "ffmpeg",
            kind = CaptureKind.VIDEO,
            outputPath = "/tmp/out.mp4",
            inputArgs = lavfiInputs,
            audioInputIndex = 1,
        )
        val encoder = command[command.indexOf("-c:v:0") + 1]
        if (desktopOs == DesktopOs.MAC) {
            assertEquals("hevc_videotoolbox", encoder)
            assertTrue("-preset is a libx264 option", "-preset" !in command)
        } else {
            assertEquals("libx264", encoder)
        }
    }

    /** Audio capture must not drag in video encoders or the tee muxer. */
    @Test
    fun audioCommandHasNoVideoStages() {
        val command = buildCaptureCommand(
            binary = "ffmpeg",
            kind = CaptureKind.AUDIO,
            outputPath = "/tmp/out.m4a",
            inputArgs = listOf("-f", "lavfi", "-i", "sine"),
            audioInputIndex = 0,
        )
        assertTrue(
            command.none {
                it == "tee" || it == "libx264" || it == "h264_videotoolbox" ||
                    it == "hevc_videotoolbox" || it == "-filter_complex"
            },
        )
        assertEquals("/tmp/out.m4a", command.last())
    }

    @Test
    fun portraitCameraUsesSelectedFormatForBothRecordingAndPreview() {
        assumeTrue("FFmpeg is not available", ffmpeg != null)
        for (aspect in VideoAspectRatio.entries) {
            for (quality in VideoQuality.entries) {
                val settings = VideoCaptureSettings(aspect, quality)
                val output = File.createTempFile("mira-format-test-", ".mp4").apply { delete() }
                val camera = File.createTempFile("mira-camera-edges-", ".jpg")
                val source = java.awt.image.BufferedImage(360, 640, java.awt.image.BufferedImage.TYPE_INT_RGB)
                source.createGraphics().apply {
                    color = java.awt.Color.WHITE
                    fillRect(0, 0, 360, 640)
                    color = java.awt.Color.RED
                    fillRect(0, 0, 360, 64)
                    color = java.awt.Color.BLUE
                    fillRect(0, 576, 360, 64)
                    color = java.awt.Color.GREEN
                    fillRect(0, 64, 36, 512)
                    color = java.awt.Color.YELLOW
                    fillRect(324, 64, 36, 512)
                    dispose()
                }
                javax.imageio.ImageIO.write(source, "jpg", camera)
                try {
                    val command = buildCaptureCommand(
                        binary = ffmpeg!!,
                        kind = CaptureKind.VIDEO,
                        outputPath = output.absolutePath,
                        inputArgs = listOf(
                            "-f", "mjpeg", "-i", camera.absolutePath,
                            "-f", "lavfi", "-i", "sine=frequency=440",
                        ),
                        audioInputIndex = 1,
                        durationSeconds = 1,
                        videoSettings = settings,
                    )
                    assertEquals(quality.bitrate.toString(), command[command.indexOf("-b:v:0") + 1])
                    val result = run(command)
                    assertEquals("$settings: ${result.stderr}", 0, result.exitCode)
                    val preview = javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(result.stdout))
                    val decoded = run(listOf(
                        ffmpeg, "-v", "error", "-i", output.absolutePath,
                        "-frames:v", "1", "-f", "mjpeg", "-c:v", "mjpeg", "pipe:1",
                    ))
                    assertEquals(decoded.stderr, 0, decoded.exitCode)
                    val recording = javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(decoded.stdout))
                    val edge = quality.shortEdge
                    val expected = when (aspect) {
                        VideoAspectRatio.LANDSCAPE -> edge * 16 / 9 to edge
                        VideoAspectRatio.PORTRAIT, VideoAspectRatio.ORIGINAL -> edge to edge * 16 / 9
                        VideoAspectRatio.SQUARE -> edge to edge
                    }
                    assertEquals("$settings width", expected.first, recording.width)
                    assertEquals("$settings height", expected.second, recording.height)
                    // Cropping to the chosen shape must fill the frame, never pad it with black bars.
                    for (image in listOf(recording, preview)) {
                        val corners = listOf(
                            0 to 0, image.width - 1 to 0,
                            0 to image.height - 1, image.width - 1 to image.height - 1,
                        )
                        for ((x, y) in corners) {
                            val actual = java.awt.Color(image.getRGB(x, y))
                            assertTrue(
                                "$settings left a pad bar at $x,$y: $actual",
                                actual.red + actual.green + actual.blue > 90,
                            )
                        }
                    }
                    assertEquals(
                        "$settings preview must match the saved frame",
                        recording.width.toDouble() / recording.height,
                        preview.width.toDouble() / preview.height,
                        0.005,
                    )
                } finally {
                    output.delete()
                    camera.delete()
                }
            }
        }
    }

    private data class Result(val exitCode: Int, val stdout: ByteArray, val stderr: String)

    private fun run(command: List<String>): Result {
        val process = ProcessBuilder(command).start()
        val stdout = java.util.concurrent.CompletableFuture.supplyAsync { process.inputStream.readBytes() }
        val stderr = java.util.concurrent.CompletableFuture.supplyAsync { process.errorStream.bufferedReader().readText() }
        try {
            assertTrue("Capture pipeline stalled", process.waitFor(20, TimeUnit.SECONDS))
            return Result(process.exitValue(), stdout.get(), stderr.get())
        } finally {
            process.destroyForcibly()
        }
    }

    /**
     * Reads the real stream layout back.
     *
     * Skips rather than substituting a plausible answer when ffprobe is missing: a stub that
     * happens to contain every expected substring turns this whole test into a no-op that still
     * reports green.
     */
    private fun probe(file: File): String {
        val probeBinary = File(ffmpeg!!).resolveSibling("ffprobe")
        assumeTrue("ffprobe is not available next to ffmpeg", probeBinary.canExecute())
        val process = ProcessBuilder(
            probeBinary.absolutePath, "-hide_banner", "-v", "error",
            "-show_entries", "stream=codec_name,width,height,pix_fmt,channels,sample_rate",
            "-of", "default=nw=1", file.absolutePath,
        ).redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().readText()
        process.waitFor(30, TimeUnit.SECONDS)
        return text
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
