package com.joetr.mira.media

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO

/** Real subprocess lifecycle, with a gate standing in for the macOS permission dialogs. */
class DesktopRecorderStartupTest {
    private class CaptureFixture(timeoutMs: Long = 120_000, flushOnStop: Boolean = false) : AutoCloseable {
        val directory = kotlin.io.path.createTempDirectory("mira-startup-test").toFile()
        val gate = File(directory, "permission-granted")
        val frame = File(directory, "frame.jpg").apply {
            ImageIO.write(BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "jpg", this)
        }
        val flush = File(directory, "flush.jpg").apply {
            if (flushOnStop) outputStream().use { stream -> repeat(512) { stream.write(frame.readBytes()) } }
            else writeBytes(byteArrayOf())
        }
        val launches = AtomicInteger()
        val controller = DesktopMediaRecorderController(timeoutMs) { kind, output, _ ->
            launches.incrementAndGet()
            // A header can appear before any audio has been encoded.
            output.writeText("container header")
            listOf(
                "/bin/sh", "-c",
                "while [ ! -f \"\$1\" ]; do sleep 0.05; done; " +
                    (if (kind == CaptureKind.VIDEO) "cat \"\$2\"; " else
                        "printf 'out_time_us=100000\\n' >&2; ") +
                    "dd bs=1 count=1 of=/dev/null 2>/dev/null; sleep 0.2; cat \"\$3\"",
                "capture", gate.absolutePath, frame.absolutePath, flush.absolutePath,
            )
        }

        override fun close() {
            controller.release()
            directory.deleteRecursively()
        }
    }

    @Test
    fun videoWaitsPastTheOldPermissionDeadlineAndStartsOnlyAfterAFrame() = runBlocking {
        assumeTrue(File("/bin/sh").canExecute())
        CaptureFixture().use { fixture ->
            val startup = async { fixture.controller.start(CaptureKind.VIDEO) }
            withTimeout(5_000) { while (fixture.launches.get() == 0) delay(10) }
            delay(10_000) // Old grace + watchdog was only 9.2 seconds.
            assertEquals(RecordingState.Preparing, fixture.controller.state.value)
            fixture.gate.writeText("allowed")
            withTimeout(5_000) { startup.await() }
            assertTrue(fixture.controller.state.value is RecordingState.Recording)
            assertEquals(1, fixture.launches.get())
        }
    }

    @Test
    fun audioRequiresEncodedProgressAndConcurrentStartsShareOneProcess() = runBlocking {
        assumeTrue(File("/bin/sh").canExecute())
        CaptureFixture().use { fixture ->
            val startup = async { fixture.controller.start(CaptureKind.AUDIO) }
            withTimeout(5_000) { while (fixture.launches.get() == 0) delay(10) }
            fixture.controller.start(CaptureKind.AUDIO)
            delay(200)
            assertEquals(1, fixture.launches.get())
            assertEquals(RecordingState.Preparing, fixture.controller.state.value)
            fixture.gate.writeText("allowed")
            withTimeout(5_000) { startup.await() }
            assertTrue(fixture.controller.state.value is RecordingState.Recording)
        }
    }

    @Test
    fun cancellingDuringPermissionWaitDoesNotResurrectRecording() = runBlocking {
        assumeTrue(File("/bin/sh").canExecute())
        CaptureFixture().use { fixture ->
            val startup = async { fixture.controller.start(CaptureKind.VIDEO) }
            withTimeout(5_000) { while (fixture.launches.get() == 0) delay(10) }
            fixture.controller.cancel()
            fixture.gate.writeText("allowed")
            withTimeout(5_000) { startup.await() }
            delay(200)
            assertEquals(RecordingState.Idle, fixture.controller.state.value)
        }
    }

    @Test
    fun stoppingPublishesFinalizingAndDrainsPreviewUntilEncoderExits() = runBlocking {
        assumeTrue(File("/bin/sh").canExecute())
        CaptureFixture(flushOnStop = true).use { fixture ->
            fixture.gate.writeText("allowed")
            fixture.controller.start(CaptureKind.VIDEO)
            val stopping = async { fixture.controller.stop() }
            withTimeout(1_000) {
                while (fixture.controller.state.value != RecordingState.Finalizing) delay(5)
            }
            fixture.controller.stop() // Repeated stop is a no-op.
            withTimeout(2_000) { stopping.await() }
            val finished = fixture.controller.state.value
            assertTrue("Expected a clean finish, got $finished", finished is RecordingState.Finished)
            assertTrue((finished as RecordingState.Finished).result.posterBytes != null)
        }
    }

