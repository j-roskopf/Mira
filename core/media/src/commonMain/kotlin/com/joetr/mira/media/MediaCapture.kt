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
    public const val VIDEO_BITRATE: Int = 2_500_000
    public const val AUDIO_BITRATE: Int = 64_000
    public const val AUDIO_CHANNELS: Int = 1
    public const val AUDIO_SAMPLE_RATE: Int = 44_100

    public const val MAX_DURATION_MS: Long = 10 * 60 * 1000
    public const val WARN_DURATION_MS: Long = 5 * 60 * 1000
}

public enum class VideoAspectRatio(public val label: String, public val ratio: Float?) {
    ORIGINAL("Camera original", null),
    LANDSCAPE("Landscape 16:9", 16f / 9f),
    PORTRAIT("Portrait 9:16", 9f / 16f),
    SQUARE("Square 1:1", 1f),
}

public enum class VideoQuality(public val label: String, public val shortEdge: Int, public val bitrate: Int) {
    DATA_SAVER("360p · smallest files", 360, 350_000),
    STANDARD("720p · smaller files", 720, 1_200_000),
    HIGH("1080p · higher quality", 1080, 2_500_000),
}

public enum class CameraFacing(public val label: String) {
    BACK("Back camera"),
    FRONT("Front camera"),
}

public data class VideoCaptureSettings(
    val aspectRatio: VideoAspectRatio = VideoAspectRatio.ORIGINAL,
    val quality: VideoQuality = VideoQuality.STANDARD,
    val facing: CameraFacing = CameraFacing.BACK,
)

public enum class CaptureKind { AUDIO, VIDEO, PHOTO }

public sealed interface RecordingState {
    public data object Idle : RecordingState
    public data object Preparing : RecordingState
    public data object Finalizing : RecordingState
    public data class Recording(val elapsedMs: Long, val amplitude: Float = 0f) : RecordingState
    public data class Finished(val result: CaptureResult) : RecordingState
    public data class Failed(val message: String) : RecordingState
}

/**
 * A finished recording, still photo, or picked-from-library item.
 *
 * Every platform produces mp4/H.264+AAC or m4a/AAC for recordings — Android and iOS natively,
 * desktop via the bundled FFmpeg, and web by transcoding its webm with ffmpeg.wasm before this is
 * emitted. H.264 is deliberate: it is the one video codec every target can decode, so a file
 * recorded on any device plays on every other one. HEVC is not used for capture even where the
 * hardware encoder is faster, because Android and the browser cannot be relied on to decode it.
 * A still photo is always a JPEG. Nothing is normalised server-side, so media captured or picked
 * anywhere plays or displays everywhere.
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

    public val supportsVideoSettings: Boolean get() = false

    /** Which aspect ratios this platform can actually record. */
    public val supportedAspectRatios: List<VideoAspectRatio> get() = VideoAspectRatio.entries

    /** Which quality presets this platform can actually record. */
    public val supportedQualities: List<VideoQuality> get() = VideoQuality.entries

    /** Whether the user can switch between the front and back camera. */
    public val supportsCameraFacing: Boolean get() = false

    /**
     * Whether [CameraPreview] owns the whole viewport, as opposed to being laid out inside an
     * explicitly shaped frame.
     *
     * Desktop draws the feed into a frame that matches the selected output shape — dropping the
     * whole viewport would either stretch the feed or bury it in black bars — so its controls get
     * their own row below the preview rather than floating on top of it.
     */
    public val previewFillsViewport: Boolean get() = true

    /**
     * Whether rotating the device changes the shape [CameraPreview] is laid out in, so the capture
     * screen should move its controls to the side when that shape goes wide.
     *
     * Only handheld devices rotate this way — a desktop window or browser tab can be wide without
     * the user having turned anything sideways, so those platforms keep a fixed bottom dock.
     */
    public val adaptsControlsToOrientation: Boolean get() = false

    public fun configureVideo(settings: VideoCaptureSettings) {}

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
    settings: VideoCaptureSettings = VideoCaptureSettings(),
)
