package com.joetr.mira.media

import android.media.MediaRecorder
import android.os.Build
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.joetr.mira.platform.AndroidContextHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

public actual fun supportsCapture(kind: CaptureKind): Boolean = true

public actual fun createMediaRecorderController(): MediaRecorderController =
    AndroidMediaRecorderController()

/**
 * Android capture: `MediaRecorder` for audio, CameraX `Recorder` for video.
 *
 * Both are configured to the same limits as every other platform, and both already emit
 * m4a/AAC and mp4/H.264 — so nothing needs transcoding before upload.
 */
internal class AndroidMediaRecorderController : MediaRecorderController {

    private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)
    override val state: StateFlow<RecordingState> = _state.asStateFlow()

    internal val videoCapture = MutableStateFlow<VideoCapture<Recorder>?>(null)

    private var audioRecorder: MediaRecorder? = null
    private var activeRecording: Recording? = null
    private var outputFile: File? = null
    private var startedAt: Long = 0
    private var currentKind: CaptureKind = CaptureKind.AUDIO

    override suspend fun start(kind: CaptureKind) {
        currentKind = kind
        _state.value = RecordingState.Preparing
        val context = AndroidContextHolder.application
        val extension = if (kind == CaptureKind.VIDEO) "mp4" else "m4a"
        val file = File(context.cacheDir, "mira-capture-${System.currentTimeMillis()}.$extension")
        outputFile = file
        startedAt = System.currentTimeMillis()

        if (kind == CaptureKind.AUDIO) {
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
            return
        }

        val capture = ensureVideoCapture()
        if (capture == null) {
            _state.value = RecordingState.Failed("Camera is unavailable")
            return
        }
        val options = FileOutputOptions.Builder(file).build()
        activeRecording = capture.output
            .prepareRecording(context, options)
            .withAudioEnabled()
            .start(ContextCompat.getMainExecutor(context)) { event ->
                when (event) {
                    is VideoRecordEvent.Status ->
                        _state.value = RecordingState.Recording(
                            event.recordingStats.recordedDurationNanos / 1_000_000,
                        )
                    is VideoRecordEvent.Finalize ->
                        if (event.hasError()) {
                            _state.value = RecordingState.Failed("Recording failed (${event.error})")
                        }
                    else -> Unit
                }
            }
        _state.value = RecordingState.Recording(0)
    }

    override suspend fun stop() {
        val elapsed = System.currentTimeMillis() - startedAt
        withContext(Dispatchers.IO) {
            audioRecorder?.runCatching {
                stop()
                release()
            }
            audioRecorder = null
            activeRecording?.stop()
            activeRecording = null
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
        audioRecorder?.runCatching {
            stop()
            release()
        }
        audioRecorder = null
        activeRecording?.stop()
        activeRecording = null
        outputFile?.delete()
        outputFile = null
        _state.value = RecordingState.Idle
    }

    override fun release() {
        cancel()
        videoCapture.value = null
    }

    private suspend fun ensureVideoCapture(): VideoCapture<Recorder>? {
        videoCapture.value?.let { return it }
        val context = AndroidContextHolder.application
        val activity = AndroidContextHolder.activity ?: return null
        val provider = withContext(Dispatchers.IO) {
            runCatching { ProcessCameraProvider.getInstance(context).get() }.getOrNull()
        } ?: return null

        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.FHD))
            .setTargetVideoEncodingBitRate(CaptureLimits.VIDEO_BITRATE)
            .build()
        val capture = VideoCapture.withOutput(recorder)

        return withContext(Dispatchers.Main) {
            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(
                    activity as androidx.lifecycle.LifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    capture,
                )
                videoCapture.value = capture
                capture
            }.getOrNull()
        }
    }
}

@Composable
public actual fun CameraPreview(
    controller: MediaRecorderController,
    modifier: Modifier,
) {
    AndroidView(
        modifier = modifier.background(Color.Black),
        factory = { context ->
            PreviewView(context).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
        },
    )
}
