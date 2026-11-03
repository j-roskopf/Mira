package com.joetr.mira.media

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean

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
internal class DesktopMediaRecorderController(
    private val startupTimeoutMs: Long = 120_000L,
    private val previewCommandFactory: ((VideoCaptureSettings) -> List<String>)? = null,
    private val commandFactory: ((CaptureKind, File, String?) -> List<String>)? = null,
) : MediaRecorderController {

    private companion object {
        const val MAX_STDERR_LINES = 40
        const val PHOTO_TIMEOUT_MS = 5_000L
        val PROGRESS_LINE = Regex("^[a-zA-Z0-9_]+=.*")
    }

    override val supportsVideoSettings: Boolean = true
    override val previewFillsViewport: Boolean = false
    private var videoSettings = VideoCaptureSettings()
    private var cameraSize: CameraSize? = null

    override fun configureVideo(settings: VideoCaptureSettings) {
        check(_state.value !is RecordingState.Recording && _state.value != RecordingState.Preparing && _state.value != RecordingState.Finalizing)
        if (settings == videoSettings) return
        videoSettings = settings
        // A framing preview is already running; rebuild it for the new shape.
        if (previewProcess != null && process == null) {
            scope.launch { startPreview(settings) }
        }
    }

    private val startLock = Mutex()
    private val generation = AtomicLong()

    private val previewLock = Mutex()
    private val previewGeneration = AtomicLong()

    private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)
    override val state: StateFlow<RecordingState> = _state.asStateFlow()

    @Volatile private var previewJpeg: ByteArray? = null

    internal val previewFrame = MutableStateFlow<ImageBitmap?>(null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var process: Process? = null

    /** Lightweight camera handle used only for framing before the shutter is pressed. */
    @Volatile private var previewProcess: Process? = null
    @Volatile private var previewSettings: VideoCaptureSettings? = null
    private val shutdownHook = Thread(
        {
            process?.destroyForcibly()
            previewProcess?.destroyForcibly()
        },
        "mira-capture-shutdown",
    ).also { Runtime.getRuntime().addShutdownHook(it) }
    private var outputFile: File? = null
    private var startedAt: Long = 0
    private var currentKind: CaptureKind = CaptureKind.AUDIO
    private val stderrLines = mutableListOf<String>()

    override suspend fun start(kind: CaptureKind) {
        if (!startLock.tryLock()) return
        val request = generation.get()
        try {
            if (process != null || _state.value == RecordingState.Finalizing) return
            if (commandFactory == null && FfmpegBinary.path == null) {
                _state.value = RecordingState.Failed("FFmpeg is not available in this build")
                return
            }
            if (kind == CaptureKind.PHOTO) {
                capturePhoto(request)
                return
            }
            // The camera belongs to one process at a time; release the framing feed first.
            // Taking Preparing first also stops a settings-triggered preview restart from
            // slipping in and grabbing the camera while the recorder starts.
            _state.value = RecordingState.Preparing
            stopPreview()
            previewFrame.value = null
            previewJpeg = null
            currentKind = kind
            cameraSize = preferredCameraSize(videoSettings)
            val launchKind = kind
            val extension = if (launchKind == CaptureKind.VIDEO) "mp4" else "m4a"
            val file = File.createTempFile("mira-capture-", ".$extension").apply { deleteOnExit() }
            outputFile = file

            if (launch(launchKind, file, pixelFormat = null, request = request)) return
            if (generation.get() != request) return

            if (launchKind == CaptureKind.VIDEO && desktopOs == DesktopOs.MAC && cameraSize != null) {
                val fallbackSize = fallbackCameraSize(stderrText(), cameraSize!!)
                if (fallbackSize != null && fallbackSize != cameraSize) {
                    cameraSize = fallbackSize
                    if (launch(launchKind, file, pixelFormat = null, request = request)) return
                    if (generation.get() != request) return
                }
            }
            val offered = parseSupportedPixelFormats(stderrText())
            val fallback = MAC_PIXEL_FORMATS.firstOrNull { it in offered } ?: offered.firstOrNull()
            if (fallback != null && launch(launchKind, file, pixelFormat = fallback, request = request)) return
            if (generation.get() == request) _state.value = RecordingState.Failed(failureMessage())
        } catch (cancelled: CancellationException) {
            if (generation.get() == request) cancel()
            throw cancelled
        } finally {
            startLock.unlock()
        }
    }

    /**
     * Starts one FFmpeg capture attempt.
     *
     * Returns false when the process dies on startup — which is how an unsupported pixel format or
     * a missing camera presents itself, since FFmpeg exits before producing a single frame.
     */
    private suspend fun launch(
        kind: CaptureKind,
        file: File,
        pixelFormat: String?,
        request: Long,
    ): Boolean {
        val binary = if (commandFactory == null) FfmpegBinary.path ?: return false else ""
        synchronized(stderrLines) { stderrLines.clear() }

        val command = commandFactory?.invoke(kind, file, pixelFormat)
            ?: buildCommand(binary, kind, file, pixelFormat)
        val started = withContext(Dispatchers.IO) {
            runCatching {
                ProcessBuilder(command).redirectErrorStream(false).start().also { process = it }
            }.getOrNull()
        } ?: return false

        if (generation.get() != request) {
            started.destroyForcibly()
            if (process === started) process = null
            return true
        }
        val mediaProduced = AtomicBoolean(false)
        val stderrJob = scope.launch { drain(started, kind, mediaProduced) }

        // Read stdout before waiting, not after. The preview pipe holds about 64KB — roughly
        // three frames — and once it fills FFmpeg blocks on the write. With tee that stalls the
        // mp4 branch as well.
        if (kind == CaptureKind.VIDEO) {
            scope.launch { readMjpegFrames(started, mediaProduced) }
        }

        // Permission dialogs and device warmup are part of Preparing. A live process alone
        // is not evidence of recording, and an MP4 header alone is not evidence of audio.
        val ready = withTimeoutOrNull(startupTimeoutMs) {
            while (process === started && started.isAlive) {
                if (mediaProduced.get()) {
                    return@withTimeoutOrNull true
                }
                delay(50)
            }
            false
        }
        if (process !== started) return true // cancelled; never resurrect this attempt
        if (ready != true) {
            process = null
            if (ready == null) {
                started.destroyForcibly()
                _state.value = RecordingState.Failed(
                    "Capture did not start. Check camera and microphone access and whether " +
                        "another app is using the device, then try again.",
                )
                return true
            }
            stderrJob.join() // Pixel-format retry must see the complete diagnostic.
            return false
        }

        startedAt = System.currentTimeMillis()
        _state.value = RecordingState.Recording(0)

        scope.launch { tickElapsed(started) }
        scope.launch { watchForDeath(started) }
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
        inputArgs = if (kind == CaptureKind.VIDEO) videoInputArgs(pixelFormat, cameraSize) else audioInputArgs(),
        // Linux opens video and audio as two separate inputs; macOS and Windows as one.
        audioInputIndex = if (desktopOs == DesktopOs.LINUX) 1 else 0,
        videoSettings = videoSettings,
    )

    override suspend fun stop() {
        if (_state.value == RecordingState.Preparing) {
            cancel()
            return
        }
        if (_state.value !is RecordingState.Recording) return
        val running = process ?: return
        val request = generation.get()
        val elapsed = System.currentTimeMillis() - startedAt
        val file = outputFile
        val kind = currentKind
        val poster = previewJpeg
        _state.value = RecordingState.Finalizing
        try {
            val completed = withContext(Dispatchers.IO) {
                // Keep draining stdout while FFmpeg flushes both tee outputs; otherwise the
                // preview pipe fills and prevents the MP4 trailer from being written.
                runCatching {
                    running.outputStream.write('q'.code)
                    running.outputStream.flush()
                }
                if (!running.waitFor(5, TimeUnit.SECONDS)) running.destroy()
                if (!running.waitFor(3, TimeUnit.SECONDS)) {
                    running.destroyForcibly()
                    running.waitFor(2, TimeUnit.SECONDS)
                }
                !running.isAlive && running.exitValue() == 0
            }
            if (generation.get() != request) return
            if (!completed || file == null || !file.exists() || file.length() == 0L) {
                _state.value = RecordingState.Failed("Could not finish this recording. Please try again.")
                return
            }
            _state.value = RecordingState.Finished(
                CaptureResult(
                    kind = kind,
                    path = file.absolutePath,
                    bytes = null,
                    mime = if (kind == CaptureKind.VIDEO) "video/mp4" else "audio/mp4",
                    sizeBytes = file.length(),
                    durationMs = elapsed,
                    posterBytes = if (kind == CaptureKind.VIDEO) poster else null,
                ),
            )
        } finally {
            if (process === running) process = null
        }
    }

    /**
     * Opens the camera for framing before the shutter is pressed.
     *
     * Recording cannot start until the user commits, and a photo should be one tap, so framing
     * runs as its own lightweight MJPEG-only process. It is torn down the moment a real capture
     * begins, because a camera handle belongs to one process at a time.
     */
    internal suspend fun startPreview(settings: VideoCaptureSettings = videoSettings) {
        previewLock.withLock {
            if (process != null ||
                _state.value == RecordingState.Preparing ||
                _state.value == RecordingState.Finalizing
            ) {
                return
            }
            if (previewProcess != null) {
                if (previewSettings == settings) return
                // The shape (or quality) changed; the running feed is now stale.
                stopPreview()
            }
            if (previewCommandFactory == null && FfmpegBinary.path == null) return
            videoSettings = settings
            val request = previewGeneration.get()
            var size = preferredCameraSize(videoSettings)
            previewJpeg = null
            previewFrame.value = null
            val formats: List<String?> = if (desktopOs == DesktopOs.MAC) MAC_PIXEL_FORMATS else listOf(null)
            for (format in formats) {
                if (previewGeneration.get() != request) return
                if (launchPreview(format, size, request)) return
                // A camera may not offer the requested size even though it can produce the
                // selected shape; retry with the closest mode it actually advertised.
                if (desktopOs == DesktopOs.MAC && size != null) {
                    fallbackCameraSize(stderrText(), size)?.let { size = it }
                }
            }
            if (previewGeneration.get() == request) {
                _state.value = RecordingState.Failed(failureMessage())
            }
        }
    }

    /** Starts one framing attempt; [pixelFormat] candidates are retried by [startPreview]. */
    private suspend fun launchPreview(
        pixelFormat: String?,
        size: CameraSize?,
        request: Long,
    ): Boolean {
        val binary = FfmpegBinary.path ?: ""
        synchronized(stderrLines) { stderrLines.clear() }
        val command = previewCommandFactory?.invoke(videoSettings)
            ?: buildPreviewCommand(
                binary = binary,
                inputArgs = videoOnlyInputArgs(pixelFormat, size),
                videoSettings = videoSettings,
            )
        val started = withContext(Dispatchers.IO) {
            runCatching { ProcessBuilder(command).redirectErrorStream(false).start() }.getOrNull()
        } ?: return false

        if (previewGeneration.get() != request) {
            started.destroyForcibly()
            return true
        }
        previewProcess = started
        previewSettings = videoSettings
        val firstFrame = AtomicBoolean(false)
        val stderrJob = scope.launch { drain(started, CaptureKind.VIDEO, AtomicBoolean(false)) }
        scope.launch { readPreviewFrames(started, firstFrame) }

        val ready = withTimeoutOrNull(startupTimeoutMs) {
            while (previewProcess === started && started.isAlive) {
                if (firstFrame.get()) return@withTimeoutOrNull true
                delay(50)
            }
            false
        }
        if (previewProcess !== started) return true
        if (ready == true) {
            scope.launch { watchPreviewDeath(started) }
            return true
        }
        previewProcess = null
        previewSettings = null
        started.destroyForcibly()
        stderrJob.join() // Pixel-format retry must see the complete diagnostic.
        return false
    }

    private suspend fun readPreviewFrames(
        active: Process,
        firstFrame: AtomicBoolean,
    ) = withContext(Dispatchers.IO) {
        readJpegFrames(active.inputStream) { bytes ->
            if (previewProcess !== active) return@readJpegFrames false
            val decoded = decodeJpegFrame(bytes) ?: return@readJpegFrames false
            previewJpeg = bytes
            previewFrame.value = decoded
            firstFrame.set(true)
            true
        }
    }

    private suspend fun watchPreviewDeath(active: Process) = withContext(Dispatchers.IO) {
        active.waitFor()
        if (previewProcess === active) {
            previewProcess = null
            previewSettings = null
            previewFrame.value = null
            previewJpeg = null
        }
    }

    /** Non-suspending teardown for composables, which cannot await process exit. */
    internal fun cancelPreview() {
        previewGeneration.incrementAndGet()
        previewProcess?.destroyForcibly()
        previewProcess = null
        previewSettings = null
        previewFrame.value = null
        previewJpeg = null
    }

    /** Ends the framing process and waits for the camera handle to actually close. */
    private suspend fun stopPreview() {
        previewGeneration.incrementAndGet()
        val running = previewProcess ?: return
        previewProcess = null
        previewSettings = null
        withContext(Dispatchers.IO) {
            running.destroyForcibly()
            running.waitFor(2, TimeUnit.SECONDS)
        }
    }

    /**
     * A photo is one tap: snapshot the latest framing frame rather than starting a recording the
     * user would have to stop again (which also started a misleading timer).
     */
    private suspend fun capturePhoto(request: Long) {
        currentKind = CaptureKind.PHOTO
        if (previewProcess == null) startPreview()
        if (generation.get() != request) return
        _state.value = RecordingState.Preparing
        val jpeg = withTimeoutOrNull(PHOTO_TIMEOUT_MS) {
            while (previewJpeg == null && previewProcess?.isAlive == true) delay(30)
            previewJpeg
        }
        if (generation.get() != request) return
        if (jpeg == null) {
            _state.value = RecordingState.Failed("Could not take a photo. Please try again.")
            return
        }
        stopPreview()
        previewFrame.value = null
        previewJpeg = null
        _state.value = RecordingState.Finished(
            CaptureResult(
                kind = CaptureKind.PHOTO,
                path = null,
                bytes = jpeg,
                mime = "image/jpeg",
                sizeBytes = jpeg.size.toLong(),
                durationMs = 0,
            ),
        )
    }

    override fun cancel() {
        generation.incrementAndGet()
        previewGeneration.incrementAndGet()
        process?.destroyForcibly()
        process = null
        previewProcess?.destroyForcibly()
        previewProcess = null
        previewSettings = null
        outputFile?.delete()
        outputFile = null
        previewFrame.value = null
        previewJpeg = null
        _state.value = RecordingState.Idle
    }

    override fun release() {
        cancel()
        scope.cancel()
        runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
    }

    private suspend fun tickElapsed(active: Process) {
        while (scope.isActive && process === active && _state.value is RecordingState.Recording) {
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

    private suspend fun readMjpegFrames(active: Process, mediaProduced: AtomicBoolean) = withContext(Dispatchers.IO) {
        readJpegFrames(active.inputStream) { bytes ->
            if (process !== active) return@readJpegFrames false
            if (_state.value == RecordingState.Finalizing) return@readJpegFrames true
            val decoded = decodeJpegFrame(bytes)
            if (decoded == null) {
                // Do not silently claim to be recording with an unusable black preview.
                _state.value = RecordingState.Failed("Could not display the camera preview. Please try again.")
                process = null
                active.destroyForcibly()
                return@readJpegFrames false
            }
            previewJpeg = bytes
            previewFrame.value = decoded
            mediaProduced.set(true)
            isActive
        }
    }

    /**
     * Collects FFmpeg's stderr.
     *
     * Deliberately does not end the recording on its own. FFmpeg writes recoverable complaints
     * here too, and treating the first line as fatal meant a warning killed a capture that was
     * still running fine. The process exiting is the only thing that actually means failure.
     */
    private suspend fun drain(
        active: Process,
        kind: CaptureKind,
        mediaProduced: AtomicBoolean,
    ) = withContext(Dispatchers.IO) {
        runCatching {
            active.errorStream.bufferedReader().forEachLine { line ->
                // FFmpeg progress confirms encoded audio, unlike a container header.
                if (kind == CaptureKind.AUDIO && line.startsWith("out_time_us=")) {
                    if ((line.substringAfter('=').toLongOrNull() ?: 0) > 0) mediaProduced.set(true)
                }
                if (line.isNotBlank() && !PROGRESS_LINE.matches(line)) {
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
    settings: VideoCaptureSettings,
) {
    val desktopController = controller as? DesktopMediaRecorderController
    val frames = remember(desktopController) {
        desktopController?.previewFrame ?: MutableStateFlow(null)
    }
    val frame by frames.collectAsState()

    // Open the camera for framing as soon as the preview mounts, and reshape it whenever the
    // selected settings change. Reading the settings here keeps the live feed tied to the UI even
    // if a recorder-side configuration callback is missed or delivered out of order.
    LaunchedEffect(desktopController, settings) {
        desktopController?.startPreview(settings)
    }
    DisposableEffect(desktopController) {
        onDispose { desktopController?.cancelPreview() }
    }

    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        frame?.let { bitmap ->
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                // Show the whole camera frame. Cropping to fill a wide window magnifies the feed
                // and hides what is actually being recorded.
                contentScale = ContentScale.Fit,
            )
        }
    }
}

/**
 * The framing-only pipeline: MJPEG straight to stdout.
 *
 * No encoders, muxer, file, or audio — this exists to keep the camera open for a live view before
 * the shutter, and a lightweight process is quick to close so the recording pipeline can reopen
 * the same device.
 */
internal fun buildPreviewCommand(
    binary: String,
    inputArgs: List<String>,
    videoSettings: VideoCaptureSettings = VideoCaptureSettings(),
): List<String> = buildList {
    add(binary)
    add("-hide_banner")
    add("-loglevel")
    add("error")
    addAll(inputArgs)
    add("-vf")
    add("${videoFilter(videoSettings)},scale=$PREVIEW_WIDTH:-2,format=yuvj420p")
    // AVFoundation can report a microsecond time base as a million fps; cap the output rate so
    // the preview pipe is not flooded with duplicate frames.
    add("-r:v")
    add("30")
    add("-c:v")
    add("mjpeg")
    add("-q:v")
    add("6")
    add("-f")
    add("mjpeg")
    add("pipe:1")
}

/**
 * Builds the FFmpeg capture command.
 *
 * Extracted from the controller so the pipeline can be tested against synthetic `lavfi` inputs,
 * which need no camera or microphone and therefore run anywhere.
 *
 * Video takes **two encoders**, not one. The `tee` muxer shares a single encoded stream across its
 * slaves, so the recording file (H.264 or HEVC) and an MJPEG preview cannot come from the same
 * encode — tee rejects it with "mjpeg muxer supports only codec mjpeg for type video". The filter
 * graph splits the camera feed, each branch gets its own encoder, and `select` routes the right
 * streams to each slave.
 */
internal fun buildCaptureCommand(
    binary: String,
    kind: CaptureKind,
    outputPath: String,
    inputArgs: List<String>,
    audioInputIndex: Int,
    durationSeconds: Long = CaptureLimits.MAX_DURATION_MS / 1000,
    videoSettings: VideoCaptureSettings = VideoCaptureSettings(),
): List<String> = buildList {
    add(binary)
    add("-hide_banner")
    add("-loglevel")
    add("error")
    add("-progress")
    add("pipe:2")
    addAll(inputArgs)
    add("-t")
    add(durationSeconds.toString())

    if (kind == CaptureKind.VIDEO) {
        add("-filter_complex")
        add(
            "[0:v]${videoFilter(videoSettings)},split=2[rec][prev];" +
                "[rec]format=yuv420p[vrec];" +
                "[prev]scale=$PREVIEW_WIDTH:-2,format=yuvj420p[vprev]",
        )
        add("-map")
        add("[vrec]")
        add("-map")
        add("[vprev]")
        add("-map")
        add("$audioInputIndex:a")

        addAll(videoEncoderArgs(videoSettings.quality.bitrate))
        // AVFoundation can report its microsecond time base as a 1,000,000 fps rate.
        // The input -framerate option does not constrain output synchronization: without
        // this, tee duplicates tens of thousands of frames and starves the preview branch.
        add("-r:v")
        add("30")
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
        // Streams 0 and 1 are the recording and MJPEG branches in -map order, 2 is the audio. The
        // comma inside a tee slave option must be escaped or tee reads it as the next option.
        add("[select=0\\,2:f=mp4]$outputPath|[select=1:f=mjpeg]pipe:1")
    } else {
        add("-y")
        add(outputPath)
    }
}

/** Width of the MJPEG preview branch; height follows the camera's aspect ratio. */
internal const val PREVIEW_WIDTH: Int = 640

/**
 * The recording-branch video encoder.
 *
 * macOS uses VideoToolbox: it is a system framework, so the bundled FFmpeg needs no external
 * library and stays self-contained, and encoding runs on dedicated hardware. HEVC on VideoToolbox
 * is hardware-accelerated on every Mac capable of running this app, so it is used unconditionally
 * there rather than probed for — unlike Android, there is no fleet of older/budget machines behind
 * this build. It takes no `-preset` — that option is libx264's, and passing it makes FFmpeg exit.
 * Windows and Linux stay on libx264: software HEVC (`libx265`) is meaningfully slower and risks not
 * keeping up with a live recording on weaker hardware.
 */
internal fun videoEncoderArgs(bitrate: Int = CaptureLimits.VIDEO_BITRATE): List<String> = when (desktopOs) {
    DesktopOs.MAC -> listOf(
        "-c:v:0", "hevc_videotoolbox",
        "-b:v:0", bitrate.toString(),
        "-realtime", "1",
        "-tag:v:0", "hvc1",
    )
    DesktopOs.WINDOWS, DesktopOs.LINUX -> listOf(
        "-c:v:0", "libx264",
        "-preset", "veryfast",
        "-b:v:0", bitrate.toString(),
    )
}

/** Fit the camera image so it covers the selected shape, cropping the overflow. */
internal fun videoFilter(settings: VideoCaptureSettings): String {
    val edge = settings.quality.shortEdge
    val (width, height) = when (settings.aspectRatio) {
        VideoAspectRatio.LANDSCAPE -> edge * 16 / 9 to edge
        VideoAspectRatio.PORTRAIT -> edge to edge * 16 / 9
        VideoAspectRatio.SQUARE -> edge to edge
        VideoAspectRatio.ORIGINAL -> return "scale=${edge * 16 / 9}:${edge * 16 / 9}:force_original_aspect_ratio=decrease:force_divisible_by=2,setsar=1"
    }
    // Crop rather than pad: a padded square or portrait frame is mostly black bars, so it never
    // reads as the shape the user picked. Cropping fills the frame, matching what the phone
    // cameras already do with their own previews.
    return "scale=$width:$height:force_original_aspect_ratio=increase:force_divisible_by=2," +
        "crop=$width:$height,setsar=1"
}
