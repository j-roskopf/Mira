package com.joetr.mira.media

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.StateFlow

/**
 * Where an attachment's bytes are coming from.
 *
 * Which one you get depends on the platform's retention policy: desktop keeps everything locally,
 * mobile keeps an LRU-capped cache, and web never holds media at all — so the same attachment is a
 * [LocalFile] on one device and a [Remote] stream on another.
 */
public sealed interface MediaSource {
    public data class LocalFile(val path: String) : MediaSource

    public data class Remote(val url: String) : MediaSource

    /** Bytes held in memory — web's only option, and the path for media not yet uploaded. */
    public data class Bytes(val bytes: ByteArray, val mime: String) : MediaSource {
        override fun equals(other: Any?): Boolean =
            this === other || (other is Bytes && bytes.contentEquals(other.bytes))

        override fun hashCode(): Int = bytes.contentHashCode()
    }
}

public data class PlaybackState(
    val playing: Boolean = false,
    val preparing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val ended: Boolean = false,
    val errorMessage: String? = null,
    /** Encoded frame size after rotation metadata, when the platform reports it. */
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
) {
    /** 0..1, safe when the duration is not yet known. */
    public val progress: Float
        get() = if (durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
}

public interface MediaPlayerController {
    public val state: StateFlow<PlaybackState>

    public suspend fun load(source: MediaSource, kind: CaptureKind)
    public suspend fun play()
    public fun pause()
    public suspend fun seekTo(positionMs: Long)
    public fun release()
}

public expect fun createMediaPlayerController(): MediaPlayerController

/**
 * Renders video frames for [controller].
 *
 * Audio-only playback needs no surface, so this is only placed for video attachments.
 */
@Composable
public expect fun VideoSurface(
    controller: MediaPlayerController,
    modifier: Modifier,
)
