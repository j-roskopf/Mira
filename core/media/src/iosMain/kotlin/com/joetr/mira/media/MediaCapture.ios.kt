package com.joetr.mira.media

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.interop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.AVFAudio.AVAudioRecorder
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayAndRecord
import platform.AVFAudio.AVEncoderBitRateKey
import platform.AVFAudio.AVFormatIDKey
import platform.AVFAudio.AVNumberOfChannelsKey
import platform.AVFAudio.AVSampleRateKey
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVCaptureDeviceInput
import platform.AVFoundation.AVCaptureDevicePositionBack
import platform.AVFoundation.AVCaptureDevicePositionFront
import platform.AVFoundation.AVCaptureDeviceTypeBuiltInWideAngleCamera
import platform.AVFoundation.AVCaptureFileOutputRecordingDelegateProtocol
import platform.AVFoundation.AVCaptureMovieFileOutput
import platform.AVFoundation.AVCapturePhotoCaptureDelegateProtocol
import platform.AVFoundation.AVCapturePhotoOutput
import platform.AVFoundation.AVCapturePhotoSettings
import platform.AVFoundation.AVCaptureSession
import platform.AVFoundation.AVCaptureSessionPreset1280x720
import platform.AVFoundation.AVCaptureSessionPreset640x480
import platform.AVFoundation.AVCaptureSessionPresetHigh
import platform.AVFoundation.AVCaptureVideoPreviewLayer
import platform.AVFoundation.AVCaptureFileOutput
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.AVMediaTypeAudio
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVVideoAverageBitRateKey
import platform.AVFoundation.AVVideoCodecKey
import platform.AVFoundation.AVVideoCodecTypeH264
import platform.AVFoundation.AVVideoCompressionPropertiesKey
import platform.AVFoundation.AVVideoExpectedSourceFrameRateKey
import platform.AVFoundation.AVVideoMaxKeyFrameIntervalKey
import platform.AVFoundation.AVVideoProfileLevelH264HighAutoLevel
import platform.AVFoundation.AVVideoProfileLevelKey
import platform.AVFoundation.defaultDeviceWithDeviceType
import platform.AVFoundation.fileDataRepresentation
import platform.CoreAudioTypes.kAudioFormatMPEG4AAC
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSError
import platform.UIKit.UIView
import platform.darwin.NSObject
import kotlinx.coroutines.CompletableDeferred
import kotlin.time.ExperimentalTime
import kotlin.time.Clock

public actual fun supportsCapture(kind: CaptureKind): Boolean = true

@OptIn(ExperimentalForeignApi::class)
internal fun NSData.toByteArray(): ByteArray {
    val length = this.length.toInt()
    if (length == 0) return ByteArray(0)
    val out = ByteArray(length)
    out.usePinned { pinned ->
        platform.posix.memcpy(pinned.addressOf(0), this.bytes, this.length)
    }
    return out
}

public actual fun createMediaRecorderController(): MediaRecorderController =
    IosMediaRecorderController()

/**
 * Pins the movie output to H.264 at the bitrate the quality tier asks for.
 *
 * `AVCaptureSessionPresetHigh` otherwise lets the system choose a generous bitrate (often well
 * above the Android and desktop targets), which makes iPhone recordings the largest files in the
 * archive for no visible benefit. A 30-frame keyframe interval keeps seeking responsive without
 * inflating the file the way all-intra would.
 */
@OptIn(ExperimentalForeignApi::class)
private fun AVCaptureMovieFileOutput.applyRecordingBitrate(bitrate: Int) {
    val connection = connectionWithMediaType(AVMediaTypeVideo) ?: return
    val compression = mapOf<Any?, Any?>(
        AVVideoAverageBitRateKey to bitrate,
        AVVideoExpectedSourceFrameRateKey to 30,
        AVVideoMaxKeyFrameIntervalKey to 30,
        AVVideoProfileLevelKey to AVVideoProfileLevelH264HighAutoLevel,
    )
    setOutputSettings(
        mapOf<Any?, Any?>(
            AVVideoCodecKey to AVVideoCodecTypeH264,
            AVVideoCompressionPropertiesKey to compression,
        ),
        connection,
    )
}

