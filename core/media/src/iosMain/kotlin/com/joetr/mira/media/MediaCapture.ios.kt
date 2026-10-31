package com.joetr.mira.media

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.interop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
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
import platform.AVFoundation.AVCaptureFileOutputRecordingDelegateProtocol
import platform.AVFoundation.AVCaptureMovieFileOutput
import platform.AVFoundation.AVCaptureSession
import platform.AVFoundation.AVCaptureSessionPresetHigh
import platform.AVFoundation.AVCaptureVideoPreviewLayer
import platform.AVFoundation.AVCaptureFileOutput
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.AVMediaTypeAudio
import platform.AVFoundation.AVMediaTypeVideo
import platform.CoreAudioTypes.kAudioFormatMPEG4AAC
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSError
import platform.UIKit.UIView
import platform.darwin.NSObject
import kotlin.time.ExperimentalTime
import kotlin.time.Clock

public actual fun supportsCapture(kind: CaptureKind): Boolean = true

public actual fun createMediaRecorderController(): MediaRecorderController =
    IosMediaRecorderController()

/**
 * iOS capture: `AVAudioRecorder` for audio, `AVCaptureMovieFileOutput` for video.
 *
 * Both already produce m4a/AAC and mp4/H.264 at the limits Mira applies everywhere, so nothing is
 * transcoded before upload.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalTime::class)
internal class IosMediaRecorderController : MediaRecorderController {

    private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)
    override val state: StateFlow<RecordingState> = _state.asStateFlow()

    internal var captureSession: AVCaptureSession? = null
        private set

    private var audioRecorder: AVAudioRecorder? = null
    private var movieOutput: AVCaptureMovieFileOutput? = null
    private var outputUrl: NSURL? = null
    private var startedAt: Long = 0
    private var currentKind: CaptureKind = CaptureKind.AUDIO

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

    override suspend fun start(kind: CaptureKind) {
        currentKind = kind
        _state.value = RecordingState.Preparing
        startedAt = nowMillis()

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

        val session = captureSession ?: AVCaptureSession().also { created ->
            created.sessionPreset = AVCaptureSessionPresetHigh
            AVCaptureDevice.defaultDeviceWithMediaType(AVMediaTypeVideo)?.let { device ->
                AVCaptureDeviceInput.deviceInputWithDevice(device, null)?.let { input ->
                    if (created.canAddInput(input)) created.addInput(input)
                }
            }
            AVCaptureDevice.defaultDeviceWithMediaType(AVMediaTypeAudio)?.let { device ->
                AVCaptureDeviceInput.deviceInputWithDevice(device, null)?.let { input ->
                    if (created.canAddInput(input)) created.addInput(input)
                }
            }
            val output = AVCaptureMovieFileOutput()
            if (created.canAddOutput(output)) created.addOutput(output)
            movieOutput = output
            created.startRunning()
            captureSession = created
        }

        val output = movieOutput
        if (output == null) {
            _state.value = RecordingState.Failed("Camera is unavailable")
            return
        }
        if (!session.running) session.startRunning()
        output.startRecordingToOutputFileURL(url, recordingDelegate)
        _state.value = RecordingState.Recording(0)
    }

    override suspend fun stop() {
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
        outputUrl?.path?.let { NSFileManager.defaultManager.removeItemAtPath(it, null) }
        outputUrl = null
        _state.value = RecordingState.Idle
    }

    override fun release() {
        cancel()
        captureSession?.stopRunning()
        captureSession = null
        movieOutput = null
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
) {
    val iosController = controller as? IosMediaRecorderController
    UIKitView(
        modifier = modifier.background(Color.Black),
        factory = {
            val view = UIView()
            iosController?.captureSession?.let { session ->
                val layer = AVCaptureVideoPreviewLayer(session = session)
                layer.videoGravity = AVLayerVideoGravityResizeAspectFill
                layer.frame = view.bounds
                view.layer.addSublayer(layer)
            }
            view
        },
    )
}
