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

    private companion object {
        /** A capture that is still alive after this started cleanly. */
        const val STARTUP_GRACE_MS = 1_200L
        const val MAX_STDERR_LINES = 40

        /** How long a capture may produce nothing before it is treated as blocked. */
        const val STALL_TIMEOUT_MS = 4_000L
    }


    private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)
    override val state: StateFlow<RecordingState> = _state.asStateFlow()

    internal val previewFrame = MutableStateFlow<ImageBitmap?>(null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var process: Process? = null
    private var outputFile: File? = null
    private var startedAt: Long = 0
    private var currentKind: CaptureKind = CaptureKind.AUDIO
    private val stderrLines = mutableListOf<String>()
    private var framesSeen = 0

    override suspend fun start(kind: CaptureKind) {
        if (FfmpegBinary.path == null) {
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

        if (launch(kind, file, pixelFormat = null)) return

        // avfoundation rejects a pixel format by printing the ones it does support, so the
        // failure carries its own fix. Retry once with whichever of our preferences the device
        // actually offers rather than guessing a second time.
        val offered = parseSupportedPixelFormats(stderrText())
        val fallback = MAC_PIXEL_FORMATS.firstOrNull { it in offered } ?: offered.firstOrNull()
        if (fallback != null && launch(kind, file, pixelFormat = fallback)) return

        _state.value = RecordingState.Failed(failureMessage())
    }

    /**
     * Starts one FFmpeg capture attempt.
     *
     * Returns false when the process dies on startup — which is how an unsupported pixel format or
     * a missing camera presents itself, since FFmpeg exits before producing a single frame.
     */
    private suspend fun launch(kind: CaptureKind, file: File, pixelFormat: String?): Boolean {
        val binary = FfmpegBinary.path ?: return false
        stderrLines.clear()

        val command = buildCommand(binary, kind, file, pixelFormat)
        val started = withContext(Dispatchers.IO) {
            runCatching {
                ProcessBuilder(command).redirectErrorStream(false).start()
            }.getOrNull()
        } ?: return false

        process = started
        scope.launch { drain(started.errorStream) }

        // Startup failures are immediate; a healthy capture is still alive after this.
        val exitedEarly = withContext(Dispatchers.IO) {
            started.waitFor(STARTUP_GRACE_MS, TimeUnit.MILLISECONDS)
        }
        if (exitedEarly) {
            process = null
            return false
        }

        startedAt = System.currentTimeMillis()
        _state.value = RecordingState.Recording(0)

        framesSeen = 0
        if (kind == CaptureKind.VIDEO) {
            scope.launch { readMjpegFrames(started.inputStream) }
        }
        scope.launch { tickElapsed() }
        scope.launch { watchForDeath(started) }
        scope.launch { watchForStall(started, kind, file) }
        return true
    }

    private fun buildCommand(
        binary: String,
        kind: CaptureKind,
        file: File,
        pixelFormat: String?,
    ): List<String> = buildCaptureCommand(
        binary = binary,
        kind = kind,
        outputPath = file.absolutePath,
        inputArgs = if (kind == CaptureKind.VIDEO) videoInputArgs(pixelFormat) else audioInputArgs(),
        // Linux opens video and audio as two separate inputs; macOS and Windows as one.
        audioInputIndex = if (desktopOs == DesktopOs.LINUX) 1 else 0,
    )

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
            decodeJpegFrame(bytes)?.let {
                previewFrame.value = it
                framesSeen++
            }
            isActive
        }
    }

    /**
     * Catches a capture that starts but never produces anything.
     *
     * Without camera or microphone permission, macOS does not fail the FFmpeg process — it simply
     * never delivers a frame, so the recorder would sit in Recording forever with a ticking timer
     * and an empty preview. Nothing in stderr says so; silence is the only symptom.
     */
    private suspend fun watchForStall(active: Process, kind: CaptureKind, file: File) {
        delay(STALL_TIMEOUT_MS)
        if (process !== active || _state.value !is RecordingState.Recording) return

        val producedSomething =
            if (kind == CaptureKind.VIDEO) framesSeen > 0 else file.length() > 0
        if (producedSomething) return

        active.destroyForcibly()
        process = null
        _state.value = RecordingState.Failed(
            if (kind == CaptureKind.VIDEO) {
                "No video from the camera. Allow camera access for Mira in System Settings → " +
                    "Privacy & Security."
            } else {
                "No audio from the microphone. Allow microphone access for Mira in System " +
                    "Settings → Privacy & Security."
            },
        )
    }


    /**
     * Collects FFmpeg's stderr.
     *
     * Deliberately does not end the recording on its own. FFmpeg writes recoverable complaints
     * here too, and treating the first line as fatal meant a warning killed a capture that was
     * still running fine. The process exiting is the only thing that actually means failure.
     */
    private suspend fun drain(stream: InputStream) = withContext(Dispatchers.IO) {
        runCatching {
            stream.bufferedReader().forEachLine { line ->
                if (line.isNotBlank()) {
                    synchronized(stderrLines) {
                        if (stderrLines.size < MAX_STDERR_LINES) stderrLines.add(line)
                    }
                }
            }
        }
    }

    /** Ends the recording if FFmpeg dies while we still believe it is capturing. */
    private suspend fun watchForDeath(active: Process) = withContext(Dispatchers.IO) {
        active.waitFor()
        if (process === active && _state.value is RecordingState.Recording) {
            process = null
            _state.value = RecordingState.Failed(failureMessage())
        }
    }

    private fun stderrText(): String = synchronized(stderrLines) { stderrLines.joinToString("\n") }

    private fun failureMessage(): String {
        val text = stderrText()
        return when {
            text.contains(PIXEL_FORMAT_ERROR) ->
                "This camera does not support the requested video format"
            text.contains("Operation not permitted", ignoreCase = true) ->
                "Mira needs camera and microphone access in System Settings"
            text.isBlank() -> "Recording stopped unexpectedly"
            else -> text.lineSequence().first { it.isNotBlank() }.substringAfterLast(']').trim()
                .take(200)
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

/**
 * Builds the FFmpeg capture command.
 *
 * Extracted from the controller so the pipeline can be tested against synthetic `lavfi` inputs,
 * which need no camera or microphone and therefore run anywhere.
 *
 * Video takes **two encoders**, not one. The `tee` muxer shares a single encoded stream across its
 * slaves, so an H.264 file and an MJPEG preview cannot come from the same encode — tee rejects it
 * with "mjpeg muxer supports only codec mjpeg for type video". The filter graph splits the camera
 * feed, each branch gets its own encoder, and `select` routes the right streams to each slave.
 */
internal fun buildCaptureCommand(
    binary: String,
    kind: CaptureKind,
    outputPath: String,
    inputArgs: List<String>,
    audioInputIndex: Int,
    durationSeconds: Long = CaptureLimits.MAX_DURATION_MS / 1000,
): List<String> = buildList {
    add(binary)
    add("-hide_banner")
    add("-loglevel")
    add("error")
    addAll(inputArgs)
    add("-t")
    add(durationSeconds.toString())

    if (kind == CaptureKind.VIDEO) {
        add("-filter_complex")
        add(
            "[0:v]split=2[rec][prev];" +
                "[rec]scale=${CaptureLimits.VIDEO_WIDTH}:-2,format=yuv420p[vrec];" +
                "[prev]scale=$PREVIEW_WIDTH:-2,format=yuvj420p[vprev]",
        )
        add("-map")
        add("[vrec]")
        add("-map")
        add("[vprev]")
        add("-map")
        add("$audioInputIndex:a")

        add("-c:v:0")
        add("libx264")
        add("-preset")
        add("veryfast")
        add("-b:v:0")
        add(CaptureLimits.VIDEO_BITRATE.toString())
        add("-c:v:1")
        add("mjpeg")
        add("-q:v:1")
        add("6")
    }

    add("-c:a")
    add("aac")
    add("-b:a")
    add(CaptureLimits.AUDIO_BITRATE.toString())
    add("-ac")
    add(CaptureLimits.AUDIO_CHANNELS.toString())
    add("-ar")
    add(CaptureLimits.AUDIO_SAMPLE_RATE.toString())

    if (kind == CaptureKind.VIDEO) {
        add("-f")
        add("tee")
        // Streams 0 and 1 are the H.264 and MJPEG branches in -map order, 2 is the audio. The
        // comma inside a tee slave option must be escaped or tee reads it as the next option.
        add("[select=0\\,2:f=mp4]$outputPath|[select=1:f=mjpeg]pipe:1")
    } else {
        add("-y")
        add(outputPath)
    }
}

/** Width of the MJPEG preview branch; height follows the camera's aspect ratio. */
internal const val PREVIEW_WIDTH: Int = 640