/**
 * iOS capture: `AVAudioRecorder` for audio, `AVCaptureMovieFileOutput` for video.
 *
 * Both already produce m4a/AAC and mp4/H.264 at the limits Mira applies everywhere, so nothing is
 * transcoded before upload. H.264 is kept deliberately even though AVFoundation can encode HEVC:
 * Android and browser clients cannot be relied on to decode HEVC, and every attachment has to play
 * everywhere.
 *
 * No aspect ratio picker is offered: AVFoundation already orients the recording to match the
 * device, so the captured shape just follows however the phone is held.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalTime::class)
internal class IosMediaRecorderController : MediaRecorderController {

    private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)
    override val state: StateFlow<RecordingState> = _state.asStateFlow()

    internal var captureSession: AVCaptureSession? = null
        private set

    override val supportsVideoSettings: Boolean = true
    override val supportedAspectRatios: List<VideoAspectRatio> = emptyList()
    override val supportedQualities: List<VideoQuality> = VideoQuality.entries
    override val supportsCameraFacing: Boolean = true
    override val adaptsControlsToOrientation: Boolean = true

    private var videoSettings = VideoCaptureSettings()
    private var videoInput: AVCaptureDeviceInput? = null
    private var audioRecorder: AVAudioRecorder? = null
    private var movieOutput: AVCaptureMovieFileOutput? = null
    private var photoOutput: AVCapturePhotoOutput? = null
    private var outputUrl: NSURL? = null
    private var startedAt: Long = 0
    private var currentKind: CaptureKind = CaptureKind.AUDIO
    private var photoResult: CompletableDeferred<NSData?>? = null

    private val recordingDelegate = object : NSObject(), AVCaptureFileOutputRecordingDelegateProtocol {
        override fun captureOutput(
            output: AVCaptureFileOutput,
            didFinishRecordingToOutputFileAtURL: NSURL,
            fromConnections: List<*>,
            error: NSError?,
        ) {
            if (error != null) {
                _state.value = RecordingState.Failed(error.localizedDescription)
            }
        }
    }

    private val photoDelegate = object : NSObject(), AVCapturePhotoCaptureDelegateProtocol {
        override fun captureOutput(
            output: AVCapturePhotoOutput,
            didFinishProcessingPhoto: platform.AVFoundation.AVCapturePhoto,
            error: NSError?,
        ) {
            photoResult?.complete(didFinishProcessingPhoto.fileDataRepresentation())
        }
    }

    override fun configureVideo(settings: VideoCaptureSettings) {
        if (_state.value == RecordingState.Preparing ||
            _state.value == RecordingState.Finalizing ||
            _state.value is RecordingState.Recording
        ) {
            return
        }
        if (settings == videoSettings) return
        val facingChanged = settings.facing != videoSettings.facing
        val qualityChanged = settings.quality != videoSettings.quality
        videoSettings = settings
        if (captureSession != null && (facingChanged || qualityChanged)) reconfigureSession()
    }

    /** Opens the camera for framing without starting any recording or capture. */
    internal fun ensurePreviewSession(): AVCaptureSession = ensureSession()

    private fun ensureSession(): AVCaptureSession = captureSession ?: AVCaptureSession().also { created ->
        created.beginConfiguration()
        created.sessionPreset = videoSettings.quality.iosPreset()
        addVideoInput(created)
        AVCaptureDevice.defaultDeviceWithMediaType(AVMediaTypeAudio)?.let { device ->
            AVCaptureDeviceInput.deviceInputWithDevice(device, null)?.let { input ->
                if (created.canAddInput(input)) created.addInput(input)
            }
        }
        val output = AVCaptureMovieFileOutput()
        if (created.canAddOutput(output)) created.addOutput(output)
        output.applyRecordingBitrate(videoSettings.quality.bitrate)
        movieOutput = output
        val stillOutput = AVCapturePhotoOutput()
        if (created.canAddOutput(stillOutput)) created.addOutput(stillOutput)
        photoOutput = stillOutput
        created.commitConfiguration()
        created.startRunning()
        captureSession = created
    }

    private fun addVideoInput(session: AVCaptureSession) {
        val position = when (videoSettings.facing) {
            CameraFacing.BACK -> AVCaptureDevicePositionBack
            CameraFacing.FRONT -> AVCaptureDevicePositionFront
        }
        val device = AVCaptureDevice.defaultDeviceWithDeviceType(
            AVCaptureDeviceTypeBuiltInWideAngleCamera,
            AVMediaTypeVideo,
            position,
        ) ?: return
        val input = AVCaptureDeviceInput.deviceInputWithDevice(device, null) ?: return
        if (session.canAddInput(input)) {
            session.addInput(input)
            videoInput = input
        }
    }

    /** Applies a new preset or camera facing to an already running preview session. */
    private fun reconfigureSession() {
        val session = captureSession ?: return
        session.beginConfiguration()
        runCatching {
            session.sessionPreset = videoSettings.quality.iosPreset()
            videoInput?.let { session.removeInput(it) }
            videoInput = null
            addVideoInput(session)
        }
        session.commitConfiguration()
    }

    override suspend fun start(kind: CaptureKind) {
        currentKind = kind
        _state.value = RecordingState.Preparing
        startedAt = nowMillis()

        if (kind == CaptureKind.PHOTO) {
            capturePhoto()
            return
        }

        val extension = if (kind == CaptureKind.VIDEO) "mp4" else "m4a"
        val path = NSTemporaryDirectory() + "mira-capture-$startedAt.$extension"
        val url = NSURL.fileURLWithPath(path)
        outputUrl = url

        if (kind == CaptureKind.AUDIO) {
            configureAudioSession()

            val settings = mapOf<Any?, Any?>(
                AVFormatIDKey to kAudioFormatMPEG4AAC,
                AVSampleRateKey to CaptureLimits.AUDIO_SAMPLE_RATE.toDouble(),
                AVNumberOfChannelsKey to CaptureLimits.AUDIO_CHANNELS,
                AVEncoderBitRateKey to CaptureLimits.AUDIO_BITRATE,
            )
            val recorder = AVAudioRecorder(uRL = url, settings = settings, error = null)
            recorder.meteringEnabled = true
            if (!recorder.prepareToRecord() || !recorder.recordForDuration(
                    CaptureLimits.MAX_DURATION_MS / 1000.0,
                )
            ) {
                _state.value = RecordingState.Failed("Could not start recording")
                return
            }
            audioRecorder = recorder
            _state.value = RecordingState.Recording(0)
            return
        }

        val session = ensureSession()

        val output = movieOutput
        if (output == null) {
            _state.value = RecordingState.Failed("Camera is unavailable")
            return
        }
        if (!session.running) session.startRunning()
        output.startRecordingToOutputFileURL(url, recordingDelegate)
        _state.value = RecordingState.Recording(0)
    }

    /** A still photo finishes entirely inside [start] — there is nothing for [stop] to do. */
    private suspend fun capturePhoto() {
        val session = ensureSession()
        val output = photoOutput
        if (output == null) {
            _state.value = RecordingState.Failed("Camera is unavailable")
            return
        }
        if (!session.running) session.startRunning()

        val deferred = CompletableDeferred<NSData?>()
        photoResult = deferred
        output.capturePhotoWithSettings(AVCapturePhotoSettings.photoSettings(), photoDelegate)
        val data = runCatching { deferred.await() }.getOrNull()?.toByteArray()
        photoResult = null

        if (data == null) {
            _state.value = RecordingState.Failed("Could not take a photo. Please try again.")
            return
        }
        _state.value = RecordingState.Finished(
            CaptureResult(
                kind = CaptureKind.PHOTO,
                path = null,
                bytes = data,
                mime = "image/jpeg",
                sizeBytes = data.size.toLong(),
                durationMs = 0,
            ),
        )
    }

    override suspend fun stop() {
        if (currentKind == CaptureKind.PHOTO) return
        val elapsed = nowMillis() - startedAt
        audioRecorder?.stop()
        audioRecorder = null
        movieOutput?.stopRecording()

        val url = outputUrl
        val path = url?.path
        if (path == null || !NSFileManager.defaultManager.fileExistsAtPath(path)) {
            _state.value = RecordingState.Failed("Recording produced no audio or video")
            return
        }
        val attributes = NSFileManager.defaultManager.attributesOfItemAtPath(path, null)
        val size = (attributes?.get("NSFileSize") as? Number)?.toLong() ?: 0L

        _state.value = RecordingState.Finished(
            CaptureResult(
                kind = currentKind,
                path = path,
                bytes = null,
                mime = if (currentKind == CaptureKind.VIDEO) "video/mp4" else "audio/mp4",
                sizeBytes = size,
                durationMs = elapsed,
            ),
        )
    }

    override fun cancel() {
        audioRecorder?.stop()
        audioRecorder?.deleteRecording()
        audioRecorder = null
        movieOutput?.stopRecording()
        photoResult?.complete(null)
        photoResult = null
        outputUrl?.path?.let { NSFileManager.defaultManager.removeItemAtPath(it, null) }
        outputUrl = null
        _state.value = RecordingState.Idle
    }

    override fun release() {
        cancel()
        captureSession?.stopRunning()
        captureSession = null
        videoInput = null
        movieOutput = null
        photoOutput = null
    }

    private fun VideoQuality.iosPreset(): String? = when (this) {
        VideoQuality.HIGH -> AVCaptureSessionPresetHigh
        VideoQuality.STANDARD -> AVCaptureSessionPreset1280x720
        VideoQuality.DATA_SAVER -> AVCaptureSessionPreset640x480
    }

    private fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()

    /**
     * Recording needs the shared session in a record-capable category; failures here are not fatal
     * on their own, so they surface when the recorder itself refuses to start.
     */
    private fun configureAudioSession() {
        val session = AVAudioSession.sharedInstance()
        // Setting the category is what matters; AVAudioRecorder activates the session itself when
        // it starts, and an explicit activation here adds nothing we can act on.
        runCatching { session.setCategory(AVAudioSessionCategoryPlayAndRecord, null) }
    }
}

