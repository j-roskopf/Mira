package com.joetr.mira.media

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
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

public actual fun supportsCapture(kind: CaptureKind): Boolean = hasMediaDevices()

public actual fun createMediaRecorderController(): MediaRecorderController =
    WebMediaRecorderController()

/**
 * Web capture: `MediaRecorder` into webm, then transcoded to mp4/m4a with ffmpeg.wasm before the
 * result is emitted.
 *
 * Chrome's `MediaRecorder` only produces webm/VP8+Opus, which iOS `AVPlayer` cannot play — so a
 * voice note recorded in a browser would be unopenable on an iPhone a year later. Transcoding here
 * rather than in a Cloud Function keeps Mira entirely serverless; the ~25 MB ffmpeg.wasm payload is
 * loaded lazily on the first recording, so it never touches first paint.
 */
internal class WebMediaRecorderController : MediaRecorderController {

    private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)
    override val state: StateFlow<RecordingState> = _state.asStateFlow()

    private var handle: JsAny? = null
    private var startedAt: Double = 0.0
    private var currentKind: CaptureKind = CaptureKind.AUDIO
    private var pending: CompletableDeferred<CaptureResult?>? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var ticker: Job? = null

    override suspend fun start(kind: CaptureKind) {
        currentKind = kind
        _state.value = RecordingState.Preparing
        startedAt = currentTimeMs()

        if (kind == CaptureKind.PHOTO) {
            val started = startWebPhotoPreview()
            if (started == null) {
                _state.value = RecordingState.Failed("Camera permission was declined")
                return
            }
            handle = started
            _state.value = RecordingState.Recording(0)
            return
        }

        val started = startWebRecording(kind == CaptureKind.VIDEO)
        if (started == null) {
            _state.value = RecordingState.Failed("Microphone or camera permission was declined")
            return
        }
        handle = started
        _state.value = RecordingState.Recording(0)
        ticker = scope.launch { tickElapsed(started) }
    }

    /**
     * `MediaRecorder` has no progress event, so this is the only clock the recording has. The
     * level reading rides along on the same poll, pulled from the analyser node [started] set up
     * in `startWebRecordingJs`.
     */
    private suspend fun tickElapsed(started: JsAny) {
        while (scope.isActive && handle === started && _state.value is RecordingState.Recording) {
            delay(100)
            if (handle !== started || _state.value !is RecordingState.Recording) return
            val elapsed = (currentTimeMs() - startedAt).toLong()
            val amplitude = getWebAmplitude(started)
            _state.value = RecordingState.Recording(elapsed, amplitude)
            if (elapsed >= CaptureLimits.MAX_DURATION_MS) {
                stop()
                return
            }
        }
    }

    override suspend fun stop() {
        val active = handle ?: return
        ticker?.cancel()
        ticker = null

        if (currentKind == CaptureKind.PHOTO) {
            _state.value = RecordingState.Finalizing
            val photo = capturePhotoFromPreview(active)
            handle = null
            if (photo == null || photo.isEmpty()) {
                _state.value = RecordingState.Failed("Could not take a photo. Please try again.")
                return
            }
            _state.value = RecordingState.Finished(
                CaptureResult(
                    kind = CaptureKind.PHOTO,
                    path = null,
                    bytes = photo,
                    mime = "image/jpeg",
                    sizeBytes = photo.size.toLong(),
                    durationMs = 0,
                ),
            )
            return
        }

        val elapsed = (currentTimeMs() - startedAt).toLong()
        _state.value = RecordingState.Preparing

        val bytes = stopWebRecording(active)
        handle = null
        if (bytes == null || bytes.isEmpty()) {
            _state.value = RecordingState.Failed("Recording produced no audio or video")
            return
        }

        // Normalise to mp4/m4a so the result plays on every other platform, iOS included.
        val converted = transcodeToMp4(bytes, currentKind == CaptureKind.VIDEO)
            ?: run {
                _state.value = RecordingState.Failed("Could not convert the recording")
                return
            }

        _state.value = RecordingState.Finished(
            CaptureResult(
                kind = currentKind,
                path = null,
                bytes = converted,
                mime = if (currentKind == CaptureKind.VIDEO) "video/mp4" else "audio/mp4",
                sizeBytes = converted.size.toLong(),
                durationMs = elapsed,
            ),
        )
    }

    override fun cancel() {
        ticker?.cancel()
        ticker = null
        handle?.let { cancelWebRecording(it) }
        handle = null
        pending?.cancel()
        pending = null
        _state.value = RecordingState.Idle
    }

    override fun release() {
        cancel()
        scope.cancel()
    }
}

/**
 * Bounds of every live preview, in composition order, so the JS `<video>` element follows whichever
 * one is on top — the inline preview or the full-screen one.
 */
private var nextPreviewId: Int = 0
private val previewBounds = linkedMapOf<Int, List<Double>>()

@Composable
public actual fun CameraPreview(
    controller: MediaRecorderController,
    modifier: Modifier,
    settings: VideoCaptureSettings,
) {
    // The preview is a <video> element positioned by the JS side; Compose reserves the space.
    val id = remember { nextPreviewId++ }
    DisposableEffect(id) {
        onDispose {
            previewBounds.remove(id)
            previewBounds.values.lastOrNull()?.let { bounds ->
                positionWebPreview(bounds[0], bounds[1], bounds[2], bounds[3])
            }
        }
    }
    Box(
        modifier
            .background(Color.Black)
            .onGloballyPositioned { coordinates ->
                val bounds = coordinates.boundsInWindow()
                val values = listOf(
                    bounds.left.toDouble(),
                    bounds.top.toDouble(),
                    bounds.width.toDouble(),
                    bounds.height.toDouble(),
                )
                previewBounds[id] = values
                positionWebPreview(values[0], values[1], values[2], values[3])
            },
    )
}
