package com.joetr.mira.media

import android.media.MediaPlayer
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import com.joetr.mira.platform.AndroidContextHolder
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
import kotlinx.coroutines.withContext
import java.io.File

public actual fun createMediaPlayerController(): MediaPlayerController =
    AndroidMediaPlayerController()

/**
 * Playback through the platform's own `MediaPlayer`.
 *
 * Mira only ever produces m4a/AAC and mp4/H.264, which Android has decoded natively since API 16 —
 * so there is nothing here that would justify pulling in ExoPlayer.
 */
internal class AndroidMediaPlayerController : MediaPlayerController {

    private val _state = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    internal val surfaceHolder = MutableStateFlow<SurfaceHolder?>(null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var player: MediaPlayer? = null
    private var tempFile: File? = null

    override suspend fun load(source: MediaSource, kind: CaptureKind) {
        release()
        _state.value = PlaybackState(preparing = true)

        val path = withContext(Dispatchers.IO) { resolvePath(source) }
        if (path == null) {
            _state.value = PlaybackState(errorMessage = "Could not open this recording")
            return
        }

        val created = runCatching {
            MediaPlayer().apply {
                setDataSource(path)
                surfaceHolder.value?.let { setDisplay(it) }
                setOnCompletionListener {
                    _state.value = _state.value.copy(playing = false, ended = true)
                }
                setOnErrorListener { _, what, _ ->
                    _state.value = PlaybackState(errorMessage = "Playback failed ($what)")
                    true
                }
                prepare()
            }
        }.getOrNull()

        if (created == null) {
            _state.value = PlaybackState(errorMessage = "Could not open this recording")
            return
        }

        player = created
        _state.value = PlaybackState(durationMs = created.duration.toLong().coerceAtLeast(0))
        scope.launch { trackPosition() }
    }

    override suspend fun play() {
        val active = player ?: return
        if (_state.value.ended) active.seekTo(0)
        active.start()
        _state.value = _state.value.copy(playing = true, ended = false)
    }

    override fun pause() {
        player?.takeIf { it.isPlaying }?.pause()
        _state.value = _state.value.copy(playing = false)
    }

    override suspend fun seekTo(positionMs: Long) {
        player?.seekTo(positionMs.toInt())
        _state.value = _state.value.copy(positionMs = positionMs, ended = false)
    }

    override fun release() {
        player?.runCatching {
            stop()
            release()
        }
        player = null
        tempFile?.delete()
        tempFile = null
        _state.value = PlaybackState()
    }

    private suspend fun trackPosition() {
        while (scope.isActive && player != null) {
            player?.let { active ->
                if (_state.value.playing) {
                    _state.value = _state.value.copy(
                        positionMs = active.currentPosition.toLong(),
                    )
                }
            }
            delay(200)
        }
    }

    private fun resolvePath(source: MediaSource): String? = when (source) {
        is MediaSource.LocalFile -> source.path.takeIf { File(it).exists() }
        is MediaSource.Remote -> source.url
        is MediaSource.Bytes -> {
            val extension = if (source.mime.startsWith("video/")) "mp4" else "m4a"
            val file = File(
                AndroidContextHolder.application.cacheDir,
                "mira-playback-${source.bytes.size}.$extension",
            )
            runCatching {
                file.writeBytes(source.bytes)
                tempFile = file
                file.absolutePath
            }.getOrNull()
        }
    }

    internal fun attachSurface(holder: SurfaceHolder?) {
        surfaceHolder.value = holder
        player?.setDisplay(holder)
    }
}

@Composable
public actual fun VideoSurface(
    controller: MediaPlayerController,
    modifier: Modifier,
) {
    val androidController = controller as? AndroidMediaPlayerController

    AndroidView(
        modifier = modifier.background(Color.Black),
        factory = { context ->
            SurfaceView(context).apply {
                holder.addCallback(
                    object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            androidController?.attachSurface(holder)
                        }

                        override fun surfaceChanged(
                            holder: SurfaceHolder,
                            format: Int,
                            width: Int,
                            height: Int,
                        ) = Unit

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            androidController?.attachSurface(null)
                        }
                    },
                )
            }
        },
    )

    DisposableEffect(controller) {
        onDispose { androidController?.attachSurface(null) }
    }
}