@OptIn(ExperimentalForeignApi::class)
@Composable
public actual fun CameraPreview(
    controller: MediaRecorderController,
    modifier: Modifier,
    settings: VideoCaptureSettings,
) {
    val iosController = controller as? IosMediaRecorderController
    // Open the camera for framing as soon as the preview mounts, rather than waiting for
    // start() — otherwise the box stays black until recording (or a photo) has already begun.
    androidx.compose.runtime.DisposableEffect(iosController) {
        iosController?.ensurePreviewSession()
        onDispose {}
    }
    UIKitView(
        modifier = modifier.background(Color.Black),
        factory = { UIView() },
        update = { view -> view.syncPreviewLayer(iosController?.captureSession) },
        onResize = { view, _ -> view.syncPreviewLayer(iosController?.captureSession) },
        // Let Compose receive taps so the preview can expand to full screen.
        interactive = false,
    )
}

/** Attaches the preview layer the first time a session becomes available, then just resizes it. */
@OptIn(ExperimentalForeignApi::class)
private fun UIView.syncPreviewLayer(session: AVCaptureSession?) {
    val existing = layer.sublayers?.firstOrNull() as? AVCaptureVideoPreviewLayer
    if (existing != null) {
        existing.frame = bounds
        return
    }
    if (session == null) return
    val newLayer = AVCaptureVideoPreviewLayer(session = session)
    newLayer.videoGravity = AVLayerVideoGravityResizeAspectFill
    newLayer.frame = bounds
    layer.addSublayer(newLayer)
}
