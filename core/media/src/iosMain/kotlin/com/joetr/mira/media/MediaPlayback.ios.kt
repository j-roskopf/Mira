package com.joetr.mira.media

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.interop.UIKitView
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import kotlinx.cinterop.ExperimentalForeignApi
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
import platform.AVFoundation.AVLayerVideoGravityResizeAspect
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerLayer
import platform.AVFoundation.currentItem
import platform.AVFoundation.currentTime
import platform.AVFoundation.duration
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.seekToTime
import platform.CoreGraphics.CGRectGetHeight
import platform.CoreGraphics.CGRectGetWidth
import platform.CoreGraphics.CGRectMake
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.Foundation.NSData
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.writeToFile
import platform.QuartzCore.CATransaction
import platform.UIKit.UIView
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.create

public actual fun createMediaPlayerController(): MediaPlayerController = IosMediaPlayerController()

/**
 * Playback through `AVPlayer`.
 *
 * Everything Mira stores is mp4/H.264 or m4a/AAC, including anything recorded in a browser, which
 * ffmpeg.wasm transcodes before upload — so AVPlayer can open any attachment in the archive.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosMediaPlayerController : MediaPlayerController {

    private val _state = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    internal var player: AVPlayer? = null
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override suspend fun load(source: MediaSource, kind: CaptureKind) {
        release()
        _state.value = PlaybackState(preparing = true)

        val url = resolveUrl(source)
        if (url == null) {
            _state.value = PlaybackState(errorMessage = "Could not open this recording")
            return
        }

        val item = AVPlayerItem(uRL = url)
        val created = AVPlayer(playerItem = item)
        player = created

        // The duration is not known the instant the item is created; poll briefly rather than
        // registering a KVO observer for a value that lands within a frame or two.
        var durationMs = 0L
        repeat(20) {
            val seconds = CMTimeGetSeconds(item.duration)
            if (!seconds.isNaN() && seconds > 0) {
                durationMs = (seconds * 1000).toLong()
                return@repeat
            }
            delay(50)
        }

        _state.value = PlaybackState(durationMs = durationMs)
        scope.launch { trackPosition() }
    }

    override suspend fun play() {
        val active = player ?: return
        if (_state.value.ended) seekTo(0)
        active.play()
        _state.value = _state.value.copy(playing = true, ended = false)
    }

    override fun pause() {
        player?.pause()
        _state.value = _state.value.copy(playing = false)
    }

    override suspend fun seekTo(positionMs: Long) {
        player?.seekToTime(CMTimeMakeWithSeconds(positionMs / 1000.0, 600))
        _state.value = _state.value.copy(positionMs = positionMs, ended = false)
    }

    override fun release() {
        player?.pause()
        player = null
        _state.value = PlaybackState()
    }

    private suspend fun trackPosition() {
        while (scope.isActive && player != null) {
            val active = player
            if (active != null && _state.value.playing) {
                val seconds = CMTimeGetSeconds(active.currentTime())
                if (!seconds.isNaN()) {
                    val positionMs = (seconds * 1000).toLong()
                    val duration = _state.value.durationMs
                    _state.value = _state.value.copy(
                        positionMs = positionMs,
                        ended = duration > 0 && positionMs >= duration - 150,
                        playing = !(duration > 0 && positionMs >= duration - 150),
                    )
                }
            }
            delay(200)
        }
    }

    private fun resolveUrl(source: MediaSource): NSURL? = when (source) {
        is MediaSource.LocalFile -> NSURL.fileURLWithPath(source.path)
        is MediaSource.Remote -> NSURL.URLWithString(source.url)
        is MediaSource.Bytes -> {
            val extension = if (source.mime.startsWith("video/")) "mp4" else "m4a"
            val path = NSTemporaryDirectory() + "mira-playback-${source.bytes.size}.$extension"
            val data = source.bytes.toNSData()
            if (data != null && data.writeToFile(path, true)) {
                NSURL.fileURLWithPath(path)
            } else {
                null
            }
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun ByteArray.toNSData(): NSData? {
    if (isEmpty()) return null
    return usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
    }
}

@OptIn(ExperimentalForeignApi::class)
@Composable
public actual fun VideoSurface(
    controller: MediaPlayerController,
    modifier: Modifier,
) {
    val iosController = controller as? IosMediaPlayerController
    val density = LocalDensity.current
    var surfaceSize by remember { mutableStateOf(IntSize.Zero) }

    UIKitView(
        modifier = modifier
            .onSizeChanged { surfaceSize = it }
            .background(Color.Black),
        factory = { PlayerView() },
        update = { view ->
            view.playerLayer.player = iosController?.player
            view.setVideoFrame(surfaceSize, density)
        },
        interactive = false,
    )
}

/**
 * Hosts the `AVPlayerLayer` and keeps it the same size as the view.
 *
 * Compose creates the interop view at its intrinsic size and resizes it when the layout settles,
 * after `factory` has already run. Syncing from `layoutSubviews` is the only hook that is
 * guaranteed to fire for every resize, including the one that gives the tile its real size.
 */
@OptIn(ExperimentalForeignApi::class)
private class PlayerView : UIView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0)) {
    val playerLayer = AVPlayerLayer().apply {
        videoGravity = AVLayerVideoGravityResizeAspect
    }

    private var frameFromCompose = false

    init {
        layer.addSublayer(playerLayer)
    }

    /**
     * Sizes the layer from Compose rather than from [bounds].
     *
     * The interop view can report a stale or partial height while Compose is still settling the
     * tile, which is what left the video at a fraction of its box. The Compose size is the source
     * of truth, and the interop's clipping view still trims the layer to the visible tile.
     */
    fun setVideoFrame(size: IntSize, density: Density) {
        if (size.width <= 0 || size.height <= 0) return
        frameFromCompose = true
        applyFrame(
            width = size.width / density.density.toDouble(),
            height = size.height / density.density.toDouble(),
        )
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        if (!frameFromCompose) {
            applyFrame(CGRectGetWidth(bounds), CGRectGetHeight(bounds))
        }
    }

    private fun applyFrame(width: Double, height: Double) {
        // Frame changes are animatable; without this the layer can be captured mid-resize.
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        playerLayer.frame = CGRectMake(0.0, 0.0, width, height)
        CATransaction.commit()
    }
}
