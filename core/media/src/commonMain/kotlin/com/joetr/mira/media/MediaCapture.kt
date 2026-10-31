package com.joetr.mira.media

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.StateFlow

/**
 * Capture limits, applied identically on every platform.
 *
 * 64 kbps mono AAC is indistinguishable from 128 kbps stereo for speech at a quarter the size, and
 * a journal accumulates forever. The length cap is there so a pocket recording cannot quietly cost
 * a gigabyte.
 */
public object CaptureLimits {
    public const val VIDEO_WIDTH: Int = 1920
    public const val VIDEO_HEIGHT: Int = 1080
    public const val VIDEO_BITRATE: Int = 4_000_000
    public const val AUDIO_BITRATE: Int = 64_000
    public const val AUDIO_CHANNELS: Int = 1
    public const val AUDIO_SAMPLE_RATE: Int = 44_100

    public const val MAX_DURATION_MS: Long = 10 * 60 * 1000
    public const val WARN_DURATION_MS: Long = 5 * 60 * 1000
}

public enum class CaptureKind { AUDIO, VIDEO }

public sealed interface RecordingState {
    public data object Idle : RecordingState
    public data object Preparing : RecordingState
    public data class Recording(val elapsedMs: Long, val amplitude: Float = 0f) : RecordingState
    public data class Finished(val result: CaptureResult) : RecordingState
    public data class Failed(val message: String) : RecordingState
}

/**
 * A finished recording.
 *
 * Every platform produces mp4/H.264+AAC or m4a/AAC — Android and iOS natively, desktop via the
 * bundled FFmpeg, and web by transcoding its webm with ffmpeg.wasm before this is emitted. Nothing
 * is normalised server-side, so media recorded anywhere plays everywhere.
 */
public data class CaptureResult(
    val kind: CaptureKind,
    val path: String?,
    val bytes: ByteArray?,
    val mime: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val posterBytes: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is CaptureResult && path == other.path && kind == other.kind)

    override fun hashCode(): Int = (path?.hashCode() ?: 0) * 31 + kind.hashCode()
}

public interface MediaRecorderController {
    public val state: StateFlow<RecordingState>

    public suspend fun start(kind: CaptureKind)
    public suspend fun stop()
    public fun cancel()
    public fun release()
}

/** Whether this platform can record [kind] at all. Desktop video needs the bundled FFmpeg. */
public expect fun supportsCapture(kind: CaptureKind): Boolean

public expect fun createMediaRecorderController(): MediaRecorderController

/**
 * Live camera preview for video capture.
 *
 * On desktop this renders frames from FFmpeg's MJPEG stdout stream — one process writes the file
 * and the preview at once via the `tee` muxer, so the camera is only opened once.
 */
@Composable
public expect fun CameraPreview(
    controller: MediaRecorderController,
    modifier: Modifier,
)
