package com.joetr.mira.media

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Drives the real controller against the real microphone.
 *
 * Everything else about capture is tested through synthetic inputs; this is the one place the
 * actual process lifecycle runs — launch, startup grace, the stall watchdog declining to fire,
 * stopping cleanly with `q` so the container is finalised, and the resulting file being playable.
 *
 * Skipped where no microphone is reachable, which includes CI and any machine that has not granted
 * microphone access to the process running Gradle.
 */
class DesktopRecorderIntegrationTest {

    @Test
    fun recordsAudioThroughTheControllerAndProducesAPlayableFile() = runBlocking {
        assumeTrue("FFmpeg is not available", FfmpegBinary.isAvailable)
        assumeTrue("No microphone is reachable", microphoneIsReachable())

        val controller = createMediaRecorderController()
        try {
            controller.start(CaptureKind.AUDIO)

            // The controller only reports Recording once the process has survived startup.
            val recording = controller.state.value
            assertTrue("expected Recording, was $recording", recording is RecordingState.Recording)

            // Comfortably past the stall watchdog, which must not fire on a healthy capture.
            delay(5_000)
            assertTrue(
                "the stall watchdog fired on a working microphone: ${controller.state.value}",
                controller.state.value is RecordingState.Recording,
            )

            controller.stop()

            val finished = controller.state.value
            assertTrue("expected Finished, was $finished", finished is RecordingState.Finished)

            val result = (finished as RecordingState.Finished).result
            assertEquals(CaptureKind.AUDIO, result.kind)
            assertEquals("audio/mp4", result.mime)
            assertTrue("no duration recorded", result.durationMs > 1_000)

            val file = File(result.path!!)
            assertTrue("capture file is missing", file.exists())
            assertEquals("size does not match the file", file.length(), result.sizeBytes)
            assertTrue("capture file is empty", result.sizeBytes > 0)

            // Stopping with 'q' rather than a kill is what makes the container readable.
            val probe = probe(file)
            println("controller capture probe:\n$probe")
            assertTrue("expected AAC audio, got: $probe", probe.contains("aac"))
            assertTrue("expected a readable duration, got: $probe", probe.contains("duration="))
        } finally {
            controller.release()
        }
    }

    private fun microphoneIsReachable(): Boolean {
        val binary = FfmpegBinary.path ?: return false
        val probe = File.createTempFile("mira-mic-probe-", ".m4a").apply { delete() }
        return try {
            val process = ProcessBuilder(
                binary, "-hide_banner", "-loglevel", "error",
                "-f", "avfoundation", "-i", ":0", "-t", "1", "-y", probe.absolutePath,
            ).redirectErrorStream(true).start()
            val finished = process.waitFor(20, TimeUnit.SECONDS)
            if (!finished) process.destroyForcibly()
            finished && probe.length() > 0
        } catch (_: Throwable) {
            false
        } finally {
            probe.delete()
        }
    }

    private fun probe(file: File): String {
        val probeBinary = File(FfmpegBinary.path!!).resolveSibling("ffprobe")
        assumeTrue("ffprobe is not available next to ffmpeg", probeBinary.canExecute())
        val process = ProcessBuilder(
            probeBinary.absolutePath, "-hide_banner", "-v", "error",
            "-show_entries", "stream=codec_name,channels,sample_rate",
            "-show_entries", "format=duration",
            "-of", "default=nw=1", file.absolutePath,
        ).redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().readText()
        process.waitFor(30, TimeUnit.SECONDS)
        return text
    }
}
