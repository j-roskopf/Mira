package com.joetr.mira.media

/**
 * Reads a [MediaSource] fully into memory.
 *
 * Only ever used for small payloads like a photo thumbnail — nothing here is meant for audio or
 * video, which stream through [MediaPlayerController] instead. A [MediaSource.Remote] is not
 * fetched; the caller shows a placeholder for that case, the same way playback already does when a
 * recording's source is unavailable.
 */
public suspend fun MediaSource.readBytesForDisplay(): ByteArray? = when (this) {
    is MediaSource.Bytes -> bytes
    is MediaSource.LocalFile -> readLocalFileBytes(path)
    is MediaSource.Remote -> null
}

internal expect suspend fun readLocalFileBytes(path: String): ByteArray?
