package com.joetr.mira.media

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.interop.UIKitView
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
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.Foundation.NSData
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.writeToFile
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
    UIKitView(
        modifier = modifier.background(Color.Black),
        factory = {
            val view = UIView()
            iosController?.player?.let { player ->
                val layer = AVPlayerLayer()
                layer.player = player
                layer.videoGravity = AVLayerVideoGravityResizeAspect
                layer.frame = view.bounds
                view.layer.addSublayer(layer)
            }
            view
        },
    )
}
