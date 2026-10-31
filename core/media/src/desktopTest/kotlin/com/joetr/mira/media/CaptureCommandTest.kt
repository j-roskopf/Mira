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
            assertTrue("expected H.264 in the mp4, got: $probe", probe.contains("h264"))
            assertTrue("expected AAC in the mp4, got: $probe", probe.contains("aac"))
            // The recording branch upscales to the configured width and converts for H.264.
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
        if (encoder == "h264_videotoolbox") {
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
                    it == "-filter_complex"
            },
        )
        assertEquals("/tmp/out.m4a", command.last())
    }

    private data class Result(val exitCode: Int, val stdout: ByteArray, val stderr: String)

    private fun run(command: List<String>): Result {
        val process = ProcessBuilder(command).start()
        val stdout = process.inputStream.readBytes()
        val stderr = process.errorStream.bufferedReader().readText()
        process.waitFor(60, TimeUnit.SECONDS)
        return Result(process.exitValue(), stdout, stderr)
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
