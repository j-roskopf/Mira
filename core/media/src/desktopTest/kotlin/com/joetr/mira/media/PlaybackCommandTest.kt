package com.joetr.mira.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Captures a file and plays it straight back.
 *
 * The round trip is the point. Auditing the capture path alone missed that playback needs the
 * pcm_s16le *encoder* and the s16le muxer, so a trimmed build could record perfectly and then fail
 * to play a single note back.
 */
class PlaybackCommandTest {

    private val ffmpeg: String? = FfmpegBinary.path

    @Test
    fun audioRecordedByMiraDecodesToPcmForTheSoundSystem() {
        assumeTrue("FFmpeg is not available", ffmpeg != null)
        val recorded = captureFixture(CaptureKind.AUDIO, "m4a")
        try {
            val result = run(buildAudioPlaybackCommand(ffmpeg!!, recorded.absolutePath, 0))

            assertEquals("playback failed: ${result.stderr}", 0, result.exitCode)

            // Raw signed 16-bit stereo at 44.1 kHz: two seconds is roughly 352,800 bytes.
            val expected = PLAYBACK_SAMPLE_RATE * PLAYBACK_CHANNELS * 2 * 2
            assertTrue(
                "expected around $expected PCM bytes, got ${result.stdout.size}",
                result.stdout.size > expected / 2,
            )
            assertTrue("PCM was silent", result.stdout.any { it != 0.toByte() })
        } finally {
            recorded.delete()
        }
    }

    @Test
    fun videoRecordedByMiraDecodesToPreviewFrames() {
        assumeTrue("FFmpeg is not available", ffmpeg != null)
        val recorded = captureFixture(CaptureKind.VIDEO, "mp4")
        try {
            val result = run(buildVideoPlaybackCommand(ffmpeg!!, recorded.absolutePath, 0, fps = 24))

            assertEquals("playback failed: ${result.stderr}", 0, result.exitCode)
            val frames = countJpegFrames(result.stdout)
            assertTrue("expected decoded frames, got $frames", frames > 10)
        } finally {
            recorded.delete()
        }
    }

    /** Seeking restarts the decode from an offset, so the offset has to be accepted. */
    @Test
    fun seekingPartwayThroughStillDecodes() {
        assumeTrue("FFmpeg is not available", ffmpeg != null)
        val recorded = captureFixture(CaptureKind.AUDIO, "m4a")
        try {
            val whole = run(buildAudioPlaybackCommand(ffmpeg!!, recorded.absolutePath, 0))
            val seeked = run(buildAudioPlaybackCommand(ffmpeg, recorded.absolutePath, 1_000))

            assertEquals(0, seeked.exitCode)
            assertTrue("seeking produced nothing", seeked.stdout.isNotEmpty())
            assertTrue(
                "seeking past a second should yield less audio",
                seeked.stdout.size < whole.stdout.size,
            )
        } finally {
            recorded.delete()
        }
    }

    /** The duration line the player parses has to be present in FFmpeg's own report. */
    @Test
    fun probeReportsADurationThePlayerCanParse() {
        assumeTrue("FFmpeg is not available", ffmpeg != null)
        val recorded = captureFixture(CaptureKind.AUDIO, "m4a")
        try {
            val result = run(buildProbeCommand(ffmpeg!!, recorded.absolutePath))
            val text = result.stdout.decodeToString() + result.stderr
            val match = Regex("""Duration:\s*(\d+):(\d{2}):(\d{2})\.(\d{1,2})""").find(text)
            assertTrue("no parsable duration in:\n$text", match != null)
        } finally {
            recorded.delete()
        }
    }

    /** Records a short fixture with the real capture command, from synthetic inputs. */
    private fun captureFixture(kind: CaptureKind, extension: String): File {
        val file = File.createTempFile("mira-playback-fixture-", ".$extension").apply { delete() }
        val inputs = if (kind == CaptureKind.VIDEO) {
            listOf(
                "-f", "lavfi", "-i", "testsrc=size=640x480:rate=30",
                "-f", "lavfi", "-i", "sine=frequency=440",
            )
        } else {
            listOf("-f", "lavfi", "-i", "sine=frequency=440")
        }
        val result = run(
            buildCaptureCommand(
                binary = ffmpeg!!,
                kind = kind,
                outputPath = file.absolutePath,
                inputArgs = inputs,
                audioInputIndex = if (kind == CaptureKind.VIDEO) 1 else 0,
                durationSeconds = 2,
            ),
        )
        check(result.exitCode == 0 && file.length() > 0) {
            "could not record the fixture: ${result.stderr}"
        }
        return file
    }

    private data class Result(val exitCode: Int, val stdout: ByteArray, val stderr: String)

    private fun run(command: List<String>): Result {
        val process = ProcessBuilder(command).start()
        val stdout = process.inputStream.readBytes()
        val stderr = process.errorStream.bufferedReader().readText()
        process.waitFor(60, TimeUnit.SECONDS)
        return Result(process.exitValue(), stdout, stderr)
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
