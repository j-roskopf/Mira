package com.joetr.mira.media

import android.Manifest
import android.media.MediaRecorder
import android.os.Build
import android.util.Size
import android.view.Surface
import android.view.View
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.joetr.mira.platform.AndroidContextHolder
import com.joetr.mira.platform.requestAndroidPermissions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

public actual fun supportsCapture(kind: CaptureKind): Boolean = true

public actual fun createMediaRecorderController(): MediaRecorderController =
    AndroidMediaRecorderController()

/**
 * Android capture: `MediaRecorder` for audio, CameraX `Recorder` for video.
 *
 * Both are configured to the same limits as every other platform, and both already emit m4a/AAC
 * and mp4/H.264 — so nothing needs transcoding before upload. H.264 is CameraX's default and is
 * kept deliberately: it is the one codec every client in the archive can decode. Video binds a
 * `Preview` alongside the recorder so the live camera is visible while composing.
 *
 * No aspect ratio picker is offered: CameraX already rotates the recording to match the device's
 * orientation, so the captured shape just follows however the phone is held.
 */
internal class AndroidMediaRecorderController : MediaRecorderController {

    private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)
    override val state: StateFlow<RecordingState> = _state.asStateFlow()

    override val supportsVideoSettings: Boolean = true

    // Rotating the phone already rotates the recording via CameraX's target rotation, so there is
    // nothing left for a manual shape picker to do.
    override val supportedAspectRatios: List<VideoAspectRatio> = emptyList()
    override val supportedQualities: List<VideoQuality> = VideoQuality.entries
    override val supportsCameraFacing: Boolean = true
    override val adaptsControlsToOrientation: Boolean = true

    private val videoCapture = MutableStateFlow<VideoCapture<Recorder>?>(null)
    @Volatile private var imageCaptureUseCase: ImageCapture? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val setupMutex = Mutex()

    @Volatile private var videoSettings = VideoCaptureSettings()
    private var cameraProvider: ProcessCameraProvider? = null
    private var previewUseCase: Preview? = null

    // Inline and full-screen previews can both exist. Only the most recently attached one gets the
    // surface; detaching it hands the stream back to the one underneath.
    private val previewViews = mutableListOf<PreviewView>()
    @Volatile private var activePreview: PreviewView? = null
    private val rotationListeners = mutableMapOf<PreviewView, View.OnLayoutChangeListener>()

    private var audioRecorder: MediaRecorder? = null
    private var activeRecording: Recording? = null
    private var finalizeSignal: CompletableDeferred<VideoRecordEvent.Finalize>? = null
    private var outputFile: File? = null
    private var startedAt: Long = 0
    private var currentKind: CaptureKind = CaptureKind.AUDIO
    private var audioTicker: Job? = null

    override fun configureVideo(settings: VideoCaptureSettings) {
        check(
            _state.value !is RecordingState.Recording &&
                _state.value != RecordingState.Preparing &&
                _state.value != RecordingState.Finalizing,
        )
        videoSettings = settings
        if (activePreview == null) return
        scope.launch {
            setupMutex.withLock {
                if (activePreview != null && _state.value == RecordingState.Idle) {
                    bindCameraLocked(record = false)
                }
            }
        }
    }

    override suspend fun start(kind: CaptureKind) {
        currentKind = kind
        _state.value = RecordingState.Preparing
        val context = AndroidContextHolder.application

        if (kind == CaptureKind.PHOTO) {
            capturePhoto(context)
            return
        }

        val extension = if (kind == CaptureKind.VIDEO) "mp4" else "m4a"
        val file = File(context.cacheDir, "mira-capture-${System.currentTimeMillis()}.$extension")
        outputFile = file
        startedAt = System.currentTimeMillis()

        if (kind == CaptureKind.AUDIO) {
            ensureCapturePermissions(CaptureKind.AUDIO)?.let { denied ->
                _state.value = RecordingState.Failed(denied)
                return
            }
            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            runCatching {
                recorder.apply {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setAudioEncodingBitRate(CaptureLimits.AUDIO_BITRATE)
                    setAudioSamplingRate(CaptureLimits.AUDIO_SAMPLE_RATE)
                    setAudioChannels(CaptureLimits.AUDIO_CHANNELS)
                    setMaxDuration(CaptureLimits.MAX_DURATION_MS.toInt())
                    setOutputFile(file.absolutePath)
                    prepare()
                    start()
                }
            }.onFailure {
                _state.value = RecordingState.Failed(it.message ?: "Could not start recording")
                return
            }
            audioRecorder = recorder
            _state.value = RecordingState.Recording(0)
            audioTicker = scope.launch { tickAudioElapsed() }
            return
        }

        setupMutex.withLock {
            ensureCapturePermissions(CaptureKind.VIDEO)?.let { denied ->
                _state.value = RecordingState.Failed(denied)
                return@withLock
            }
            if (!bindCameraLocked(record = true)) {
                _state.value = RecordingState.Failed("Camera is unavailable")
                return
            }
            val capture = videoCapture.value ?: run {
                _state.value = RecordingState.Failed("Camera is unavailable")
                return
            }
            val options = FileOutputOptions.Builder(file).build()
            val finalize = CompletableDeferred<VideoRecordEvent.Finalize>()
            finalizeSignal = finalize
            activeRecording = capture.output
                .prepareRecording(context, options)
                .withAudioEnabled()
                .start(ContextCompat.getMainExecutor(context)) { event ->
                    when (event) {
                        is VideoRecordEvent.Status ->
                            _state.value = RecordingState.Recording(
                                event.recordingStats.recordedDurationNanos / 1_000_000,
                            )
                        is VideoRecordEvent.Finalize -> {
                            if (event.hasError()) {
                                _state.value = RecordingState.Failed("Recording failed (${event.error})")
                            }
                            finalize.complete(event)
                        }
                        else -> Unit
                    }
                }
            _state.value = RecordingState.Recording(0)
        }
    }

    /** A still photo finishes entirely inside [start] — there is nothing for [stop] to do. */
    private suspend fun capturePhoto(context: android.content.Context) {
        ensureCapturePermissions(CaptureKind.PHOTO)?.let { denied ->
            _state.value = RecordingState.Failed(denied)
            return
        }
        val file = File(context.cacheDir, "mira-capture-${System.currentTimeMillis()}.jpg")
        outputFile = file

        val saved = setupMutex.withLock {
            if (!bindCameraLocked(record = false)) return@withLock false
            val capture = imageCaptureUseCase ?: return@withLock false
            val options = ImageCapture.OutputFileOptions.Builder(file).build()
            val result = CompletableDeferred<Boolean>()
            capture.takePicture(
                options,
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        result.complete(true)
                    }

                    override fun onError(exception: ImageCaptureException) {
                        result.complete(false)
                    }
                },
            )
            runCatching { result.await() }.getOrDefault(false)
        }

        if (!saved || !file.exists() || file.length() == 0L) {
            outputFile = null
            _state.value = RecordingState.Failed("Could not take a photo. Please try again.")
            return
        }
        outputFile = null
        _state.value = RecordingState.Finished(
            CaptureResult(
                kind = CaptureKind.PHOTO,
                path = file.absolutePath,
                bytes = null,
                mime = "image/jpeg",
                sizeBytes = file.length(),
                durationMs = 0,
            ),
        )
    }

    /**
     * `MediaRecorder` has no progress callback, unlike CameraX's video recorder — so audio needs
     * its own clock. [MediaRecorder.getMaxAmplitude] piggybacks on the same poll to drive the live
     * waveform, scaled from its 0..32767 range down to 0f..1f.
     */
    private suspend fun tickAudioElapsed() {
        while (scope.isActive && _state.value is RecordingState.Recording) {
            delay(100)
            val recorder = audioRecorder ?: return
            if (_state.value !is RecordingState.Recording) return
            val elapsed = System.currentTimeMillis() - startedAt
            val amplitude = runCatching { recorder.maxAmplitude }.getOrDefault(0)
                .div(32767f)
                .coerceIn(0f, 1f)
            _state.value = RecordingState.Recording(elapsed, amplitude)
            if (elapsed >= CaptureLimits.MAX_DURATION_MS) {
                stop()
                return
            }
        }
    }

    override suspend fun stop() {
        if (currentKind == CaptureKind.PHOTO) return
        val elapsed = System.currentTimeMillis() - startedAt
        val finalize = finalizeSignal
        finalizeSignal = null
        audioTicker?.cancel()
        audioTicker = null
        if (currentKind == CaptureKind.VIDEO) _state.value = RecordingState.Finalizing
        withContext(Dispatchers.IO) {
            audioRecorder?.runCatching {
                stop()
                release()
            }
            audioRecorder = null
            activeRecording?.stop()
            activeRecording = null
        }

        if (currentKind == CaptureKind.VIDEO) {
            // CameraX finishes the mp4 asynchronously; releasing the camera before the finalize
            // event can truncate the file, so wait for it before unbinding.
            withTimeoutOrNull(10_000) { finalize?.await() }
            setupMutex.withLock { unbindCameraLocked() }
            if (_state.value is RecordingState.Failed) return
        }

        val file = outputFile
        if (file == null || !file.exists() || file.length() == 0L) {
            _state.value = RecordingState.Failed("Recording produced no audio or video")
            return
        }
        _state.value = RecordingState.Finished(
            CaptureResult(
                kind = currentKind,
                path = file.absolutePath,
                bytes = null,
                mime = if (currentKind == CaptureKind.VIDEO) "video/mp4" else "audio/mp4",
                sizeBytes = file.length(),
                durationMs = elapsed,
            ),
        )
    }

    override fun cancel() {
        audioTicker?.cancel()
        audioTicker = null
        audioRecorder?.runCatching {
            stop()
            release()
        }
        audioRecorder = null
        activeRecording?.stop()
        activeRecording = null
        finalizeSignal?.cancel()
        finalizeSignal = null
        outputFile?.delete()
        outputFile = null
        _state.value = RecordingState.Idle
    }

    override fun release() {
        cancel()
        synchronized(rotationListeners) {
            rotationListeners.forEach { (view, listener) -> view.removeOnLayoutChangeListener(listener) }
            rotationListeners.clear()
        }
        synchronized(previewViews) {
            previewViews.clear()
            activePreview = null
        }
        previewUseCase = null
        videoCapture.value = null
        imageCaptureUseCase = null
        scope.cancel()
        runCatching { cameraProvider?.unbindAll() }
        cameraProvider = null
    }

    /** Attaches a live preview surface, requesting camera access the first time. */
    internal fun attachPreview(view: PreviewView) {
        synchronized(previewViews) {
            previewViews.remove(view)
            previewViews.add(view)
            activePreview = view
        }
        val listener = View.OnLayoutChangeListener { changed, _, _, _, _, _, _, _, _ ->
            updateTargetRotation(changed.display?.rotation)
        }
        synchronized(rotationListeners) {
            rotationListeners.remove(view)?.let { view.removeOnLayoutChangeListener(it) }
            rotationListeners[view] = listener
        }
        view.addOnLayoutChangeListener(listener)
        updateTargetRotation(view.display?.rotation)
        scope.launch {
            setupMutex.withLock {
                when {
                    _state.value is RecordingState.Recording -> setPreviewSurface(view)
                    _state.value == RecordingState.Preparing ||
                        _state.value == RecordingState.Finalizing -> Unit
                    else -> {
                        ensureCapturePermissions(CaptureKind.VIDEO)?.let { denied ->
                            _state.value = RecordingState.Failed(denied)
                            return@withLock
                        }
                        bindCameraLocked(record = false)
                    }
                }
            }
        }
    }

    /** Detaches the preview surface, handing the stream back or releasing the camera when idle. */
    internal fun detachPreview(view: PreviewView) {
        synchronized(rotationListeners) {
            rotationListeners.remove(view)?.let { view.removeOnLayoutChangeListener(it) }
        }
        synchronized(previewViews) {
            previewViews.remove(view)
            if (activePreview === view) activePreview = previewViews.lastOrNull()
        }
        scope.launch {
            setupMutex.withLock {
                val active = activePreview
                if (active != null && active !== view) {
                    if (_state.value is RecordingState.Recording) {
                        setPreviewSurface(active)
                    } else if (_state.value != RecordingState.Preparing &&
                        _state.value != RecordingState.Finalizing
                    ) {
                        bindCameraLocked(record = false)
                    }
                    return@withLock
                }
                if (_state.value is RecordingState.Recording ||
                    _state.value == RecordingState.Preparing ||
                    _state.value == RecordingState.Finalizing
                ) {
                    return@withLock
                }
                unbindCameraLocked()
            }
        }
    }

    private suspend fun setPreviewSurface(view: PreviewView) {
        withContext(Dispatchers.Main) {
            previewUseCase?.setSurfaceProvider(view.surfaceProvider)
        }
    }

    /** Keeps CameraX's output rotation aligned with the display after the device turns. */
    private fun updateTargetRotation(rotation: Int?) {
        if (rotation == null) return
        scope.launch {
            setupMutex.withLock {
                withContext(Dispatchers.Main) {
                    previewUseCase?.targetRotation = rotation
                    videoCapture.value?.targetRotation = rotation
                    imageCaptureUseCase?.targetRotation = rotation
                }
            }
        }
    }

    private fun currentDisplayRotation(): Int {
        val activity = AndroidContextHolder.activity ?: return Surface.ROTATION_0
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.display?.rotation ?: Surface.ROTATION_0
        } else {
            @Suppress("DEPRECATION")
            activity.windowManager.defaultDisplay.rotation
        }
    }

    /** Releases the camera and clears the bound use cases. */
    private suspend fun unbindCameraLocked() {
        withContext(Dispatchers.Main) {
            previewUseCase?.setSurfaceProvider(null)
            cameraProvider?.unbindAll()
        }
        previewUseCase = null
        videoCapture.value = null
        imageCaptureUseCase = null
    }

    /**
     * Binds the camera use cases. [record] adds the `VideoCapture` alongside the preview; without
     * it the camera still streams for framing (and gets an `ImageCapture` use case so a still photo
     * can be taken) but no video recording is prepared.
     */
    private suspend fun bindCameraLocked(record: Boolean): Boolean {
        val activity = AndroidContextHolder.activity as? LifecycleOwner ?: return false
        val provider = ensureCameraProvider() ?: return false
        val preview = Preview.Builder().apply {
            val ratio = videoSettings.aspectRatio.cameraXAspectRatio()
            if (ratio != AspectRatio.RATIO_DEFAULT) {
                setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(
                            AspectRatioStrategy(ratio, AspectRatioStrategy.FALLBACK_RULE_AUTO),
                        )
                        .build(),
                )
            }
        }.build()
        val useCases = mutableListOf<UseCase>(preview)
        val capture = if (record) buildVideoCapture().also { useCases.add(it) } else null
        val stillCapture = if (!record) {
            val shortEdge = videoSettings.quality.shortEdge
            ImageCapture.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(shortEdge * 4 / 3, shortEdge),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                            ),
                        )
                        .build(),
                )
                .build()
                .also { useCases.add(it) }
        } else {
            null
        }
        val selector = when (videoSettings.facing) {
            CameraFacing.BACK -> CameraSelector.DEFAULT_BACK_CAMERA
            CameraFacing.FRONT -> CameraSelector.DEFAULT_FRONT_CAMERA
        }
        val rotation = currentDisplayRotation()

        return withContext(Dispatchers.Main) {
            runCatching {
                // Set the rotation before binding: CameraX configures the surface from it during
                // bind, and applying it afterwards can leave a new use case with the old buffer.
                preview.targetRotation = rotation
                capture?.targetRotation = rotation
                stillCapture?.targetRotation = rotation
                provider.unbindAll()
                provider.bindToLifecycle(activity, selector, *useCases.toTypedArray())
                previewUseCase = preview
                videoCapture.value = capture
                imageCaptureUseCase = stillCapture
                activePreview?.let { preview.setSurfaceProvider(it.surfaceProvider) }
            }.isSuccess
        }
    }

    private fun buildVideoCapture(): VideoCapture<Recorder> {
        val quality = when (videoSettings.quality) {
            VideoQuality.HIGH -> Quality.FHD
            VideoQuality.STANDARD -> Quality.HD
            VideoQuality.DATA_SAVER -> Quality.SD
        }
        val recorder = Recorder.Builder()
            .setQualitySelector(
                QualitySelector.from(quality, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)),
            )
            .setTargetVideoEncodingBitRate(videoSettings.quality.bitrate)
            .apply {
                // CameraX defaults to H.264. Do not ask for HEVC: Android devices are uneven
                // about decoding it, and the archive has to play everywhere.
                val ratio = videoSettings.aspectRatio.cameraXAspectRatio()
                if (ratio != AspectRatio.RATIO_DEFAULT) setAspectRatio(ratio)
            }
            .build()
        return VideoCapture.withOutput(recorder)
    }

    private suspend fun ensureCameraProvider(): ProcessCameraProvider? {
        cameraProvider?.let { return it }
        val context = AndroidContextHolder.application
        val provider = withContext(Dispatchers.IO) {
            runCatching { ProcessCameraProvider.getInstance(context).get() }.getOrNull()
        } ?: return null
        cameraProvider = provider
        return provider
    }

    /**
     * Shows the system runtime permission dialog for the permissions [kind] needs.
     *
     * Returns a user-facing failure message when anything is denied, or null when capture may
     * proceed. A permanently denied permission resolves immediately without a dialog.
     */
    private suspend fun ensureCapturePermissions(kind: CaptureKind): String? {
        val permissions = buildList {
            if (kind != CaptureKind.PHOTO) add(Manifest.permission.RECORD_AUDIO)
            if (kind == CaptureKind.VIDEO || kind == CaptureKind.PHOTO) add(Manifest.permission.CAMERA)
        }
        val granted = requestAndroidPermissions(permissions)
        if (granted.values.all { it }) return null
        return when (kind) {
            CaptureKind.VIDEO -> "Camera and microphone access are needed to record video. Allow them in Settings."
            CaptureKind.PHOTO -> "Camera access is needed to take a photo. Allow it in Settings."
            CaptureKind.AUDIO -> "Microphone access is needed to record audio. Allow it in Settings."
        }
    }
}

private fun VideoAspectRatio.cameraXAspectRatio(): Int = when (this) {
    VideoAspectRatio.ORIGINAL -> AspectRatio.RATIO_DEFAULT
    VideoAspectRatio.LANDSCAPE, VideoAspectRatio.PORTRAIT -> AspectRatio.RATIO_16_9
    VideoAspectRatio.SQUARE -> AspectRatio.RATIO_4_3
}

@Composable
public actual fun CameraPreview(
    controller: MediaRecorderController,
    modifier: Modifier,
    settings: VideoCaptureSettings,
) {
    val androidController = controller as? AndroidMediaRecorderController
    val context = LocalContext.current
    val view = remember {
        PreviewView(context).apply {
            // The capture page is full screen, so fill it with the camera feed instead of
            // letterboxing — black bars made the preview look cropped inside the editor.
            scaleType = PreviewView.ScaleType.FILL_CENTER
            // TextureView follows Compose clipping and rotation; SurfaceView can look frozen or
            // ignore transforms when the device turns.
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    DisposableEffect(androidController, view) {
        androidController?.attachPreview(view)
        onDispose { androidController?.detachPreview(view) }
    }
    AndroidView(
        modifier = modifier.background(Color.Black),
        factory = { view },
    )
}
