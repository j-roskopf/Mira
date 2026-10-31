package com.joetr.mira.media

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit

public actual fun supportsCapture(kind: CaptureKind): Boolean = FfmpegBinary.isAvailable

public actual fun createMediaRecorderController(): MediaRecorderController =
    DesktopMediaRecorderController()

/**
 * Desktop capture, driven by a bundled FFmpeg subprocess.
 *
 * One process does both jobs: it writes the mp4/m4a file *and* streams MJPEG frames on stdout for
 * the live preview, via the `tee` muxer. Two processes would mean opening the camera twice, which
 * many webcams refuse outright.
 */
internal class DesktopMediaRecorderController : MediaRecorderController {

    private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)
    override val state: StateFlow<RecordingState> = _state.asStateFlow()

    internal val previewFrame = MutableStateFlow<ImageBitmap?>(null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var process: Process? = null
    private var outputFile: File? = null
    private var startedAt: Long = 0
    private var currentKind: CaptureKind = CaptureKind.AUDIO

    override suspend fun start(kind: CaptureKind) {
        val binary = FfmpegBinary.path
        if (binary == null) {
            _state.value = RecordingState.Failed("FFmpeg is not available in this build")
            return
        }
        if (process != null) return

        _state.value = RecordingState.Preparing
        currentKind = kind

        val extension = if (kind == CaptureKind.VIDEO) "mp4" else "m4a"
        val file = withContext(Dispatchers.IO) {
            File.createTempFile("mira-capture-", ".$extension").apply { deleteOnExit() }
        }
        outputFile = file

        val command = buildList {
            add(binary)
            add("-hide_banner")
            add("-loglevel")
            add("error")
            addAll(if (kind == CaptureKind.VIDEO) videoInputArgs() else audioInputArgs())
            add("-t")
            add((CaptureLimits.MAX_DURATION_MS / 1000).toString())

            add("-c:a")
            add("aac")
            add("-b:a")
            add(CaptureLimits.AUDIO_BITRATE.toString())
            add("-ac")
            add(CaptureLimits.AUDIO_CHANNELS.toString())
            add("-ar")
            add(CaptureLimits.AUDIO_SAMPLE_RATE.toString())

            if (kind == CaptureKind.VIDEO) {
                add("-c:v")
                add("libx264")
                add("-preset")
                add("veryfast")
                add("-b:v")
                add(CaptureLimits.VIDEO_BITRATE.toString())
                add("-vf")
                add("scale=${CaptureLimits.VIDEO_WIDTH}:-2")
                add("-pix_fmt")
                add("yuv420p")
                // File and preview from one capture: the file gets mp4, stdout gets MJPEG.
                add("-f")
                add("tee")
                add("-map")
                add("0:v")
                add("-map")
                add(if (desktopOs == DesktopOs.LINUX) "1:a" else "0:a")
                add("[f=mp4]${file.absolutePath}|[f=mjpeg:s=640x360]pipe:1")
            } else {
                add("-y")
                add(file.absolutePath)
            }
        }

        val started = withContext(Dispatchers.IO) {
            runCatching {
                ProcessBuilder(command)
                    .redirectErrorStream(false)
                    .start()
            }.getOrNull()
        }

        if (started == null) {
            _state.value = RecordingState.Failed("Could not start the recorder")
            return
        }

        process = started
        startedAt = System.currentTimeMillis()
        _state.value = RecordingState.Recording(0)

        if (kind == CaptureKind.VIDEO) {
            scope.launch { readMjpegFrames(started.inputStream) }
        }
        scope.launch { tickElapsed() }
        scope.launch { drain(started.errorStream) }
    }

    override suspend fun stop() {
        val running = process ?: return
        withContext(Dispatchers.IO) {
            // 'q' asks FFmpeg to finalise the container; killing it leaves an unplayable file.
            runCatching {
                running.outputStream.write('q'.code)
                running.outputStream.flush()
            }
            if (!running.waitFor(5, TimeUnit.SECONDS)) running.destroy()
            if (!running.waitFor(3, TimeUnit.SECONDS)) running.destroyForcibly()
        }
        process = null

        val file = outputFile
        val elapsed = System.currentTimeMillis() - startedAt
        if (file == null || !file.exists() || file.length() == 0L) {
            _state.value = RecordingState.Failed("Recording produced no audio or video")
            return
        }

        val poster = if (currentKind == CaptureKind.VIDEO) extractPoster(file) else null

        _state.value = RecordingState.Finished(
            CaptureResult(
                kind = currentKind,
                path = file.absolutePath,
                bytes = null,
                mime = if (currentKind == CaptureKind.VIDEO) "video/mp4" else "audio/mp4",
                sizeBytes = file.length(),
                durationMs = elapsed,
                posterBytes = poster,
            ),
        )
    }

    override fun cancel() {
        process?.destroyForcibly()
        process = null
        outputFile?.delete()
        outputFile = null
        previewFrame.value = null
        _state.value = RecordingState.Idle
    }

    override fun release() {
        cancel()
        scope.cancel()
    }

    /** First frame of the clip, for the timeline's video poster. */
    private suspend fun extractPoster(file: File): ByteArray? = withContext(Dispatchers.IO) {
        val binary = FfmpegBinary.path ?: return@withContext null
        val poster = File.createTempFile("mira-poster-", ".jpg").apply { deleteOnExit() }
        val ok = runCatching {
            ProcessBuilder(
                binary, "-hide_banner", "-loglevel", "error",
                "-y", "-i", file.absolutePath,
                "-frames:v", "1", "-vf", "scale=640:-2",
                poster.absolutePath,
            ).start().waitFor(10, TimeUnit.SECONDS)
        }.getOrDefault(false)
        val bytes = if (ok && poster.length() > 0) poster.readBytes() else null
        poster.delete()
        bytes
    }

    private suspend fun tickElapsed() {
        while (scope.isActive && process != null) {
            val elapsed = System.currentTimeMillis() - startedAt
            if (_state.value is RecordingState.Recording) {
                _state.value = RecordingState.Recording(elapsed)
            }
            if (elapsed >= CaptureLimits.MAX_DURATION_MS) {
                stop()
                return
            }
            delay(100)
        }
    }

    private suspend fun readMjpegFrames(stream: InputStream) = withContext(Dispatchers.IO) {
        readJpegFrames(stream) { bytes ->
            decodeJpegFrame(bytes)?.let { previewFrame.value = it }
            isActive
        }
    }


    private suspend fun drain(stream: InputStream) = withContext(Dispatchers.IO) {
        runCatching {
            stream.bufferedReader().forEachLine { line ->
                if (line.isNotBlank() && _state.value is RecordingState.Recording) {
                    // FFmpeg only writes here on real errors at this log level.
                    _state.value = RecordingState.Failed(line.take(200))
                }
            }
        }
    }
}


@Composable
public actual fun CameraPreview(
    controller: MediaRecorderController,
    modifier: Modifier,
) {
    val desktopController = controller as? DesktopMediaRecorderController
    val frame by (desktopController?.previewFrame ?: MutableStateFlow(null)).collectAsState()

    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        frame?.let { bitmap ->
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}
