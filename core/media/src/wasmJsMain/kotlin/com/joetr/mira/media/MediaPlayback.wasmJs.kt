package com.joetr.mira.media

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

public actual fun createMediaPlayerController(): MediaPlayerController = WebMediaPlayerController()

/**
 * Playback through a real `<audio>` or `<video>` element.
 *
 * The browser already decodes mp4/H.264 and m4a/AAC, so Mira hands it the element and reads back
 * position and duration. For video, the element is positioned over the space Compose reserves for
 * it — canvas-rendered Compose has no way to draw a decoded video frame itself.
 */
internal class WebMediaPlayerController : MediaPlayerController {

    private val _state = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val elementId = "mira-player-${nextPlayerId()}"
    private var loaded = false

    // The Compose surface is laid out before load() finishes, so its bounds have to be remembered
    // and replayed once the element exists. onGloballyPositioned will not fire again on its own.
    private var bounds: Bounds? = null

    override suspend fun load(source: MediaSource, kind: CaptureKind) {
        release()
        _state.value = PlaybackState(preparing = true)

        val url = when (source) {
            is MediaSource.Remote -> source.url
            is MediaSource.Bytes -> createObjectUrl(source.bytes.toUint8Array(), source.mime)
            // The browser has no readable filesystem path, and web keeps no local media anyway.
            is MediaSource.LocalFile -> null
        }

        if (url == null) {
            _state.value = PlaybackState(errorMessage = "Could not open this recording")
            return
        }

        createPlayerElement(elementId, url, kind == CaptureKind.VIDEO)
        loaded = true
        bounds?.let { applyBounds(it) }

        var durationMs = 0L
        repeat(40) {
            durationMs = playerDurationMs(elementId).toLong()
            if (durationMs > 0) return@repeat
            delay(50)
        }

        _state.value = PlaybackState(durationMs = durationMs)
        scope.launch { trackPosition() }
    }

    override suspend fun play() {
        if (!loaded) return
        if (_state.value.ended) seekTo(0)
        playPlayer(elementId)
        _state.value = _state.value.copy(playing = true, ended = false)
    }

    override fun pause() {
        if (!loaded) return
        pausePlayer(elementId)
        _state.value = _state.value.copy(playing = false)
    }

    override suspend fun seekTo(positionMs: Long) {
        if (!loaded) return
        seekPlayer(elementId, positionMs / 1000.0)
        _state.value = _state.value.copy(positionMs = positionMs, ended = false)
    }

    override fun release() {
        if (loaded) removePlayerElement(elementId)
        loaded = false
        _state.value = PlaybackState()
    }

    internal fun position(x: Double, y: Double, width: Double, height: Double) {
        val next = Bounds(x, y, width, height)
        bounds = next
        if (loaded) applyBounds(next)
    }

    private fun applyBounds(bounds: Bounds) {
        positionPlayerElement(elementId, bounds.x, bounds.y, bounds.width, bounds.height)
    }

    private suspend fun trackPosition() {
        while (scope.isActive && loaded) {
            if (_state.value.playing) {
                val positionMs = (playerPositionMs(elementId)).toLong()
                val duration = _state.value.durationMs
                val ended = playerEnded(elementId)
                _state.value = _state.value.copy(
                    positionMs = if (duration > 0) positionMs.coerceAtMost(duration) else positionMs,
                    ended = ended,
                    playing = !ended,
                )
            }
            delay(200)
        }
    }
}

private data class Bounds(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
)

private var playerCounter = 0

private fun nextPlayerId(): Int = ++playerCounter

@Composable
public actual fun VideoSurface(
    controller: MediaPlayerController,
    modifier: Modifier,
) {
    val webController = controller as? WebMediaPlayerController
    // Compose's web density is the device pixel ratio, so boundsInWindow() is in physical pixels.
    // The element is positioned with CSS pixels, which is that value divided by the density.
    val density = LocalDensity.current.density

    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black)
            .onGloballyPositioned { coordinates ->
                val bounds = coordinates.boundsInWindow()
                webController?.position(
                    bounds.left.toDouble() / density,
                    bounds.top.toDouble() / density,
                    bounds.width.toDouble() / density,
                    bounds.height.toDouble() / density,
                )
            },
    )

    DisposableEffect(controller) {
        onDispose { webController?.position(0.0, 0.0, 0.0, 0.0) }
    }
}
