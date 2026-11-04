package com.joetr.mira.media

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import com.joetr.mira.platform.AndroidContextHolder
import kotlinx.coroutines.CompletableDeferred
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
import kotlin.math.min

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

    @Volatile private var surface: Surface? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var player: MediaPlayer? = null
    private var tempFile: File? = null

    /** Bumped by [release] so an in-flight load cannot publish state after it was cancelled. */
    @Volatile private var loadGeneration: Int = 0

    override suspend fun load(source: MediaSource, kind: CaptureKind) {
        release()
        val generation = loadGeneration
        _state.value = PlaybackState(preparing = true)

        val path = withContext(Dispatchers.IO) { resolvePath(source) }
        if (path == null) {
            _state.value = PlaybackState(errorMessage = "Could not open this recording")
            return
        }

        // MediaPlayer's data-source setup and prepare both block — `prepare()` until the first
        // frame is decoded, and `setDataSource()` on a remote URL until the network responds.
        // Doing either on the caller's (main) thread freezes the UI, so hand the work to IO and
        // wait for the async prepare callback instead.
        val prepared = CompletableDeferred<MediaPlayer?>()
        val created = withContext(Dispatchers.IO) {
            runCatching {
                MediaPlayer().apply {
                    setDataSource(path)
                    surface?.let { setSurface(it) }
                    setOnCompletionListener {
                        if (generation == loadGeneration) {
                            _state.value = _state.value.copy(playing = false, ended = true)
                        }
                    }
                    setOnErrorListener { _, what, _ ->
                        if (generation == loadGeneration) {
                            _state.value = PlaybackState(errorMessage = "Playback failed ($what)")
                        }
                        prepared.complete(null)
                        true
                    }
                    setOnPreparedListener { prepared.complete(this) }
                    prepareAsync()
                }
            }.getOrNull()
        }

        if (generation != loadGeneration) {
            created?.runCatching { release() }
            return
        }
        if (created == null) {
            _state.value = PlaybackState(errorMessage = "Could not open this recording")
            return
        }

        player = created
        // The surface may have attached while the player was still being created; make sure the
        // prepared player picks up whichever surface is current before the first frame arrives.
        surface?.let { created.setSurface(it) }
        val ready = prepared.await() ?: return
        if (generation != loadGeneration) return

        val (videoWidth, videoHeight) = if (kind == CaptureKind.VIDEO) {
            withContext(Dispatchers.IO) { videoDimensions(path) }
        } else {
            0 to 0
        }
        _state.value = PlaybackState(
            durationMs = ready.duration.toLong().coerceAtLeast(0),
            videoWidth = videoWidth,
            videoHeight = videoHeight,
        )
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
        loadGeneration++
        player?.runCatching { release() }
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

    /**
     * Frame size with rotation metadata applied.
     *
     * MediaPlayer stretches its surface to fill the host view, so callers need the real aspect
     * ratio or portrait clips come out squashed. Remote URLs may not resolve here; they fall back
     * to the default tile ratio.
     */
    private fun videoDimensions(path: String): Pair<Int, Int> = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(path)
            val width = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            val height = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
            val rotation = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
            if (rotation == 90 || rotation == 270) height to width else width to height
        } finally {
            retriever.release()
        }
    }.getOrDefault(0 to 0)

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

    internal fun attachSurface(surface: Surface) {
        this.surface = surface
        player?.setSurface(surface)
    }

    /**
     * Clears the surface only when the one going away is currently in use. Moving from the inline
     * tile to the full-screen player composes the new surface before the old one is destroyed, and
     * an unconditional clear would black out the new one.
     */
    internal fun detachSurface(surface: Surface) {
        if (this.surface !== surface) return
        this.surface = null
        player?.setSurface(null)
    }
}

@Composable
public actual fun VideoSurface(
    controller: MediaPlayerController,
    modifier: Modifier,
) {
    val androidController = controller as? AndroidMediaPlayerController
    val playback by (androidController?.state ?: MutableStateFlow(PlaybackState())).collectAsState()

    AndroidView(
        modifier = modifier.background(Color.Black),
        factory = { context ->
            MiraVideoTextureView(context).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(
                        texture: SurfaceTexture,
                        width: Int,
                        height: Int,
                    ) {
                        val created = Surface(texture)
                        surface = created
                        androidController?.attachSurface(created)
                    }

                    override fun onSurfaceTextureSizeChanged(
                        texture: SurfaceTexture,
                        width: Int,
                        height: Int,
                    ) = Unit

                    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                        surface?.let {
                            androidController?.detachSurface(it)
                            it.release()
                        }
                        surface = null
                        return true
                    }

                    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
                }
            }
        },
        update = { view ->
            view.videoWidth = playback.videoWidth
            view.videoHeight = playback.videoHeight
        },
    )
}

/**
 * A `TextureView` that letterboxes the decoded video instead of stretching it.
 *
 * `SurfaceView` is a separate window: it ignores Compose sizing and clipping and stretched portrait
 * clips into landscape tiles. TextureView is laid out like any other view, so it can be scaled to
 * the aspect ratio MediaPlayer reports.
 */
private class MiraVideoTextureView(context: Context) : TextureView(context) {
    init {
        isOpaque = false
    }

    var videoWidth: Int = 0
        set(value) {
            if (field != value) {
                field = value
                applyFit()
            }
        }

    var videoHeight: Int = 0
        set(value) {
            if (field != value) {
                field = value
                applyFit()
            }
        }

    var surface: Surface? = null

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        applyFit()
    }

    private fun applyFit() {
        if (videoWidth <= 0 || videoHeight <= 0 || width <= 0 || height <= 0) {
            setTransform(Matrix())
            return
        }
        val scale = min(width.toFloat() / videoWidth, height.toFloat() / videoHeight)
        val dx = (width - videoWidth * scale) / 2f
        val dy = (height - videoHeight * scale) / 2f
        setTransform(
            Matrix().apply {
                // TextureView already stretches the buffer to the view bounds. Scale that
                // view-sized texture to the fitted dimensions, not the original video pixels.
                setScale(videoWidth * scale / width, videoHeight * scale / height)
                postTranslate(dx, dy)
            },
        )
    }
}