    @Test
    fun cancellingWhileFinalizingDoesNotPublishFinished() = runBlocking {
        assumeTrue(File("/bin/sh").canExecute())
        CaptureFixture().use { fixture ->
            fixture.gate.writeText("allowed")
            fixture.controller.start(CaptureKind.AUDIO)
            val stopping = async { fixture.controller.stop() }
            withTimeout(1_000) {
                while (fixture.controller.state.value != RecordingState.Finalizing) delay(5)
            }
            fixture.controller.cancel()
            stopping.await()
            assertEquals(RecordingState.Idle, fixture.controller.state.value)
        }
    }

    @Test
    fun invalidPreviewFailsInsteadOfClaimingToRecordWithABlackPreview() = runBlocking {
        assumeTrue(File("/bin/sh").canExecute())
        CaptureFixture().use { fixture ->
            fixture.frame.writeBytes(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte()))
            fixture.gate.writeText("allowed")
            withTimeout(5_000) { fixture.controller.start(CaptureKind.VIDEO) }
            val state = fixture.controller.state.value
            assertTrue(state is RecordingState.Failed)
            assertTrue((state as RecordingState.Failed).message.contains("preview"))
        }
    }

    @Test
    fun startupTimeoutDoesNotClaimPermissionWasDenied() = runBlocking {
        assumeTrue(File("/bin/sh").canExecute())
        CaptureFixture(timeoutMs = 200).use { fixture ->
            fixture.controller.start(CaptureKind.VIDEO)
            val state = fixture.controller.state.value
            assertTrue(state is RecordingState.Failed)
            val message = (state as RecordingState.Failed).message
            assertFalse(message.contains("Allow camera access"))
            assertTrue(message.contains("Capture did not start"))
            assertEquals(1, fixture.launches.get())
        }
    }

    @Test
    fun changingTheShapeRestartsTheFramingPreview() = runBlocking {
        assumeTrue(File("/bin/sh").canExecute())
        val directory = kotlin.io.path.createTempDirectory("mira-preview-shape").toFile()
        val frame = File(directory, "frame.jpg").apply {
            ImageIO.write(BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "jpg", this)
        }
        val seen = java.util.Collections.synchronizedList(mutableListOf<VideoCaptureSettings>())
        val controller = DesktopMediaRecorderController(
            previewCommandFactory = { settings ->
                seen.add(settings)
                listOf(
                    "/bin/sh", "-c",
                    "cat \"\$1\"; while true; do sleep 0.1; done",
                    "preview", frame.absolutePath,
                )
            },
        )
        try {
            controller.startPreview()
            assertTrue(controller.previewFrame.value != null)
            controller.configureVideo(VideoCaptureSettings(aspectRatio = VideoAspectRatio.SQUARE))
            withTimeout(5_000) { while (seen.size < 2) delay(10) }
            assertEquals(VideoAspectRatio.SQUARE, seen.last().aspectRatio)
        } finally {
            controller.release()
            directory.deleteRecursively()
        }
    }

    /** End-to-end: the frame handed to the UI itself must become square, not just the command. */
    @Test
    fun changingTheShapeUpdatesTheFramingFrame() = runBlocking {
        assumeTrue("FFmpeg is not available", FfmpegBinary.path != null)
        val controller = DesktopMediaRecorderController(
            previewCommandFactory = { settings ->
                buildPreviewCommand(
                    binary = FfmpegBinary.path!!,
                    inputArgs = listOf("-f", "lavfi", "-i", "testsrc=size=1280x720:rate=30"),
                    videoSettings = settings,
                )
            },
        )
        try {
            controller.startPreview()
            val wide = controller.previewFrame.value
            assertTrue("no framing frame", wide != null)
            assertEquals(640, wide!!.width)
            assertEquals(360, wide.height)

            controller.configureVideo(VideoCaptureSettings(aspectRatio = VideoAspectRatio.SQUARE))
            withTimeout(10_000) {
                while (controller.previewFrame.value?.let { it.width == it.height } != true) delay(20)
            }
            val square = controller.previewFrame.value!!
            assertEquals(640, square.width)
            assertEquals(640, square.height)
        } finally {
            controller.release()
        }
    }
}
